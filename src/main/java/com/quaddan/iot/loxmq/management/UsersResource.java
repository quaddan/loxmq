/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.management;

import com.quaddan.iot.loxmq.miniserver.admin.AddNfcTagRequest;
import com.quaddan.iot.loxmq.miniserver.admin.AdminCommandException;
import com.quaddan.iot.loxmq.miniserver.admin.AdminCommandTimeoutException;
import com.quaddan.iot.loxmq.miniserver.admin.CreatedGroup;
import com.quaddan.iot.loxmq.miniserver.admin.CreatedUser;
import com.quaddan.iot.loxmq.miniserver.admin.EditGroupRequest;
import com.quaddan.iot.loxmq.miniserver.admin.EditUserRequest;
import com.quaddan.iot.loxmq.miniserver.admin.GroupMutationService;
import com.quaddan.iot.loxmq.miniserver.admin.UpdatePasswordRequest;
import com.quaddan.iot.loxmq.miniserver.admin.UserAuthService;
import com.quaddan.iot.loxmq.miniserver.admin.UserMutationService;
import com.quaddan.iot.loxmq.miniserver.admin.UserNfcService;
import com.quaddan.iot.loxmq.miniserver.admin.UserService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.Map;

/**
 * REST surface for the Miniserver user management subsystem. Sits at
 * {@code /api/v1/users} + {@code /api/v1/groups}.
 *
 * <h3>Surface</h3>
 * <ul>
 *   <li>Read-only: list, get, snapshot, groups</li>
 *   <li>Lifecycle: disable, group assign/remove</li>
 *   <li>Mutations: create, delete, edit metadata</li>
 * </ul>
 *
 * <h3>Error mapping</h3>
 * Same envelope as {@code SchedulesResource}:
 * {@code {status, code, message}} with HTTP statuses 400 / 503 / 504 /
 * 502 depending on the failure class.
 *
 * <p>Spec: {@code docs/loxone/1700_Usermanagement.pdf} V17.
 */
@Path( "/api/v1" )
@ApplicationScoped
@Produces( MediaType.APPLICATION_JSON )
@Tag( name = "Users",
      description = "Read + write surface for the Miniserver user / group configuration." )
public class UsersResource
{
    @Inject
    UserService          users;
    @Inject
    UserMutationService  mutations;
    @Inject
    UserAuthService      auth;
    @Inject
    UserNfcService       nfc;
    @Inject
    GroupMutationService groupMutations;

    @GET
    @Path( "/users" )
    public Response listUsers()
    {
        return safe( () -> Response.ok( users.listUsers() ).build() );
    }

    @GET
    @Path( "/users/{uuid}" )
    public Response getUser( @PathParam( "uuid" ) String uuid )
    {
        return safe( () -> Response.ok( users.getUser( uuid ) ).build() );
    }

    @GET
    @Path( "/groups" )
    public Response listGroups()
    {
        return safe( () -> Response.ok( users.listGroups() ).build() );
    }

    @GET
    @Path( "/users-snapshot" )
    public Response snapshot()
    {
        // Single-call helper for the dashboard page: users + groups
        // in one fetch. Two admin commands in sequence, but only one
        // REST round-trip from the operator's browser.
        return safe( () -> Response.ok( users.snapshot() ).build() );
    }

    // ============================================================
    //  Mutations
    // ============================================================

    /**
     * Create a new user.
     *
     * <p>Body: {@link EditUserRequest} with at least {@code name} set.
     * Other attributes (email, custom fields, userState, …) are
     * forwarded if non-null — saves the operator a follow-up PUT.
     *
     * <p>Returns {@link CreatedUser} ({@code uuid}, {@code name}). The
     * Miniserver may have replaced invalid characters in the requested
     * name by {@code _} — read the returned {@code name}.
     */
    @POST
    @Path( "/users" )
    public Response createUser( EditUserRequest spec )
    {
        return safe( () ->
                     {
                         CreatedUser created = mutations.createUser( spec );
                         return Response.status( Response.Status.CREATED )
                                        .entity( created )
                                        .build();
                     } );
    }

