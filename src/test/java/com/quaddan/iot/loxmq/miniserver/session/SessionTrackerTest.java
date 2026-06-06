/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SessionTracker}. Pure JVM — the bean has no
 * CDI dependencies (only {@code @ApplicationScoped} for production
 * wiring), so we can {@code new SessionTracker()} directly.
 *
 * <p>Focus is on the handshake-duration tracking — the rest of the
 * state-machine invariants are exercised via
 * {@code SessionOrchestratorTest} and the various integration tests.
 */
@DisplayName( "SessionTracker — state transitions + handshake duration" )
class SessionTrackerTest
{
    @Test
    @DisplayName( "fresh tracker: state=DISCONNECTED, no handshake duration measured yet" )
    void freshTracker()
    {
        SessionTracker t = new SessionTracker();
        assertThat( t.state() ).isEqualTo( SessionState.DISCONNECTED );
        assertThat( t.lastHandshakeDuration() ).isEmpty();
        assertThat( t.connectedAt() ).isEmpty();
    }

    @Test
    @DisplayName( "CONNECTING → RUNNING: lastHandshakeDuration captured" )
    void handshakeDurationCaptured() throws InterruptedException
    {
        SessionTracker t = new SessionTracker();
        t.transition( SessionState.CONNECTING );
        // Sleep a tiny window to make the duration strictly > zero and
        // give us a meaningful assertion. 10 ms is enough on any CI box.
        Thread.sleep( 10 );
        t.transition( SessionState.RUNNING );

        assertThat( t.lastHandshakeDuration() ).isPresent();
        Duration d = t.lastHandshakeDuration().orElseThrow();
        assertThat( d ).isGreaterThanOrEqualTo( Duration.ofMillis( 5 ) );
        assertThat( d ).isLessThan( Duration.ofSeconds( 5 ) );  // hard sanity cap
    }

    @Test
    @DisplayName( "CONNECTING → FAILED: no handshake duration recorded (mid-handshake failure)" )
    void failedMidHandshake_NoDurationRecorded() throws InterruptedException
    {
        SessionTracker t = new SessionTracker();
        t.transition( SessionState.CONNECTING );
        Thread.sleep( 5 );
        t.fail( "timeout waiting for keyexchange" );

        // The start instant is discarded — the next attempt starts a fresh
        // measurement window. lastHandshakeDuration stays empty because no
        // successful CONNECTING → RUNNING has happened yet.
        assertThat( t.lastHandshakeDuration() ).isEmpty();
        assertThat( t.state() ).isEqualTo( SessionState.FAILED );
        assertThat( t.lastError() ).contains( "timeout waiting for keyexchange" );
    }

    @Test
    @DisplayName( "second handshake overwrites the first duration (most-recent wins)" )
    void secondHandshakeOverwrites() throws InterruptedException
    {
        SessionTracker t = new SessionTracker();

        // First handshake — quick.
        t.transition( SessionState.CONNECTING );
        Thread.sleep( 5 );
        t.transition( SessionState.RUNNING );
        Duration first = t.lastHandshakeDuration().orElseThrow();

        // Disconnect + second handshake — longer.
        t.transition( SessionState.DISCONNECTED );
        t.transition( SessionState.CONNECTING );
        Thread.sleep( 25 );
        t.transition( SessionState.RUNNING );
        Duration second = t.lastHandshakeDuration().orElseThrow();

        // The second duration should be the one exposed now.
        assertThat( second ).isGreaterThan( first );
    }

    @Test
    @DisplayName( "RUNNING without a prior CONNECTING: handshake duration NOT set (defensive)" )
    void runningWithoutConnecting_NoDuration()
    {
        SessionTracker t = new SessionTracker();
        // Skip CONNECTING and jump straight to RUNNING — this would be a
        // bug in calling code, but the tracker must not emit a garbage
        // duration (Instant.now() - null = NPE; the guard returns empty).
        t.transition( SessionState.RUNNING );
        assertThat( t.lastHandshakeDuration() ).isEmpty();
        assertThat( t.state() ).isEqualTo( SessionState.RUNNING );
    }

    @Test
    @DisplayName( "CLOSED clears connectedAt + token but PRESERVES the last handshake duration" )
    void closedKeepsHandshakeDuration() throws InterruptedException
    {
        SessionTracker t = new SessionTracker();
        t.transition( SessionState.CONNECTING );
        Thread.sleep( 5 );
        t.transition( SessionState.RUNNING );
        assertThat( t.lastHandshakeDuration() ).isPresent();

        t.transition( SessionState.CLOSED );

        // connectedAt should reset, but the historical handshake duration
        // is intentionally preserved — the operator can still see
        // "last successful handshake took X ms" after a clean close.
        assertThat( t.connectedAt() ).isEmpty();
        assertThat( t.lastHandshakeDuration() ).isPresent();
    }
}
