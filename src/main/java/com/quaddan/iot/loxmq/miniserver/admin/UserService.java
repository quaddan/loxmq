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
 * Translates dashboard read requests for the Miniserver user surface
 * into {@code jdev/sps/getuser*} commands.
 *
 * <p>Read-only. Mutations (disable, group assign/remove) live in their
 * own bean to keep the audit surface narrow — write operations have
 * stricter logging requirements than reads.
 *
 * <p>Spec: {@code docs/loxone/1700_Usermanagement.pdf} V17 (2026-03-31).
 */
@ApplicationScoped
public class UserService
{
    private static final Logger LOG = Logger.getLogger( UserService.class );

    /** Per-call budget — same logic as {@code ScheduleService}. */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds( 8 );

    @Inject
    MiniserverAdminCommandClient adminClient;

    /**
     * List all configured users — name, uuid, isAdmin, userState.
     *
     * <p>Wire path: {@code jdev/sps/getuserlist2}. Reply shape per spec:
     * {@code {"LL":{"value":[{name,uuid,isAdmin,userState,expirationAction?}, ...],"Code":"200"}}}
     */
    public List< User > listUsers()
    {
        JsonNode ll = adminClient.sendAndAwait( "getuserlist2", DEFAULT_TIMEOUT );
        // Check the Code BEFORE parsing. 403 on this endpoint = the
        // binding user is missing the User-Mgmt bit (0x800); we want
        // to surface that to the operator, not return an empty list.
        AdminCommandResponses.requireOk( ll, "getuserlist2" );
        // Loxone V17 wraps arrays as JSON-encoded strings inside
        // `value`. unwrapValue re-parses transparently.
        JsonNode value = adminClient.unwrapValue( ll );
        if ( !value.isArray() )
        {
            LOG.warnf( "getuserlist2 returned non-array value: %s", value );
            return List.of();
        }
        List< User > out = new ArrayList<>( value.size() );
        for ( JsonNode node : value )
        {
            out.add( new User(
                    node.path( "uuid" ).asText( "" ),
                    node.path( "name" ).asText( "" ),
                    node.path( "isAdmin" ).asBoolean( false ),
                    node.path( "userState" ).asInt( 0 ),
                    node.hasNonNull( "expirationAction" )
                    ? ( Integer ) node.get( "expirationAction" ).asInt()
                    : null ) );
        }
        return out;
    }

    /**
     * Full user-configuration object for a given UUID.
     *
     * <p>Wire path: {@code jdev/sps/getuser/{uuid}}. Reply shape:
     * top-level JSON inside {@code value} (NOT wrapped in another LL/value
     * envelope — checked against the spec sample).
     */
    public UserDetail getUser( String uuid )
    {
        if ( uuid == null || uuid.length() < 30 )
        {
            throw new IllegalArgumentException( "UUID looks malformed: " + uuid );
        }
        JsonNode ll = adminClient.sendAndAwait( "getuser/" + uuid, DEFAULT_TIMEOUT );
        AdminCommandResponses.requireOk( ll, "getuser/" + uuid );
        // Transparent re-parse if `value` is JSON-encoded.
        JsonNode v = adminClient.unwrapValue( ll );
        // The Miniserver flattens the user object directly into "value".
        // Some firmware variants return it at the LL root level instead.
        // Try both — the second form is a fallback for older shipments.
        JsonNode user = v.isObject() ? v : ll;

        return new UserDetail(
                user.path( "uuid" ).asText( "" ),
                user.path( "name" ).asText( "" ),
                user.path( "desc" ).asText( "" ),
                user.path( "userid" ).asText( "" ),
                user.path( "firstname" ).asText( "" ),
                user.path( "lastname" ).asText( "" ),
                user.path( "email" ).asText( "" ),
                user.path( "phone" ).asText( "" ),
                user.path( "uniqueUserId" ).asText( "" ),
                user.path( "company" ).asText( "" ),
                user.path( "department" ).asText( "" ),
                user.path( "personalno" ).asText( "" ),
                user.path( "title" ).asText( "" ),
                user.path( "debitor" ).asText( "" ),
                user.path( "customField1" ).asText( "" ),
                user.path( "customField2" ).asText( "" ),
                user.path( "customField3" ).asText( "" ),
                user.path( "customField4" ).asText( "" ),
                user.path( "customField5" ).asText( "" ),
                user.path( "lastedit" ).asLong( 0L ),
                user.path( "userState" ).asInt( 0 ),
                // validUntil / validFrom / expirationAction are optional
                // per V17 spec p.14 (only set for userState 2/3/4).
                // hasNonNull guards against missing field AND null value.
                user.hasNonNull( "validUntil" ) ? user.get( "validUntil" ).asLong() : null,
                user.hasNonNull( "validFrom" ) ? user.get( "validFrom" ).asLong() : null,
                user.hasNonNull( "expirationAction" ) ? user.get( "expirationAction" ).asInt() : null,
                user.path( "isAdmin" ).asBoolean( false ),
                user.path( "changePassword" ).asBoolean( false ),
                // V17 Usermanagement.pdf p.5-6 returns the field as
                // `usergroups` (lowercase) while older code reading
                // `userGroups` (camelCase) would always return an empty
                // list and the Groups table would show "no members"
                // regardless of actual assignment. Try both spellings to
                // stay tolerant across firmware drift (the spec is
                // inconsistent: nfcTags is camelCase next to usergroups
                // lowercase).
                extractStringListTolerant( user, "usergroups", "userGroups" ),
                extractStringListTolerant( user, "nfcTags", "nfctags" ) );
    }

