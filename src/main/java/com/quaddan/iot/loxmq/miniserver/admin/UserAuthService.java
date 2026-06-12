/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.quaddan.iot.loxmq.config.LoxoneConfig;
import com.quaddan.iot.loxmq.miniserver.crypto.KeyAndSalt;
import com.quaddan.iot.loxmq.miniserver.crypto.LoxoneCryptoService;
import com.quaddan.iot.loxmq.miniserver.http.LoxoneJsonParser;
import com.quaddan.iot.loxmq.miniserver.http.MiniserverHttpClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * Auth-side write surface for Miniserver users.
 *
 * <p>Three operations, all going through {@code jdev/sps/updateuser*} :
 * <ul>
 *   <li>{@link #updatePassword(String, String)} — main user password</li>
 *   <li>{@link #updateVisuPassword(String, String)} — visualization
 *       password (PIN-like, used by Loxone Config UI to confirm
 *       sensitive ops)</li>
 *   <li>{@link #updateAccessCode(String, String)} — numeric access
 *       code (used by NFC Code Touch / Intercom)</li>
 * </ul>
 *
 * <h3>Why separated from {@code UserMutationService}</h3>
 * <ul>
 *   <li>Different security posture — passwords/codes
 *       <strong>must never</strong> appear in a log (neither audit
 *       nor application). Keeping this in a single class ≤ 200 lines
 *       allows visual review of the code to ensure no
 *       {@code log.info(...password...)} slipped in.</li>
 *   <li>Wider dependencies
 *       ({@link MiniserverHttpClient} + {@link LoxoneCryptoService}
 *       + {@link LoxoneJsonParser}) — these deps had no business in
 *       {@code UserMutationService} (which only needs the admin
 *       client + the user service).</li>
 * </ul>
 *
 * <h3>Recipe password / visu</h3>
 * <ol>
 *   <li>HTTP GET {@code jdev/sys/getkey2/{userName}} — fetches the
 *       salt and hash algo of the target user (NOT the binding user).
 *       No auth required on this endpoint (see
 *       {@link MiniserverHttpClient#fetchKeyAndSalt(String)}).</li>
 *   <li>{@code pwHash = digest(newPassword + ":" + salt).toUpperCase()}
 *       via {@link LoxoneCryptoService#passwordHash}.</li>
 *   <li>HTTPS GET {@code jdev/sps/updateuserpwdh/{uuid}/{pwHash}}
 *       (resp. {@code updateuservisupwdh}) — via the usual admin
 *       client, so auth included.</li>
 * </ol>
 *
 * <h3>Recipe access code</h3>
 * The access code is numeric, transmitted as-is (no hash —
 * notably because the NFC Code Touch tag sends the value in
 * cleartext on the bus). URL-encoded for path cleanliness.
 *
 * <h3>Safety guards</h3>
 * Like {@link UserMutationService}, we refuse to touch the
 * binding user — changing its password without syncing
 * {@code application.yaml} disconnects it at the next
 * session-renewal. The operator must do it in two steps:
 * edit the {@code application.yaml}, restart, then use the UI / API
 * to align the Miniserver. Not a workflow to automate here.
 *
 * <p>Spec: {@code docs/loxone/1700_Usermanagement.pdf} V17
 * §"Update Password" + §"Update Visualization Password" +
 * §"Update Access Code".
 */
@ApplicationScoped
public class UserAuthService
{
    /** Operator audit trail. Audit messages here NEVER include the
     *  password / hash / code itself — only the user uuid + name + op
     *  name. */
    private static final Logger AUDIT = Logger.getLogger( "audit" );

    private static final Logger LOG = Logger.getLogger( UserAuthService.class );

    /** Per-call budget. */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds( 12 );

    @Inject
    MiniserverAdminCommandClient adminClient;
    @Inject
    UserService                  userService;
    @Inject
    MiniserverHttpClient         httpClient;
    @Inject
    LoxoneCryptoService          crypto;
    @Inject
    LoxoneJsonParser             parser;
    @Inject
    LoxoneConfig                 config;

    // ============================================================
    //  Password update
    // ============================================================

    /**
     * Set the user's main login password.
     *
     * <p>Two HTTP round-trips:
     * <ol>
     *   <li>{@code GET jdev/sys/getkey2/{userName}} — fetch the
     *       user's salt + hashAlg (public, no auth).</li>
     *   <li>{@code GET jdev/sps/updateuserpwdh/{uuid}/{hash}} (via
     *       the admin client, so auth'd).</li>
     * </ol>
     *
     * <p>The hash is {@code digest(plaintext + ":" + salt).toUpperCase()}
     * computed by {@link LoxoneCryptoService#passwordHash} — same
     * recipe as step 1 of {@code getjwt}.
     */
    public void updatePassword( String uuid, String newPassword )
    {
        validateUuid( uuid );
        requireNonBlankPassword( newPassword, "password" );

        UserDetail target = userService.getUser( uuid );
        guardSelf( target, "password" );

        String   hash = computeUserPasswordHash( target.name(), newPassword );
        String   cmd  = "updateuserpwdh/" + uuid + "/" + hash;
        JsonNode ll   = adminClient.sendAndAwait( cmd, DEFAULT_TIMEOUT );
        AdminCommandResponses.requireOk( ll, "update password for " + uuid );

        AUDIT.infof( "%s UPDATE_PWD %s/%s — OK", auditTs(), uuid, target.name() );
        LOG.infof( "Password updated for uuid=%s name=%s", uuid, target.name() );
    }

    /**
     * Set the user's visualization password.
     *
     * <p>Same recipe as {@link #updatePassword(String, String)} but
     * targets the {@code updateuservisupwdh} endpoint. The visu
     * password is a separate hash slot — used by Loxone Config / app
     * to gate sensitive operations behind a re-prompt without
     * disclosing the main password.
     */
    public void updateVisuPassword( String uuid, String newVisuPassword )
    {
        validateUuid( uuid );
        requireNonBlankPassword( newVisuPassword, "visu-password" );

        UserDetail target = userService.getUser( uuid );
        // The visu password CAN be set on self (it's a secondary
        // credential — changing it doesn't break the binding's main
        // session). No self-guard here.

        String   hash = computeUserPasswordHash( target.name(), newVisuPassword );
        String   cmd  = "updateuservisupwdh/" + uuid + "/" + hash;
        JsonNode ll   = adminClient.sendAndAwait( cmd, DEFAULT_TIMEOUT );
        AdminCommandResponses.requireOk( ll, "update visu-password for " + uuid );

        AUDIT.infof( "%s UPDATE_VISU_PWD %s/%s — OK", auditTs(), uuid, target.name() );
        LOG.infof( "Visu password updated for uuid=%s name=%s", uuid, target.name() );
    }

    /**
     * Set the user's access code — numeric PIN used at physical
     * entry points (NFC Code Touch, Intercom).
     *
     * <p>No hashing client-side: the code is transmitted as-is to
     * {@code updateuseraccesscode/{uuid}/{code}}; the Miniserver
     * stores it in whatever form is required by the consuming
     * controls (the NFC Code Touch reads digits from the keypad
     * and compares them in cleartext on its side). URL-encoded
     * defensively.
     */
    public void updateAccessCode( String uuid, String code )
    {
        validateUuid( uuid );
        if ( code == null || code.isBlank() )
        {
            throw new IllegalArgumentException( "Access code must be non-blank" );
        }
        if ( !code.chars().allMatch( Character::isDigit ) )
        {
            throw new IllegalArgumentException( "Access code must be digits only (0-9)" );
        }
        if ( code.length() < 4 || code.length() > 12 )
        {
            // Loxone doesn't formally bound the length but 4 is the
            // minimum the iOS app accepts and 12 keeps the path short
            // enough for the URL.
            throw new IllegalArgumentException( "Access code must be between 4 and 12 digits" );
        }

        UserDetail target = userService.getUser( uuid );
        // Access code on self is OK — same rationale as visu.

        String   encoded = URLEncoder.encode( code, StandardCharsets.UTF_8 );
        String   cmd     = "updateuseraccesscode/" + uuid + "/" + encoded;
        JsonNode ll      = adminClient.sendAndAwait( cmd, DEFAULT_TIMEOUT );
        AdminCommandResponses.requireOk( ll, "update access code for " + uuid );

        AUDIT.infof( "%s UPDATE_ACCESS_CODE %s/%s — OK (length=%d)",
                     auditTs(), uuid, target.name(), code.length() );
        LOG.infof( "Access code updated for uuid=%s name=%s", uuid, target.name() );
    }

    // ============================================================
    //  Internals
    // ============================================================

    /** Step 1 + 2 of the recipe — fetch the target user's salt then
     *  hash the new plaintext against it. Centralised so the
     *  exception handling matches the test in
     *  {@link MiniserverAdminCommandClient}. */
    private String computeUserPasswordHash( String userName, String newPlaintext )
    {
        try
        {
            String     body = httpClient.fetchKeyAndSalt( userName );
            KeyAndSalt ks   = parser.parseKeyAndSalt( body );
            return crypto.passwordHash( newPlaintext, ks );
        }
        catch ( Exception e )
        {
            // parseKeyAndSalt throws a checked JsonProcessingException ;
            // fetchKeyAndSalt + passwordHash throw RuntimeExceptions.
            // Wrap everything uniformly as AdminCommandException so the
            // REST layer maps it to 502 like any other Miniserver-side
            // failure.
            throw new AdminCommandException(
                    "Failed to compute password hash for " + userName + " — getkey2 lookup failed", e );
        }
    }

    private static void requireNonBlankPassword( String pw, String label )
    {
        if ( pw == null || pw.isBlank() )
        {
            throw new IllegalArgumentException( label + " must be non-blank" );
        }
        if ( pw.length() > 200 )
        {
            // Defensive — the Miniserver almost certainly imposes a
            // much shorter limit, but we don't know the exact bound.
            // 200 is roomy enough for any sane operator value.
            throw new IllegalArgumentException( label + " is too long (≤ 200 chars expected)" );
        }
    }

    private void guardSelf( UserDetail target, String opLabel )
    {
        if ( isBindingUser( target.name() ) )
        {
            String msg = "Refusing to change " + opLabel + " of user " + target.name()
                         + " — this is the binding's own login. Update "
                         + "loxone.miniserver.security.credentials.password in "
                         + "application.yaml first, then restart the binding.";
            AUDIT.warnf( "%s REFUSE %s %s — self", auditTs(), opLabel.toUpperCase(), target.name() );
            throw new IllegalArgumentException( msg );
        }
    }

    private boolean isBindingUser( String userName )
    {
        if ( userName == null || userName.isBlank() )
        { return false; }
        String bindingName = decodeBase64(config.miniserver().security().credentials().user() );
        return userName.equals( bindingName );
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
