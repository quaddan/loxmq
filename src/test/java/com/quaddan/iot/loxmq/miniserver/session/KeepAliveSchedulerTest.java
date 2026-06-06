/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.session;

import com.quaddan.iot.loxmq.config.LoxoneConfig;
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

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link KeepAliveScheduler}: scheduling, send → text, RTT
 * computation on response, cancel on send failure.
 *
 * <h3>Why @QuarkusTest</h3>
 * The scheduler reads
 * {@code loxone.miniserver.connection.ws.keepalive-interval} and
 * {@code loxone.miniserver.cmd.keepalive} from {@link LoxoneConfig}.
 * Booting Quarkus is the cheapest way to wire the real config tree;
 * the test profile pins the interval down to 50 ms so the schedule
 * fires fast enough to assert from a JUnit test.
 *
 * <h3>WebSocket fake</h3>
 * {@link RecordingWebSocket} captures every {@link MiniserverWebSocket#sendText}
 * and optionally throws to exercise the cancel-on-failure path.
 * Installed via {@link QuarkusMock} so the production bean's
 * {@code @Inject MiniserverWebSocket} resolves to the fake.
 */
@QuarkusTest
@TestProfile( KeepAliveSchedulerTest.FastIntervalProfile.class )
@DisplayName( "KeepAliveScheduler — scheduling, RTT, cancel-on-failure" )
class KeepAliveSchedulerTest
{
    public static class FastIntervalProfile implements QuarkusTestProfile
    {
        @Override
        public Map< String, String > getConfigOverrides()
        {
            // 50 ms — short enough for the suite to stay fast, long enough
            // to keep CI flake at zero on a typical box.
            return Map.of( "loxone.miniserver.connection.ws.keepalive-interval", "PT0.05S" );
        }
    }

    @Inject
    KeepAliveScheduler scheduler;

    private RecordingWebSocket ws;

    @BeforeEach
    void install()
    {
        ws = new RecordingWebSocket();
        QuarkusMock.installMockForType( ws, JdkMiniserverWebSocket.class );
        // Full state reset — the scheduler is @ApplicationScoped, so a
        // sibling test that populated lastSentAtRef / lastRttRef would
        // leak its state into "responseBeforeAnySend_Ignored" if we
        // only call cancel() (which preserves the last RTT for the
        // dashboard's last-known-good display).
        scheduler.resetStateForTests();
    }

    @AfterEach
    void teardown()
    {
        scheduler.resetStateForTests();
    }

    @Test
    @DisplayName( "MiniserverConnectedEvent → schedule armed, isScheduled() true" )
    @Timeout( value = 5, unit = TimeUnit.SECONDS )
    void onConnected_armsSchedule()
    {
        scheduler.onMiniserverConnected( fakeConnectedEvent() );
        assertThat( scheduler.isScheduled() ).isTrue();
    }

    @Test
    @DisplayName( "scheduled ticks fire at the configured interval ⇒ ≥2 sends within 1 s" )
    @Timeout( value = 5, unit = TimeUnit.SECONDS )
    void scheduledTicks_FireRepeatedly() throws InterruptedException
    {
        scheduler.onMiniserverConnected( fakeConnectedEvent() );
        waitFor( () -> ws.sent.size() >= 2, Duration.ofSeconds( 2 ) );
        assertThat( ws.sent ).allMatch( "keepalive"::equals );
    }

    @Test
    @DisplayName( "sendOne directly: text sent and last RTT computed when response arrives" )
    @Timeout( value = 5, unit = TimeUnit.SECONDS )
    void sendOne_RttComputedOnResponse()
    {
        Instant before = Instant.now();
        scheduler.sendOne();
        Instant after = Instant.now();

        assertThat( ws.sent ).containsExactly( "keepalive" );

        // Drive a response 10 ms later; assert RTT lands in [arrived-after, arrived-before].
        Instant arrived = after.plusMillis( 10 );
        scheduler.onKeepAliveResponse( new MiniserverKeepAliveResponseEvent( arrived ) );

        Optional< Duration > rtt = scheduler.lastRtt();
        assertThat( rtt ).isPresent();
        assertThat( rtt.orElseThrow() )
                .isBetween( Duration.between( after, arrived ),
                            Duration.between( before, arrived ) );
        assertThat( scheduler.lastResponseAt() ).contains( arrived );
    }

    @Test
    @DisplayName( "response without prior send → ignored, no NPE, no RTT recorded" )
    @Timeout( value = 5, unit = TimeUnit.SECONDS )
    void responseBeforeAnySend_Ignored()
    {
        // No connected event, no sendOne — just a spurious response.
        scheduler.onKeepAliveResponse( new MiniserverKeepAliveResponseEvent( Instant.now() ) );
        assertThat( scheduler.lastRtt() ).isEmpty();
    }

    @Test
    @DisplayName( "sendText throws → schedule auto-cancels, isScheduled() flips to false" )
    @Timeout( value = 5, unit = TimeUnit.SECONDS )
    void sendException_CancelsSchedule() throws InterruptedException
    {
        ws.failWith = new RuntimeException( "ws closed mid-send" );
        scheduler.onMiniserverConnected( fakeConnectedEvent() );

        waitFor( () -> !scheduler.isScheduled(), Duration.ofSeconds( 2 ) );
        assertThat( scheduler.isScheduled() ).isFalse();
    }

    // -------------------------------------------------------------------------
    //  Helpers
    // -------------------------------------------------------------------------

    private static MiniserverConnectedEvent fakeConnectedEvent()
    {
        return new MiniserverConnectedEvent( Instant.now(), Optional.empty() );
    }

    /** Poll until the supplier returns true or the timeout elapses.
     *  Cheaper than dragging Awaitility in just for this. */
    private static void waitFor( java.util.function.BooleanSupplier cond, Duration timeout )
            throws InterruptedException
    {
        long deadline = System.nanoTime() + timeout.toNanos();
        while ( System.nanoTime() < deadline )
        {
            if ( cond.getAsBoolean() )
            { return; }
            Thread.sleep( 20 );
        }
    }

    /** Recording WS fake — captures sends in order, optionally throws.
     *  Extends {@link JdkMiniserverWebSocket} so {@code QuarkusMock} can
     *  install it for the production type. Parent {@code @Inject} fields
     *  stay null since we never invoke {@code super}. */
    @Vetoed
    public static final class RecordingWebSocket extends JdkMiniserverWebSocket
    {
        public final List< String >   sent     = new CopyOnWriteArrayList<>();
        public       RuntimeException failWith = null;

        @Override
        public void connect( URI uri, Listener listener ) { /* no-op */ }

        @Override
        public void close( String reason ) { /* no-op */ }

        @Override
        public boolean isOpen() { return true; }

        @Override
        public void sendText( String text )
        {
            sent.add( text );
            if ( failWith != null )
            { throw failWith; }
        }
    }
}
