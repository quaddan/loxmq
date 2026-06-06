/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.management;

import com.quaddan.iot.loxmq.miniserver.message.DecodedMessages;
import com.quaddan.iot.loxmq.miniserver.message.TextStatesEvent;
import com.quaddan.iot.loxmq.miniserver.message.ValueStatesEvent;
import com.quaddan.iot.loxmq.miniserver.session.ControlMetadata;
import com.quaddan.iot.loxmq.miniserver.session.LoxApp3MetadataResolver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Locale;

/**
 * Observe binary state events (Value + Text), enrich with metadata
 * resolved from LoxAPP3 (room / category / control name / format /
 * unit), and push to the dashboard SSE stream as event type
 * {@code "state"}.
 *
 * <h3>Why a separate bean (vs. modifying StatesPublisher)</h3>
 * Keeps the MQTT publish path (StatesPublisher) and the dashboard SSE
 * path (this class) independent — a regression in metadata resolution
 * can never wedge the MQTT publish hot path. Both observers receive the
 * same {@code ValueStatesEvent} / {@code TextStatesEvent} via CDI fan-out.
 *
 * <h3>Decisions</h3>
 * <ul>
 *   <li><b>Value + Text only</b> — DayTimer / Weather are structurally
 *       more complex (nested objects), out-of-scope for the live-states
 *       view.</li>
 *   <li><b>No throttle</b> — operator-set, observed flow is ~5
 *       updates/sec on a typical site; the browser handles it easily.
 *       Re-evaluate if a high-event miniserver is encountered.</li>
 *   <li><b>No backend buffer</b> — events not delivered to a subscribed
 *       browser are lost. The dashboard reconnects via {@code EventSource}
 *       auto-retry on drop; missed events during the disconnection
 *       window are accepted (the live view is best-effort observability).</li>
 *   <li><b>UNKNOWN UUIDs surfaced, not skipped</b> — the resolver
 *       returns {@link ControlMetadata#unknown()} for state-UUIDs not in
 *       the current LoxAPP3 index; we emit them anyway with
 *       {@code controlName="UNKNOWN"} so the operator sees what's
 *       arriving even if the structure file is stale.</li>
 * </ul>
 *
 * <h3>SSE payload shape</h3>
 * <pre>
 * {
 *   "type":      "state",
 *   "kind":      "value" | "text",
 *   "ts":        "2026-05-25T15:42:13.456Z",
 *   "uuid":      "1c40574b-0349-a847-07ffc10e78392737",
 *   "room":      "Salon",
 *   "cat":       "Température",
 *   "name":      "Sonde Salon",
 *   "value":     21.5,                  // number for value, string for text
 *   "formatted": "21.5°C",              // String.format(meta.format, value)
 *   "unit":      "°C"
 * }
 * </pre>
 * The JS handler on the dashboard {@code addEventListener('state', ...)} picks
 * this up. (Don't confuse with {@code "type":"session"} / etc. already
 * fanned out by {@code StateStreamResource} — the SSE {@code event:}
 * attribute is what routes; the JSON {@code type} field is cosmetic.)
 */
@ApplicationScoped
public class LiveStateSseEnricher
{
    private static final Logger LOG = Logger.getLogger( LiveStateSseEnricher.class );

    @Inject
    LoxApp3MetadataResolver resolver;
    @Inject
    StateStreamResource     streamResource;

    public void onValueStates( @ObservesAsync ValueStatesEvent event )
    {
        for ( DecodedMessages.ValueState s : event.valueStates().values() )
        {
            push( "value", s.uuid(), s.value() );
        }
    }

    public void onTextStates( @ObservesAsync TextStatesEvent event )
    {
        for ( DecodedMessages.TextState s : event.textStates().values() )
        {
            push( "text", s.uuid(), s.value() );
        }
    }

    /**
     * Build the JSON payload + dispatch via {@link StateStreamResource#pushState(String)}.
     * Hand-rolled JSON (same approach as StateStreamResource for the other
     * event types) — avoid pulling Jackson on a hot per-state path.
     */
    private void push( String kind, String uuid, Object rawValue )
    {
        ControlMetadata meta      = resolver.resolve( uuid );
        String          formatted = formatValue( rawValue, meta.format() );
        String          valueJson = jsonValue( rawValue );

        String payload = """
                         {"type":"state","kind":"%s","ts":"%s","uuid":"%s","room":%s,"cat":%s,"name":%s,"value":%s,"formatted":%s,"unit":%s}"""
                                 .formatted(
                                         kind,
                                         Instant.now().toString(),
                                         uuid,
                                         jsonString( meta.roomName() ),
                                         jsonString( meta.catName() ),
                                         jsonString( meta.controlName() ),
                                         valueJson,
                                         jsonString( formatted ),
                                         jsonString( meta.unit() ) );
        streamResource.pushState( payload );
    }

    /**
     * Apply the printf-style format from LoxAPP3 to the raw value. Falls
     * back to {@code String.valueOf(value)} if the format is empty or
     * {@code String.format} throws (e.g. the format is malformed for the
     * value type — observed for Text states whose control has a numeric
     * format declared).
     */
    private static String formatValue( Object rawValue, String format )
    {
        if ( format == null || format.isEmpty() || rawValue == null )
        {
            return String.valueOf( rawValue );
        }
        try
        {
            return String.format( Locale.ROOT, format, rawValue );
        }
        catch ( RuntimeException e )
        {
            // Format mismatch (e.g. %.1f on a String) — log once at TRACE
            // to avoid spam, return raw value.
            LOG.tracef( "format mismatch on %s with format=%s — fallback to raw",
                        rawValue.getClass().getSimpleName(), format );
            return String.valueOf( rawValue );
        }
    }

    /** Serialise the raw value to a JSON literal: numeric verbatim for
     *  Double, JSON-string-escaped for String. */
    private static String jsonValue( Object rawValue )
    {
        if ( rawValue == null )
        { return "null"; }
        if ( rawValue instanceof Double d )
        {
            if ( d.isNaN() || d.isInfinite() )
            {
                return "null";    // JSON has no NaN/Infinity
            }
            return d.toString();
        }
        return jsonString( rawValue.toString() );
    }

    /** Minimal JSON string escaper — same shape as
     *  {@code StateStreamResource#jsonString(String)} (which is private),
     *  duplicated here to avoid coupling. */
    private static String jsonString( String raw )
    {
        if ( raw == null )
        { return "null"; }
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
                    { sb.append( String.format( "\\u%04x", ( int ) c ) ); }
                    else
                    { sb.append( c ); }
                }
            }
        }
        sb.append( '"' );
        return sb.toString();
    }
}
