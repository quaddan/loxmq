/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.session;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link TokenRefreshScheduler}.
 *
 * <p>Two layers, matching the two concerns of the scheduler:
 * <ul>
 *   <li><b>Anchor computation</b> — {@link TokenRefreshScheduler#computeNextFire}
 *       snaps the next refresh to {@code delay-time} on the calendar day that
 *       {@code now + period} lands on. Pure and deterministic, so it is tested
 *       directly with fixed inputs (no wall-clock, no waiting).</li>
 *   <li><b>Executor mechanics</b> — fire-once, cancel, replace and exception
 *       containment are exercised via the {@link TokenRefreshScheduler#scheduleAfter}
 *       seam with a short explicit delay, so the suite stays fast instead of
 *       waiting hours for the real {@code delay-time} anchor.</li>
 * </ul>
 */
@QuarkusTest
@DisplayName( "TokenRefreshScheduler — anchor computation + schedule / cancel / replace" )
class TokenRefreshSchedulerTest
{
    @Inject
    TokenRefreshScheduler scheduler;

    @AfterEach
    void cleanup()
    {
        scheduler.cancel();
    }

    // ----------------------------------------------------------------
    //  Anchor computation (pure, deterministic)
    // ----------------------------------------------------------------

    @Test
    @DisplayName( "24h period → next day at delay-time" )
    void anchors24hToNextDayAtDelayTime()
    {
        LocalDateTime now  = LocalDateTime.of( 2026, 6, 25, 10, 0 );
        LocalDateTime fire = TokenRefreshScheduler.computeNextFire( now, Duration.ofHours( 24 ), LocalTime.of( 4, 30 ) );

        assertThat( fire ).isEqualTo( LocalDateTime.of( 2026, 6, 26, 4, 30 ) );
    }

    @Test
    @DisplayName( "36h period → the day it lands on, at delay-time" )
    void anchors36hToLandingDayAtDelayTime()
    {
        LocalDateTime now  = LocalDateTime.of( 2026, 6, 25, 0, 0 );
        // now + 36h = 2026-06-26 12:00 → snap to that date at 04:30.
        LocalDateTime fire = TokenRefreshScheduler.computeNextFire( now, Duration.ofHours( 36 ), LocalTime.of( 4, 30 ) );

        assertThat( fire ).isEqualTo( LocalDateTime.of( 2026, 6, 26, 4, 30 ) );
    }

    @Test
    @DisplayName( "snapped time already past → pushed to the next day" )
    void pushesPastAnchorToNextDay()
    {
        LocalDateTime now  = LocalDateTime.of( 2026, 6, 25, 10, 0 );
        // now + 1h lands today, but 04:30 today is already past → +1 day.
        LocalDateTime fire = TokenRefreshScheduler.computeNextFire( now, Duration.ofHours( 1 ), LocalTime.of( 4, 30 ) );

        assertThat( fire ).isEqualTo( LocalDateTime.of( 2026, 6, 26, 4, 30 ) );
    }

    @Test
    @DisplayName( "result is always strictly after now" )
    void resultAlwaysAfterNow()
    {
        LocalDateTime now = LocalDateTime.of( 2026, 6, 25, 4, 30 );
        // now == delay-time exactly: snapped time equals now (not after) → +1 day.
        LocalDateTime fire = TokenRefreshScheduler.computeNextFire( now, Duration.ofHours( 24 ), LocalTime.of( 4, 30 ) );

        assertThat( fire ).isAfter( now );
    }

    // ----------------------------------------------------------------
    //  Executor mechanics (via the scheduleAfter seam)
    // ----------------------------------------------------------------

    @Test
    @DisplayName( "scheduleAfter + fire: runnable executes once, then not pending" )
    void firesAfterDelay() throws InterruptedException
    {
        CountDownLatch fired = new CountDownLatch( 1 );
        Duration       delay = scheduler.scheduleAfter( Duration.ofMillis( 200 ), fired::countDown );

        assertThat( delay.toMillis() ).isEqualTo( 200L );
        assertThat( fired.await( 2, TimeUnit.SECONDS ) ).isTrue();
        assertThat( scheduler.isRefreshPending() ).isFalse();    // fired → no longer pending
    }

    @Test
    @DisplayName( "cancel() prevents a pending schedule from firing" )
    void cancelStopsRefresh() throws InterruptedException
    {
        CountDownLatch fired = new CountDownLatch( 1 );
        scheduler.scheduleAfter( Duration.ofMillis( 200 ), fired::countDown );
        assertThat( scheduler.isRefreshPending() ).isTrue();

        scheduler.cancel();
        assertThat( scheduler.isRefreshPending() ).isFalse();

        // Wait past the 200 ms delay to confirm nothing fires.
        assertThat( fired.await( 400, TimeUnit.MILLISECONDS ) ).isFalse();
    }

    @Test
    @DisplayName( "scheduleAfter replaces a pending schedule (single in-flight)" )
    void replaceCancelsPrevious() throws InterruptedException
    {
        CountDownLatch first  = new CountDownLatch( 1 );
        CountDownLatch second = new CountDownLatch( 1 );

        // First scheduled far out; replaced immediately by a short one.
        scheduler.scheduleAfter( Duration.ofSeconds( 5 ), first::countDown );
        scheduler.scheduleAfter( Duration.ofMillis( 200 ), second::countDown );

        assertThat( second.await( 2, TimeUnit.SECONDS ) ).isTrue();
        assertThat( first.getCount() ).isEqualTo( 1L );          // first never fired
    }

    @Test
    @DisplayName( "a refresh runnable that throws doesn't break the scheduler" )
    void runnableThrowsIsContained() throws InterruptedException
    {
        CountDownLatch attempted = new CountDownLatch( 1 );
        scheduler.scheduleAfter( Duration.ofMillis( 200 ), () ->
                                 {
                                     try { throw new RuntimeException( "simulated refresh failure" ); }
                                     finally { attempted.countDown(); }
                                 } );

        // The exception is swallowed by the scheduler; the latch counts down
        // (proving the runnable ran) and a subsequent scheduleAfter still works.
        assertThat( attempted.await( 2, TimeUnit.SECONDS ) ).isTrue();

        CountDownLatch retry = new CountDownLatch( 1 );
        scheduler.scheduleAfter( Duration.ofMillis( 200 ), retry::countDown );
        assertThat( retry.await( 2, TimeUnit.SECONDS ) ).isTrue();
    }
}
