/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.quaddan.iot.loxmq.config.LoxoneConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * Write surface for Miniserver users. Kept separate from
 * {@link UserService} so:
 * <ul>
 *   <li>Audit logging (dedicated {@code audit.log} via the
 *       {@code "audit"} logger category) lives only on the mutation
 *       paths — no noise on every read.</li>
 *   <li>Safety guards (refuse to disable / delete / edit admin users
 *       or the binding's own login) sit in one bean, easy to grep /
 *       review.</li>
 * </ul>
 *
 * <h3>Security note — no bearer auth</h3>
 * The mutation endpoints are reachable from any client on the LXC's
 * network. The binding's overall security posture is LAN+VPN (cf.
 * {@code RUNBOOK.md §"Quand escalader"} + {@code FAQ.md} TLS section),
 * so adding bearer auth here in isolation would be premature.
 *
 * <p>If the dashboard ever gets exposed beyond the LAN, a session-aware
 * filter is the right place to add auth — uniformly across all
 * mutations + reads. Doing it ad-hoc on this resource alone would only
 * delay the inevitable broader refactor.
 *
 * <p>What we DO add:
 * <ul>
 *   <li>Dedicated audit log so the operator can trail every mutation
 *       (who-could-not-be-determined-but-when + what).</li>
 *   <li>Pre-flight check on disable / delete / edit to refuse admin
 *       targets — protects against an honest mistake (operator typo,
 *       mass-disable script). A determined attacker on the LAN could
 *       still hit the Miniserver directly via Loxone Config so the
 *       binding-side guard is belt+suspenders.</li>
 *   <li>Self-protection — refuse mutations that would lock the binding
 *       out of the Miniserver: delete or rename the binding's own user.
 *       The binding's username is resolved from
 *       {@code loxone.miniserver.security.credentials.user} (base64-
 *       wrapped at-rest, same as elsewhere in the codebase).</li>
 * </ul>
 *
 * <h3>Operations exposed</h3>
 * <ul>
 *   <li>{@link #createUser(EditUserRequest)}</li>
 *   <li>{@link #deleteUser(String)}</li>
 *   <li>{@link #editUser(String, EditUserRequest)}</li>
 *   <li>{@link #disable(String)}</li>
 *   <li>{@link #assignToGroup(String, String)}</li>
 *   <li>{@link #removeFromGroup(String, String)}</li>
 * </ul>
 *
 * <p>Spec: {@code docs/loxone/1700_Usermanagement.pdf} V17.
 */
@ApplicationScoped
public class UserMutationService
{
    /** Operator audit trail. Routed to {@code audit.log} via the
     *  {@code "audit"} category configured in {@code application.yaml}.
     *  Format: INFO + structured message {@code <timestamp> <op> <target> [<details>]}. */
    private static final Logger AUDIT = Logger.getLogger( "audit" );

    private static final Logger LOG = Logger.getLogger( UserMutationService.class );

    /** Per-call budget. Slightly longer than reads since the Miniserver
     *  may serialise the user record back to disk on edit. */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds( 12 );

    /** Loxone {@code userState} code that means "inactive / disabled".
     *  Spec p. 14 §"userstate". */
    private static final int USER_STATE_INACTIVE = 1;

    @Inject
    MiniserverAdminCommandClient adminClient;
    @Inject
    UserService                  userService;
    @Inject
    LoxoneConfig                 config;
    @Inject
    ObjectMapper                 jsonMapper;

    // ============================================================
    //  create
    // ============================================================

    /**
     * Create a new user on the Miniserver via {@code addoredituser/{json}}
     * with no {@code uuid} in the body (Loxone allocates one).
     *
     * <p>{@code spec.name()} is required (the login name); all other
     * fields are forwarded only if non-null. The Miniserver replaces
     * invalid characters in the name with {@code _} server-side, so the
     * value returned in {@link CreatedUser#name()} may differ from the
     * one submitted.
     *
     * <p>The operator can also pass {@code userState}, {@code email},
     * custom fields, etc. in one shot — saves an extra
     * {@link #editUser(String, EditUserRequest)} round-trip if known
     * upfront. Groups and NFC tags are NOT settable here; use the
     * dedicated endpoints after creation.
     */
    public CreatedUser createUser( EditUserRequest spec )
    {
        if ( spec == null || spec.name() == null || spec.name().isBlank() )
        {
            throw new IllegalArgumentException(
                    "Name is required when creating a user" );
        }
        if ( spec.name().length() > 200 )
        {
            throw new IllegalArgumentException(
                    "User name is too long (≤ 200 chars expected)" );
        }

        ObjectNode payload = buildPatchPayload( spec );
        // Defensive — even if the caller passed userGroups via a future
        // extension of EditUserRequest, the spec says "When during
        // editing a user and no groups-array is set, the group-assignment
        // will remain unchanged". On create the same rule applies → no
        // membership at start. Operator must call assignToGroup() next.

        String   json = payload.toString();
        String   cmd  = "addoredituser/" + URLEncoder.encode( json, StandardCharsets.UTF_8 );
        JsonNode ll   = adminClient.sendAndAwait( cmd, DEFAULT_TIMEOUT );
        requireOk( ll, "create user " + spec.name() );

        // Reply shape: {"LL":{"value":{"uuid":"…","name":"…"}, "Code":"200"}}.
        // unwrapValue handles the case where `value` is a JSON-encoded
        // string (some firmware variants).
        JsonNode value   = adminClient.unwrapValue( ll );
        String   newUuid = value.path( "uuid" ).asText( "" );
        String   newName = value.path( "name" ).asText( spec.name() );

        if ( newUuid.isEmpty() )
        {
            // The Miniserver returned 200 but no uuid — surfacing this as
            // an exception is more useful than silently returning a
            // partial object.
            throw new AdminCommandException(
                    "create user " + spec.name() + " — Miniserver returned 200 "
                    + "but no uuid in reply (value=" + value + ")" );
        }

        AUDIT.infof( "%s CREATE %s/%s — OK", auditTs(), newUuid, newName );
        LOG.infof( "User created: uuid=%s name=%s", newUuid, newName );
        return new CreatedUser( newUuid, newName );
    }

    // ============================================================
    //  delete
    // ============================================================

    /**
     * Delete a user via {@code deleteuser/{uuid}} — admin-guarded
     * convenience overload (force=false). See
     * {@link #deleteUser(String, boolean)} for the full semantics.
     */
    public void deleteUser( String uuid )
    {
        deleteUser( uuid, false );
    }

    /**
     * Delete a user via {@code deleteuser/{uuid}} with optional
     * admin-guard override.
     *
     * <p><strong>Refuses unconditionally</strong> if the target is the
     * binding's own login — would lock the binding out of the
     * Miniserver at the next reconnect. {@code force=true} does NOT
     * bypass this guard; it's a hard rule.
     *
     * <p><strong>Refuses when {@code force=false}</strong> if the
     * target is {@code isAdmin=true}. Guards against operator typo or
     * a mass-script. Bypassable via {@code force=true} so the dashboard
     * can legitimately manage admins (the UI gates this behind a 2-step
     * confirm with name-retype).
     *
     * <p>Two round-trips: pre-flight {@code getuser} + actual
     * {@code deleteuser}. The pre-flight is the only way to know if
     * the target is admin / matches the binding user.
     *
     * @param uuid  Loxone user UUID
     * @param force if true, bypass the admin-protection guard (self-
     *              protection still applies). Audit log records the
     *              override.
     */
    public void deleteUser( String uuid, boolean force )
    {
        validateUuid( uuid );

        UserDetail target = userService.getUser( uuid );
        if ( isBindingUser( target.name() ) )
        {
            // Self-guard — NOT bypassable by force. This is a hard
            // safety rule: if the binding deletes its own login, it
            // can't reconnect afterwards.
            String msg = "Refusing to delete user " + uuid + " (" + target.name()
                         + ") — this is the binding's own login. Deleting it "
                         + "would lock the binding out of the Miniserver at "
                         + "the next reconnect.";
            AUDIT.warnf( "%s REFUSE delete %s/%s — self (force ignored)",
                         auditTs(), uuid, target.name() );
            throw new IllegalArgumentException( msg );
        }
        if ( target.isAdmin() && !force )
        {
            String msg = "Refusing to delete user " + uuid + " (" + target.name()
                         + ") — user is admin. Pass force=true to override "
                         + "(audit-logged) or use Loxone Config.";
            AUDIT.warnf( "%s REFUSE delete %s/%s — isAdmin=true, force=false",
                         auditTs(), uuid, target.name() );
            throw new IllegalArgumentException( msg );
        }

        String   cmd = "deleteuser/" + uuid;
        JsonNode ll  = adminClient.sendAndAwait( cmd, DEFAULT_TIMEOUT );
        requireOk( ll, cmd );

        if ( target.isAdmin() )
        {
            AUDIT.warnf( "%s DELETE_ADMIN_OVERRIDE %s/%s — OK (force=true)",
                         auditTs(), uuid, target.name() );
            LOG.warnf( "ADMIN user deleted via force flag: uuid=%s name=%s",
                       uuid, target.name() );
        }
        else
        {
            AUDIT.infof( "%s DELETE %s/%s — OK", auditTs(), uuid, target.name() );
            LOG.infof( "User deleted: uuid=%s name=%s", uuid, target.name() );
        }
    }

    // ============================================================
    //  edit metadata
    // ============================================================

    /**
     * Patch user metadata via {@code addoredituser/{json}}.
     *
     * <p>Only non-null fields in {@code patch} are forwarded — this
     * matches the Loxone spec ({@code "All other json attributes are
     * optional"}) and avoids accidentally clearing fields the operator
     * didn't touch.
     *
     * <p><strong>Refuses</strong> if the target is the binding's own
     * login AND the patch tries to {@code rename} it (name is the key
     * the binding sends in {@code getjwt}). All other field edits on
     * self are allowed.
     *
     * <p>Does NOT touch group membership ({@code userGroups}) — Loxone
     * leaves it untouched when the array is absent from the JSON. Use
     * {@link #assignToGroup(String, String)} /
     * {@link #removeFromGroup(String, String)} for membership changes.
     */
    public void editUser( String uuid, EditUserRequest patch )
    {
        validateUuid( uuid );
        if ( patch == null )
        {
            throw new IllegalArgumentException( "patch must not be null" );
        }

        UserDetail target = userService.getUser( uuid );
        if ( isBindingUser( target.name() )
             && patch.name() != null
             && !target.name().equals( patch.name() ) )
        {
            String msg = "Refusing to rename binding user " + target.name()
                         + " → " + patch.name() + " — it would lock the binding "
                         + "out at the next session renewal.";
            AUDIT.warnf( "%s REFUSE edit %s/%s — rename of self to %s",
                         auditTs(), uuid, target.name(), patch.name() );
            throw new IllegalArgumentException( msg );
        }

        ObjectNode payload = buildPatchPayload( patch );
        payload.put( "uuid", uuid );

        String   json = payload.toString();
        String   cmd  = "addoredituser/" + URLEncoder.encode( json, StandardCharsets.UTF_8 );
        JsonNode ll   = adminClient.sendAndAwait( cmd, DEFAULT_TIMEOUT );
        requireOk( ll, "edit user " + uuid );

        AUDIT.infof( "%s EDIT %s/%s — OK fields=[%s]",
                     auditTs(), uuid, target.name(), summarizePatchedFields( patch ) );
        LOG.infof( "User edited: uuid=%s name=%s", uuid, target.name() );
    }

    // ============================================================
    //  disable
    // ============================================================

    /**
     * Set {@code userState=1} (inactive) — admin-guarded convenience
     * overload (force=false). See {@link #disable(String, boolean)}
     * for the full semantics.
     */
    public void disable( String userUuid )
    {
        disable( userUuid, false );
    }

    /**
     * Set {@code userState=1} (inactive) on the target user, with
     * optional admin-guard override.
     *
     * <p><strong>Refuses unconditionally</strong> if the target is the
     * binding's own login — disabling it would prevent the binding
     * from reconnecting. {@code force=true} does NOT bypass this
     * guard.
     *
     * <p><strong>Refuses when {@code force=false}</strong> if the
     * target is {@code isAdmin=true}. Defensive against operator typo
     * or a mass-script. The dashboard can bypass this via
     * {@code force=true} (gated behind a 2-step UI confirm with
     * name-retype).
     *
     * <p>Wire: two round-trips (pre-flight {@code getuser} + actual
     * {@code addoredituser}). The pre-flight isn't a strict requirement
     * (the Miniserver enforces admin-modification rights server-side
     * too) but the binding-side guard surfaces a clearer error message
     * + creates an audit record EVEN when the Miniserver would have
     * silently refused.
     *
     * @param userUuid Loxone user UUID
     * @param force    if true, bypass the admin-protection guard
     *                 (self-protection still applies). Audit logs the
     *                 override under the {@code DISABLE_ADMIN_OVERRIDE}
     *                 verb.
     */
    public void disable( String userUuid, boolean force )
    {
        validateUuid( userUuid );

        UserDetail target = userService.getUser( userUuid );
        if ( isBindingUser( target.name() ) )
        {
            // Self-guard — NOT bypassable by force.
            String msg = "Refusing to disable user " + userUuid + " (" + target.name()
                         + ") — this is the binding's own login. Disabling it "
                         + "would prevent the binding from reconnecting.";
            AUDIT.warnf( "%s REFUSE disable %s/%s — self (force ignored)",
                         auditTs(), userUuid, target.name() );
            throw new IllegalArgumentException( msg );
        }
        if ( target.isAdmin() && !force )
        {
            String msg = "Refusing to disable user " + userUuid + " (" + target.name()
                         + ") — user is admin. Pass force=true to override "
                         + "(audit-logged) or use Loxone Config.";
            AUDIT.warnf( "%s REFUSE disable %s/%s — isAdmin=true, force=false",
                         auditTs(), userUuid, target.name() );
            throw new IllegalArgumentException( msg );
        }

        // Build addoredituser/{json}. Spec p. 7: the path takes a
        // URL-encoded JSON blob with the user's UUID + the fields to
        // modify. Only userState here — name / email / etc. stay
        // untouched.
        String   json = "{\"uuid\":\"" + userUuid + "\",\"userState\":" + USER_STATE_INACTIVE + "}";
        String   cmd  = "addoredituser/" + URLEncoder.encode( json, StandardCharsets.UTF_8 );
        JsonNode ll   = adminClient.sendAndAwait( cmd, DEFAULT_TIMEOUT );
        requireOk( ll, "disable " + userUuid );

        if ( target.isAdmin() )
        {
            AUDIT.warnf( "%s DISABLE_ADMIN_OVERRIDE %s/%s — OK (force=true)",
                         auditTs(), userUuid, target.name() );
            LOG.warnf( "ADMIN user disabled via force flag: uuid=%s name=%s",
                       userUuid, target.name() );
        }
        else
        {
            AUDIT.infof( "%s DISABLE %s/%s — OK", auditTs(), userUuid, target.name() );
            LOG.infof( "User disabled: uuid=%s name=%s", userUuid, target.name() );
        }
    }

    // ============================================================
    //  groups
    // ============================================================

    /**
     * Add a user to a group via {@code assignusertogroup/{userUuid}/{groupUuid}}.
     * No admin-protection guard here — group membership changes don't
     * directly disable a user, just shift permissions. Audit-logged
     * either way.
     */
    public void assignToGroup( String userUuid, String groupUuid )
    {
        validateUuid( userUuid );
        validateUuid( groupUuid );
        String   cmd = "assignusertogroup/" + userUuid + "/" + groupUuid;
        JsonNode ll  = adminClient.sendAndAwait( cmd, DEFAULT_TIMEOUT );
        requireOk( ll, cmd );
        AUDIT.infof( "%s ASSIGN user=%s group=%s — OK", auditTs(), userUuid, groupUuid );
        LOG.infof( "User %s assigned to group %s", userUuid, groupUuid );
    }

    /** Remove a user from a group via {@code removeuserfromgroup/{userUuid}/{groupUuid}}. */
    public void removeFromGroup( String userUuid, String groupUuid )
    {
        validateUuid( userUuid );
        validateUuid( groupUuid );
        String   cmd = "removeuserfromgroup/" + userUuid + "/" + groupUuid;
        JsonNode ll  = adminClient.sendAndAwait( cmd, DEFAULT_TIMEOUT );
        requireOk( ll, cmd );
        AUDIT.infof( "%s REMOVE user=%s group=%s — OK", auditTs(), userUuid, groupUuid );
        LOG.infof( "User %s removed from group %s", userUuid, groupUuid );
    }

    // ============================================================
    //  Internals — payload build + self-detection
    // ============================================================

    /**
     * Translate an {@link EditUserRequest} into an {@link ObjectNode}
     * containing only the non-null fields. Re-used by
     * {@link #createUser(EditUserRequest)} (without {@code uuid}) and
     * {@link #editUser(String, EditUserRequest)} (with {@code uuid}
     * re-added by the caller).
     *
     * <p>Centralized so adding a new Loxone field requires only one
     * change here, and so the <em>"null → don't send"</em> semantics
     * stay uniform across create and edit.
     */
    private ObjectNode buildPatchPayload( EditUserRequest p )
    {
        ObjectNode o = jsonMapper.createObjectNode();
        if ( p.name() != null )
        { o.put( "name", p.name() ); }
        if ( p.desc() != null )
        { o.put( "desc", p.desc() ); }
        if ( p.userid() != null )
        { o.put( "userid", p.userid() ); }
        if ( p.firstname() != null )
        { o.put( "firstname", p.firstname() ); }
        if ( p.lastname() != null )
        { o.put( "lastname", p.lastname() ); }
        if ( p.email() != null )
        { o.put( "email", p.email() ); }
        if ( p.phone() != null )
        { o.put( "phone", p.phone() ); }
        if ( p.uniqueUserId() != null )
        { o.put( "uniqueUserId", p.uniqueUserId() ); }
        if ( p.company() != null )
        { o.put( "company", p.company() ); }
        if ( p.department() != null )
        { o.put( "department", p.department() ); }
        if ( p.personalno() != null )
        { o.put( "personalno", p.personalno() ); }
        if ( p.title() != null )
        { o.put( "title", p.title() ); }
        if ( p.debitor() != null )
        { o.put( "debitor", p.debitor() ); }
        if ( p.customField1() != null )
        { o.put( "customField1", p.customField1() ); }
        if ( p.customField2() != null )
        { o.put( "customField2", p.customField2() ); }
        if ( p.customField3() != null )
        { o.put( "customField3", p.customField3() ); }
        if ( p.customField4() != null )
        { o.put( "customField4", p.customField4() ); }
        if ( p.customField5() != null )
        { o.put( "customField5", p.customField5() ); }
        if ( p.userState() != null )
        { o.put( "userState", p.userState() ); }
        if ( p.validUntil() != null )
        { o.put( "validUntil", p.validUntil() ); }
        if ( p.validFrom() != null )
        { o.put( "validFrom", p.validFrom() ); }
        if ( p.expirationAction() != null )
        { o.put( "expirationAction", p.expirationAction() ); }
        if ( p.changePassword() != null )
        { o.put( "changePassword", p.changePassword() ); }
        return o;
    }

    /** Comma-separated list of the field names that were non-null in
     *  the patch — used as a compact audit-log summary so the operator
     *  can see <em>which</em> fields were touched without dumping the
     *  full JSON. */
    private static String summarizePatchedFields( EditUserRequest p )
    {
        StringBuilder sb = new StringBuilder();
        appendIf( sb, "name", p.name() );
        appendIf( sb, "desc", p.desc() );
        appendIf( sb, "userid", p.userid() );
        appendIf( sb, "firstname", p.firstname() );
        appendIf( sb, "lastname", p.lastname() );
        appendIf( sb, "email", p.email() );
        appendIf( sb, "phone", p.phone() );
        appendIf( sb, "uniqueUserId", p.uniqueUserId() );
        appendIf( sb, "company", p.company() );
        appendIf( sb, "department", p.department() );
        appendIf( sb, "personalno", p.personalno() );
        appendIf( sb, "title", p.title() );
        appendIf( sb, "debitor", p.debitor() );
        appendIf( sb, "customField1", p.customField1() );
        appendIf( sb, "customField2", p.customField2() );
        appendIf( sb, "customField3", p.customField3() );
        appendIf( sb, "customField4", p.customField4() );
        appendIf( sb, "customField5", p.customField5() );
        appendIf( sb, "userState", p.userState() );
        appendIf( sb, "validUntil", p.validUntil() );
        appendIf( sb, "validFrom", p.validFrom() );
        appendIf( sb, "expirationAction", p.expirationAction() );
        appendIf( sb, "changePassword", p.changePassword() );
        return sb.toString();
    }

    private static void appendIf( StringBuilder sb, String label, Object value )
    {
        if ( value == null )
        { return; }
        if ( sb.length() > 0 )
        { sb.append( ',' ); }
        sb.append( label );
    }

    /** True iff {@code userName} matches the binding's own login
     *  (decoded from the base64-wrapped {@code loxone.miniserver.security
     *  .credentials.user} property). Used by the delete / edit / disable
     *  guards to refuse self-mutilation. */
    private boolean isBindingUser( String userName )
    {
        if ( userName == null || userName.isBlank() )
        { return false; }
        String bindingName = decodeBase64(
                config.miniserver().security().credentials().user() );
        return userName.equals( bindingName );
    }

    private static void validateUuid( String uuid )
    {
        if ( uuid == null || uuid.length() < 30 || uuid.length() > 50 )
        {
            throw new IllegalArgumentException( "UUID looks malformed: " + uuid );
        }
    }

    /** Delegate to the central {@link AdminCommandResponses#requireOk}.
     *  Kept as a thin pass-through for grep-locality. */
    private static void requireOk( JsonNode ll, String label )
    {
        AdminCommandResponses.requireOk( ll, label );
    }

    /** ISO-8601 UTC timestamp prefix for audit entries. Separate from
     *  the JBoss LogManager's own timestamp so the audit log line
     *  carries the canonical instant even if the log format changes
     *  later. */
    private static String auditTs()
    {
        return Instant.now().toString();
    }

    /** Mirror of {@code MiniserverAdminCommandClient.decodeBase64} —
     *  kept duplicated to avoid widening the public surface of that
     *  class. Tolerant on plaintext input (dev / legacy configs). */
    private static String decodeBase64( String value )
    {
        if ( value == null || value.isBlank() )
        { return ""; }
        try
        {
            return new String( Base64.getDecoder().decode( value ), StandardCharsets.UTF_8 );
        }
        catch ( IllegalArgumentException notBase64 )
        {
            return value;
        }
    }
}