    /**
     * Patch user metadata.
     *
     * <p>Body: {@link EditUserRequest} — only non-null fields are
     * forwarded. Group membership and NFC tags must go through their
     * dedicated endpoints.
     *
     * <p>Refuses to rename the binding's own login.
     */
    @PUT
    @Path( "/users/{uuid}" )
    public Response editUser( @PathParam( "uuid" ) String uuid, EditUserRequest patch )
    {
        return safe( () ->
                     {
                         mutations.editUser( uuid, patch );
                         return Response.ok( Map.of( "status", "success",
                                                     "operation", "edit",
                                                     "uuid", uuid ) ).build();
                     } );
    }

    /**
     * Delete a user.
     *
     * <p>Refuses on the binding's own login unconditionally. Refuses
     * on admin targets unless {@code force=true} is supplied
     * (query-param) — the UI gates this behind a 2-step confirm with
     * name-retype. Audit-logged; admin override surfaces as
     * {@code DELETE_ADMIN_OVERRIDE} in the audit trail.
     */
    @DELETE
    @Path( "/users/{uuid}" )
    public Response deleteUser( @PathParam( "uuid" ) String uuid,
                                @QueryParam( "force" ) @DefaultValue( "false" ) boolean force )
    {
        return safe( () ->
                     {
                         mutations.deleteUser( uuid, force );
                         return Response.ok( Map.of( "status", "success",
                                                     "operation", "delete",
                                                     "uuid", uuid,
                                                     "force", force ) ).build();
                     } );
    }

    /**
     * Disable a user — sets {@code userState=1} (inactive).
     *
     * <p>Refuses on the binding's own login unconditionally. Refuses
     * on admin targets unless {@code force=true} is supplied. Every
     * call audit-logged; admin override surfaces as
     * {@code DISABLE_ADMIN_OVERRIDE} in the audit trail.
     */
    @POST
    @Path( "/users/{uuid}/disable" )
    public Response disable( @PathParam( "uuid" ) String uuid,
                             @QueryParam( "force" ) @DefaultValue( "false" ) boolean force )
    {
        return safe( () ->
                     {
                         mutations.disable( uuid, force );
                         return Response.ok( Map.of( "status", "success",
                                                     "operation", "disable",
                                                     "uuid", uuid,
                                                     "force", force ) ).build();
                     } );
    }

    /** Add the user to a group. */
    @POST
    @Path( "/users/{userUuid}/groups/{groupUuid}" )
    public Response assignToGroup( @PathParam( "userUuid" ) String userUuid,
                                   @PathParam( "groupUuid" ) String groupUuid )
    {
        return safe( () ->
                     {
                         mutations.assignToGroup( userUuid, groupUuid );
                         return Response.ok( Map.of( "status", "success",
                                                     "operation", "assign-to-group",
                                                     "userUuid", userUuid,
                                                     "groupUuid", groupUuid ) ).build();
                     } );
    }

    /** Remove the user from a group. */
    @DELETE
    @Path( "/users/{userUuid}/groups/{groupUuid}" )
    public Response removeFromGroup( @PathParam( "userUuid" ) String userUuid,
                                     @PathParam( "groupUuid" ) String groupUuid )
    {
        return safe( () ->
                     {
                         mutations.removeFromGroup( userUuid, groupUuid );
                         return Response.ok( Map.of( "status", "success",
                                                     "operation", "remove-from-group",
                                                     "userUuid", userUuid,
                                                     "groupUuid", groupUuid ) ).build();
                     } );
    }

    // ============================================================
    //  Group CRUD
    // ============================================================

    /**
     * Create a new user group.
     *
     * <p>Body: {@link EditGroupRequest} with at least {@code name}
     * set. Returns {@link CreatedGroup} ({@code uuid}, {@code name}).
     * Wire: {@code POST jdev/sps/addeditgroup/{json}} without uuid.
     */
    @POST
    @Path( "/groups" )
    public Response createGroup( EditGroupRequest spec )
    {
        return safe( () ->
                     {
                         CreatedGroup created = groupMutations.createGroup( spec );
                         return Response.status( Response.Status.CREATED )
                                        .entity( created )
                                        .build();
                     } );
    }

