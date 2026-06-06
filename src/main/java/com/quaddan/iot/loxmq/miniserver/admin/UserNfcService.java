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
import java.time.Instant;
import java.util.regex.Pattern;

/**
 * NFC tag write surface for Miniserver users.
 *
 * <p>Three operations exposed:
 * <ul>
 *   <li>{@link #discoverTag()} — listen for the next tap on any NFC
 *       reader and return the tag ID.</li>
 *   <li>{@link #addTagToUser(String, String, String)} — assign an NFC
 *       tag (existing or freshly discovered) to a user.</li>
 *   <li>{@link #removeTagFromUser(String, String)} — unassign.</li>
 * </ul>
 *
 * <h3>Why separated from {@code UserMutationService}</h3>
 * Same rationale as {@link UserAuthService}: clean audit surface +
 * keeps the per-domain bean ≤ 200 lines. NFC is a self-contained
 * sub-domain (has its own UI tab, its own "discover-then-add" flow,
 * its own validation).
 *
 * <h3>Discover workflow</h3>
 * The {@code discovernfc} endpoint asks the Miniserver to monitor the
 * NFC readers (NFC Code Touch, Intercom, etc.). Empirically synchronous:
 * the request blocks until a tag is tapped OR the {@code requestTimeout}
 * HTTP expires. The UI surfaces this with a spinner + cancel button.
 *
 * <h3>Spec: {@code docs/loxone/1700_Usermanagement.pdf} V17 §"NFC".</h3>
 */
@ApplicationScoped
public class UserNfcService
{
    private static final Logger AUDIT = Logger.getLogger( "audit" );
    private static final Logger LOG   = Logger.getLogger( UserNfcService.class );

    /** Standard timeout for {@code addusernfc} / {@code removeusernfc}. */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds( 12 );

    /** Longer budget for {@code discovernfc} — the Miniserver blocks
     *  waiting for a tag tap. 60 s gives the operator enough time to
     *  walk to the reader. The HTTP request layer's own
     *  {@code requestTimeout} cap (typically configured longer for
     *  this admin client) is what actually enforces the upper bound. */
    private static final Duration DISCOVER_TIMEOUT = Duration.ofSeconds( 60 );

    /** Hex chars only, optionally with {@code :} separators. 4 to 32
     *  hex digits (1 to 16 byte UIDs). */
    private static final Pattern NFC_TAG_PATTERN =
            Pattern.compile( "^[0-9A-Fa-f]([0-9A-Fa-f:]*[0-9A-Fa-f])?$" );

    @Inject
    MiniserverAdminCommandClient adminClient;
    @Inject
    UserService                  userService;

    // ============================================================
    //  discover
    // ============================================================

    /**
     * Tell the Miniserver to surface the next NFC tap. Synchronous —
     * blocks until a tag is tapped or the HTTP layer times out.
     *
     * <p>Wire path: {@code jdev/sps/discovernfc}.
     *
     * <p>Returns the raw tag ID as reported by the Miniserver (hex,
     * may include {@code :} separators depending on reader firmware).
     * The caller normalises before passing to
     * {@link #addTagToUser(String, String, String)}.
     */
    public String discoverTag()
    {
        JsonNode ll = adminClient.sendAndAwait( "discovernfc", DISCOVER_TIMEOUT );
        AdminCommandResponses.requireOk( ll, "discovernfc" );
        JsonNode v = adminClient.unwrapValue( ll );

        // Three observed shapes:
        // - string: "AA:BB:CC:DD"
        // - object: {"tagId":"AA:BB:CC:DD"} or {"id":"..."}
        // - empty value (no tag tapped before timeout) → empty string
        String tagId;
        if ( v.isTextual() )
        {
            tagId = v.asText( "" );
        }
        else if ( v.isObject() )
        {
            tagId = v.path( "tagId" ).asText(
                    v.path( "id" ).asText(
                            v.path( "uid" ).asText( "" ) ) );
        }
        else
        {
            tagId = "";
        }

        AUDIT.infof( "%s NFC_DISCOVER — tag=%s",
                     auditTs(),
                     tagId.isEmpty() ? "<timeout>" : tagId );
        return tagId;
    }

