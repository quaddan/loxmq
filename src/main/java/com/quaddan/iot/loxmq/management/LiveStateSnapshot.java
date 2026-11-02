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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory "last known value per state-UUID" snapshot — the read-model the
 * {@code /commands} page queries ({@code GET /api/v1/state/current}) to show the
 * current state of a selected control without a round-trip to {@code /states}.
 *
 * <h3>Why this exists (vs. reading retained MQTT)</h3>
 * LoxMQ <em>is</em> the publisher of the retained state topics, so the value
 * the broker retains is the value this binding already decodes and fans out as
 * {@link ValueStatesEvent} / {@link TextStatesEvent}. Caching it here — at the
 * source, off the same CDI fan-out {@link LiveStateSseEnricher} consumes — gives
 * the exact retained value with no broker subscription, no credential exposure
 * to the browser, and no extra network hop. On every RUNNING transition the
 * Miniserver re-sends the full state table ({@code enablebinstatusupdate}), so
 * the snapshot is complete shortly after connect; commands can only be sent
 * while RUNNING anyway, so a value is always available when it matters.
 *
 * <h3>Freshness</h3>
 * The page reads this once on load for the immediate value, then keeps it
 * current from the live SSE stream ({@code /api/v1/state/stream}) — the same
 * stream the enricher feeds. The two stay consistent because both observe the
 * same event.
 *
 * <h3>Scope</h3>
 * Value + Text states only (same as {@link LiveStateSseEnricher}); DayTimer /
 * Weather are structurally richer and out of scope for this readout.
 */
@ApplicationScoped
public class LiveStateSnapshot
{
    private static final Logger LOG = Logger.getLogger( LiveStateSnapshot.class );

    @Inject
    LoxApp3MetadataResolver resolver;

    /** state-UUID → last known value. {@link ConcurrentHashMap}: written on the
     *  CDI async dispatcher, read on REST threads — no coarse lock needed. */
    private final Map< String, StateValue > latest = new ConcurrentHashMap<>();

    /**
     * Last known value of a single state.
     *
     * @param formatted printf-formatted value (unit embedded when the LoxAPP3
     *                  {@code details.format} carries one, e.g. {@code "21.5°C"});
     *                  the bare value otherwise
     * @param unit      the extracted unit alone ({@code ""} when none)
     * @param ts        capture instant (ISO-8601) — lets the page show age
     */
    public record StateValue(String formatted, String unit, String ts)
    {
    }

    public void onValueStates( @ObservesAsync ValueStatesEvent event )
    {
        for ( DecodedMessages.ValueState s : event.valueStates().values() )
        {
            put( s.uuid(), s.value() );
        }
    }

    public void onTextStates( @ObservesAsync TextStatesEvent event )
    {
        for ( DecodedMessages.TextState s : event.textStates().values() )
        {
            put( s.uuid(), s.value() );
        }
    }

    private void put( String uuid, Object rawValue )
    {
        if ( uuid == null )
        { return; }
        ControlMetadata meta = resolver.resolve( uuid );
        latest.put( uuid, new StateValue(
                formatValue( rawValue, meta.format() ),
                meta.unit(),
                Instant.now().toString() ) );
    }

    /** Immutable snapshot of the last known value per state-UUID. Returned
     *  verbatim by {@code GET /api/v1/state/current}. */
    public Map< String, StateValue > snapshot()
    {
        return Map.copyOf( latest );
    }

    /** Number of state-UUIDs currently held — exposed for tests / observability. */
    public int size()
    {
        return latest.size();
    }

    /**
     * Apply the printf-style LoxAPP3 format to the raw value — same contract as
     * {@code LiveStateSseEnricher.formatValue}: empty/missing format or a format
     * mismatch falls back to {@code String.valueOf(value)}.
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
            LOG.tracef( "format mismatch on %s with format=%s — fallback to raw",
                        rawValue.getClass().getSimpleName(), format );
            return String.valueOf( rawValue );
        }
    }
}