    /**
     * Patch a group's metadata.
     *
     * <p>Body: {@link EditGroupRequest} — only non-null fields are
     * forwarded. Wire: {@code POST jdev/sps/addeditgroup/{json}} with
     * uuid embedded in the JSON.
     */
    @PUT
    @Path( "/groups/{uuid}" )
    public Response editGroup( @PathParam( "uuid" ) String uuid, EditGroupRequest patch )
    {
        return safe( () ->
                     {
                         groupMutations.editGroup( uuid, patch );
                         return Response.ok( Map.of( "status", "success",
                                                     "operation", "edit-group",
                                                     "uuid", uuid ) ).build();
                     } );
    }

    /**
     * Delete a user group. The Miniserver enforces the "this is a
     * system group" rule server-side; we relay 4xx if
     * applicable. Users that were members lose the conveyed
     * permissions but are NOT themselves deleted.
     */
    @DELETE
    @Path( "/groups/{uuid}" )
    public Response deleteGroup( @PathParam( "uuid" ) String uuid )
    {
        return safe( () ->
                     {
                         groupMutations.deleteGroup( uuid );
                         return Response.ok( Map.of( "status", "success",
                                                     "operation", "delete-group",
                                                     "uuid", uuid ) ).build();
                     } );
    }

    // ============================================================
    //  Metadata helpers
    // ============================================================

    /**
     * Custom-field labels configured in Loxone Config.
     * Returns a 5-entry map {@code customField1 → label1}, etc. Empty
     * strings for unconfigured slots.
     *
     * <p>Mounted under {@code /user-metadata/...} to dodge the
     * {@code /users/{uuid}/...} JAX-RS pattern.
     */
    @GET
    @Path( "/user-metadata/custom-fields" )
    public Response getCustomFields()
    {
        return safe( () -> Response.ok( users.getCustomFields() ).build() );
    }

    /**
     * Suggestion lists for the optional user properties (Company /
     * Department / Title / ...). Surfaced to the UI for autocomplete.
     */
    @GET
    @Path( "/user-metadata/property-options" )
    public Response getUserPropertyOptions()
    {
        return safe( () -> Response.ok( users.getUserPropertyOptions() ).build() );
    }

    /**
     * Real-time check: is this external {@code userid} still
     * available? Returns {@code {"userid": "...", "available": true|false}}.
     * Used by the create form to warn before submission.
     */
    @GET
    @Path( "/user-metadata/check-userid/{userid}" )
    public Response checkUserId( @PathParam( "userid" ) String userid )
    {
        return safe( () ->
                     {
                         boolean available = users.isUserIdAvailable( userid );
                         return Response.ok( Map.of( "userid", userid,
                                                     "available", available ) ).build();
                     } );
    }

    /**
     * Effective control permissions for the given user uuid (audit
     * view "what can Alice operate?"). Shape relayed verbatim from
     * the Miniserver — see {@link UserService#getControlPermissions}.
     */
    @GET
    @Path( "/users/{uuid}/control-permissions" )
    public Response getControlPermissions( @PathParam( "uuid" ) String uuid )
    {
        return safe( () -> Response.ok( users.getControlPermissions( uuid ) ).build() );
    }

    // ============================================================
    //  Auth ops
    // ============================================================

    /**
     * Set the user's main login password.
     *
     * <p>Body: {@link UpdatePasswordRequest} — {@code {"value":"..."}}.
     * Refuses on the binding's own login (would brick the next
     * session-renewal).
     *
     * <p>Audit-logged; the plaintext / hash never appears in any
     * log line.
     */
    @POST
    @Path( "/users/{uuid}/auth/password" )
    public Response updatePassword( @PathParam( "uuid" ) String uuid, UpdatePasswordRequest body )
    {
        return safe( () ->
                     {
                         auth.updatePassword( uuid, body == null ? null : body.value() );
                         return Response.ok( Map.of( "status", "success",
                                                     "operation", "update-password",
                                                     "uuid", uuid ) ).build();
                     } );
    }

    /**
     * Set the user's visualization password.
     * Same body shape as {@link #updatePassword}.
     */
    @POST
    @Path( "/users/{uuid}/auth/visu-password" )
    public Response updateVisuPassword( @PathParam( "uuid" ) String uuid, UpdatePasswordRequest body )
    {
        return safe( () ->
                     {
                         auth.updateVisuPassword( uuid, body == null ? null : body.value() );
                         return Response.ok( Map.of( "status", "success",
                                                     "operation", "update-visu-password",
                                                     "uuid", uuid ) ).build();
                     } );
    }