    // ============================================================
    //  add / remove
    // ============================================================

    /**
     * Assign an NFC tag to a user.
     *
     * <p>Wire path: {@code jdev/sps/addusernfc/{userUuid}/{tagId}/{friendlyName}}.
     * If {@code friendlyName} is null or blank, the trailing segment is
     * omitted — the Miniserver uses the tag ID as the display name in
     * that case.
     *
     * <p>The same tag can be added to multiple users without error
     * (the Miniserver does NOT enforce uniqueness across users); the
     * operator is responsible for not double-assigning.
     */
    public void addTagToUser( String userUuid, String tagId, String friendlyName )
    {
        validateUuid( userUuid );
        validateTagId( tagId );

        UserDetail target = userService.getUser( userUuid );    // pre-flight, surfaces 404

        String cmd;
        if ( friendlyName == null || friendlyName.isBlank() )
        {
            cmd = "addusernfc/" + userUuid + "/" + tagId;
        }
        else
        {
            if ( friendlyName.length() > 100 )
            {
                throw new IllegalArgumentException( "friendlyName too long (≤ 100 chars expected)" );
            }
            cmd = "addusernfc/" + userUuid + "/" + tagId + "/"
                  + URLEncoder.encode( friendlyName, StandardCharsets.UTF_8 );
        }
        JsonNode ll = adminClient.sendAndAwait( cmd, DEFAULT_TIMEOUT );
        AdminCommandResponses.requireOk( ll, "addusernfc " + tagId );

        AUDIT.infof( "%s NFC_ADD user=%s/%s tag=%s name=%s — OK",
                     auditTs(), userUuid, target.name(), tagId,
                     friendlyName == null ? "" : friendlyName );
        LOG.infof( "NFC tag %s added to user %s/%s", tagId, userUuid, target.name() );
    }

    /**
     * Unassign an NFC tag from a user.
     *
     * <p>Wire path: {@code jdev/sps/removeusernfc/{userUuid}/{tagId}}.
     */
    public void removeTagFromUser( String userUuid, String tagId )
    {
        validateUuid( userUuid );
        validateTagId( tagId );

        UserDetail target = userService.getUser( userUuid );

        String   cmd = "removeusernfc/" + userUuid + "/" + tagId;
        JsonNode ll  = adminClient.sendAndAwait( cmd, DEFAULT_TIMEOUT );
        AdminCommandResponses.requireOk( ll, "removeusernfc " + tagId );

        AUDIT.infof( "%s NFC_REMOVE user=%s/%s tag=%s — OK",
                     auditTs(), userUuid, target.name(), tagId );
        LOG.infof( "NFC tag %s removed from user %s/%s", tagId, userUuid, target.name() );
    }

    // ============================================================
    //  Internals
    // ============================================================

    private static void validateUuid( String uuid )
    {
        if ( uuid == null || uuid.length() < 30 || uuid.length() > 50 )
        {
            throw new IllegalArgumentException( "UUID looks malformed: " + uuid );
        }
    }

    private static void validateTagId( String tagId )
    {
        if ( tagId == null || tagId.isBlank() )
        {
            throw new IllegalArgumentException( "NFC tag ID must be non-blank" );
        }
        if ( tagId.length() > 64 )
        {
            // Defensive — a real NFC UID is at most 16 bytes (32 hex
            // chars). Allow some headroom for {@code :} separators.
            throw new IllegalArgumentException( "NFC tag ID is too long (≤ 64 chars expected)" );
        }
        if ( !NFC_TAG_PATTERN.matcher( tagId ).matches() )
        {
            throw new IllegalArgumentException(
                    "NFC tag ID must be hex (optionally separated by ':') : " + tagId );
        }
    }

    private static String auditTs()
    {
        return Instant.now().toString();
    }
}
