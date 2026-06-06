/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

/**
 * Write surface for Miniserver user groups.
 *
 * <p>Three operations, mirror of {@link UserMutationService} for the
 * other half of the user-management model:
 * <ul>
 *   <li>{@link #createGroup(EditGroupRequest)} — new group</li>
 *   <li>{@link #editGroup(String, EditGroupRequest)} — patch
 *       metadata (name, description)</li>
 *   <li>{@link #deleteGroup(String)} — irreversible</li>
 * </ul>
 *
 * <h3>No binding-side safety guard on system groups</h3>
 *
 * Unlike {@link UserMutationService} which refuses delete/disable on
 * the binding user itself (technical reason: would break the session),
 * groups have no obvious equivalent. Loxone has hardcoded groups
 * ("Loxone Config", "All Access", "Operator", …) which are probably
 * rejected by the Miniserver server-side — we relay the error via
 * {@link AdminCommandResponses#requireOk} rather than duplicating the
 * binding-side logic with a fragile name mapping (the names are
 * localized to the Loxone Config language).
 *
 * <p>If usage reveals cases where the Miniserver accepts a delete we'd
 * want to block (e.g. the group carrying the User-Mgmt bit), a
 * binding-side guard can be added later.
 *
 * <h3>Wire paths — {@code usergroup} convention, not {@code group}</h3>
 *
 * Despite symmetry with {@code addedituser} / {@code deleteuser},
 * the Miniserver V17 returns {@code 404} on {@code addeditgroup} /
 * {@code deletegroup} paths: {@code GET /jdev/sps/deletegroup/{uuid} →
 * HTTP 404}.
 *
 * <p>The correct paths follow the <strong>{@code usergroup}</strong>
 * convention (consistent with {@code assignusertogroup} and
 * {@code removeuserfromgroup}):
 *
 * <pre>
 *   addeditgroup  → addeditusergroup
 *   deletegroup   → deleteusergroup
 * </pre>
 *
 * <h3>Spec</h3>
 * {@code docs/loxone/1700_Usermanagement.pdf} V17 §"Add or edit
 * existing user group" + §"Delete user group".
 */
@ApplicationScoped
public class GroupMutationService
{
    /** Operator audit trail. Routed to {@code audit.log} via the
     *  {@code "audit"} logger category. */
    private static final Logger AUDIT = Logger.getLogger( "audit" );

    private static final Logger LOG = Logger.getLogger( GroupMutationService.class );

    /** Per-call budget. Same as {@link UserMutationService} — the
     *  Miniserver may serialise to disk on edit. */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds( 12 );

    @Inject
    MiniserverAdminCommandClient adminClient;
    @Inject
    ObjectMapper                 jsonMapper;

    // ============================================================
    //  create
    // ============================================================

    /**
     * Create a new group via {@code addeditgroup/{json}} (no
     * {@code uuid} in the body — the Miniserver allocates one).
     *
     * <p>{@code spec.name()} is required. Other fields forwarded only
     * if non-null. Returns the new UUID + the actually-registered name
     * (the Miniserver may sanitise the name server-side).
     */
    public CreatedGroup createGroup( EditGroupRequest spec )
    {
        if ( spec == null || spec.name() == null || spec.name().isBlank() )
        {
            throw new IllegalArgumentException(
                    "Name is required when creating a group" );
        }
        if ( spec.name().length() > 200 )
        {
            throw new IllegalArgumentException(
                    "Group name is too long (≤ 200 chars expected)" );
        }

        ObjectNode payload = buildPatchPayload( spec );
        String     json    = payload.toString();
        String     cmd     = "addeditusergroup/" + URLEncoder.encode( json, StandardCharsets.UTF_8 );
        JsonNode   ll      = adminClient.sendAndAwait( cmd, DEFAULT_TIMEOUT );
        AdminCommandResponses.requireOk( ll, "create group " + spec.name() );

        JsonNode value   = adminClient.unwrapValue( ll );
        String   newUuid = value.path( "uuid" ).asText( "" );
        String   newName = value.path( "name" ).asText( spec.name() );

        if ( newUuid.isEmpty() )
        {
            throw new AdminCommandException(
                    "create group " + spec.name() + " — Miniserver returned 200 "
                    + "but no uuid in reply (value=" + value + ")" );
        }

        AUDIT.infof( "%s CREATE_GROUP %s/%s — OK", auditTs(), newUuid, newName );
        LOG.infof( "Group created: uuid=%s name=%s", newUuid, newName );
        return new CreatedGroup( newUuid, newName );
    }