    /**
     * Set the user's numeric access code. Used by NFC Code Touch /
     * Intercom physical entry points. 4 to 12 digits.
     * Same body shape as {@link #updatePassword} — the {@code value}
     * field carries the digits.
     */
    @POST
    @Path( "/users/{uuid}/auth/access-code" )
    public Response updateAccessCode( @PathParam( "uuid" ) String uuid, UpdatePasswordRequest body )
    {
        return safe( () ->
                     {
                         auth.updateAccessCode( uuid, body == null ? null : body.value() );
                         return Response.ok( Map.of( "status", "success",
                                                     "operation", "update-access-code",
                                                     "uuid", uuid ) ).build();
                     } );
    }

    // ============================================================
    //  NFC ops
    // ============================================================

    /**
     * Discover the next NFC tap. Synchronous — the Miniserver blocks
     * until a tag is tapped on any reader (NFC Code
     * Touch, Intercom, etc.) or the HTTP layer times out.
     *
     * <p>Returns {@code {"tagId":"..."}} — the operator then submits
     * it via {@link #addNfcTag(String, AddNfcTagRequest)} along with a
     * friendly name.
     *
     * <p>Empty {@code tagId} = no tap before timeout.
     */
    @POST
    @Path( "/nfc/discover" )
    public Response discoverNfcTag()
    {
        return safe( () ->
                     {
                         String tagId = nfc.discoverTag();
                         return Response.ok( Map.of( "tagId", tagId ) ).build();
                     } );
    }

    /**
     * Assign an NFC tag to a user.
     *
     * <p>Body: {@link AddNfcTagRequest} — {@code tagId} required,
     * {@code name} optional. Validation: hex chars (optionally with
     * {@code :}), 1-64 chars total.
     */
    @POST
    @Path( "/users/{uuid}/nfc" )
    public Response addNfcTag( @PathParam( "uuid" ) String uuid, AddNfcTagRequest body )
    {
        return safe( () ->
                     {
                         if ( body == null )
                         {
                             throw new IllegalArgumentException( "body must include tagId" );
                         }
                         nfc.addTagToUser( uuid, body.tagId(), body.name() );
                         return Response.ok( Map.of( "status", "success",
                                                     "operation", "nfc-add",
                                                     "uuid", uuid,
                                                     "tagId", body.tagId() ) ).build();
                     } );
    }

    /** Unassign an NFC tag from a user. */
    @DELETE
    @Path( "/users/{uuid}/nfc/{tagId}" )
    public Response removeNfcTag( @PathParam( "uuid" ) String uuid,
                                  @PathParam( "tagId" ) String tagId )
    {
        return safe( () ->
                     {
                         nfc.removeTagFromUser( uuid, tagId );
                         return Response.ok( Map.of( "status", "success",
                                                     "operation", "nfc-remove",
                                                     "uuid", uuid,
                                                     "tagId", tagId ) ).build();
                     } );
    }

    // ============================================================
    //  Helpers
    // ============================================================

    /** Common try/catch wrapper — every endpoint funnels through this
     *  to keep the error-mapping logic in one place. */
    private static Response safe( java.util.function.Supplier< Response > body )
    {
        try
        {
            return body.get();
        }
        catch ( IllegalArgumentException e )
        {
            return error( Response.Status.BAD_REQUEST, "invalid-input", e.getMessage() );
        }
        catch ( IllegalStateException e )
        {
            return error( Response.Status.SERVICE_UNAVAILABLE, "session-not-running", e.getMessage() );
        }
        catch ( AdminCommandTimeoutException e )
        {
            return error( Response.Status.GATEWAY_TIMEOUT, "miniserver-timeout", e.getMessage() );
        }
        catch ( AdminCommandException e )
        {
            return error( Response.Status.BAD_GATEWAY, "miniserver-error", e.getMessage() );
        }
    }

    private static Response error( Response.Status status, String code, String message )
    {
        return Response.status( status )
                       .entity( Map.of( "status", "error",
                                        "code", code,
                                        "message", message == null ? "" : message ) )
                       .build();
    }
}
