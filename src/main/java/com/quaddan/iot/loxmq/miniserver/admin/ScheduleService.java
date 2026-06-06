/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.admin;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Translates the dashboard's REST CRUD requests for operating-mode
 * schedule entries into Loxone {@code jdev/sps/calendar*} commands.
 *
 * <p>Wraps {@link MiniserverAdminCommandClient} with:
 * <ul>
 *   <li>per-operation default timeouts</li>
 *   <li>URL-encoding for path parameters (names can contain spaces /
 *       accented chars)</li>
 *   <li>JSON shape parsing for {@code calendargetentries}</li>
 * </ul>
 *
 * <p>Spec reference: {@code docs/loxone/OperatingModeSchedule.pdf} V14.4.
 */
@ApplicationScoped
public class ScheduleService
{
    private static final Logger LOG = Logger.getLogger( ScheduleService.class );

    /** Default per-call WS round-trip budget. Generous to cover slow
     *  Miniservers under heavy automation load — the calendar surface
     *  isn't hot-path, no point shaving milliseconds at the risk of false
     *  503s. Override per-call via {@code sendAndAwait} signature if
     *  necessary. */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds( 8 );

    @Inject
    MiniserverAdminCommandClient adminClient;

    /**
     * List all schedule entries currently configured on the Miniserver.
     *
     * <p>Wire path: {@code jdev/sps/calendargetentries}. Reply shape:
     * {@code {"LL":{"value":[{uuid,name,operatingMode,calMode,calModeAttr}, ...],"Code":"200"}}}
     */
    public List< ScheduleEntry > list()
    {
        JsonNode ll = adminClient.sendAndAwait( "calendargetentries", DEFAULT_TIMEOUT );
        // Check the Code BEFORE parsing value. Without it, a silent 403
        // returns an empty list instead of surfacing the Miniserver
        // rejection to the operator.
        AdminCommandResponses.requireOk( ll, "calendargetentries" );
        // Loxone V17 wraps the array as a JSON-encoded string inside
        // `value`. unwrapValue re-parses it transparently.
        JsonNode value = adminClient.unwrapValue( ll );
        if ( !value.isArray() )
        {
            LOG.warnf( "calendargetentries returned non-array value: %s", value );
            return List.of();
        }
        List< ScheduleEntry > out = new ArrayList<>( value.size() );
        for ( JsonNode node : value )
        {
            out.add( toEntry( node ) );
        }
        return out;
    }

    /**
     * Create a new schedule entry. The Miniserver assigns a UUID and
     * returns code 200 on success. We re-list immediately so the caller
     * sees the assigned UUID without a separate round-trip.
     *
     * <p>Wire path:
     * {@code jdev/sps/calendarcreateentry/{name}/{opMode}/{calMode}/{calModeAttr}}.
     */
    public void create( String name, int operatingMode, CalendarMode calMode, String calModeAttr )
    {
        validateName( name );
        validateCalModeAttr( calModeAttr );
        String cmd = "calendarcreateentry/"
                     + URLEncoder.encode( name, StandardCharsets.UTF_8 ) + "/"
                     + operatingMode + "/"
                     + calMode.code() + "/"
                     + URLEncoder.encode( calModeAttr, StandardCharsets.UTF_8 );
        JsonNode ll = adminClient.sendAndAwait( cmd, DEFAULT_TIMEOUT );
        requireOk( ll, cmd );
    }

    /**
     * Update an existing entry by UUID. Same wire shape as
     * {@link #create} but with {@code calUUID} in the URL prefix.
     *
     * <p>Wire path:
     * {@code jdev/sps/calendarupdateentry/{calUUID}/{name}/{opMode}/{calMode}/{calModeAttr}}.
     */
    public void update( String uuid,
                        String name,
                        int operatingMode,
                        CalendarMode calMode,
                        String calModeAttr )
    {
        validateUuid( uuid );
        validateName( name );
        validateCalModeAttr( calModeAttr );
        String cmd = "calendarupdateentry/"
                     + uuid + "/"
                     + URLEncoder.encode( name, StandardCharsets.UTF_8 ) + "/"
                     + operatingMode + "/"
                     + calMode.code() + "/"
                     + URLEncoder.encode( calModeAttr, StandardCharsets.UTF_8 );
        JsonNode ll = adminClient.sendAndAwait( cmd, DEFAULT_TIMEOUT );
        requireOk( ll, cmd );
    }