    /**
     * List all configurable user groups.
     *
     * <p>Wire path: {@code jdev/sps/getgrouplist}.
     */
    public List< UserGroup > listGroups()
    {
        JsonNode ll = adminClient.sendAndAwait( "getgrouplist", DEFAULT_TIMEOUT );
        AdminCommandResponses.requireOk( ll, "getgrouplist" );
        // Loxone V17 wraps arrays as JSON-encoded strings inside
        // `value`. unwrapValue re-parses transparently.
        JsonNode value = adminClient.unwrapValue( ll );
        if ( !value.isArray() )
        {
            LOG.warnf( "getgrouplist returned non-array value: %s", value );
            return List.of();
        }
        List< UserGroup > out = new ArrayList<>( value.size() );
        for ( JsonNode node : value )
        {
            out.add( new UserGroup(
                    node.path( "uuid" ).asText( "" ),
                    node.path( "name" ).asText( "" ),
                    node.path( "description" ).asText( "" ) ) );
        }
        return out;
    }

    /** Snapshot for the dashboard's "Users" page: users + groups in
     *  one round-trip pair. Saves the dashboard from two sequential
     *  fetches. */
    public Map< String, Object > snapshot()
    {
        return Map.of(
                "users", listUsers(),
                "groups", listGroups() );
    }

    // ============================================================
    //  Metadata helpers
    // ============================================================

    /**
     * Custom user-field labels configured in Loxone Config. Returns a
     * 5-entry map {@code {"customField1" → "Badge", ..., "customField5"
     * → "..."}}; missing slots map to the empty string.
     *
     * <p>Wire path: {@code jdev/sps/getcustomuserfields}.
     *
     * <p>Two reply shapes observed in the wild:
     * <ol>
     *   <li>Array of objects: {@code [{"slot":1, "name":"Badge"}, ...]} —
     *       the V17 form.</li>
     *   <li>Plain object: {@code {"customField1":"Badge", ...}} —
     *       legacy form.</li>
     * </ol>
     * Both are normalised to the canonical 5-entry map so the UI doesn't
     * have to care.
     */
    public Map< String, String > getCustomFields()
    {
        JsonNode ll = adminClient.sendAndAwait( "getcustomuserfields", DEFAULT_TIMEOUT );
        AdminCommandResponses.requireOk( ll, "getcustomuserfields" );
        JsonNode v = adminClient.unwrapValue( ll );

        Map< String, String > out = new LinkedHashMap<>();
        for ( int i = 1; i <= 5; i++ )
        {
            out.put( "customField" + i, "" );
        }

        if ( v.isArray() )
        {
            // V17 form — array of {slot, name} or {id, label}.
            for ( JsonNode node : v )
            {
                int slot = node.path( "slot" ).asInt( -1 );
                String name = node.path( "name" ).asText(
                        node.path( "label" ).asText( "" ) );
                if ( slot < 1 || slot > 5 )
                {
                    // try {id:"customFieldN"} fallback
                    String id = node.path( "id" ).asText( "" );
                    if ( id.matches( "customField[1-5]" ) )
                    {
                        out.put( id, name );
                    }
                    continue;
                }
                out.put( "customField" + slot, name );
            }
        }
        else if ( v.isObject() )
        {
            // Legacy form — keys are "customField1"..."customField5".
            for ( int i = 1; i <= 5; i++ )
            {
                String key = "customField" + i;
                if ( v.has( key ) )
                {
                    out.put( key, v.path( key ).asText( "" ) );
                }
            }
        }
        else
        {
            LOG.warnf( "getcustomuserfields returned unexpected shape: %s", v );
        }
        return out;
    }

