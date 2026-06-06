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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link TokenRefreshScheduler} — schedule, cancel, fire, replace.
 *
 * <p>Uses a short refresh period (200 ms) so the suite stays fast. The
 * scheduler is a thin wrapper around {@link java.util.concurrent.ScheduledExecutorService}
 * — the behaviour we care about is "calls the runnable once after the
 * configured period, and only the latest schedule wins".
 */
@QuarkusTest
@TestProfile( TokenRefreshSchedulerTest.ShortRefreshProfile.class )
@DisplayName( "TokenRefreshScheduler — schedule / cancel / replace" )
class TokenRefreshSchedulerTest
{
    @Inject
    TokenRefreshScheduler scheduler;

    public static class ShortRefreshProfile implements QuarkusTestProfile
    {
        @Override
        public Map< String, String > getConfigOverrides()
        {
            return Map.of(
                    "loxone.miniserver.security.token.refresh.period", "PT0.2S"
                         );
        }
    }

    @AfterEach
    void cleanup()
    {
        scheduler.cancel();
    }

    @Test
    @DisplayName( "scheduleNext + fire: runnable executes once after the period" )
    void firesAfterPeriod() throws InterruptedException
    {
        CountDownLatch fired  = new CountDownLatch( 1 );
        Duration       period = scheduler.scheduleNext( fired::countDown );

        assertThat( period.toMillis() ).isEqualTo( 200L );
        assertThat( fired.await( 2, TimeUnit.SECONDS ) ).isTrue();
        assertThat( scheduler.isRefreshPending() ).isFalse();    // fired → no longer pending
    }

    @Test
    @DisplayName( "cancel() prevents a pending schedule from firing" )
    void cancelStopsRefresh() throws InterruptedException
    {
        CountDownLatch fired = new CountDownLatch( 1 );
        scheduler.scheduleNext( fired::countDown );
        assertThat( scheduler.isRefreshPending() ).isTrue();

        scheduler.cancel();
        assertThat( scheduler.isRefreshPending() ).isFalse();

        // Wait past the 200 ms period to confirm nothing fires.
        assertThat( fired.await( 400, TimeUnit.MILLISECONDS ) ).isFalse();
    }

    @Test
    @DisplayName( "scheduleNext replaces a pending schedule (single in-flight)" )
    void replaceCancelsPrevious() throws InterruptedException
    {
        CountDownLatch first  = new CountDownLatch( 1 );
        CountDownLatch second = new CountDownLatch( 1 );

        scheduler.scheduleNext( first::countDown );
        // Replace before the first one fires.
        scheduler.scheduleNext( second::countDown );

        assertThat( second.await( 2, TimeUnit.SECONDS ) ).isTrue();
        assertThat( first.getCount() ).isEqualTo( 1L );          // first never fired
    }

    @Test
    @DisplayName( "a refresh runnable that throws doesn't break the scheduler" )
    void runnableThrowsIsContained() throws InterruptedException
    {
        CountDownLatch attempted = new CountDownLatch( 1 );
        scheduler.scheduleNext( () ->
                                {
                                    try { throw new RuntimeException( "simulated refresh failure" ); }
                                    finally { attempted.countDown(); }
                                } );

        // The exception is swallowed by the scheduler; the latch counts down
        // (proving the runnable ran) and a subsequent scheduleNext works.
        assertThat( attempted.await( 2, TimeUnit.SECONDS ) ).isTrue();

        CountDownLatch retry = new CountDownLatch( 1 );
        scheduler.scheduleNext( retry::countDown );
        assertThat( retry.await( 2, TimeUnit.SECONDS ) ).isTrue();
    }
}
