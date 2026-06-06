/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.session;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit + lightweight integration tests for {@link ReconnectScheduler}.
 *
 * <h3>Two flavours of test</h3>
 * <ul>
 *   <li><b>Pure</b> tests of {@link ReconnectScheduler#computeDelay} — verify
 *       the exponential ladder + LONG_PAUSE cap + jitter bounds. No
 *       scheduling happens.</li>
 *   <li><b>End-to-end</b> tests that actually {@code scheduleReconnect()} a
 *       runnable and {@link CountDownLatch} for it to fire. The config sets
 *       short delays (100 ms) so the suite stays fast.</li>
 * </ul>
 */
@QuarkusTest
@TestProfile( ReconnectSchedulerTest.ShortBackoffProfile.class )
@DisplayName( "ReconnectScheduler — backoff + jitter + cancellation" )
class ReconnectSchedulerTest
{
    @Inject
    ReconnectScheduler scheduler;

    public static class ShortBackoffProfile implements QuarkusTestProfile
    {
        @Override
        public Map< String, String > getConfigOverrides()
        {
            // Short, deterministic backoff for fast tests:
            //   initialDelay = 100 ms, maxDelay = 1 s, multiplier = 2.0, jitter = 0
            // jitter=0 makes the delay computation deterministic — we cover
            // the jittered case in a separate test that just checks bounds.
            return Map.of(
                    "loxone.miniserver.reconnect.enable", "true",
                    "loxone.miniserver.reconnect.initial-delay", "PT0.1S",
                    "loxone.miniserver.reconnect.max-delay", "PT1S",
                    "loxone.miniserver.reconnect.multiplier", "2.0",
                    "loxone.miniserver.reconnect.jitter-factor", "0"
                         );
        }
    }

    @AfterEach
    void cleanup()
    {
        scheduler.cancel();
        scheduler.notifySuccess();      // reset attempt count between tests
    }

    // ==========================================================================
    //  Pure tests of computeDelay
    // ==========================================================================

    @Test
    @DisplayName( "NORMAL policy: attempts 1 → 100 ms, 2 → 200 ms, 3 → 400 ms (geometric)" )
    void normalBackoffLadder()
    {
        assertThat( scheduler.computeDelay( LoxoneCloseCode.ReconnectPolicy.NORMAL, 1 ).toMillis() )
                .isEqualTo( 100L );
        assertThat( scheduler.computeDelay( LoxoneCloseCode.ReconnectPolicy.NORMAL, 2 ).toMillis() )
                .isEqualTo( 200L );
        assertThat( scheduler.computeDelay( LoxoneCloseCode.ReconnectPolicy.NORMAL, 3 ).toMillis() )
                .isEqualTo( 400L );
        assertThat( scheduler.computeDelay( LoxoneCloseCode.ReconnectPolicy.NORMAL, 4 ).toMillis() )
                .isEqualTo( 800L );
    }

    @Test
    @DisplayName( "NORMAL policy caps at maxDelay (1 s) past attempt 4" )
    void normalBackoffCap()
    {
        // attempt 5 would be 1600 ms but capped at 1000.
        assertThat( scheduler.computeDelay( LoxoneCloseCode.ReconnectPolicy.NORMAL, 5 ).toMillis() )
                .isEqualTo( 1000L );
        assertThat( scheduler.computeDelay( LoxoneCloseCode.ReconnectPolicy.NORMAL, 99 ).toMillis() )
                .isEqualTo( 1000L );
    }

    @Test
    @DisplayName( "LONG_PAUSE policy jumps straight to maxDelay regardless of attempt count" )
    void longPauseIgnoresAttempts()
    {
        assertThat( scheduler.computeDelay( LoxoneCloseCode.ReconnectPolicy.LONG_PAUSE, 1 ).toMillis() )
                .isEqualTo( 1000L );
        assertThat( scheduler.computeDelay( LoxoneCloseCode.ReconnectPolicy.LONG_PAUSE, 99 ).toMillis() )
                .isEqualTo( 1000L );
    }

    // ==========================================================================
    //  Scheduling end-to-end
    // ==========================================================================

    @Test
    @DisplayName( "schedule + fire: the runnable executes after the delay" )
    void firesAfterDelay() throws InterruptedException
    {
        CountDownLatch fired = new CountDownLatch( 1 );
        Duration delay = scheduler.scheduleReconnect( fired::countDown,
                                                      LoxoneCloseCode.ReconnectPolicy.NORMAL );

        // First attempt's delay = initialDelay = 100 ms.
        assertThat( delay.toMillis() ).isEqualTo( 100L );

        // Allow some headroom for the executor to wake up.
        assertThat( fired.await( 2, TimeUnit.SECONDS ) ).isTrue();
    }

    @Test
    @DisplayName( "DO_NOT_RECONNECT: no schedule, no fire, attempt count resets" )
    void doNotReconnect() throws InterruptedException
    {
        AtomicInteger calls = new AtomicInteger();
        // Burn one attempt first so we can verify reset behaviour.
        scheduler.scheduleReconnect( calls::incrementAndGet,
                                     LoxoneCloseCode.ReconnectPolicy.NORMAL );
        Thread.sleep( 200 );        // let the first attempt fire
        assertThat( calls.get() ).isEqualTo( 1 );
        assertThat( scheduler.attemptCount() ).isEqualTo( 1 );

        // DO_NOT_RECONNECT should not schedule + should reset.
        Duration delay = scheduler.scheduleReconnect( calls::incrementAndGet,
                                                      LoxoneCloseCode.ReconnectPolicy.DO_NOT_RECONNECT );
        assertThat( delay ).isEqualTo( Duration.ZERO );
        assertThat( scheduler.attemptCount() ).isZero();

        Thread.sleep( 200 );
        assertThat( calls.get() ).isEqualTo( 1 );   // never incremented again
        assertThat( scheduler.isReconnectPending() ).isFalse();
    }

    @Test
    @DisplayName( "cancel() prevents a pending schedule from firing" )
    void cancellation() throws InterruptedException
    {
        CountDownLatch fired = new CountDownLatch( 1 );
        scheduler.scheduleReconnect( fired::countDown,
                                     LoxoneCloseCode.ReconnectPolicy.NORMAL );
        assertThat( scheduler.isReconnectPending() ).isTrue();

        scheduler.cancel();
        assertThat( scheduler.isReconnectPending() ).isFalse();

        // The original runnable should NOT fire.
        assertThat( fired.await( 300, TimeUnit.MILLISECONDS ) ).isFalse();
    }

    @Test
    @DisplayName( "notifySuccess() resets attempt count + cancels pending" )
    void notifySuccessResetsBackoff() throws InterruptedException
    {
        scheduler.scheduleReconnect( () ->
                                     { /* no-op */ },
                                     LoxoneCloseCode.ReconnectPolicy.NORMAL );
        scheduler.scheduleReconnect( () ->
                                     { /* no-op */ },
                                     LoxoneCloseCode.ReconnectPolicy.NORMAL );
        assertThat( scheduler.attemptCount() ).isEqualTo( 2 );

        scheduler.notifySuccess();
        assertThat( scheduler.attemptCount() ).isZero();
        assertThat( scheduler.isReconnectPending() ).isFalse();
    }

    @Test
    @DisplayName( "second schedule cancels the first (single in-flight contract)" )
    void singleInFlight() throws InterruptedException
    {
        CountDownLatch first  = new CountDownLatch( 1 );
        CountDownLatch second = new CountDownLatch( 1 );

        scheduler.scheduleReconnect( first::countDown,
                                     LoxoneCloseCode.ReconnectPolicy.NORMAL );
        // Replace it before the delay elapses.
        scheduler.scheduleReconnect( second::countDown,
                                     LoxoneCloseCode.ReconnectPolicy.NORMAL );

        // The 2nd should fire; the 1st should NOT.
        assertThat( second.await( 2, TimeUnit.SECONDS ) ).isTrue();
        assertThat( first.getCount() ).isEqualTo( 1L );
    }
}