    // ============================================================
    //  edit
    // ============================================================

    /**
     * Patch group metadata via {@code addeditgroup/{json}} with the
     * group's {@code uuid} embedded in the body.
     *
     * <p>Only non-null fields in {@code patch} are forwarded — same
     * semantics as {@link UserMutationService#editUser(String,
     * EditUserRequest)}. Group membership is NOT touched here; use
     * {@link UserMutationService#assignToGroup(String, String)} /
     * {@link UserMutationService#removeFromGroup(String, String)}.
     */
    public void editGroup( String uuid, EditGroupRequest patch )
    {
        validateUuid( uuid );
        if ( patch == null )
        {
            throw new IllegalArgumentException( "patch must not be null" );
        }

        ObjectNode payload = buildPatchPayload( patch );
        payload.put( "uuid", uuid );

        String   json = payload.toString();
        String   cmd  = "addeditusergroup/" + URLEncoder.encode( json, StandardCharsets.UTF_8 );
        JsonNode ll   = adminClient.sendAndAwait( cmd, DEFAULT_TIMEOUT );
        AdminCommandResponses.requireOk( ll, "edit group " + uuid );

        AUDIT.infof( "%s EDIT_GROUP %s — OK fields=[%s]",
                     auditTs(), uuid, summarizePatchedFields( patch ) );
        LOG.infof( "Group edited: uuid=%s", uuid );
    }

    // ============================================================
    //  delete
    // ============================================================

    /**
     * Delete a group via {@code deletegroup/{uuid}}.
     *
     * <p>No binding-side admin-guard — the Miniserver enforces the
     * "you can't delete a system group" rule and we surface the
     * Code=4xx via {@link AdminCommandResponses#requireOk}.
     *
     * <p>Users that were members of the group are NOT deleted — they
     * stay in the system but lose the permissions that group conveyed.
     */
    public void deleteGroup( String uuid )
    {
        validateUuid( uuid );

        String   cmd = "deleteusergroup/" + uuid;
        JsonNode ll  = adminClient.sendAndAwait( cmd, DEFAULT_TIMEOUT );
        AdminCommandResponses.requireOk( ll, cmd );

        AUDIT.infof( "%s DELETE_GROUP %s — OK", auditTs(), uuid );
        LOG.infof( "Group deleted: uuid=%s", uuid );
    }

    // ============================================================
    //  Internals
    // ============================================================

    /** Translate an {@link EditGroupRequest} into an {@link ObjectNode}
     *  containing only the non-null fields. Re-used by create (without
     *  uuid) and edit (with uuid re-added by the caller). */
    private ObjectNode buildPatchPayload( EditGroupRequest p )
    {
        ObjectNode o = jsonMapper.createObjectNode();
        if ( p.name() != null )
        { o.put( "name", p.name() ); }
        if ( p.description() != null )
        { o.put( "description", p.description() ); }
        return o;
    }

    private static String summarizePatchedFields( EditGroupRequest p )
    {
        StringBuilder sb = new StringBuilder();
        if ( p.name() != null )
        {
            sb.append( "name" );
        }
        if ( p.description() != null )
        {
            if ( sb.length() > 0 )
            { sb.append( ',' ); }
            sb.append( "description" );
        }
        return sb.toString();
    }

    private static void validateUuid( String uuid )
    {
        if ( uuid == null || uuid.length() < 30 || uuid.length() > 50 )
        {
            throw new IllegalArgumentException( "UUID looks malformed: " + uuid );
        }
    }

    private static String auditTs()
    {
        return Instant.now().toString();
    }
}
