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
     * States live panel AND the Commands page. No format / unit here.
     *
     * <p>{@code type} is the Loxone control type (e.g. {@code "Switch"},
     * {@code "Jalousie"}) — drives the per-type command list on the Commands
     * page. {@code uuid} is the control's {@code uuidAction} (the UUID a
     * command is addressed to), falling back to the control's own UUID key
     * when {@code uuidAction} is absent.
     */
    public record ControlInfo(String controlName,
                              String roomName,
                              String catName,
                              String type,
                              String uuid)
    {
    }

    /** A named state of a control: its Loxone state name (e.g.
     *  {@code "position"}, {@code "active"}) + the state-UUID that carries
     *  its live value. */
    public record StateRef(String name, String uuid)
    {
    }

    /**
     * Richer, command-oriented view of the topology, used by the Commands page.
     * Unlike {@link #topology()} (top-level controls only — for /states), this
     * surface ALSO exposes {@code subControls} (e.g. the individual Dimmer /
     * Switch outputs of a {@code LightControllerV2}) as first-class selectable
     * controls, and carries, per control, the list of its named
     * {@link StateRef states}.
     *
     * <p>From {@code controlStates} the page derives both:
     * <ul>
     *   <li>a <strong>state-UUID → command-target UUID</strong> lookup so a
     *       pasted <em>state</em> UUID (what MQTT and /states surface) resolves
     *       to the {@code uuidAction} a command is addressed to;</li>
     *   <li>the list of a selected control's states to display their current
     *       value (fetched from {@code GET /api/v1/state/current}).</li>
     * </ul>
     *
     * @param topology      controls (top-level + sub-controls) + room / cat lists
     * @param controlStates command-target UUID → its named states
     */
    public record CommandSurface(Topology topology, Map< String, List< StateRef > > controlStates)
    {
        public static CommandSurface empty() { return new CommandSurface( Topology.empty(), Map.of() ); }
    }

    /** Topology snapshot updated in the same pass as {@link #stateUuidToControl}
     *  on reparse. Same consistency guarantee: volatile single-reference swap. */
    private volatile Topology topology = Topology.empty();

    /** Command-oriented surface (topology incl. sub-controls + state index).
     *  Same volatile single-reference swap as {@link #topology}. */
    private volatile CommandSurface commandSurface = CommandSurface.empty();

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
            Topology                       nextTopology = computeTopology( json.get() );
            CommandSurface                 nextSurface  = computeCommandSurface( json.get() );
            Map< Integer, String >         nextOpModes  = parseOperatingModes( json.get() );
            // Swap order: map first (used by the hot path resolve()),
            // then topology (only used by the /states page on load),
            // then the command surface (only used by the /commands page),
            // then operatingModes (only used by the /schedules page).
            // No cross-field atomicity guarantee, but the order minimizes
            // the window where resolve() sees the new map and /states
            // sees the old topology (worst case: a freshly renamed
            // control appears in resolve() but not yet in the dropdown
            // — auto-resolved on the next page F5 refresh).
            stateUuidToControl = next;
            topology           = nextTopology;
            commandSurface     = nextSurface;
            operatingModes     = nextOpModes;
            LOG.infof( "LoxApp3 metadata indexed: %d state UUIDs mapped to controls (rooms=%d, cats=%d, unique controls=%d, command controls incl. sub=%d, command controls-with-states=%d, opModes=%d)",
                       ( Integer ) next.size(),
                       ( Integer ) nextTopology.rooms().size(),
                       ( Integer ) nextTopology.categories().size(),
                       ( Integer ) nextTopology.controls().size(),
                       ( Integer ) nextSurface.topology().controls().size(),
                       ( Integer ) nextSurface.controlStates().size(),
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
     * Current snapshot of the command surface — topology including
     * sub-controls plus the state-UUID → command-target index. Used by
     * {@code CommandsPageResource}. Returns {@link CommandSurface#empty()}
     * until the first reparse happens.
     */
    public CommandSurface commandSurface()
    {
        return commandSurface;
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
     * Build the Topology snapshot directly from the {@code controls} block of
     * the LoxAPP3 (one entry per control, carrying its {@code type} +
     * command-target {@code uuid}) — unlike the state index, which is keyed by
     * state-UUID. Walks the same JSON the state parse does (cheap; only on a
     * RUNNING transition, same pattern as {@link #parseOperatingModes}).
     * Lexicographic sort of rooms + categories; controls sorted by name.
     *
     * <p>Dedup key includes the UUID so EVERY distinct control is present —
     * the Commands page's UUID filter can then locate any control, even two
     * sharing the same (name, room, cat).
     */
    Topology computeTopology( String loxApp3Json ) throws Exception
    {
        JsonNode              root      = jsonMapper.readTree( loxApp3Json );
        Map< String, String > roomNames = readNameMap( root.path( "rooms" ) );
        Map< String, String > catNames  = readNameMap( root.path( "cats" ) );

        TreeSet< String >                              rooms    = new TreeSet<>();    // natural lex sort
        TreeSet< String >                              cats     = new TreeSet<>();
        java.util.LinkedHashMap< String, ControlInfo > controls = new java.util.LinkedHashMap<>();

        JsonNode controlsNode = root.path( "controls" );
        if ( controlsNode.isObject() )
        {
            for ( Map.Entry< String, JsonNode > entry : controlsNode.properties() )
            {
                JsonNode control = entry.getValue();
                if ( !control.isObject() )
                { continue; }

                String name = control.path( "name" ).asText( "" );
                String room = roomNames.getOrDefault( control.path( "room" ).asText( "" ), "" );
                String cat  = catNames.getOrDefault( control.path( "cat" ).asText( "" ), "" );
                String type = control.path( "type" ).asText( "" );
                // Command target: uuidAction when present, else the control's own UUID key.
                String uuid = control.path( "uuidAction" ).asText( entry.getKey() );

                name = name.isEmpty() ? "UNKNOWN" : name;
                room = room.isEmpty() ? "UNKNOWN" : room;
                cat  = cat.isEmpty() ? "UNKNOWN" : cat;
                type = type.isEmpty() ? "UNKNOWN" : type;

                rooms.add( room );
                cats.add( cat );
                String key = name + "|" + room + "|" + cat + "|" + uuid;
                controls.putIfAbsent( key, new ControlInfo( name, room, cat, type, uuid ) );
            }
        }

        List< ControlInfo > sortedControls = new ArrayList<>( controls.values() );
        sortedControls.sort( ( a, b ) -> a.controlName().compareToIgnoreCase( b.controlName() ) );
        return new Topology(
                List.copyOf( rooms ),
                List.copyOf( cats ),
                List.copyOf( sortedControls ) );
    }

    /**
     * Build the command-oriented {@link CommandSurface} for the Commands page.
     * Like {@link #computeTopology} but, crucially, it <strong>recurses into
     * {@code subControls}</strong> so nested commandable controls (e.g. each
     * Dimmer / Switch output of a {@code LightControllerV2}) become selectable
     * entries addressed by their own {@code uuidAction} — exactly the
     * {@code jdev/sps/io/<uuidAction>/<cmd>} form the send path already uses
     * (uuidAction may carry a channel suffix like {@code /AI2}). It also builds
     * the <strong>state-UUID → command-target UUID</strong> index so the page
     * can resolve a pasted state UUID (the kind MQTT and /states expose) to the
     * control that command is sent to.
     *
     * <p>Sub-control names are prefixed with the parent name
     * ({@code "Parent › Child"}) for disambiguation; room / category are
     * inherited from the enclosing top-level control (sub-controls carry none).
     *
     * <p>{@link #topology()} and {@link #parse()} are deliberately left
     * untouched: the /states page keeps its top-level-only view and enrichment.
     */
    CommandSurface computeCommandSurface( String loxApp3Json ) throws Exception
    {
        JsonNode              root      = jsonMapper.readTree( loxApp3Json );
        Map< String, String > roomNames = readNameMap( root.path( "rooms" ) );
        Map< String, String > catNames  = readNameMap( root.path( "cats" ) );

        TreeSet< String >                              rooms         = new TreeSet<>();
        TreeSet< String >                              cats          = new TreeSet<>();
        java.util.LinkedHashMap< String, ControlInfo > controls      = new java.util.LinkedHashMap<>();
        Map< String, List< StateRef > >                controlStates = new HashMap<>();

        JsonNode controlsNode = root.path( "controls" );
        if ( controlsNode.isObject() )
        {
            for ( Map.Entry< String, JsonNode > entry : controlsNode.properties() )
            {
                JsonNode control = entry.getValue();
                if ( !control.isObject() )
                { continue; }

                String room = roomNames.getOrDefault( control.path( "room" ).asText( "" ), "" );
                String cat  = catNames.getOrDefault( control.path( "cat" ).asText( "" ), "" );
                room = room.isEmpty() ? "UNKNOWN" : room;
                cat  = cat.isEmpty() ? "UNKNOWN" : cat;
                rooms.add( room );
                cats.add( cat );

                addControl( controls, controlStates, entry.getKey(), control, room, cat, "" );
            }
        }

        List< ControlInfo > sortedControls = new ArrayList<>( controls.values() );
        sortedControls.sort( ( a, b ) -> a.controlName().compareToIgnoreCase( b.controlName() ) );
        Topology topo = new Topology(
                List.copyOf( rooms ),
                List.copyOf( cats ),
                List.copyOf( sortedControls ) );
        return new CommandSurface( topo, Map.copyOf( controlStates ) );
    }

    /**
     * Add one control (then, recursively, its {@code subControls}) to the
     * command surface: one {@link ControlInfo} + the list of its named
     * {@link StateRef states}, keyed by the control's command-target UUID.
     *
     * @param namePrefix parent display name for a sub-control ({@code ""} at
     *                   the top level) — produces {@code "Parent › Child"}
     */
    private static void addControl( Map< String, ControlInfo > controls,
                                    Map< String, List< StateRef > > controlStates,
                                    String key, JsonNode control,
                                    String room, String cat, String namePrefix )
    {
        String name = control.path( "name" ).asText( "" );
        name = name.isEmpty() ? "UNKNOWN" : name;
        String display = namePrefix.isEmpty() ? name : namePrefix + " › " + name;
        String type = control.path( "type" ).asText( "" );
        type = type.isEmpty() ? "UNKNOWN" : type;
        // Command target: uuidAction when present, else the control's own UUID key.
        String uuid = control.path( "uuidAction" ).asText( key );

        String ckey = display + "|" + room + "|" + cat + "|" + uuid;
        controls.putIfAbsent( ckey, new ControlInfo( display, room, cat, type, uuid ) );

        // Collect this control's named states (each carries a state-UUID that
        // delivers its live value). A state value may be a single UUID or an
        // array of them — index each under the same state name.
        JsonNode states = control.path( "states" );
        if ( states.isObject() )
        {
            List< StateRef > refs = new ArrayList<>();
            for ( Map.Entry< String, JsonNode > s : states.properties() )
            {
                String   sname = s.getKey();
                JsonNode v     = s.getValue();
                if ( v.isTextual() )
                {
                    refs.add( new StateRef( sname, v.asText() ) );
                }
                else if ( v.isArray() )
                {
                    for ( JsonNode item : v )
                    {
                        if ( item.isTextual() )
                        { refs.add( new StateRef( sname, item.asText() ) ); }
                    }
                }
            }
            if ( !refs.isEmpty() )
            {
                controlStates.merge( uuid, refs, ( a, b ) ->
                {
                    a.addAll( b );
                    return a;
                } );
            }
        }

        // Recurse into sub-controls (inherit parent room / cat; prefix name).
        JsonNode subControls = control.path( "subControls" );
        if ( subControls.isObject() )
        {
            for ( Map.Entry< String, JsonNode > sub : subControls.properties() )
            {
                if ( !sub.getValue().isObject() )
                { continue; }
                addControl( controls, controlStates, sub.getKey(), sub.getValue(), room, cat, display );
            }
        }
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

                String roomName = roomNames.getOrDefault( control.path( "room" ).asText( "" ), "" );
                String catName  = catNames.getOrDefault( control.path( "cat" ).asText( "" ), "" );
                // Walk the control AND its sub-controls (which inherit the
                // parent's room / cat but keep their own name + format) so
                // every state-UUID — including a LightControllerV2's Dimmer
                // outputs one level down — resolves to a named, formatted
                // control rather than UNKNOWN.
                indexStatesRecursive( index, control, roomName, catName );
            }
        }

        return Map.copyOf( index );    // immutable snapshot for the swap
    }

    /** Index every state-UUID of {@code control} (and, recursively, its
     *  {@code subControls}) to its {@link ControlMetadata}. Sub-controls
     *  inherit the enclosing control's room / category (they carry none) but
     *  keep their own name + {@code details.format}. */
    private static void indexStatesRecursive( Map< String, ControlMetadata > index,
                                              JsonNode control, String roomName, String catName )
    {
        String controlName = control.path( "name" ).asText( "" );
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

        JsonNode subControls = control.path( "subControls" );
        if ( subControls.isObject() )
        {
            for ( Map.Entry< String, JsonNode > sub : subControls.properties() )
            {
                if ( sub.getValue().isObject() )
                { indexStatesRecursive( index, sub.getValue(), roomName, catName ); }
            }
        }
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
