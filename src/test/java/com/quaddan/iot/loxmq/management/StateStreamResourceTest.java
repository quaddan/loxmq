/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.management;

import com.quaddan.iot.loxmq.miniserver.session.SessionState;
import com.quaddan.iot.loxmq.miniserver.session.SessionStateChangedEvent;
import com.quaddan.iot.loxmq.transport.MqttConnectedEvent;
import com.quaddan.iot.loxmq.transport.MqttDisconnectedEvent;
import io.smallrye.mutiny.Multi;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link StateStreamResource}. Pure JVM — the bean has
 * no CDI dependencies on its own (the {@code @Observes} / {@code @ObservesAsync}
 * methods are wired by Quarkus ArC in production but can be invoked
 * directly here), so we can {@code new StateStreamResource()} and call
 * the observer methods like regular Java.
 *
 * <p>Asserts that each event type produces the documented JSON shape
 * (the dashboard JS parses {@code .type} to label the refresh) and
 * that subscribers attached to {@link StateStreamResource#stream()}
 * receive the broadcast.
 */
@DisplayName( "StateStreamResource — event-to-SSE-payload translation + fan-out" )
class StateStreamResourceTest
{
    @Test
    @DisplayName( "SessionStateChangedEvent → JSON with type/previous/current/lastError" )
    void sessionEventShape()
    {
        StateStreamResource r    = new StateStreamResource();
        List< String >      seen = subscribe( r );

        Instant at = Instant.parse( "2026-05-20T10:00:00Z" );
        r.onSessionStateChanged( new SessionStateChangedEvent(
                SessionState.CONNECTING, SessionState.RUNNING, at, null ) );

        assertThat( seen ).hasSize( 1 );
        assertThat( seen.get( 0 ) )
                .contains( "\"type\":\"session\"" )
                .contains( "\"ts\":\"2026-05-20T10:00:00Z\"" )
                .contains( "\"previous\":\"CONNECTING\"" )
                .contains( "\"current\":\"RUNNING\"" )
                .contains( "\"lastError\":null" );
    }

    @Test
    @DisplayName( "SessionStateChangedEvent with lastError: lastError is JSON-string-escaped" )
    void sessionEventEscapesLastError()
    {
        StateStreamResource r    = new StateStreamResource();
        List< String >      seen = subscribe( r );

        // Pick a payload that exercises quote + backslash + newline —
        // the three escapes that bite if the JSON serialiser is naive.
        String hairy = "boom \"reason\"\nwith\\backslash";
        r.onSessionStateChanged( new SessionStateChangedEvent(
                SessionState.CONNECTING, SessionState.FAILED, Instant.now(), hairy ) );

        assertThat( seen ).hasSize( 1 );
        // Each problematic char should be escaped: " → \", \ → \\, \n → \n
        assertThat( seen.get( 0 ) )
                .contains( "\"lastError\":\"boom \\\"reason\\\"\\nwith\\\\backslash\"" );
    }

    @Test
    @DisplayName( "MqttConnectedEvent → mqtt-connected JSON" )
    void mqttConnectedShape()
    {
        StateStreamResource r    = new StateStreamResource();
        List< String >      seen = subscribe( r );

        r.onMqttConnected( new MqttConnectedEvent() );

        assertThat( seen ).hasSize( 1 );
        assertThat( seen.get( 0 ) )
                .contains( "\"type\":\"mqtt-connected\"" )
                .contains( "\"ts\":\"" );  // timestamp is now() — just check the field exists
    }

    @Test
    @DisplayName( "MqttDisconnectedEvent → mqtt-disconnected JSON with source + reason" )
    void mqttDisconnectedShape()
    {
        StateStreamResource r    = new StateStreamResource();
        List< String >      seen = subscribe( r );

        Instant at = Instant.parse( "2026-05-20T10:05:00Z" );
        r.onMqttDisconnected( new MqttDisconnectedEvent( at, "SERVER", "Connection refused" ) );

        assertThat( seen ).hasSize( 1 );
        assertThat( seen.get( 0 ) )
                .contains( "\"type\":\"mqtt-disconnected\"" )
                .contains( "\"ts\":\"2026-05-20T10:05:00Z\"" )
                .contains( "\"source\":\"SERVER\"" )
                .contains( "\"reason\":\"Connection refused\"" );
    }

    @Test
    @DisplayName( "fan-out: multiple subscribers all receive each broadcast" )
    void fanOut()
    {
        StateStreamResource r    = new StateStreamResource();
        List< String >      sub1 = subscribe( r );
        List< String >      sub2 = subscribe( r );

        r.onMqttConnected( new MqttConnectedEvent() );
        r.onSessionStateChanged( new SessionStateChangedEvent(
                SessionState.DISCONNECTED, SessionState.CONNECTING, Instant.now(), null ) );

        assertThat( sub1 ).hasSize( 2 );
        assertThat( sub2 ).hasSize( 2 );
        // Same events, same order — fan-out doesn't reorder.
        assertThat( sub1 ).containsExactlyElementsOf( sub2 );
    }

    @Test
    @DisplayName( "subscriber attached AFTER emit does NOT see replayed past items" )
    void noReplay()
    {
        StateStreamResource r = new StateStreamResource();

        // Emit BEFORE subscribing.
        r.onMqttConnected( new MqttConnectedEvent() );

        List< String > late = subscribe( r );
        // Emit a second event AFTER the late subscriber attached.
        r.onMqttConnected( new MqttConnectedEvent() );

        // Only the post-subscribe event should be visible — see
        // StateStreamResource javadoc on "items pushed before a
        // subscriber attaches are NOT replayed".
        assertThat( late ).hasSize( 1 );
    }

    // ------------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------------

    /** Subscribe to the resource's stream, returning a thread-safe list
     *  that accumulates each emitted payload. The {@link Multi#subscribe()}
     *  call returns a cancellation handle we ignore — the resource lives
     *  exactly for the test, no leaks. */
    private static List< String > subscribe( StateStreamResource r )
    {
        List< String >  seen   = new CopyOnWriteArrayList<>();
        Multi< String > stream = r.stream();
        stream.subscribe().with( seen::add );
        // Tiny wait so the subscription completes before we emit — Mutiny
        // subscribe().with() is synchronous on a hot publisher like
        // BroadcastProcessor, but be explicit to make the test robust.
        sleepBriefly();
        return seen;
    }

    private static void sleepBriefly()
    {
        try { Thread.sleep( Duration.ofMillis( 5 ) ); }
        catch ( InterruptedException e ) { Thread.currentThread().interrupt(); }
    }
}
