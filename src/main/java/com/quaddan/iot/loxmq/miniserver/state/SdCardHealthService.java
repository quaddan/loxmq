/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.state;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quaddan.iot.loxmq.config.LoxoneConfig;
import com.quaddan.iot.loxmq.miniserver.http.MiniserverHttpClient;
import com.quaddan.iot.loxmq.miniserver.session.MiniserverConnectedEvent;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs the Miniserver's on-device SD-card self-test once per session and
 * holds the result for the dashboard's Miniserver Identity panel.
 *
 * <h3>What it does</h3>
 * On each {@link MiniserverConnectedEvent} (session reached RUNNING) it issues
 * a direct HTTP(S) {@code GET jdev/sys/sdtest} with HTTP Basic auth and stores
 * the parsed {@link SdCardStatus} in an {@link AtomicReference}. The dashboard
 * reads it through {@link #status()} at page-render time.
 *
 * <h3>Why a direct HTTP(S) GET (not the admin/autht channel, not the WS)</h3>
 * {@code jdev/sys/sdtest} is a system diagnostic the Miniserver authenticates
 * with a standard HTTP Basic header — no token hash, no WebSocket. Two
 * concrete reasons drove this over {@code MiniserverAdminCommandClient}:
 * <ul>
 *   <li><b>Auth fit.</b> The {@code autht} token-hash mechanism targets the
 *       {@code jdev/sps/*} admin surface; {@code jdev/sys/*} commands take
 *       Basic auth directly.</li>
 *   <li><b>Bean lifecycle.</b> {@link MiniserverHttpClient} is materialised
 *       during the handshake (well before RUNNING), whereas
 *       {@code MiniserverAdminCommandClient} may be touched for the very first
 *       time from this async-observer thread — and a first-time lazy
 *       materialisation there fails with a CDI {@code CreationException}
 *       (synthetic-bean injection point). Reusing the already-live HTTP client
 *       avoids that path entirely.</li>
 * </ul>
 *
 * <h3>Holder + observer pattern</h3>
 * Mirrors {@code KeepAliveScheduler}: {@code @ApplicationScoped @Startup}, an
 * {@code @ObservesAsync} hook on {@link MiniserverConnectedEvent}, runtime
 * value in an {@link AtomicReference}, and a read-only {@link Optional}
 * accessor for the dashboard. {@code @Startup} forces eager creation on the
 * main thread (same reasoning as {@code AppInfoPublisher}).
 *
 * <h3>Failure handling</h3>
 * The self-test is a best-effort diagnostic and must never destabilise the
 * session, so {@link #onMiniserverConnected} catches broadly — no exception
 * may escape to the async-observer exception handler. On any failure the
 * previous status (if any) is kept and the next {@link MiniserverConnectedEvent}
 * retries; the dashboard row simply stays "pending" until a test succeeds.
 */
@ApplicationScoped
@Startup
public class SdCardHealthService
{
    private static final Logger LOG = Logger.getLogger( SdCardHealthService.class );

    @Inject
    MiniserverHttpClient httpClient;

    @Inject
    LoxoneConfig config;

    @Inject
    ObjectMapper jsonMapper;

    /** Last self-test result. {@code null} until the first reply lands. */
    private final AtomicReference< SdCardStatus > statusRef = new AtomicReference<>();

    /** Session reached RUNNING → run the self-test once and stash the result. */
    void onMiniserverConnected( @ObservesAsync MiniserverConnectedEvent event )
    {
        try
        {
            String body  = httpClient.fetchSdTest( basicAuthHeader() );
            JsonNode root = jsonMapper.readTree( body );
            JsonNode ll   = root.path( "LL" ).isMissingNode() ? root : root.path( "LL" );

            String code = ll.path( "Code" ).asText( "" );
            if ( !code.isEmpty() && !"200".equals( code ) )
            {
                LOG.warnf( "SD card self-test rejected by Miniserver — Code=%s", code );
                return;
            }
            String       value  = ll.path( "value" ).asText( "" );
            SdCardStatus status = SdCardStatus.parse( value, Instant.now() );
            statusRef.set( status );
            if ( status.healthy() )
            {
                LOG.infof( "SD card self-test OK — %s", status.detail() );
            }
            else
            {
                LOG.warnf( "SD card self-test reported a problem — %s", status.detail() );
            }
        }
        catch ( Exception e )
        {
            // Best-effort diagnostic. Catch broadly so NOTHING escapes to the
            // async-observer exception handler (an uncaught throw there is
            // logged as ERROR and leaves the dashboard row stuck on "pending").
            // Common causes: HTTP 401 (bad Basic credential), transport blip,
            // unparseable body. Keep the last-known status; the next
            // MiniserverConnectedEvent retries.
            LOG.warnf( "SD card self-test failed — %s",
                       e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName() );
        }
    }

    /** Most recent SD-card self-test result, if one has completed.
     *  {@link Optional#empty()} before the first successful test (or if every
     *  attempt so far failed) — the dashboard then shows "pending". */
    public Optional< SdCardStatus > status()
    {
        return Optional.ofNullable( statusRef.get() );
    }

    // ============================================================
    //  Internals
    // ============================================================

    /** Build the {@code Authorization: Basic …} header value from the
     *  configured credentials (RFC 7617). The credentials are base64-wrapped
     *  at rest in config, so they are decoded to plaintext first, then the
     *  {@code user:password} pair is base64-encoded per the Basic scheme.
     *  Never logged. */
    private String basicAuthHeader()
    {
        String user     = decodeBase64( config.miniserver().security().credentials().user() );
        String password = decodeBase64( config.miniserver().security().credentials().password() );
        String token    = Base64.getEncoder()
                                 .encodeToString( ( user + ":" + password ).getBytes( StandardCharsets.UTF_8 ) );
        return "Basic " + token;
    }

    /** Decode a base64-encoded credential field — Loxone config stores
     *  user/password base64-wrapped for at-rest obfuscation. Returns the
     *  empty string for null/blank input, and tolerates plaintext (legacy /
     *  dev configs). Mirrors {@code SessionOrchestrator.decodeBase64}. */
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
