/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.management;

import com.quaddan.iot.loxmq.miniserver.session.SessionStateChangedEvent;
import com.quaddan.iot.loxmq.transport.MqttConnectedEvent;
import com.quaddan.iot.loxmq.transport.MqttDisconnectedEvent;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import java.time.Instant;

/**
 * Server-Sent Events stream that fans out session + broker state
 * changes to subscribed dashboards. Endpoint:
 *
 * <pre>
 *   GET /api/v1/state/stream
 *   Content-Type: text/event-stream
 * </pre>
 *
 * <h3>Why SSE (and not WebSocket)</h3>
 * Push is one-way (server → browser) for the dashboard's use-case.
 * SSE is simpler on both sides — no subprotocol negotiation, native
 * {@code EventSource} on the client (browser auto-reconnects on drop),
 * and a single {@code Multi<String>} return on the server. WebSocket
 * would have given us bidirectional channels for free, but the
 * dashboard never needs to push anything back through this channel —
 * it uses the existing REST surface for actions.
 *
 * <h3>Payload shape</h3>
 * Each emitted line is a JSON object with two stable fields:
 *
 * <pre>
 *   { "type": "session" | "mqtt-connected" | "mqtt-disconnected",
 *     "ts":   "2026-05-20T19:42:31.123Z",
 *     ...type-specific fields... }
 * </pre>
 *
 * The client doesn't need to interpret type-specific fields today —
 * it triggers a full {@code window.location.reload()} on any event,
 * letting the server-side Qute render produce the fresh state. The
 * type field is forwarded so future client code can render diffs
 * without a reload if performance ever matters (the dashboard is
 * static enough that a full reload is &lt; 100 ms locally).
 *
 * <h3>Threading</h3>
 * The {@link BroadcastProcessor} from SmallRye Mutiny is thread-safe
 * for {@code onNext} calls. We observe events {@code ObservesAsync}
 * for {@code SessionStateChangedEvent} (fired via {@code fireAsync}
 * from the WS callback thread — can't block it) and the synchronous
 * {@code @Observes} variant for the MQTT events (fired on the HiveMQ
 * event loop, which is OK to nudge briefly).
 */
@Path( "/api/v1" )
@ApplicationScoped
@Tag( name = "State stream",
      description = "Server-Sent Events stream — pushes session + broker state changes "
                    + "to the dashboard so it auto-refreshes without polling." )
public class StateStreamResource
{
    private static final Logger LOG = Logger.getLogger( StateStreamResource.class );

    /** Fan-out processor. Multiple browser tabs / clients can subscribe;
     *  each gets every event. Items pushed before a subscriber attaches
     *  are NOT replayed — that's fine: a dashboard subscribing late
     *  reads the current full state from {@code GET /api/v1/state} on
     *  initial page render, then catches deltas from this stream. */
    private final BroadcastProcessor< String > processor = BroadcastProcessor.create();

    /**
     * Class-level {@code @Path("/api/v1")} mirrors {@link ManagementResource}
     * — having two resources at the same base path is fine in JAX-RS as
     * long as each method has a distinct {@code @Path}, and avoids
     * RestEasy's quirk of resolving sub-path collisions ({@code /api/v1/state}
     * vs {@code /api/v1/state/stream}) by sometimes choosing the
     * longer-base-path class for ALL methods, which masked
     * {@code GET /api/v1/state} as 404 in the smoke tests.
     */
    @GET
    @Path( "/state/stream" )
    @Produces( MediaType.SERVER_SENT_EVENTS )
    public Multi< String > stream()
    {
        // `onOverflow().drop()` between the processor and each subscriber.
        // Without it, BroadcastProcessor invalidates the subscriber via
        // BackPressureFailure as soon as the browser doesn't request
        // before the next push (observed at ~5 state events/sec in
        // steady-state): RestEasy Reactive then logs `Exception in SSE
        // server handling, impossible to send it to client` and drops
        // the SSE connection → the browser reconnects and loses the
        // events from that window.
        //
        // `.drop()` substitutes for that behavior: the operator requests
        // unbounded upstream and drops items downstream when the
        // subscriber hasn't yet consumed the previous one. Acceptable on
        // the observability side — SSE is best-effort (not a command
        // channel). The `session` / `mqtt-*` events arrive in bursts of
        // a few per minute, never in contention with the state throughput;
        // the drop should never touch them in practice.
        return processor.onOverflow().drop();
    }

    // ------------------------------------------------------------------------
    //  CDI observers — translate events to SSE payloads.
    // ------------------------------------------------------------------------

    public void onSessionStateChanged( @ObservesAsync SessionStateChangedEvent ev )
    {
        String payload = """
                         {"type":"session","ts":"%s","previous":"%s","current":"%s","lastError":%s}""".formatted(
                ev.at().toString(),
                ev.previous().name(),
                ev.current().name(),
                ev.lastError() != null ? jsonString( ev.lastError() ) : "null" );
        emit( payload );
    }

    public void onMqttConnected( @Observes MqttConnectedEvent ev )
    {
        emit( """
              {"type":"mqtt-connected","ts":"%s"}""".formatted( Instant.now().toString() ) );
    }

    public void onMqttDisconnected( @Observes MqttDisconnectedEvent ev )
    {
        emit( """
              {"type":"mqtt-disconnected","ts":"%s","source":"%s","reason":%s}""".formatted(
                ev.at().toString(),
                ev.source(),
                jsonString( ev.reason() ) ) );
    }

    /**
     * Push a pre-serialised JSON payload of type {@code "state"} (live
     * states for the dashboard's "States live" tab). Called by
     * {@link LiveStateSseEnricher} for every Value / Text state
     * received from the binary decoder.
     *
     * <p>Pre-serialised because the enricher already has the metadata-
     * resolved fields handy (room / cat / name / formatted value / unit);
     * routing through a serialiser here would be redundant.
     */
    public void pushState( String preSerialisedJsonPayload )
    {
        emit( preSerialisedJsonPayload );
    }

    // ------------------------------------------------------------------------
    //  Internals
    // ------------------------------------------------------------------------

    private void emit( String payload )
    {
        // Defensive: a buggy subscriber that throws on onNext must not
        // kill the orchestrator/WS thread. BroadcastProcessor swallows
        // downstream exceptions per Reactive Streams contract, but the
        // catch covers e.g. a future change to a stricter processor.
        try
        {
            processor.onNext( payload );
        }
        catch ( RuntimeException e )
        {
            LOG.warnf( "SSE broadcast failed: %s — payload=%s", e.getMessage(), payload );
        }
    }

    /** Minimal JSON string escaper — we control the inputs (state names,
     *  error messages from the orchestrator) but {@code lastError} can
     *  contain quotes / backslashes / newlines from miniserver replies.
     *  Avoid pulling Jackson on a fire-and-forget hot path. */
    private static String jsonString( String raw )
    {
        StringBuilder sb = new StringBuilder( raw.length() + 8 );
        sb.append( '"' );
        for ( int i = 0; i < raw.length(); i++ )
        {
            char c = raw.charAt( i );
            switch ( c )
            {
                case '"' -> sb.append( "\\\"" );
                case '\\' -> sb.append( "\\\\" );
                case '\n' -> sb.append( "\\n" );
                case '\r' -> sb.append( "\\r" );
                case '\t' -> sb.append( "\\t" );
                default ->
                {
                    if ( c < 0x20 )
                    {
                        sb.append( String.format( "\\u%04x", ( int ) c ) );
                    }
                    else
                    {
                        sb.append( c );
                    }
                }
            }
        }
        sb.append( '"' );
        return sb.toString();
    }
}
