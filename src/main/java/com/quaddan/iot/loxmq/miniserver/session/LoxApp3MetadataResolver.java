/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Build + maintain an inverse index <strong>state-UUID → {@link ControlMetadata}</strong>
 * from the cached LoxAPP3 structure file. Used by
 * {@code LiveStateSseEnricher} to enrich {@code ValueStatesEvent}
 * and {@code TextStatesEvent} payloads with human-readable
 * room / category / control names + value format / unit before they're
 * pushed to the dashboard SSE stream.
 *
 * <h3>Why an inverse index</h3>
 * The LoxAPP3 JSON ships an "object-oriented" view:
 * <pre>
 *   "controls": {
 *      "&lt;control-UUID&gt;": {
 *          "name": "Sonde Salon", "room": "&lt;room-UUID&gt;", "cat": "&lt;cat-UUID&gt;",
 *          "details": { "format": "%.1f°C" },
 *          "states": { "value": "&lt;state-UUID&gt;",  ... }
 *      }
 *   },
 *   "rooms": { "&lt;room-UUID&gt;": { "name": "Salon" } },
 *   "cats":  { "&lt;cat-UUID&gt;":  { "name": "Température" } }
 * </pre>
 *
 * State events arriving from the binary decoder carry only the
 * <em>state-UUID</em> (the leaf inside {@code controls[x].states}). To
 * surface "Salon / Température / Sonde Salon" we need to walk that tree
 * once and build a reverse map keyed by state-UUID. ~258 KB of LoxAPP3
 * parse to ~hundreds-of-controls in-memory map at boot.
 *
 * <h3>Lifecycle</h3>
 * Re-parsed on every {@link MiniserverConnectedEvent} (fired when the
 * orchestrator reaches RUNNING with a fresh LoxAPP3 loaded). The
 * resolver is therefore always consistent with what's in
 * {@link LoxApp3Cache} — no manual refresh needed.
 *
 * <h3>Volatile single-reference swap</h3>
 * The internal map is swapped via a {@code volatile} reference on each
 * reparse — readers see a consistent snapshot at all times, no lock
 * needed on the hot {@link #resolve(String)} path. Old map gets GC'd
 * once the last in-flight resolve completes.
 *
 * <h3>Cache miss strategy</h3>
 * Unknown state-UUIDs return {@link ControlMetadata#unknown()} instead
 * of {@link Optional#empty()} — operator decision to surface
 * orphan states in the dashboard with name="UNKNOWN" + the raw value,
 * rather than silently drop them.
 */
@ApplicationScoped
public class LoxApp3MetadataResolver
{
    private static final Logger LOG = Logger.getLogger( LoxApp3MetadataResolver.class );

    @Inject
    LoxApp3Cache cache;
    @Inject
    ObjectMapper jsonMapper;

    /** Inverse map state-UUID → ControlMetadata. Volatile single-reference
     *  swap on each reparse — readers don't lock. Starts empty until the
     *  first {@link MiniserverConnectedEvent} fires + populates. */
    private volatile Map< String, ControlMetadata > stateUuidToControl = Map.of();

    /**
     * Snapshot of the LoxAPP3 topology — sorted lists of room / category
     * names + deduplicated list of controls with their (room, cat).
     * Used by {@code LiveStatesResource} to pre-populate the filter
     * dropdowns of the States live panel, cascading:
     *
     * <ul>
     *   <li>All rooms appear even if no event has been received on
     *       their controls</li>
     *   <li>Categories filtered by selected room</li>
     *   <li>Control names filtered by selected room + category</li>
     * </ul>
     *
     * The cascading filter is computed client-side from this snapshot.
     * No server round-trip on each dropdown change.
     */
    public record Topology(List< String > rooms,
                           List< String > categories,
                           List< ControlInfo > controls)
    {
        public static Topology empty() { return new Topology( List.of(), List.of(), List.of() ); }
    }

    /**
     * Compact info to pre-populate + cascade-filter the dropdowns of the
     * States live panel. No format / unit here (used for filters, not for
     * value display).
     */
    public record ControlInfo(String controlName,
                              String roomName,
                              String catName)
    {
    }

    /** Topology snapshot updated in the same pass as {@link #stateUuidToControl}
     *  on reparse. Same consistency guarantee: volatile single-reference swap. */
    private volatile Topology topology = Topology.empty();

    /**
     * Operating modes index — id (int) → display name. Read from the
     * {@code operatingModes} block of the LoxAPP3. Used by
     * {@code SchedulesPageResource} to populate the {@code <select>} in
     * the calendar entry creation form (instead of an opaque number
     * input). Empty map until the first reparse happens → the page
     * falls back to a generic numeric input.
     */
    private volatile Map< Integer, String > operatingModes = Map.of();

    /**
     * Re-parse the LoxAPP3 from the cache when the session reaches RUNNING.
     * Async observer — runs on the CDI dispatcher thread, doesn't block the
     * session orchestrator. The map swap is volatile, so concurrent readers
     * either see the old or the new map atomically.
     */
    public void onMiniserverConnected( @ObservesAsync MiniserverConnectedEvent event )
    {
        Optional< String > json = cache.load();
        if ( json.isEmpty() )
        {
            LOG.warn( "LoxApp3 cache empty at MiniserverConnectedEvent — leaving the metadata index empty. "
                      + "Resolve will return ControlMetadata.unknown() for every state UUID until next reconnect." );
            stateUuidToControl = Map.of();
            return;
        }
        try
        {
            Map< String, ControlMetadata > next         = parse( json.get() );
            Topology                       nextTopology = computeTopology( next );
            Map< Integer, String >         nextOpModes  = parseOperatingModes( json.get() );
            // Swap order: map first (used by the hot path resolve()),
            // then topology (only used by the /states page on load),
            // then operatingModes (only used by the /schedules page).
            // No cross-field atomicity guarantee, but the order minimizes
            // the window where resolve() sees the new map and /states
            // sees the old topology (worst case: a freshly renamed
            // control appears in resolve() but not yet in the dropdown
            // — auto-resolved on the next page F5 refresh).
            stateUuidToControl = next;
            topology           = nextTopology;
            operatingModes     = nextOpModes;
            LOG.infof( "LoxApp3 metadata indexed: %d state UUIDs mapped to controls (rooms=%d, cats=%d, unique controls=%d, opModes=%d)",
                       ( Integer ) next.size(),
                       ( Integer ) nextTopology.rooms().size(),
                       ( Integer ) nextTopology.categories().size(),
                       ( Integer ) nextTopology.controls().size(),
                       ( Integer ) nextOpModes.size() );
        }
        catch ( Exception e )
        {
            LOG.warnf( e, "LoxApp3 metadata parse failed — keeping previous index (size=%d). Live states will use stale names until next reconnect.",
                       ( Integer ) stateUuidToControl.size() );
        }
    }

    /**
     * Current snapshot of the topology. Used by {@code LiveStatesResource}
     * to pre-populate the filter dropdowns. Returns {@link Topology#empty()}
     * until the first reparse happens — the States live page will then
     * show empty dropdowns + a "topology not yet loaded" message until
     * the next MiniserverConnectedEvent.
     */
    public Topology topology()
    {
        return topology;
    }

    /**
     * Current snapshot of the operating modes. Numeric id → display
     * name configured by the operator in Loxone Config. Returns
     * an empty map until the first reparse happens — the Schedules page
     * falls back to a generic number input in that case.
     */
    public Map< Integer, String > operatingModes()
    {
        return operatingModes;
    }

    /**
     * Build the Topology snapshot from the state-UUID → metadata map.
     * Deduplicate controls (a Control often has multiple state-UUIDs
     * pointing to the same ControlMetadata — we keep a single entry per
     * (controlName, roomName, catName) tuple), lexicographic sort of
     * rooms and categories.
     */
    private static Topology computeTopology( Map< String, ControlMetadata > index )
    {
        TreeSet< String > rooms = new TreeSet<>();    // natural lex sort
        TreeSet< String > cats  = new TreeSet<>();
        // Dedup controls: a Set lexicographically ordered by name + an
        // intermediate Map to avoid creating the same ControlInfo twice.
        // Loxone-controls often share multiple state-UUIDs (e.g.
        // "active" + "value" + "min" + "max" for a thermostat) → we
        // deduplicate on the (name, room, cat) tuple.
        java.util.LinkedHashMap< String, ControlInfo > controls = new java.util.LinkedHashMap<>();
        for ( ControlMetadata m : index.values() )
        {
            rooms.add( m.roomName() );
            cats.add( m.catName() );
            String key = m.controlName() + "|" + m.roomName() + "|" + m.catName();
            controls.putIfAbsent( key, new ControlInfo( m.controlName(), m.roomName(), m.catName() ) );
        }
        // Sort controls by name for ops consistency.
        List< ControlInfo > sortedControls = new ArrayList<>( controls.values() );
        sortedControls.sort( ( a, b ) -> a.controlName().compareToIgnoreCase( b.controlName() ) );
        return new Topology(
                List.copyOf( rooms ),
                List.copyOf( cats ),
                List.copyOf( sortedControls ) );
    }

    /**
     * Resolve a state-UUID to its {@link ControlMetadata}. Never returns
     * {@code null}: an unknown UUID yields {@link ControlMetadata#unknown()}
     * — the dashboard surface displays "UNKNOWN" + raw value rather than
     * silently dropping the state event.
     */
    public ControlMetadata resolve( String stateUuid )
    {
        if ( stateUuid == null )
        { return ControlMetadata.unknown(); }
        ControlMetadata m = stateUuidToControl.get( stateUuid );
        return m != null ? m : ControlMetadata.unknown();
    }

    /** Size of the current index — exposed for tests + observability. */
    public int size()
    {
        return stateUuidToControl.size();
    }

    // =====================================================================
    //  Parser (package-private for unit tests)
    // =====================================================================

    /**
     * Build the inverse map from a LoxAPP3 JSON string. Pure function —
     * no side effects, no dependencies on the cache. Visible-for-test.
     *
     * <p>Tolerant: a malformed control / room / cat entry is skipped
     * with a TRACE log rather than failing the entire reindex.
     */
    Map< String, ControlMetadata > parse( String loxApp3Json ) throws Exception
    {
        JsonNode root = jsonMapper.readTree( loxApp3Json );

        // First pass: rooms + cats name maps (lookup tables for the
        // second pass that walks the controls).
        Map< String, String > roomNames = readNameMap( root.path( "rooms" ) );
        Map< String, String > catNames  = readNameMap( root.path( "cats" ) );

        // Second pass: walk controls.{uuid}.states.{stateName: stateUuid}
        // and build the inverse mapping.
        Map< String, ControlMetadata > index    = new HashMap<>();
        JsonNode                       controls = root.path( "controls" );
        if ( controls.isObject() )
        {
            for ( Map.Entry< String, JsonNode > entry : controls.properties() )
            {
                JsonNode control = entry.getValue();
                if ( !control.isObject() )
                { continue; }

                String controlName = control.path( "name" ).asText( "" );
                String roomUuid    = control.path( "room" ).asText( "" );
                String catUuid     = control.path( "cat" ).asText( "" );
                String roomName    = roomNames.getOrDefault( roomUuid, "" );
                String catName     = catNames.getOrDefault( catUuid, "" );
                String format      = control.path( "details" ).path( "format" ).asText( "" );
                String unit        = extractUnit( format );

                ControlMetadata meta = new ControlMetadata(
                        controlName.isEmpty() ? "UNKNOWN" : controlName,
                        roomName.isEmpty() ? "UNKNOWN" : roomName,
                        catName.isEmpty() ? "UNKNOWN" : catName,
                        format,
                        unit );

                JsonNode states = control.path( "states" );
                if ( states.isObject() )
                {
                    for ( Map.Entry< String, JsonNode > s : states.properties() )
                    {
                        // The state-UUID may be a String OR an Array of strings
                        // (a single Control can have multiple state-UUIDs for the
                        // same state name — observed on "active" for some HVAC
                        // controls). Index both flavours.
                        JsonNode v = s.getValue();
                        if ( v.isTextual() )
                        {
                            index.put( v.asText(), meta );
                        }
                        else if ( v.isArray() )
                        {
                            for ( JsonNode item : v )
                            {
                                if ( item.isTextual() )
                                { index.put( item.asText(), meta ); }
                            }
                        }
                    }
                }
            }
        }

        return Map.copyOf( index );    // immutable snapshot for the swap
    }

    /**
     * Read the {@code operatingModes} block from the LoxAPP3 root JSON
     * and convert string-keyed entries to an {@code Integer →
     * displayName} map.
     *
     * <p>LoxAPP3 shape (V17):
     * <pre>{@code
     *   "operatingModes": {
     *     "0":  "Au bureau",
     *     "1":  "Salon",
     *     "10": "Période de chauffage"
     *   }
     * }</pre>
     *
     * <p>Tolerant: malformed entries (non-int key, blank value) are
     * skipped silently. Empty / missing block → empty map (page falls
     * back to a generic input number).
     */
    Map< Integer, String > parseOperatingModes( String loxApp3Json ) throws Exception
    {
        JsonNode root  = jsonMapper.readTree( loxApp3Json );
        JsonNode block = root.path( "operatingModes" );
        if ( !block.isObject() )
        { return Map.of(); }
        Map< Integer, String > out = new HashMap<>();
        for ( Map.Entry< String, JsonNode > e : block.properties() )
        {
            String   key = e.getKey();
            String   label;
            JsonNode v   = e.getValue();
            if ( v.isTextual() )
            {
                label = v.asText();
            }
            else if ( v.isObject() )
            {
                // Tolerant: some firmwares wrap in {"name":"…"}.
                label = v.path( "name" ).asText( "" );
            }
            else
            {
                continue;
            }
            if ( label.isEmpty() )
            { continue; }
            try
            {
                out.put( Integer.parseInt( key ), label );
            }
            catch ( NumberFormatException nfe )
            {
                // Skip non-int keys silently — never observed in V17 but
                // belt-and-braces against future firmware drift.
            }
        }
        return out;
    }

    /** Build a UUID → name map from a LoxAPP3 sub-object (rooms or cats).
     *  Tolerant: skip entries without a {@code name}. */
    private static Map< String, String > readNameMap( JsonNode obj )
    {
        Map< String, String > out = new HashMap<>();
        if ( !obj.isObject() )
        { return out; }
        for ( Map.Entry< String, JsonNode > e : obj.properties() )
        {
            String name = e.getValue().path( "name" ).asText( "" );
            if ( !name.isEmpty() )
            { out.put( e.getKey(), name ); }
        }
        return out;
    }

    // =====================================================================
    //  Unit extraction from Loxone "details.format" (visible-for-test)
    // =====================================================================

    /**
     * Strip the printf-style conversion specifier from a Loxone
     * {@code details.format} value, returning what's left = the unit.
     *
     * <p>Loxone format examples + expected output:
     * <pre>
     *   "%.1f°C"  → "°C"
     *   "%.0f%%"  → "%"
     *   "%.2flx"  → "lx"
     *   "%dW"     → "W"
     *   "%.1f"    → ""     (no unit)
     *   ""        → ""
     *   "&lt;v.t&gt;"     → ""     (non-printf, no extractable unit)
     * </pre>
     *
     * Regex matches the conversion spec {@code %[flags][width][.precision]conv}
     * with conv ∈ {@code [dfsexgEXG]} (the integer + float + string + scientific
     * conversions Loxone emits in practice).
     */
    private static final Pattern PRINTF_SPEC = Pattern.compile(
            "%[-+#0 ]*\\d*(?:\\.\\d+)?[dfsexgDFSEXG]" );

    static String extractUnit( String format )
    {
        if ( format == null || format.isEmpty() )
        { return ""; }
        Matcher m = PRINTF_SPEC.matcher( format );
        if ( !m.find() )
        {
            return "";    // no printf spec → can't extract
        }
        // The unit is everything after the LAST printf spec (Loxone typically
        // has only one, but defensive in case of "%.0f → %.1f").
        int end = 0;
        while ( m.find( end == 0 ? 0 : end ) )
        {
            end = m.end();
        }
        String tail = format.substring( end );
        // Loxone escapes literal "%" as "%%" — surface it as "%" in the unit.
        return tail.replace( "%%", "%" );
    }
}
