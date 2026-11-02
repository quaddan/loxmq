/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.session;

import com.quaddan.iot.loxmq.miniserver.message.MiniserverKeepAliveResponseEvent;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.enterprise.inject.Vetoed;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Mockito;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the keepalive liveness paths that recover from a Miniserver reboot:
 * <ol>
 *   <li>the <b>missed-response watchdog</b> — no keepalive reply for
 *       {@code keepaliveInterval × keepaliveMaxMissed} (half-open TCP), and</li>
 *   <li>a <b>failed keepalive send</b> (detectably dead socket).</li>
 * </ol>
 * Both must hand the dead link back to the orchestrator via
 * {@link SessionOrchestrator#onConnectionDead(String)} so it tears the
 * session down and schedules a reconnect.
 *
 * <p>The orchestrator is a Mockito mock installed via {@link QuarkusMock}
 * (resolved through the scheduler's {@code Instance<SessionOrchestrator>}),
 * so the interaction can be verified without a full handshake. The profile
 * pins a 50 ms interval and {@code max-missed=2} ⇒ a ~100 ms watchdog
 * timeout: fast enough for a unit test, yet long enough that the
 * send-failure tick (the first tick, ~50 ms) runs the send BEFORE the
 * watchdog would trip.
 */
@QuarkusTest
@TestProfile( KeepAliveWatchdogTest.WatchdogProfile.class )
@DisplayName( "KeepAliveScheduler — missed-response watchdog + send-failure reconnect" )
class KeepAliveWatchdogTest
{
    public static class WatchdogProfile implements QuarkusTestProfile
    {
        @Override
        public Map< String, String > getConfigOverrides()
        {
            return Map.of(
                    "loxone.miniserver.connection.ws.keepalive-interval", "PT0.05S",
                    "loxone.miniserver.connection.ws.keepalive-max-missed", "2" );
        }
    }

    @Inject
    KeepAliveScheduler scheduler;

    private RecordingWebSocket  ws;
    private SessionOrchestrator orchestratorMock;

    @BeforeEach
    void install()
    {
        ws = new RecordingWebSocket();
        QuarkusMock.installMockForType( ws, JdkMiniserverWebSocket.class );
        orchestratorMock = Mockito.mock( SessionOrchestrator.class );
        QuarkusMock.installMockForType( orchestratorMock, SessionOrchestrator.class );
        scheduler.resetStateForTests();
    }

    @AfterEach
    void teardown()
    {
        scheduler.resetStateForTests();
    }

    @Test
    @DisplayName( "no keepalive responses ⇒ watchdog trips ⇒ onConnectionDead + schedule cancelled" )
    @Timeout( value = 5, unit = TimeUnit.SECONDS )
    void watchdogTripsWhenNoResponses()
    {
        scheduler.onMiniserverConnected( fakeConnectedEvent() );

        Mockito.verify( orchestratorMock, Mockito.timeout( 3_000 ) )
               .onConnectionDead( Mockito.contains( "keepalive timeout" ) );
        assertThat( scheduler.isScheduled() ).isFalse();
    }

    @Test
    @DisplayName( "sendText throws ⇒ onConnectionDead(\"…send failed…\") + schedule cancelled" )
    @Timeout( value = 5, unit = TimeUnit.SECONDS )
    void sendFailureTriggersReconnect()
    {
        ws.failWith = new RuntimeException( "ws closed mid-send" );
        scheduler.onMiniserverConnected( fakeConnectedEvent() );

        Mockito.verify( orchestratorMock, Mockito.timeout( 3_000 ) )
               .onConnectionDead( Mockito.contains( "send failed" ) );
        assertThat( scheduler.isScheduled() ).isFalse();
    }

    @Test
    @DisplayName( "watchdog baseline uses fresh armedAt, NOT a stale lastResponseAt from a prior session (no reconnect loop)" )
    @Timeout( value = 5, unit = TimeUnit.SECONDS )
    void watchdogIgnoresStaleLastResponseAfterReArm()
    {
        // Reproduce the post-reconnect state that caused the ~60 s reconnect
        // loop: a keepalive response was recorded long ago (previous session),
        // then the session reconnected (re-arm with a fresh armedAt).
        scheduler.sendOne();  // armedAt still null → watchdog skipped; sets lastSentAt so the response below is accepted
        scheduler.onKeepAliveResponse(
                new MiniserverKeepAliveResponseEvent( Instant.now().minusSeconds( 600 ) ) ); // lastResponseAt = 10 min ago (STALE)
        scheduler.onMiniserverConnected( fakeConnectedEvent() );  // RE-ARM → armedAt = now (fresh)
        scheduler.cancel();  // stop the live schedule so only our manual tick runs

        int sentBefore = ws.sent.size();
        scheduler.sendOne();  // manual tick right after re-arm

        // With the stale-baseline bug this tripped immediately (before sending);
        // the fix takes since = max(armedAt=now, stale) = now → it sends instead.
        Mockito.verify( orchestratorMock, Mockito.never() ).onConnectionDead( Mockito.anyString() );
        assertThat( ws.sent.size() ).isGreaterThan( sentBefore );
    }

    private static MiniserverConnectedEvent fakeConnectedEvent()
    {
        return new MiniserverConnectedEvent( Instant.now(), Optional.empty() );
    }

    /** Recording WS fake — captures sends, optionally throws. Extends
     *  {@link JdkMiniserverWebSocket} so {@code QuarkusMock} can install it
     *  for the production type. */
    @Vetoed
    public static final class RecordingWebSocket extends JdkMiniserverWebSocket
    {
        final    List< String >    sent     = new CopyOnWriteArrayList<>();
        volatile RuntimeException  failWith = null;

        @Override public void connect( URI uri, Listener listener ) { /* no-op */ }

        @Override public void close( String reason ) { /* no-op */ }

        @Override public boolean isOpen() { return true; }

        @Override
        public void sendText( String text )
        {
            sent.add( text );
            if ( failWith != null )
            { throw failWith; }
        }
    }
}