    /**
     * Suggestion lists for the optional user properties (Company,
     * Department, Title, etc.) — used by the UI to surface
     * autocomplete dropdowns.
     *
     * <p>Wire path: {@code jdev/sps/getuserpropertyoptions}.
     *
     * <p>Reply shape: a JSON object whose keys are property names
     * (matching {@link UserDetail} field names) and values are arrays of
     * existing distinct values across all configured users. Returned
     * verbatim as a {@code Map<String, List<String>>} — the firmware
     * may add new keys over time, the binding doesn't enumerate them.
     */
    public Map< String, List< String > > getUserPropertyOptions()
    {
        JsonNode ll = adminClient.sendAndAwait( "getuserpropertyoptions", DEFAULT_TIMEOUT );
        AdminCommandResponses.requireOk( ll, "getuserpropertyoptions" );
        JsonNode v = adminClient.unwrapValue( ll );

        Map< String, List< String > > out = new LinkedHashMap<>();
        if ( !v.isObject() )
        {
            LOG.warnf( "getuserpropertyoptions returned non-object value: %s", v );
            return out;
        }
        v.properties().forEach( entry ->
                                     {
                                         JsonNode arr = entry.getValue();
                                         if ( !arr.isArray() )
                                         { return; }
                                         List< String > values = new ArrayList<>( arr.size() );
                                         for ( JsonNode item : arr )
                                         {
                                             values.add( item.asText( "" ) );
                                         }
                                         out.put( entry.getKey(), values );
                                     } );
        return out;
    }

    /**
     * Check whether a given external {@code userid} is available
     * (no existing user already carries it).
     *
     * <p>Wire path: {@code jdev/sps/checkuserid/{userid}}.
     *
     * <p>Reply:
     * <ul>
     *   <li>{@code Code=200, value="true"|"false"} (or {@code 1/0}) —
     *       depending on firmware. Both parsed to a boolean.</li>
     *   <li>{@code Code=400/404} — userid format invalid → throws.</li>
     * </ul>
     */
    public boolean isUserIdAvailable( String userid )
    {
        if ( userid == null || userid.isBlank() )
        {
            throw new IllegalArgumentException( "userid must be non-blank" );
        }
        if ( userid.length() > 200 )
        {
            throw new IllegalArgumentException( "userid is too long (≤ 200 chars expected)" );
        }
        String   cmd = "checkuserid/" + URLEncoder.encode( userid, StandardCharsets.UTF_8 );
        JsonNode ll  = adminClient.sendAndAwait( cmd, DEFAULT_TIMEOUT );
        AdminCommandResponses.requireOk( ll, cmd );
        JsonNode v = adminClient.unwrapValue( ll );
        // Tolerate the three common forms: boolean, "true"/"false",
        // "0"/"1" / 0/1.
        if ( v.isBoolean() )
        { return v.asBoolean(); }
        if ( v.isNumber() )
        { return v.asInt() == 1; }
        if ( v.isTextual() )
        {
            String s = v.asText( "" ).trim().toLowerCase();
            return s.equals( "true" ) || s.equals( "1" );
        }
        // Unknown shape — be conservative, assume taken.
        LOG.warnf( "checkuserid returned unexpected value shape: %s", v );
        return false;
    }

