/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quaddan.iot.loxmq.miniserver.state.MiniserverState;
import com.quaddan.iot.loxmq.config.LoxoneConfig;
import com.quaddan.iot.loxmq.miniserver.command.MiniserverApiConnectorSetCommand;
import com.quaddan.iot.loxmq.miniserver.command.MiniserverApiConnectorSetCommandEvent;
import com.quaddan.iot.loxmq.miniserver.command.MiniserverCommand;
import com.quaddan.iot.loxmq.miniserver.command.MiniserverCommandEvent;
import com.quaddan.iot.loxmq.miniserver.command.MiniserverCommandResponseEvent;
import com.quaddan.iot.loxmq.miniserver.connection.Endpoint;
import com.quaddan.iot.loxmq.miniserver.connection.EndpointResolver;
import com.quaddan.iot.loxmq.miniserver.crypto.KeyAndSalt;
import com.quaddan.iot.loxmq.miniserver.crypto.LoxoneCryptoService;
import com.quaddan.iot.loxmq.miniserver.http.LoxoneJsonParser;
import com.quaddan.iot.loxmq.miniserver.http.MiniserverHttpClient;
import com.quaddan.iot.loxmq.miniserver.message.BinaryStatesDecoder;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Drives the WebSocket handshake state machine from {@link SessionState#DISCONNECTED}
 * up to {@link SessionState#RUNNING}.
 *
 * <h3>Step sequence</h3>
 * <ol>
 *   <li><b>Open WS</b> → {@code wss://miniserver.host:port/ws/rfc6455} with
 *       {@code remotecontrol} subprotocol. State: {@link SessionState#CONNECTING}.</li>
 *   <li><b>Send keyexchange</b> → {@code jdev/sys/keyexchange/{base64(RSA(aesKey:iv))}}
 *       (cleartext frame; the crypto layer's
 *       {@link LoxoneCryptoService#wrappedSessionKey()} returns the wrapped key).
 *       State: {@link SessionState#AWAITING_KEY_EXCHANGE_REPLY}.</li>
 *   <li><b>Validate keyexchange reply</b> (any 200 envelope is OK — the
 *       miniserver echoes the encrypted session key back).</li>
 *   <li><b>HTTP GET getkey2</b> → user salt + HMAC key + hashAlg. Done over
 *       HTTP (not encrypted WS) because the miniserver accepts both — keeps
 *       the orchestrator's flow linear.</li>
 *   <li><b>Send encrypted getjwt</b> →
 *       {@code jdev/sys/enc/{AES(jdev/sys/getjwt/{hash}/{user}/{permission}/{uuid}/{info}\0)}}.
 *       The hash is {@code HMAC(hmacKey, user:HASH(password:salt))}.
 *       State: {@link SessionState#AWAITING_TOKEN_REPLY}.</li>
 *   <li><b>Validate token reply</b>, parse {@link TokenValue} from {@code LL.value},
 *       translate to {@link MiniserverToken} and store in {@link SessionTracker}.
 *       State: {@link SessionState#AWAITING_STRUCTURE_VERSION_REPLY}.</li>
 * </ol>
 *
 * <h3>Async + synchronous wait</h3>
 * The state machine is event-driven (driven by WS callbacks on the JDK
 * reader thread). {@link #connect()} returns a {@link CompletableFuture}
 * that completes when the state reaches {@link SessionState#RUNNING}
 * or {@link SessionState#FAILED}; callers can synchronously
 * {@code .get(timeout)} to bridge it back into REST endpoint threads.
 *
 * <h3>Scope</h3>
 * Owns the full session lifecycle: the keyexchange + JWT token handshake,
 * the post-token structure-file load, keepalive, token refresh and
 * reconnect scheduling.
 */
@ApplicationScoped
public class SessionOrchestrator
{
    private static final Logger LOG = Logger.getLogger( SessionOrchestrator.class );

    @Inject
    LoxoneConfig          config;
    @Inject
    EndpointResolver      endpointResolver;
    @Inject
    LoxoneCryptoService   crypto;
    @Inject
    MiniserverHttpClient  http;
    @Inject
    LoxoneJsonParser      parser;
    @Inject
    MiniserverWebSocket   webSocket;
    @Inject
    SessionTracker        tracker;
    @Inject
    ObjectMapper          jsonMapper;
    @Inject
    LoxApp3Cache          loxApp3Cache;
    @Inject
    ReconnectScheduler    reconnectScheduler;
    @Inject
    TokenRefreshScheduler tokenRefreshScheduler;
    @Inject
    BinaryStatesDecoder   binaryDecoder;
    @Inject
    MiniserverState miniserverState;
    @Inject
    Event< MiniserverCommandResponseEvent > commandResponseEvent;
    @Inject
    Event< MiniserverConnectedEvent >       miniserverConnectedEvent;

    /** Completion signal for the in-flight handshake — completes on RUNNING. */
    private volatile CompletableFuture< MiniserverToken > inFlight;

    /**
     * Holds the LoxAPP3.json content during the post-token steps (between
     * structure-file fetch / cache load and the status-update subscription
     * ack). Cleared once {@link SessionState#RUNNING} is reached and the
     * cache has been refreshed — the in-memory copy can be MB-sized so we
     * don't keep it around longer than needed.
     */
    private volatile String pendingLoxApp3;

    /**
     * Pre-warm injected beans whose first lazy access would otherwise land
     * on a non-Quarkus thread (JDK WebSocket reader or our own reconnect
     * scheduler). ArC's @{@link io.quarkus.runtime.Startup} only forces
     * client-proxy creation; the delegate (with its @Inject fields resolved)
     * is materialised on first method call. When that first method call
     * happens on the WS reader thread in dev mode, the classloader context
     * is sometimes flaky and the injection throws
     * "Error injecting LoxoneConfig".
     *
     * <p>This {@code @PostConstruct} runs when SessionOrchestrator itself is
     * first invoked — which is always either {@code Application.onStart}
     * (Quarkus Main Thread, auto-connect path) or
     * {@code ManagementResource.connect} (Quarkus REST worker, manual
     * trigger). Both are sane Quarkus threads. By touching the downstream
     * beans here, we force their delegate creation on the calling thread,
     * before any WS callback can hit them.
     *
     * <p>Once a delegate exists, all subsequent method calls (from any
     * thread) reuse the same instance — the race window closes for the
     * lifetime of the process.
     */
    @PostConstruct
    void prewarmInjectedBeans()
    {
        // Touch a side-effect-free method on each lazy-init-prone bean.
        // The actual return value is discarded; what matters is that the
        // delegate is created + injected synchronously on this thread.
        loxApp3Cache.directoryPath();
        reconnectScheduler.attemptCount();
        tokenRefreshScheduler.isRefreshPending();
        binaryDecoder.toString();           // forces ArC to materialise the BinaryStatesDecoder delegate
        LOG.debug( "SessionOrchestrator pre-warmed downstream beans (LoxApp3Cache, ReconnectScheduler, TokenRefreshScheduler, BinaryStatesDecoder)" );
    }

    // ==========================================================================
    //  Public API
    // ==========================================================================

    /**
     * Kick off the handshake. Returns immediately with a future that completes
     * when {@link SessionState#RUNNING} is reached, or fails with a
     * {@link SessionException} on any error.
     */
    public CompletableFuture< MiniserverToken > connect()
    {
        if ( inFlight != null && !inFlight.isDone() )
        {
            return CompletableFuture.failedFuture(
                    new SessionException( "Session handshake already in progress" ) );
        }
        inFlight = new CompletableFuture<>();

        if ( !crypto.hasPublicKey() )
        {
            return failNow( "Public key not loaded — run bootstrap (POST /api/v1/bootstrap) before connecting" );
        }

        try
        {
            Endpoint endpoint = endpointResolver.wsEndpoint();
            URI      uri      = endpoint.toUri();
            tracker.transition( SessionState.CONNECTING );
            // Capture the current inFlight in the listener so any delayed
            // callbacks from a previous WS attempt won't poison this one.
            webSocket.connect( uri, new HandshakeListener( inFlight ) );
            // onOpen will fire from inside connect() (or shortly after) and
            // continue the handshake. The future completes asynchronously.
        }
        catch ( SessionException e )
        {
            return failNow( e.getMessage() );
        }
        return inFlight;
    }

    /**
     * Convenience: connect and synchronously wait for completion. Used by the
     * REST endpoint to translate the async handshake into a 200/502 response.
     */
    public MiniserverToken connectAndWait( long timeoutSeconds )
    {
        try
        {
            return connect().get( timeoutSeconds, TimeUnit.SECONDS );
        }
        catch ( TimeoutException e )
        {
            disconnect( "handshake timeout" );
            throw new SessionException( "Handshake timed out after " + timeoutSeconds + "s", e );
        }
        catch ( java.util.concurrent.ExecutionException e )
        {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if ( cause instanceof SessionException se )
            {
                throw se;
            }
            throw new SessionException( "Handshake failed: " + cause.getMessage(), cause );
        }
        catch ( InterruptedException e )
        {
            Thread.currentThread().interrupt();
            throw new SessionException( "Handshake interrupted", e );
        }
    }

    /**
     * Callback fired by {@link ReconnectScheduler} when its backoff timer
     * elapses. Re-runs {@link #connect()} fire-and-forget; if the new
     * attempt also fails, our {@code onClose} will be called again and the
     * scheduler will roll over to the next backoff step. The cycle stops
     * when the policy becomes {@link LoxoneCloseCode.ReconnectPolicy#DO_NOT_RECONNECT}
     * or when an attempt reaches RUNNING (which calls
     * {@link ReconnectScheduler#notifySuccess()}).
     */
    void doReconnect()
    {
        LOG.info( "Reconnect attempt — calling connect()" );
        try
        {
            connect();
        }
        catch ( SessionException e )
        {
            LOG.warnf( "Reconnect attempt failed immediately: %s", e.getMessage() );
            // The orchestrator's connect() returns a future that fails
            // synchronously only if the prerequisites are missing (e.g.
            // public key unloaded). For those we don't reschedule — the
            // operator has to fix the config.
        }
    }

    public void disconnect( String reason )
    {
        // Operator-initiated disconnect: cancel any pending reconnect schedule
        // FIRST so the backoff doesn't fire a stray connect right after the
        // operator asked for the session to stay down. Also cancel the token
        // refresh — the session is going away.
        reconnectScheduler.cancel();
        tokenRefreshScheduler.cancel();
        webSocket.close( reason );
        tracker.transition( SessionState.CLOSED );
        if ( inFlight != null && !inFlight.isDone() )
        {
            inFlight.completeExceptionally( new SessionException( "Disconnected: " + reason ) );
        }
    }

    // ==========================================================================
    //  State machine — driven by WebSocket callbacks
    // ==========================================================================

    /**
     * Per-handshake-attempt listener. Captures the {@code inFlight} future
     * of the attempt that constructed it so that <i>stale</i> callbacks
     * (from a previously-closed WS that fires its {@code onClose} after the
     * orchestrator has already moved on to a new attempt) don't poison the
     * new attempt.
     *
     * <p>Observed in production: a clean
     * {@code disconnect → connect} sequence racing with the JDK WS reader
     * thread's delayed {@code onClose} → the old listener saw
     * {@code inFlight} pointing at the NEW future (not yet done) and called
     * {@code failHandshake("WS closed during handshake")} on it, killing
     * the fresh attempt with the previous attempt's close code.
     *
     * <p>The fix is structural: each listener references its OWN
     * {@code myInFlight} (captured at construction). A stale callback now
     * either:
     * <ul>
     *   <li>sees its {@code myInFlight} already done (succeeded or failed
     *       by a prior code path) — ignore the callback, or</li>
     *   <li>sees {@code myInFlight != inFlight} (orchestrator started a new
     *       attempt with a different future) — also ignore.</li>
     * </ul>
     */
    private class HandshakeListener implements MiniserverWebSocket.Listener
    {
        private final CompletableFuture< MiniserverToken > myInFlight;

        HandshakeListener( CompletableFuture< MiniserverToken > myInFlight )
        {
            this.myInFlight = myInFlight;
        }

        /**
         * True if this callback belongs to a PREVIOUS attempt (the orchestrator
         * has moved on to a new {@code inFlight} future) and should be ignored.
         *
         * <p>Note: we deliberately do NOT consider {@code myInFlight.isDone()}
         * as a staleness criterion. The handshake-failure path
         * ({@link #failHandshake}) completes the future exceptionally and
         * THEN closes the WS — the subsequent {@code onClose} arrives with
         * {@code myInFlight.isDone() == true} but is still THIS attempt's
         * legitimate close callback. It must run so the reconnect scheduler
         * can pick up the policy from the close code.
         *
         * <p>Earlier versions added {@code isDone()} to the check defensively,
         * which had the unintended effect of suppressing the reconnect schedule
         * after every failHandshake — manifested as "auto-connect failed and
         * the binding never retried".
         */
        private boolean isStale()
        {
            return myInFlight != inFlight;
        }

        @Override
        public void onOpen()
        {
            if ( isStale() )
            {
                LOG.debug( "onOpen on stale listener — ignored" );
                return;
            }
            try
            {
                // Step 2: send the keyexchange command (cleartext, just contains
                // the RSA-wrapped session key).
                String cmd = config.miniserver().cmd().keyExchange() + crypto.wrappedSessionKey();
                tracker.transition( SessionState.AWAITING_KEY_EXCHANGE_REPLY );
                LOG.debug( "→ keyexchange" );
                webSocket.sendText( cmd );
            }
            catch ( Exception e )
            {
                failHandshake( "keyexchange send failed: " + e.getMessage(), e );
            }
        }

        @Override
        public void onText( String message )
        {
            if ( isStale() )
            {
                LOG.debugf( "onText on stale listener — ignored: %s", message );
                return;
            }
            try
            {
                JsonNode root = jsonMapper.readTree( message );
                switch ( tracker.state() )
                {
                    case AWAITING_KEY_EXCHANGE_REPLY -> onKeyExchangeReply( root );
                    case AWAITING_TOKEN_REPLY -> onTokenReply( root );
                    case AWAITING_STRUCTURE_VERSION_REPLY -> onStructureVersionReply( root );
                    case AWAITING_STRUCTURE_FILE -> onStructureFile( root, message );
                    case AWAITING_STATUS_UPDATE_REPLY -> onStatusUpdateReply( root );
                    case AWAITING_TOKEN_REFRESH_REPLY -> onTokenRefreshReply( root );
                    case RUNNING ->
                        // Text frames in RUNNING state are responses to
                        // encrypted commands the binding sent earlier
                        // (MQTT-initiated commands). Forward
                        // them to subscribers via CDI; CommandResponsePublisher
                        // republishes on the configured command_response topic.
                            commandResponseEvent.fire( new MiniserverCommandResponseEvent( message ) );
                    default -> LOG.warnf( "Unexpected text frame in state %s — ignored: %s",
                                          tracker.state(), message );
                }
            }
            catch ( JsonProcessingException e )
            {
                failHandshake( "Could not parse miniserver text frame: " + e.getOriginalMessage(), e );
            }
            catch ( Exception e )
            {
                failHandshake( "Unexpected error processing miniserver frame: " + e.getMessage(), e );
            }
        }

        @Override
        public void onBinary( byte[] data )
        {
            if ( isStale() )
            {
                return;       // no log: binary frames can be high-volume once RUNNING.
            }
            // Binary frames carry state-update event-tables. They only flow
            // once the session is RUNNING — anything earlier is out-of-band
            // and dropped (the Miniserver shouldn't send any, but a stray
            // header during a slow handshake shouldn't crash us either).
            //
            // The decoder is stateful and processes header / payload as a
            // two-call sequence: a header frame (8 bytes starting with 0x03)
            // primes its currentHeader; the next frame is then interpreted
            // as the payload for that header. Calling decode() here from
            // the WS reader thread keeps that ordering trivially correct —
            // a single thread, one frame at a time. The decoder itself uses
            // Event.fireAsync() so subscribers don't block this thread.
            if ( tracker.state() == SessionState.RUNNING )
            {
                binaryDecoder.decode( ByteBuffer.wrap( data ) );
            }
            else
            {
                LOG.tracef( "Binary frame received in state %s — ignored (handshake not complete)",
                            tracker.state() );
            }
        }

        @Override
        public void onClose( int statusCode, String reason )
        {
            // Map the WS close code to its Loxone-specific meaning + reconnect policy.
            LoxoneCloseCode lcc = LoxoneCloseCode.from( statusCode );

            // Escalate to ERROR when the close is NOT operator-initiated.
            // Stale listener echoes and clean operator disconnects stay
            // INFO so error.log isn't polluted by routine shutdown /
            // reconnect noise.
            // CLOSED state = operator called disconnect() before us ;
            // STALE = a previous listener firing after a fresh
            // connection took over → both are routine, neither is
            // an alarm signal.
            boolean stale             = isStale();
            boolean operatorInitiated = tracker.state() == SessionState.CLOSED;
            String  staleSuffix       = stale ? " (stale listener — ignored)" : "";

            // Always log (even stale) — close is rare enough to warrant a line.
            if ( stale || operatorInitiated )
            {
                LOG.infof( "WS closed: code=%d reason=%s lcc=%s policy=%s%s",
                           ( Integer ) statusCode, reason, lcc, lcc.policy(),
                           staleSuffix );
            }
            else
            {
                LOG.errorf( "WS closed: code=%d reason=%s lcc=%s policy=%s%s",
                            ( Integer ) statusCode, reason, lcc, lcc.policy(),
                            staleSuffix );
            }
            if ( stale )
            {
                return;
            }

            if ( !myInFlight.isDone() )
            {
                // Handshake failure path: fail the in-flight future, transition
                // to FAILED. Reconnect policy is applied below.
                failHandshake( "WS closed during handshake (code=" + statusCode
                               + " reason=" + reason + " — " + lcc.message() + ")", null );
            }
            else if ( tracker.state() == SessionState.RUNNING )
            {
                // Established session torn down — operator-clean disconnect,
                // miniserver-driven (4xxx) or network glitch (RFC standard).
                tracker.transition( SessionState.DISCONNECTED );
            }

            // Cancel any pending token refresh — the session is gone, no
            // point keeping a 24h timer alive while we wait for reconnect.
            // The next successful handshake will reschedule from RUNNING.
            tokenRefreshScheduler.cancel();

            // Schedule reconnect IF this wasn't an operator-clean disconnect AND
            // the policy allows it. The scheduler itself logs the chosen delay.
            if ( !operatorInitiated )
            {
                reconnectScheduler.scheduleReconnect( SessionOrchestrator.this::doReconnect, lcc.policy() );
            }
        }

        @Override
        public void onError( Throwable error )
        {
            if ( isStale() )
            {
                LOG.debugf( "onError on stale listener — ignored: %s", error.getMessage() );
                return;
            }
            failHandshake( "WS error: " + error.getMessage(), error );
        }
    }

    // ---------- handshake step handlers ----------

    private void onKeyExchangeReply( JsonNode root )
    {
        if ( !checkOk( root ) )
        {
            failHandshake( "keyexchange rejected by miniserver: " + describe( root ), null );
            return;
        }
        LOG.debug( "← keyexchange OK" );

        // Step 3: fetch hash key + salt over HTTP. The URL path takes the
        // DECODED username — same as the getjwt step below. Passing the
        // Base64-encoded form is technically accepted by
        // the miniserver (it returns 200) but with a "neutral" salt that
        // makes every subsequent hash check fail at getjwt with a 401.
        KeyAndSalt keyAndSalt;
        try
        {
            String userForUrl = decodeBase64( config.miniserver().security().credentials().user() );
            String body       = http.fetchKeyAndSalt( userForUrl );
            keyAndSalt = parser.parseKeyAndSalt( body );
        }
        catch ( Exception e )
        {
            failHandshake( "getkey2 failed: " + e.getMessage(), e );
            return;
        }

        // Step 4: build the encrypted getjwt command.
        //
        // Both the user-hash AND the URL path use the DECODED user — the config
        // value is Base64-encoded for storage hygiene, but the miniserver
        // expects the plaintext username in the path. Sending the encoded form
        // is what triggered the 401 we observed in real-world testing
        // ("YWRtaW4=" instead of "admin").
        try
        {
            String user     = decodeBase64( config.miniserver().security().credentials().user() );
            String password = decodeBase64( config.miniserver().security().credentials().password() );
            String hash     = crypto.createUserHash( user, password, keyAndSalt );
            String plain = config.miniserver().cmd().requestToken()
                           + hash + "/"
                           + user + "/"
                           + config.miniserver().app().permission() + "/"
                           + config.miniserver().app().id() + "/"
                           + config.miniserver().app().info();
            String encrypted = crypto.encryptCommand( plain );
            tracker.transition( SessionState.AWAITING_TOKEN_REPLY );
            LOG.debug( "→ encrypted getjwt" );
            webSocket.sendText( encrypted );
        }
        catch ( Exception e )
        {
            failHandshake( "getjwt build/send failed: " + e.getMessage(), e );
        }
    }

    private void onTokenReply( JsonNode root )
    {
        if ( !checkOk( root ) )
        {
            failHandshake( "getjwt rejected by miniserver: " + describe( root ), null );
            return;
        }
        try
        {
            JsonNode   valueNode = root.path( "LL" ).path( "value" );
            TokenValue tv        = jsonMapper.treeToValue( valueNode, TokenValue.class );
            if ( tv.token() == null || tv.key() == null || tv.validUntil() == null )
            {
                failHandshake( "getjwt reply missing required field (token/key/validUntil)", null );
                return;
            }
            MiniserverToken token = new MiniserverToken(
                    tv.token(),
                    tv.key(),
                    tv.validUntil(),
                    tv.tokenRights() != null ? tv.tokenRights() : 0,
                    Boolean.TRUE.equals( tv.unsecurePass() ) );
            tracker.setToken( token );
            LOG.infof( "Token acquired (expires %s, rights=%d, unsecurePass=%s)",
                       token.expiresAt(), ( Integer ) token.tokenRights(),
                       ( Object ) token.unsecurePass() );

            // Probe the LoxAPP3 version BEFORE downloading the
            // (potentially MB-sized) structure file. Plain WS send — these
            // post-token commands are protocol bookkeeping, not sensitive.
            tracker.transition( SessionState.AWAITING_STRUCTURE_VERSION_REPLY );
            LOG.debug( "→ LoxAPPversion3 (probe cache)" );
            webSocket.sendText( config.miniserver().cmd().requestStructureFileVersion() );
        }
        catch ( Exception e )
        {
            failHandshake( "getjwt reply parse failed: " + e.getMessage(), e );
        }
    }

    // ==========================================================================
    //  Post-token handshake steps
    // ==========================================================================

    /**
     * Handles the {@code jdev/sps/LoxAPPversion3} reply: a JSON envelope
     * whose {@code LL.value} is the {@code lastModified} timestamp string.
     * Compares against the on-disk cache and either reuses the cached
     * structure file (cache hit) or kicks off a full
     * {@code data/LoxAPP3.json} download (cache miss).
     *
     * <p>Reference: V17.0 §Structure-File: LoxAPP3.json — Download and
     * caching, p.22-23.
     */
    private void onStructureVersionReply( JsonNode root )
    {
        if ( !checkOk( root ) )
        {
            // Per spec, a non-200 here is unusual but recoverable — fall
            // through to a full structure-file download.
            LOG.warnf( "LoxAPPversion3 non-200 reply (%s) — falling back to full download",
                       describe( root ) );
            requestFullStructureFile();
            return;
        }

        String remoteLastModified = root.path( "LL" ).path( "value" ).asText( "" );
        if ( remoteLastModified.isEmpty() )
        {
            LOG.warn( "LoxAPPversion3 reply has no LL.value — falling back to full structure-file download" );
            requestFullStructureFile();
            return;
        }

        if ( loxApp3Cache.isHit( remoteLastModified ) )
        {
            Optional< String > cached = loxApp3Cache.load();
            if ( cached.isPresent() )
            {
                LOG.infof( "LoxAPP3 cache HIT (lastModified=%s, %d chars) — skipping download",
                           remoteLastModified, ( Integer ) cached.get().length() );
                pendingLoxApp3 = cached.get();
                tracker.transition( SessionState.AWAITING_STATUS_UPDATE_REPLY );
                LOG.debug( "→ enablebinstatusupdate (cache-hit path)" );
                webSocket.sendText( config.miniserver().cmd().requestStatusUpdate() );
                return;
            }
            // Cache reported hit but file disappeared between calls — recoverable.
            LOG.warn( "LoxApp3 cache reported hit but on-disk read failed; falling back to full download" );
        }
        else
        {
            LOG.infof( "LoxAPP3 cache MISS (remote lastModified=%s) — downloading", remoteLastModified );
        }
        requestFullStructureFile();
    }

    private void requestFullStructureFile()
    {
        tracker.transition( SessionState.AWAITING_STRUCTURE_FILE );
        LOG.debug( "→ data/LoxAPP3.json" );
        webSocket.sendText( config.miniserver().cmd().requestStructureFile() );
    }

    /**
     * Handles the {@code data/LoxAPP3.json} reply: the full structure file
     * as a JSON object with a top-level {@code lastModified} field. Stores
     * it in cache (if {@code lastModified} present) and triggers the
     * status-update subscription.
     */
    private void onStructureFile( JsonNode root, String rawMessage )
    {
        // The structure file is the FULL JSON payload — we don't unpack it
        // here — downstream consumers read it via the LoxApp3 event. Store the raw
        // text so the cache keeps byte-for-byte fidelity.
        pendingLoxApp3 = rawMessage;

        String lastModified = root.path( "lastModified" ).asText( "" );
        if ( lastModified.isEmpty() )
        {
            LOG.warn( "Structure file has no top-level 'lastModified' field — not caching (next reconnect will re-download)" );
        }
        else
        {
            loxApp3Cache.store( rawMessage, lastModified );
        }

        tracker.transition( SessionState.AWAITING_STATUS_UPDATE_REPLY );
        LOG.debug( "→ enablebinstatusupdate (download path)" );
        webSocket.sendText( config.miniserver().cmd().requestStatusUpdate() );
    }

    /**
     * Handles the {@code jdev/sps/enablebinstatusupdate} reply — the final
     * handshake ack. Transitions to {@link SessionState#RUNNING} and
     * completes the in-flight handshake future.
     *
     * <p>After this point the miniserver pushes binary state-update frames;
     * the binary-states decoder consumes them and emits the decoded state
     * events.
     */
    private void onStatusUpdateReply( JsonNode root )
    {
        if ( !checkOk( root ) )
        {
            failHandshake( "enablebinstatusupdate rejected: " + describe( root ), null );
            return;
        }
        tracker.transition( SessionState.RUNNING );
        LOG.infof( "Miniserver session ESTABLISHED (LoxAPP3 size=%d chars)",
                   ( Integer ) ( pendingLoxApp3 == null ? 0 : pendingLoxApp3.length() ) );
        // Drop the in-memory copy — downstream publishers reload the
        // structure file from the on-disk cache when needed. Holding onto
        // an MB-sized string for the lifetime of the session is wasteful.
        pendingLoxApp3 = null;

        // Signal RUNNING to downstream publishers (AppInfo +
        // LoxApp3 retained publishes). Async fire — the publishers may
        // take ~hundreds of ms to serialise + send a ~258 KB LoxAPP3
        // payload, we don't block the WS reader thread on that.
        // Fired ONLY here (initial RUNNING) — the two other
        // transitions to RUNNING in this class (token refresh return
        // paths) keep the session up but aren't a fresh "connect" and
        // mustn't trigger republishes.
        miniserverConnectedEvent.fireAsync(
                new MiniserverConnectedEvent( java.time.Instant.now(),
                                              miniserverState.identity() ) );

        // Reset the reconnect backoff — next disconnect starts fresh from
        // initialDelay rather than the cumulative ladder from whatever
        // failures preceded this success.
        reconnectScheduler.notifySuccess();

        // Schedule the first JWT refresh. The token validUntil
        // is ~3 months out; we refresh every `period` (default 24h) to keep
        // a wide safety margin. If the refresh fails the binding stays
        // RUNNING with the old token; reconnect at expiry would still
        // recover the session.
        tracker.token().ifPresent( token -> tokenRefreshScheduler.scheduleNext( this::refreshToken ) );

        // Complete the handshake future with the (already-stored) token.
        tracker.token().ifPresent( token -> inFlight.complete( token ) );
    }

    // ==========================================================================
    //  Token refresh + kill
    // ==========================================================================

    /**
     * Periodic JWT refresh, fired by {@link TokenRefreshScheduler}. Sends
     * {@code jdev/sys/refreshjwt/{hash}/{user}} (encrypted) over the WS;
     * reply lands in {@link #onTokenRefreshReply}.
     *
     * <p>Per V17.0 §"Refresh JWT token": {@code hash} is
     * {@code HMAC(refreshHashKey, currentTokenString)}. The
     * {@code refreshHashKey} is fetched fresh over HTTP from
     * {@code jdev/sys/getkey/{user}} (different endpoint from the {@code getkey2}
     * used at handshake time — simpler response shape).
     *
     * <p>If anything fails before the WS send (network blip on getkey,
     * crypto error, etc.) we stay RUNNING with the existing token and let
     * the scheduler retry at the next period. The session is not impacted.
     *
     * <p>Public so the operator can trigger a manual refresh from the
     * dashboard / REST endpoint (POST /api/v1/token/refresh). The
     * guards above (state == RUNNING + token present) make a
     * UI-triggered call safe ; if either guard fails the method
     * silently returns. The same legitimate
     * {@link TokenRefreshScheduler}-triggered call also goes through
     * this entry point — no duplication of business logic.
     */
    public void refreshToken()
    {
        if ( tracker.state() != SessionState.RUNNING )
        {
            LOG.debugf( "Skip refresh: state is %s (not RUNNING)", tracker.state() );
            return;
        }
        MiniserverToken token = tracker.token().orElse( null );
        if ( token == null )
        {
            LOG.warn( "Skip refresh: no token in tracker" );
            return;
        }

        try
        {
            String userDecoded = decodeBase64( config.miniserver().security().credentials().user() );

            // 1. Fetch the HMAC hash key over HTTP.
            String body    = http.fetchHashKey( userDecoded );
            String hashKey = parser.parseHashKey( body );

            // 2. Compute token hash.
            String tokenHash = crypto.hashToken( hashKey, token.token() );

            // 3. Build + encrypt the refresh command.
            String plain     = config.miniserver().cmd().refreshToken() + "/" + tokenHash + "/" + userDecoded;
            String encrypted = crypto.encryptCommand( plain );

            // 4. Send over WS.
            tracker.transition( SessionState.AWAITING_TOKEN_REFRESH_REPLY );
            LOG.debug( "→ encrypted refreshjwt" );
            webSocket.sendText( encrypted );
        }
        catch ( Exception e )
        {
            LOG.warnf( "Token refresh failed (will retry at next period): %s", e.getMessage() );
            // Make sure we don't get stuck in AWAITING_TOKEN_REFRESH_REPLY if
            // the failure happened after transitioning. Most failures are
            // before the send (getkey, encrypt) — defensive transition back.
            if ( tracker.state() == SessionState.AWAITING_TOKEN_REFRESH_REPLY )
            {
                tracker.transition( SessionState.RUNNING );
            }
            // Reschedule next refresh anyway — the scheduler doesn't auto-
            // re-arm without our notifySuccess equivalent.
            tokenRefreshScheduler.scheduleNext( this::refreshToken );
        }
    }

    /**
     * Handle the {@code jdev/sys/refreshjwt} reply. On success: update
     * the token in the tracker (new validUntil, possibly new token string +
     * key) and transition back to RUNNING. On failure: stay RUNNING with
     * the old token — the session continues, the next reconnect at expiry
     * will recover with a fresh getjwt.
     */
    private void onTokenRefreshReply( JsonNode root )
    {
        boolean ok = checkOk( root );
        try
        {
            if ( ok )
            {
                JsonNode   valueNode = root.path( "LL" ).path( "value" );
                TokenValue tv        = jsonMapper.treeToValue( valueNode, TokenValue.class );
                if ( tv != null && tv.validUntil() != null )
                {
                    MiniserverToken oldToken = tracker.token().orElse( null );
                    MiniserverToken newToken = new MiniserverToken(
                            tv.token() != null ? tv.token() : ( oldToken != null ? oldToken.token() : null ),
                            tv.key() != null ? tv.key() : ( oldToken != null ? oldToken.key() : null ),
                            tv.validUntil(),
                            tv.tokenRights() != null ? tv.tokenRights() : ( oldToken != null ? oldToken.tokenRights() : 0 ),
                            Boolean.TRUE.equals( tv.unsecurePass() ) );
                    tracker.setToken( newToken );
                    LOG.infof( "Token refreshed — new expiry %s (rights=%d)",
                               newToken.expiresAt(), ( Integer ) newToken.tokenRights() );
                }
                else
                {
                    LOG.warn( "refreshjwt reply lacked validUntil — token state unchanged" );
                }
            }
            else
            {
                LOG.warnf( "refreshjwt rejected by miniserver: %s — keeping the existing token", describe( root ) );
            }
        }
        catch ( Exception e )
        {
            LOG.warnf( "refreshjwt reply parse failed: %s — keeping the existing token", e.getMessage() );
        }
        finally
        {
            // Always go back to RUNNING regardless of refresh outcome.
            tracker.transition( SessionState.RUNNING );
            // Schedule the next periodic refresh.
            tokenRefreshScheduler.scheduleNext( this::refreshToken );
        }
    }

    /**
     * Operator-triggered token kill — sends
     * {@code jdev/sys/killtoken/{hash}/{user}} (encrypted) to invalidate
     * the token on the miniserver side, then closes the WS locally.
     * <p>
     * Same hashing strategy as {@link #refreshToken()}: HMAC of the current
     * token under the {@code getkey} hash key. If we can't send the kill
     * (no token, not RUNNING, network failure), we still close the local
     * WS — the operator's intent is to drop the session immediately.
     */
    public void killToken()
    {
        MiniserverToken token = tracker.token().orElse( null );
        if ( token == null )
        {
            LOG.info( "killToken: no token to kill — just disconnecting" );
            disconnect( "no token to kill" );
            return;
        }
        if ( tracker.state() != SessionState.RUNNING )
        {
            LOG.warnf( "killToken: state is %s (not RUNNING) — closing local without sending killtoken", tracker.state() );
            disconnect( "kill token while not RUNNING" );
            return;
        }

        try
        {
            String userDecoded = decodeBase64( config.miniserver().security().credentials().user() );
            String body        = http.fetchHashKey( userDecoded );
            String hashKey     = parser.parseHashKey( body );
            String tokenHash   = crypto.hashToken( hashKey, token.token() );
            // cmd.killToken() in config has a trailing slash already
            // (jdev/sys/killtoken/) — just append the hash then user.
            String plain     = config.miniserver().cmd().killToken() + tokenHash + "/" + userDecoded;
            String encrypted = crypto.encryptCommand( plain );
            LOG.info( "→ encrypted killtoken (miniserver-side invalidation)" );
            webSocket.sendText( encrypted );
            // Give the miniserver ~500ms to process and ack. Fire-and-forget
            // — we don't parse the reply, we just want to be sure the frame
            // made it onto the wire before we close the socket.
            try { Thread.sleep( 500 ); }
            catch ( InterruptedException ie ) { Thread.currentThread().interrupt(); }
        }
        catch ( Exception e )
        {
            LOG.warnf( "killToken send failed: %s — closing local anyway", e.getMessage() );
        }
        // Always close local. Operator's intent is to drop.
        disconnect( "token killed (miniserver-side + local)" );
    }

    // ==========================================================================
    //  Helpers
    // ==========================================================================

    /** {@code LL.Code == "200"} (case-insensitive — Loxone alternates casing). */
    private boolean checkOk( JsonNode root )
    {
        JsonNode ll = root.path( "LL" );
        if ( ll.isMissingNode() || ll.isNull() )
        {
            return false;
        }
        String code = ll.path( "Code" ).asText( ll.path( "code" ).asText( "" ) );
        return "200".equals( code );
    }

    private String describe( JsonNode root )
    {
        JsonNode ll   = root.path( "LL" );
        String   code = ll.path( "Code" ).asText( ll.path( "code" ).asText( "?" ) );
        return "Code=" + code + " body=" + root.toString();
    }

    private static String decodeBase64( String value )
    {
        if ( value == null || value.isBlank() )
        {
            return "";
        }
        return new String( Base64.getDecoder().decode( value ), java.nio.charset.StandardCharsets.UTF_8 );
    }

    private CompletableFuture< MiniserverToken > failNow( String reason )
    {
        SessionState before = tracker.state();
        tracker.fail( reason );
        SessionException ex = new SessionException( reason );
        inFlight.completeExceptionally( ex );

        // Re-arm the reconnect scheduler when the failure happened
        // MID-CONNECTION (state was CONNECTING). The classic
        // trigger is `webSocket.connect()` throwing SessionException
        // synchronously because the miniserver is unreachable — typically
        // during the firmware-reboot window right after an OUT_OF_SERVICE
        // event. Without this re-arm, the JdkAdapter's onClose never
        // fires (the WS was never opened in the first place) and the
        // existing onClose-path reschedule (HandshakeListener#onClose →
        // reconnectScheduler.scheduleReconnect) is bypassed — so attempt
        // #1 happens, fails, and the binding goes silent forever waiting
        // for an event that will never come. Operator was then forced to
        // click "Connect" in the web dashboard manually.
        //
        // Why guard on `before == CONNECTING`:
        //   - failNow is also called for pre-connection prereqs that
        //     need operator intervention (e.g. "Public key not loaded —
        //     run bootstrap before connecting" — line 159). For those
        //     the state is still DISCONNECTED when failNow runs;
        //     auto-rescheduling would just spam the same failure every
        //     few seconds without the operator being able to fix it.
        //   - When `webSocket.connect()` is reached, we've already
        //     transitioned to CONNECTING (line 166). That's the unique
        //     signature of "we tried, network refused" — exactly the
        //     case worth retrying with backoff.
        //
        // Why NORMAL policy: the WS was never opened so no LoxoneCloseCode
        // was received. NORMAL is the default exponential-backoff policy,
        // matching what an unstable network would get from a 1005/1006
        // RFC close code.
        if ( before == SessionState.CONNECTING
             && config.miniserver().reconnect().enable() )
        {
            reconnectScheduler.scheduleReconnect(
                    this::doReconnect,
                    LoxoneCloseCode.ReconnectPolicy.NORMAL );
        }
        return inFlight;
    }

    private void failHandshake( String reason, Throwable cause )
    {
        LOG.warnf( "Handshake FAILED: %s", reason );
        tracker.fail( reason );
        if ( inFlight != null && !inFlight.isDone() )
        {
            SessionException ex = cause != null
                                  ? new SessionException( reason, cause )
                                  : new SessionException( reason );
            inFlight.completeExceptionally( ex );
        }
        webSocket.close( "handshake failed" );
    }

    // =====================================================================
    //  Outbound commands from MQTT subscribers
    //  ─────────────────────────────────────────────────────────────────────
    //  CommandSubscriber parses inbound MQTT payloads and fires CDI events.
    //  We observe them here, build the plain Loxone command, encrypt it with
    //  the active session key, and forward to the WebSocket. Anything received
    //  outside of the
    //  RUNNING state is dropped — there's no point queuing commands that
    //  reference a stale session.
    // =====================================================================

    /**
     * Forward an {@link MiniserverCommand} (UUID + Loxone command string)
     * to the Miniserver as an encrypted WS text frame. Command path:
     * <pre>
     *   {cmd.prefix.root}{uuid}/{command}
     *   e.g. "jdev/sps/io/1072755d-024f-4540-…/AI1/on"
     * </pre>
     */
    public void onMiniserverCommand( @Observes MiniserverCommandEvent event )
    {
        MiniserverCommand cmd = event.command();
        if ( cmd == null || cmd.uuid() == null || cmd.command() == null )
        {
            LOG.warn( "Received null/incomplete MiniserverCommand — dropped" );
            return;
        }
        if ( tracker.state() != SessionState.RUNNING )
        {
            LOG.warnf( "Dropping command %s/%s — session is %s (not RUNNING)",
                       cmd.uuid(), cmd.command(), tracker.state() );
            return;
        }
        String plain = config.miniserver().cmd().prefix().root() + cmd.uuid() + "/" + cmd.command();
        sendEncryptedCommand( "command", plain );
    }

    /**
     * Forward an {@link MiniserverApiConnectorSetCommand} as an encrypted
     * {@code SET(…)} frame. Built path:
     * <pre>
     *   {cmd.prefix.root}{vti}/SET({functionBlock};{input};{value})
     *   e.g. "jdev/sps/io/VTI-LumiereBureau/SET(Lico;Lc1;Pulse)"
     * </pre>
     */
    public void onMiniserverApiSet( @Observes MiniserverApiConnectorSetCommandEvent event )
    {
        MiniserverApiConnectorSetCommand c = event.apiConnector();
        if ( c == null || c.virtualInputText() == null )
        {
            LOG.warn( "Received null/incomplete MiniserverApiConnectorSetCommand — dropped" );
            return;
        }
        if ( tracker.state() != SessionState.RUNNING )
        {
            LOG.warnf( "Dropping API SET %s — session is %s (not RUNNING)",
                       c.virtualInputText(), tracker.state() );
            return;
        }
        String plain = config.miniserver().cmd().prefix().root()
                       + c.virtualInputText()
                       + "/SET(" + c.functionBlock() + ";" + c.input() + ";" + c.value() + ")";
        sendEncryptedCommand( "api-set", plain );
    }

    /** Common encrypt + send. Logs the plain command at DEBUG (operator
     *  audit) but only the ciphertext-length at INFO (no secret leakage
     *  in production logs). */
    private void sendEncryptedCommand( String label, String plain )
    {
        try
        {
            String encrypted = crypto.encryptCommand( plain );
            if ( encrypted == null )
            {
                LOG.warnf( "Could not encrypt %s command (encryptCommand returned null) — dropped: %s",
                           label, plain );
                return;
            }
            LOG.debugf( "→ encrypted %s: %s", label, plain );
            webSocket.sendText( encrypted );
        }
        catch ( RuntimeException e )
        {
            LOG.warnf( e, "Could not send %s command: %s", label, plain );
        }
    }

    /**
     * Public hook for the admin-command pipeline. Encrypts and sends
     * an arbitrary {@code jdev/sps/...} command via the active WS
     * session. Caller is responsible for state validation; this method
     * does NOT check {@link SessionState#RUNNING} (the
     * {@code MiniserverAdminCommandClient} does that upstream before
     * registering its pending future).
     *
     * <p>Wraps the private {@link #sendEncryptedCommand} with the
     * {@code "admin"} label for log-trace clarity.
     */
    public void sendEncryptedAdminCommand( String plainCommand )
    {
        sendEncryptedCommand( "admin", plainCommand );
    }
}