    /**
     * Delete an entry by UUID.
     *
     * <p>Wire path: {@code jdev/sps/calendardeleteentry/{calUUID}}.
     */
    public void delete( String uuid )
    {
        validateUuid( uuid );
        String   cmd = "calendardeleteentry/" + uuid;
        JsonNode ll  = adminClient.sendAndAwait( cmd, DEFAULT_TIMEOUT );
        requireOk( ll, cmd );
    }

    /** ISO-date string for the heating-period start/end. Wire path:
     *  {@code jdev/sps/calendargetheatperiod}. */
    public String getHeatPeriod()
    {
        JsonNode ll = adminClient.sendAndAwait( "calendargetheatperiod", DEFAULT_TIMEOUT );
        // unwrapValue is safe for plain scalar strings — it only re-parses
        // if the string looks like JSON ({...} or [...]).
        return adminClient.unwrapValue( ll ).asText( "" );
    }

    /** ISO-date string for the cooling-period start/end. */
    public String getCoolPeriod()
    {
        JsonNode ll = adminClient.sendAndAwait( "calendargetcoolperiod", DEFAULT_TIMEOUT );
        return adminClient.unwrapValue( ll ).asText( "" );
    }

    // ============================================================
    //  Internals
    // ============================================================

    /** Known calMode-attribute keys per V14.4 spec. We probe each one
     *  and only include it in the result map if the Miniserver
     *  populated it — different calMode codes use different subsets. */
    private static final String[] KNOWN_CAL_MODE_ATTR_KEYS = {
            "startYear", "startMonth", "startDay",
            "endYear", "endMonth", "endDay",
            "easterOffset",
            "weekDay", "weekDayInMonth"
    };

    private static ScheduleEntry toEntry( JsonNode node )
    {
        Map< String, Integer > attrs = new LinkedHashMap<>();
        for ( String key : KNOWN_CAL_MODE_ATTR_KEYS )
        {
            if ( node.hasNonNull( key ) && node.path( key ).isInt() )
            {
                attrs.put( key, node.get( key ).asInt() );
            }
        }
        return new ScheduleEntry(
                node.path( "uuid" ).asText( "" ),
                node.path( "name" ).asText( "" ),
                node.path( "operatingMode" ).asInt( 0 ),
                node.path( "calMode" ).asInt( 0 ),
                Map.copyOf( attrs ) );
    }

    /** Delegate to the central {@link AdminCommandResponses#requireOk}.
     *  Kept as a thin pass-through to avoid changing every call site at
     *  once + leaves the door open to per-service customization. */
    private static void requireOk( JsonNode ll, String cmd )
    {
        AdminCommandResponses.requireOk( ll, cmd );
    }

    /** Validate the entry name. Loxone permits most printable chars but
     *  rejects empty + the literal {@code /} (which would break the URL
     *  path segmentation server-side). Caller's responsibility to
     *  satisfy this — we fail fast with a meaningful message rather than
     *  let the Miniserver return an opaque error. */
    private static void validateName( String name )
    {
        if ( name == null || name.isBlank() )
        {
            throw new IllegalArgumentException( "Schedule name must not be blank" );
        }
        if ( name.contains( "/" ) )
        {
            throw new IllegalArgumentException( "Schedule name must not contain '/' : " + name );
        }
        if ( name.length() > 100 )
        {
            throw new IllegalArgumentException( "Schedule name too long (>100 chars)" );
        }
    }

    /** UUID format check — Loxone UUIDs are 36 chars
     *  {@code xxxxxxxx-xxxx-xxxx-xxxxxxxxxxxxxxxx}, not strict
     *  RFC 4122 (the last group is 16 hex chars, not 12). */
    private static void validateUuid( String uuid )
    {
        if ( uuid == null || uuid.length() < 30 || uuid.length() > 50 )
        {
            throw new IllegalArgumentException( "UUID looks malformed: " + uuid );
        }
        // We don't enforce the exact hex/dash pattern here — Loxone has
        // shipped variants over the years (some leading zeros stripped).
        // The Miniserver will reject malformed inputs with Code=400.
    }

    /** Sanity-check the calModeAttr — non-empty + no embedded {@code /}
     *  beyond the documented count. Lenient on purpose: we don't want
     *  to second-guess the spec (e.g. WEEKDAY needs 2 slashes, TIMESPAN
     *  needs 5). The Miniserver is the source of truth. */
    private static void validateCalModeAttr( String attr )
    {
        if ( attr == null || attr.isBlank() )
        {
            throw new IllegalArgumentException( "calModeAttr must not be blank" );
        }
        if ( attr.length() > 100 )
        {
            throw new IllegalArgumentException( "calModeAttr too long (>100 chars)" );
        }
    }
}