    /**
     * Effective control permissions for the given user / group.
     *
     * <p>Wire path: {@code jdev/sps/getcontrolpermissions/{uuid}}.
     *
     * <p>Reply shape: firmware-specific — the binding relays the
     * unwrapped JSON tree as a {@code Map<String, Object>} so the UI
     * can introspect without us having to maintain a typed model that
     * trails the firmware.
     */
    public Map< String, Object > getControlPermissions( String uuid )
    {
        if ( uuid == null || uuid.length() < 30 )
        {
            throw new IllegalArgumentException( "UUID looks malformed: " + uuid );
        }
        JsonNode ll = adminClient.sendAndAwait( "getcontrolpermissions/" + uuid, DEFAULT_TIMEOUT );
        AdminCommandResponses.requireOk( ll, "getcontrolpermissions/" + uuid );
        JsonNode v = adminClient.unwrapValue( ll );

        Map< String, Object > out = new LinkedHashMap<>();
        if ( v.isObject() )
        {
            v.properties().forEach( e ->
                                                 out.put( e.getKey(), jsonNodeToPlainValue( e.getValue() ) ) );
        }
        else if ( v.isArray() )
        {
            // Some firmwares return an array of {controlUuid, permission}
            // objects — wrap it in a single-key map for a uniform shape.
            List< Object > list = new ArrayList<>( v.size() );
            for ( JsonNode item : v )
            {
                list.add( jsonNodeToPlainValue( item ) );
            }
            out.put( "permissions", list );
        }
        else
        {
            LOG.warnf( "getcontrolpermissions returned unexpected shape: %s", v );
        }
        return out;
    }

    // ============================================================
    //  Internals
    // ============================================================

    /** Convert a JsonNode into a plain JDK value (Map / List / String /
     *  Number / Boolean / null) so the REST layer can serialise it via
     *  the Jackson ObjectMapper without dragging JsonNode-specific
     *  reflection metadata into native image. Recursive; used by
     *  {@link #getControlPermissions} where the inner shape is
     *  firmware-specific. */
    private static Object jsonNodeToPlainValue( JsonNode node )
    {
        if ( node == null || node.isNull() )
        { return null; }
        if ( node.isBoolean() )
        { return node.asBoolean(); }
        if ( node.isInt() )
        { return node.asInt(); }
        if ( node.isLong() )
        { return node.asLong(); }
        if ( node.isDouble() || node.isFloat() )
        { return node.asDouble(); }
        if ( node.isTextual() )
        { return node.asText(); }
        if ( node.isArray() )
        {
            List< Object > list = new ArrayList<>( node.size() );
            for ( JsonNode item : node )
            { list.add( jsonNodeToPlainValue( item ) ); }
            return list;
        }
        if ( node.isObject() )
        {
            Map< String, Object > map = new LinkedHashMap<>();
            node.properties().forEach( e ->
                                                    map.put( e.getKey(), jsonNodeToPlainValue( e.getValue() ) ) );
            return map;
        }
        return node.asText( "" );
    }

    /** Best-effort string list extraction from a JsonNode field.
     *  Handles: array of strings (the canonical Loxone shape), array
     *  of objects with a {@code uuid} field, and missing / null fields
     *  (returns empty list). Tolerant on purpose — firmware variants
     *  drift here. */
    private static List< String > extractStringList( JsonNode root, String field )
    {
        JsonNode arr = root.path( field );
        if ( !arr.isArray() )
        { return List.of(); }
        List< String > out = new ArrayList<>( arr.size() );
        for ( JsonNode item : arr )
        {
            if ( item.isTextual() )
            {
                out.add( item.asText() );
            }
            else if ( item.isObject() )
            {
                String s = item.path( "uuid" ).asText( "" );
                if ( !s.isEmpty() )
                { out.add( s ); }
            }
        }
        return out;
    }

    /**
     * Same as {@link #extractStringList(JsonNode, String)} but tries
     * multiple field-name spellings in order, returning the first
     * non-empty result. Necessary because V17 Usermanagement.pdf spec
     * is inconsistent: {@code usergroups} (lowercase) sits next to
     * {@code nfcTags} (camelCase) in the same response. Older firmware
     * variants may use the opposite spelling. Trying both is cheap and
     * future-proofs the parser.
     */
    private static List< String > extractStringListTolerant( JsonNode root, String... fieldNames )
    {
        for ( String field : fieldNames )
        {
            List< String > out = extractStringList( root, field );
            if ( !out.isEmpty() )
            { return out; }
        }
        return List.of();
    }
}
