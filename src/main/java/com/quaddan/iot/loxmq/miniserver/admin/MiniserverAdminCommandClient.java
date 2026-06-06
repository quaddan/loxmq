/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quaddan.iot.loxmq.config.LoxoneConfig;
import com.quaddan.iot.loxmq.miniserver.crypto.LoxoneCryptoService;
import com.quaddan.iot.loxmq.miniserver.http.LoxoneJsonParser;
import com.quaddan.iot.loxmq.miniserver.http.MiniserverHttpClient;
import com.quaddan.iot.loxmq.miniserver.session.MiniserverToken;
import com.quaddan.iot.loxmq.miniserver.session.SessionState;
import com.quaddan.iot.loxmq.miniserver.session.SessionTracker;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Synchronous admin-command client for the {@code jdev/sps/*} REST-style
 * surface exposed by the Miniserver.
 *
 * <h3>HTTPS + autht (instead of encrypted WS)</h3>
 * Empirically against a real V17 Miniserver, the encrypted WS channel
 * returns {@code Code: 403} on admin commands (getuserlist2,
 * calendarcreateentry, etc.) <strong>even when the underlying user has
 * all rights</strong> in Loxone Config. The {@code tokenRights}
 * bitmask of the JWT acquired via {@code getjwt} only carries the
 * "non-destructive" bits (App + Op-Modes + AD + Adopt-UI); the Admin
 * (0x1), User-Mgmt (0x800), Config (0x8), FTP, etc. bits are stripped
 * on long-lived tokens to limit the damage in case of leak.
 *
 * <p>The Loxone iOS app routes these admin commands via HTTPS + a hash
 * {@code autht=HMAC(jwt, key)} in a query param. The Miniserver, on
 * this channel, verifies the user's effective rights (not the JWT
 * bitmask) → passes as an admin user.
 *
 * <p>The binding aligns with this mechanism: all {@code sendAndAwait}
 * calls (read and write) go via HTTPS GET with
 * {@code ?autht=…&user=…} appended. The encrypted WS stays for the
 * hot-path I/O commands (MQTT → Miniserver → state events).
 *
 * <h3>Collateral benefits</h3>
 * <ul>
 *   <li>No correlation via control-echo decrypt — the HTTPS reply is
 *       synchronous, the caller blocks on the GET.</li>
 *   <li>No pending futures + timeout risk.</li>
 *   <li>Less code: the {@code ConcurrentMap<String, CompletableFuture>}
 *       + {@code @Observes MiniserverCommandResponseEvent} go away.</li>
 * </ul>
 *
 * <h3>Preconditions</h3>
 * <ul>
 *   <li>Session must be {@link SessionState#RUNNING} — to have a
 *       valid JWT in {@link SessionTracker#token}.</li>
 *   <li>The binding user must be able to run the command on the
 *       Miniserver side. If the user lacks the right, the reply is
 *       {@code Code=403} with the message decorated by
 *       {@link AdminCommandResponses#requireOk}.</li>
 * </ul>
 *
 * <h3>Not a general-purpose channel</h3>
 * This class does NOT replace the I/O command pipeline MQTT →
 * orchestrator → encrypted WS. It only serves the binding's clean
 * REST/UI surface (/api/v1/schedules, /api/v1/users, …) which needs
 * a synchronous, parseable response.
 */
@ApplicationScoped
public class MiniserverAdminCommandClient
{
    private static final Logger LOG = Logger.getLogger( MiniserverAdminCommandClient.class );

    /** Loxone path prefix for admin / Structure Programming System commands.
     *  Distinct from {@code jdev/sps/io/} (I/O commands, configured via
     *  {@code loxone.miniserver.cmd.prefix.root}). */
    static final String ADMIN_COMMAND_PREFIX = "jdev/sps/";

    // ─────────────────────────────────────────────────────────────────────
    //  No hash-key cache.
    //
    //  A previous attempt cached the HMAC hash key (TTL 30s) to absorb
    //  navigation bursts. Empirically, `getkey/{user}` returns a
    //  single-use NONCE key. Every cache hit triggers a Code=401 forced
    //  by the Miniserver; the retry-on-401 refetches + retries → 3
    //  round-trips per cached-call instead of 2 without cache.
    //
    //  Caching the key is FUNDAMENTALLY incompatible with the
    //  Miniserver's nonce semantics. Systematic refetch per admin
    //  command is required. The fix for fast-navigation lag must come
    //  from elsewhere (cap concurrency, HTTP keepalive, UI debouncing…).
    // ─────────────────────────────────────────────────────────────────────

    @Inject
    MiniserverHttpClient httpClient;
    @Inject
    SessionTracker       tracker;
    @Inject
    LoxoneCryptoService  crypto;
    @Inject
    LoxoneConfig         config;
    @Inject
    ObjectMapper         jsonMapper;
    @Inject
    LoxoneJsonParser     parser;

    /** Semaphore that caps the number of admin commands in flight
     *  simultaneously. See {@link LoxoneConfig.Miniserver.Connection.Http
     *  #adminMaxConcurrent()} + {@link LoxoneConfig.Miniserver.Connection.Http
     *  #adminWaitTimeout()}.
     *
     *  <p>{@code fair=true} for FIFO — without fairness, a thread can
     *  starve another indefinitely under contention. Fairness costs a
     *  few microseconds per acquire, negligible compared to the
     *  multi-ms network round-trip. */
    Semaphore concurrencyLimit;

    /** Counter of admin commands that waited for a slot beyond
     *  instantaneous acquisition (i.e. the queue wasn't empty). Useful
     *  to empirically observe contention under burst. Exposed via
     *  {@link #pendingWaitCount()} for a future metrics endpoint. */
    private final AtomicInteger waitedCount = new AtomicInteger();

    @PostConstruct
    void initSemaphore()
    {
        int max = config.miniserver().connection().http().adminMaxConcurrent();
        if ( max < 1 )
        {
            LOG.warnf( "loxone.miniserver.connection.http.admin-max-concurrent=%d invalid, "
                       + "falling back to 1", max );
            max = 1;
        }
        this.concurrencyLimit = new Semaphore( max, true );
        LOG.infof( "Admin command concurrency capped to %d in-flight (wait timeout %s)",
                   max,
                   config.miniserver().connection().http().adminWaitTimeout() );
    }

    /** Visible-for-testing: number of admin commands that had to wait
     *  for a Semaphore slot at least once (cumulative counter). */
    int pendingWaitCount()
    {
        return waitedCount.get();
    }

    /**
     * Send {@code jdev/sps/{pathSegment}} via HTTPS + autht and block until
     * the reply arrives or the underlying HTTP request times out (configured
     * at {@code loxone.miniserver.connection.http.request-timeout}).
     *
     * @param pathSegment everything after {@code jdev/sps/} —
     *                    e.g. {@code "calendargetentries"} or
     *                    {@code "getuser/0a5fa72f-018b-0050-…"}
     * @param timeout     ignored at present (HTTPS request uses the
     *                    httpClient's configured timeout). Kept in the
     *                    signature so callers don't need to change.
     * @return the {@code LL} sub-tree of the reply JSON
     */
    public JsonNode sendAndAwait( String pathSegment, Duration timeout )
    {
        if ( pathSegment == null || pathSegment.isBlank() )
        {
            throw new IllegalArgumentException( "pathSegment must not be blank" );
        }
        SessionState state = tracker.state();
        if ( state != SessionState.RUNNING )
        {
            throw new IllegalStateException(
                    "Admin command rejected — session is " + state + " (not RUNNING). "
                    + "Path: " + pathSegment );
        }
        Optional< MiniserverToken > tokenOpt = tracker.token();
        if ( tokenOpt.isEmpty() )
        {
            throw new IllegalStateException(
                    "Admin command rejected — session RUNNING but no token cached. "
                    + "Path: " + pathSegment );
        }

        MiniserverToken token       = tokenOpt.get();
        String          fullPath    = ADMIN_COMMAND_PREFIX + pathSegment;
        String          userDecoded = decodeBase64( config.miniserver().security().credentials().user() );

        // Cap concurrency towards the Miniserver via the Semaphore.
        // Without a cap, a fast navigation burst between dashboard
        // pages overloads the device and triggers throttling on the
        // Miniserver side (V17 has ~6-8 HTTP slots empirically).
        //
        // tryAcquire with timeout for fail-fast rather than hanging
        // indefinitely if throughput drops for another reason. The
        // timeout comes from config (default 30s).
        Duration waitTimeout = config.miniserver().connection().http().adminWaitTimeout();
        boolean  acquired    = tryAcquireSlot( waitTimeout, fullPath );
        if ( !acquired )
        {
            // All slots have been busy for ≥ waitTimeout seconds. The
            // Miniserver is probably saturated OR an admin call is
            // stuck (deadlock, leak). Fail-fast rather than worsen.
            throw new AdminCommandException(
                    "Timeout waiting for admin slot (max="
                    + config.miniserver().connection().http().adminMaxConcurrent()
                    + " concurrent admin commands, waited " + waitTimeout
                    + "). Miniserver may be overloaded — back off or check /q/health." );
        }
        try
        {
            // Auto-retry once on transient failures. Empirically, the
            // 1st click on /users View occasionally fails but the 2nd
            // passes immediately. Observed symptoms:
            //   - "Failed to compute autht hash" — network blip on getkey
            //   - HTTP 401 on the admin GET — HMAC rotation on the Miniserver
            //     side between fetchHashKey and use of authtHex
            //   - "HTTPS GET failed" — isolated TCP RST / connection reset
            //
            // The operator shouldn't have to click twice for these
            // transients. The retry redoes a FULL cycle (fetchHashKey
            // → parseHashKey → hashToken → adminGet) which gives the
            // Miniserver a fresh post-rotation HMAC key and re-establishes
            // a clean HTTPS connection.
            //
            // Do NOT retry on:
            //   - 403 (permission missing — retry changes nothing)
            //   - other 4xx (input malformed, not found, etc.)
            //   - body parse failure (the Miniserver returned a valid but
            //     weird body — retry would produce the same result)
            //
            // See {@link #isRetryable} for details.
            AdminCommandException firstFailure = null;
            for ( int attempt = 1; attempt <= 2; attempt++ )
            {
                try
                {
                    return doAuthtAndFetch( fullPath, userDecoded, token );
                }
                catch ( AdminCommandException e )
                {
                    if ( attempt == 2 || !isRetryable( e ) )
                    {
                        throw e;
                    }
                    firstFailure = e;
                    // No invalidateHashKey() — there is no cache. The
                    // retry naturally redoes a fresh fetchHashKey
                    // (single-use nonce on the Miniserver side).
                    LOG.warnf( "Admin command %s attempt %d failed (%s) — retrying once with fresh autht",
                               fullPath, attempt, e.getMessage() );
                }
            }
            // Unreachable: the loop either returns from doAuthtAndFetch or
            // re-throws on the 2nd attempt. Defensive throw for compiler.
            throw firstFailure != null
                  ? firstFailure
                  : new AdminCommandException( "Unexpected retry loop exit for " + fullPath );
        }
        finally
        {
            // ALWAYS release, even if the admin command threw (which
            // can be a network timeout, a 403, etc.). Without this
            // finally, a single throw during an admin call would leak
            // 1 semaphore slot forever → leak until the pool is 100%
            // blocked.
            concurrencyLimit.release();
        }
    }

    /** Acquire a Semaphore slot with timeout. If the queue wasn't empty
     *  (i.e. {@link Semaphore#availablePermits()} = 0 at the time of
     *  the call), increment the contention counter for observability. */
    private boolean tryAcquireSlot( Duration waitTimeout, String fullPath )
    {
        boolean immediate = concurrencyLimit.tryAcquire();
        if ( immediate )
        {
            return true;
        }
        // Slot not available immediately → we enter the queue. Debug
        // log to avoid spam on moderate contention (this is by design
        // here, not a failure).
        waitedCount.incrementAndGet();
        LOG.debugf( "Admin slot busy, waiting up to %s for %s (queue length ~%d)",
                    waitTimeout, fullPath, concurrencyLimit.getQueueLength() );
        try
        {
            return concurrencyLimit.tryAcquire( waitTimeout.toMillis(), TimeUnit.MILLISECONDS );
        }
        catch ( InterruptedException e )
        {
            Thread.currentThread().interrupt();
            // Rare case: the Quarkus thread was interrupted while
            // waiting. Do NOT silently acquire the permit → fail the call.
            throw new AdminCommandException(
                    "Interrupted while waiting for admin slot (" + fullPath + ")" );
        }
    }

    /**
     * Single attempt at the autht computation + HTTPS GET + reply parse
     * pipeline. Extracted from {@link #sendAndAwait} so the retry loop
     * can call it twice without duplicating the logic.
     *
     * <p>Throws {@link AdminCommandException} on any failure; the
     * caller examines the exception message via {@link #isRetryable} to
     * decide whether to retry.
     */
    private JsonNode doAuthtAndFetch( String fullPath, String userDecoded, MiniserverToken token )
    {
        // The HMAC key for `autht` comes from /jdev/sys/getkey/{user}
        // (separate HTTP GET), NOT from the `key` field of the JWT.
        // SYSTEMATIC refetch on each admin command — the key is a
        // single-use nonce on the Miniserver side.
        String authtHex;
        try
        {
            String body    = httpClient.fetchHashKey( userDecoded );
            String hashKey = parser.parseHashKey( body );
            authtHex = crypto.hashToken( hashKey, token.token() );
        }
        catch ( Exception e )
        {
            // Include the cause's message in the wrapper so the UI
            // surface bar shows what actually failed. The full
            // stacktrace is logged at WARN for the operator to grab
            // from application.log if needed.
            String detail = e.getMessage() != null && !e.getMessage().isBlank()
                            ? " — " + e.getMessage()
                            : " (" + e.getClass().getSimpleName() + ")";
            LOG.warnf( e, "autht computation failed for %s (user=%s)", fullPath, userDecoded );
            throw new AdminCommandException(
                    "Failed to compute autht hash for " + fullPath + detail, e );
        }

        String responseBody;
        try
        {
            LOG.debugf( "→ admin HTTP: %s (user=%s)", fullPath, userDecoded );
            responseBody = httpClient.sendAuthenticatedAdminGet( fullPath, userDecoded, authtHex );
        }
        catch ( RuntimeException e )
        {
            // Map HTTP-layer failures into the binding's admin exception
            // hierarchy so REST handlers can render them uniformly. The
            // underlying InvalidLoxoneResponseException carries the HTTP
            // status + URL in its message.
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            if ( msg.contains( "HTTP 401" ) || msg.contains( "HTTP 403" ) )
            {
                // 401 here = the Miniserver couldn't verify our autht hash
                // (token expired, key drift, wrong user). 403 = user lacks
                // permission for this command. Both map to the same
                // user-facing hint via decorateCodeError. The retry loop
                // in sendAndAwait will refetch a fresh hash key for 401
                // but skip 403 (permission won't change on retry).
                String code = msg.contains( "401" ) ? "401" : "403";
                throw new AdminCommandException(
                        AdminCommandResponses.decorateCodeError( code, fullPath, "" ), e );
            }
            // Include the cause's message — a bare "HTTPS GET failed
            // for X" gives no hint at the underlying network / parse
            // error.
            LOG.warnf( e, "HTTPS GET failed for %s", fullPath );
            throw new AdminCommandException( "HTTPS GET failed for " + fullPath + " — " + msg, e );
        }

        try
        {
            JsonNode root = jsonMapper.readTree( responseBody );
            JsonNode ll   = root.path( "LL" );
            if ( ll.isMissingNode() || ll.isNull() )
            {
                // Some endpoints (older firmware?) return the payload at
                // root without the LL envelope. Fall back to root in that
                // case — caller's unwrapValue will navigate via
                // ll.path("value") which would just be missing.
                LOG.debugf( "← admin HTTP reply has no LL envelope for %s — using root", fullPath );
                return root;
            }
            LOG.debugf( "← admin HTTP reply OK for %s (Code=%s)",
                        fullPath, ll.path( "Code" ).asText( "" ) );
            return ll;
        }
        catch ( Exception e )
        {
            throw new AdminCommandException(
                    "Could not parse admin reply for " + fullPath
                    + " (body length=" + responseBody.length() + ")", e );
        }
    }

    /**
     * Decide whether an {@link AdminCommandException} indicates a
     * transient failure that's worth retrying once. Driven by the
     * message prefix / substring (the exception type is uniformly
     * {@code AdminCommandException}, so the message is the only
     * discriminator).
     *
     * <p>Retry on:
     * <ul>
     *   <li>{@code "Failed to compute autht hash"} — fetchHashKey blip
     *       (network), parseHashKey blip (JSON), hashToken (rare).</li>
     *   <li>{@code "HTTPS GET failed"} — TCP-level error on the admin
     *       GET (timeout, connection reset).</li>
     *   <li>{@code "Code=401"} — Miniserver rejected the autht hash
     *       (HMAC key rotated between fetch and use). Refetch + retry.</li>
     * </ul>
     *
     * <p>Do <strong>not</strong> retry on:
     * <ul>
     *   <li>{@code "Code=403"} — permission missing, won't change.</li>
     *   <li>{@code "Code=400/404"} — input malformed / not found.</li>
     *   <li>{@code "Could not parse admin reply"} — Miniserver returned
     *       a successful body but in unparseable form; retry gets the
     *       same body.</li>
     * </ul>
     *
     * <p>Visible-for-testing — exercised by
     * {@code MiniserverAdminCommandClientTest}.
     */
    static boolean isRetryable( AdminCommandException e )
    {
        String msg = e.getMessage();
        if ( msg == null )
        { return false; }
        if ( msg.startsWith( "Failed to compute autht hash" ) )
        { return true; }
        if ( msg.startsWith( "HTTPS GET failed" ) )
        { return true; }
        if ( msg.contains( "Code=401" ) )
        { return true; }
        return false;
    }

    /**
     * Helper for admin services — extract the {@code value} field from the
     * {@code LL} sub-tree and, if it's a JSON-encoded string, re-parse it
     * as a JsonNode.
     *
     * <p>Loxone V17 sometimes serialises array / object values as a
     * <strong>string containing JSON</strong> inside the {@code value}
     * field rather than as a native JsonNode (observed on
     * {@code calendargetentries}). This helper handles both forms
     * transparently — callers always get a native JsonNode they can
     * introspect with {@code isArray()} / {@code .path(...)} etc.
     */
    public JsonNode unwrapValue( JsonNode ll )
    {
        if ( ll == null )
        { return jsonMapper.nullNode(); }
        JsonNode value = ll.path( "value" );
        if ( !value.isTextual() )
        {
            return value;
        }
        String asText = value.asText( "" );
        if ( asText.isEmpty() )
        {
            return jsonMapper.nullNode();
        }
        char first = asText.charAt( 0 );
        if ( first != '{' && first != '[' )
        {
            return value;
        }
        try
        {
            return jsonMapper.readTree( asText );
        }
        catch ( Exception e )
        {
            LOG.warnf( e, "Failed to re-parse string-encoded value: %s",
                       asText.length() > 80 ? asText.substring( 0, 80 ) + "…" : asText );
            return value;
        }
    }

    // ============================================================
    //  Internals
    // ============================================================

    /** Decode a base64-encoded credential field — Loxone config stores
     *  user/password base64-wrapped for at-rest obfuscation. Returns the
     *  empty string for null/blank input. Mirrors
     *  {@code SessionOrchestrator.decodeBase64} (kept duplicated to avoid
     *  a public surface change on the orchestrator). */
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
            // Tolerate plaintext credentials in dev / legacy configs.
            return value;
        }
    }
}
