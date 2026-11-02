/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.management;

import com.fasterxml.jackson.databind.JsonNode;
import com.quaddan.iot.loxmq.miniserver.session.*;
import com.quaddan.iot.loxmq.config.LoxoneConfig;
import com.quaddan.iot.loxmq.miniserver.admin.AdminCommandException;
import com.quaddan.iot.loxmq.miniserver.admin.AdminCommandResponses;
import com.quaddan.iot.loxmq.miniserver.admin.MiniserverAdminCommandClient;
import com.quaddan.iot.loxmq.miniserver.bootstrap.BootstrapException;
import com.quaddan.iot.loxmq.miniserver.bootstrap.BootstrapOrchestrator;
import com.quaddan.iot.loxmq.miniserver.bootstrap.BootstrapTracker;
import com.quaddan.iot.loxmq.miniserver.connection.ConnectionModeResolver;
import com.quaddan.iot.loxmq.miniserver.connection.EndpointResolver;
import com.quaddan.iot.loxmq.miniserver.identity.MiniserverIdentity;
import com.quaddan.iot.loxmq.miniserver.session.TokenRefreshScheduler;
import com.quaddan.iot.loxmq.miniserver.state.FirmwareUpdateService;
import com.quaddan.iot.loxmq.miniserver.state.MiniserverState;
import com.quaddan.iot.loxmq.miniserver.state.SdCardHealthService;
import com.quaddan.iot.loxmq.transport.MqttClient;
import com.quaddan.iot.loxmq.transport.TransportException;
import com.quaddan.iot.loxmq.transport.connection.TransportConnectionResolver;
import com.quaddan.iot.loxmq.transport.subscribe.InboundCommandGate;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Operational REST API for the binding.
 * <p>
 * Mounted under {@code /api/v1/}. Endpoints return JSON. The Qute-templated
 * HTML dashboard (rendered by a separate resource) consumes a subset of these
 * for the operator UI.
 *
 * <h3>Endpoints</h3>
 * Full surface is documented via the OpenAPI annotations on each method —
 * see {@code /q/openapi} on a running binding for the canonical reference.
 * The main groups, all live and wired:
 * <ul>
 *   <li>State / introspection : {@code GET /api/v1/state}</li>
 *   <li>Session lifecycle     : {@code POST /api/v1/connect}, {@code /reconnect},
 *       {@code /disconnect}, {@code /token/kill}, {@code /bootstrap}</li>
 *   <li>Transport lifecycle   : {@code POST /api/v1/transport/connect|disconnect}</li>
 * </ul>
 *
 * <h3>Security note</h3>
 * No authentication on these endpoints. The binding is expected to run on a
 * LAN/VPN-only host with the HTTPS endpoint exposed through a reverse proxy
 * that handles auth (basic-auth, OIDC, mTLS). If the binding is ever exposed
 * directly to the internet, plug in {@code quarkus-smallrye-jwt} or
 * {@code quarkus-oidc} before going live. The inbound MQTT topics
 * ({@code …/command}, {@code …/api}) already drop retained messages
 * and cap payload size at 4 KB — the same hardening
 * posture is expected for any HTTP auth layer added later.
 */
@Path( "/api/v1" )
@Produces( MediaType.APPLICATION_JSON )
@Tag( name = "Management", description = "Operational endpoints to inspect and steer the binding." )
public class ManagementResource
{
    private static final Logger LOG = Logger.getLogger( ManagementResource.class );

    @Inject
    LoxoneConfig config;

    @Inject
    MiniserverState miniserverState;

    @Inject
    ConnectionModeResolver modeResolver;

    @Inject
    EndpointResolver endpointResolver;

    @Inject
    BootstrapOrchestrator bootstrap;

    @Inject
    BootstrapTracker bootstrapTracker;

    @Inject
    SessionOrchestrator session;

    @Inject
    SessionTracker sessionTracker;

    @Inject
    InboundCommandGate inboundGate;

    @Inject
    SdCardHealthService sdCardHealth;

    @Inject
    FirmwareUpdateService firmwareUpdateService;

    @Inject
    TransportConnectionResolver transportResolver;

    @Inject
    LoxApp3Cache loxApp3Cache;

    @Inject
    ReconnectScheduler reconnectScheduler;

    @Inject
    TokenRefreshScheduler tokenRefreshScheduler;

    @Inject
    MqttClient mqtt;

    @Inject
    KeepAliveScheduler keepAliveScheduler;

    @Inject
    MiniserverAdminCommandClient adminClient;

    @Inject
    SystemCommandRegistry systemCommands;

    /** loxmq's own artifact version, injected by Quarkus from the Maven build.
     *  Surfaced at the root of {@code GET /api/v1/state} so an operator can
     *  confirm which binding release produced a given snapshot. The
     *  {@code defaultValue} is defensive — same pattern as
     *  {@code AppInfoPublisher#bindingVersion}. */
    @ConfigProperty( name = "quarkus.application.version", defaultValue = "unknown" )
    String bindingVersion;

    /**
     * {@code GET /api/v1/state} — aggregated runtime snapshot of the binding.
     * <p>
     * Read-only. Assembles a JSON object with {@code timestamp}, {@code miniserver}
     * (host/port/secure preference + resolved mode + endpoints + downgrade reason +
     * app uuid/info + parsed identity + {@code sessionEstablished}), {@code bootstrap}
     * (status/timings/lastError), {@code session} (state + token + loxApp3 cache +
     * reconnect + tokenRefresh blocks), {@code broker} (config + effective scheme/uri +
     * live {@code connected}), {@code health} (keepalive RTT, last handshake duration)
     * and loxmq's own {@code version}. No side effects. Always HTTP 200.
     */
    @GET
    @Path( "/state" )
    @Operation( summary = "Snapshot of the binding state",
                description = "Returns the active configuration and runtime status: " +
                              "the connection-mode resolution (preferred vs " +
                              "effective + downgrade reason), the parsed Miniserver " +
                              "identity, the handshake step, last " +
                              "broker publish, and reconnect counters." )
    public Map< String, Object > state()
    {
        // LinkedHashMap to keep field order stable in JSON output (helps log/diff readability).
        Map< String, Object > miniserver = new LinkedHashMap<>();
        miniserver.put( "host", config.miniserver().connection().host() );
        miniserver.put( "port", config.miniserver().connection().port() );
        miniserver.put( "secure", config.miniserver().connection().secure() );    // operator preference
        miniserver.put( "effectiveMode", modeResolver.effective().name() );              // PLAIN | SECURE
        miniserver.put( "effectiveEndpoint", endpointResolver.httpEndpoint().toString() );
        miniserver.put( "wsEndpoint", endpointResolver.wsEndpoint().toString() );
        miniserver.put( "downgradeReason", modeResolver.downgradeReason().orElse( null ) );
        miniserver.put( "uuid", config.miniserver().app().id() );
        miniserver.put( "info", config.miniserver().app().info() );
        miniserver.put( "identity", identityPayload() );
        // sessionEstablished mirrors the live session state — RUNNING means the
        // miniserver handshake completed and binary state-update
        // frames are flowing into the decoder.
        miniserver.put( "sessionEstablished", sessionTracker.state() == SessionState.RUNNING );

        Map< String, Object > broker = new LinkedHashMap<>();
        broker.put( "protocol", config.transport().connection().protocol() );    // raw config value
        broker.put( "secure", config.transport().connection().secure() );      // operator preference
        broker.put( "effectiveProtocol", transportResolver.effectiveProtocol() );         // resolved (tcp/ssl/ws/wss)
        broker.put( "effectiveUri", transportResolver.effectiveUri().toString() );
        broker.put( "host", config.transport().connection().host() );
        broker.put( "port", config.transport().connection().port() );
        broker.put( "path", config.transport().connection().path().orElse( "" ) );
        broker.put( "mode", config.transport().mode() );
        // Live broker state — true between transport/connect and the next
        // disconnect (clean or broker-initiated).
        broker.put( "connected", mqtt.isConnected() );

        Map< String, Object > bootstrapBlock = new LinkedHashMap<>();
        bootstrapBlock.put( "status", bootstrapTracker.status().name() );
        bootstrapBlock.put( "startedAt", bootstrapTracker.startedAt().map( Object::toString ).orElse( null ) );
        bootstrapBlock.put( "completedAt", bootstrapTracker.completedAt().map( Object::toString ).orElse( null ) );
        bootstrapBlock.put( "durationMs", bootstrapTracker.lastDuration().map( d -> ( Object ) d.toMillis() ).orElse( null ) );
        bootstrapBlock.put( "lastError", bootstrapTracker.lastError().orElse( null ) );

        Map< String, Object > sessionBlock = new LinkedHashMap<>();
        sessionBlock.put( "state", sessionTracker.state().name() );
        sessionBlock.put( "stateChangedAt", sessionTracker.stateChangedAt().toString() );
        sessionBlock.put( "connectedAt", sessionTracker.connectedAt().map( Object::toString ).orElse( null ) );
        sessionBlock.put( "lastError", sessionTracker.lastError().orElse( null ) );
        sessionTracker.token().ifPresent( t ->
                                          {
                                              Map< String, Object > tk = new LinkedHashMap<>();
                                              tk.put( "expiresAt", t.expiresAt().toString() );
                                              tk.put( "tokenRights", t.tokenRights() );
                                              tk.put( "unsecurePass", t.unsecurePass() );
                                              tk.put( "expired", t.expired() );
                                              sessionBlock.put( "token", tk );
                                          } );

        Map< String, Object > loxApp3Block = new LinkedHashMap<>();
        loxApp3Block.put( "cacheDirectory", loxApp3Cache.directoryPath() );
        loxApp3Block.put( "cached", loxApp3Cache.load().isPresent() );
        sessionBlock.put( "loxApp3", loxApp3Block );

        Map< String, Object > reconnectBlock = new LinkedHashMap<>();
        reconnectBlock.put( "enabled", config.miniserver().reconnect().enable() );
        reconnectBlock.put( "attemptCount", reconnectScheduler.attemptCount() );
        reconnectBlock.put( "pending", reconnectScheduler.isReconnectPending() );
        sessionBlock.put( "reconnect", reconnectBlock );

        Map< String, Object > tokenRefreshBlock = new LinkedHashMap<>();
        tokenRefreshBlock.put( "periodSec", config.miniserver().security().token().refresh().period().toSeconds() );
        tokenRefreshBlock.put( "pending", tokenRefreshScheduler.isRefreshPending() );
        sessionBlock.put( "tokenRefresh", tokenRefreshBlock );

        // Health block — observability surface mirroring the Micrometer
        // meters at /q/metrics so operators get the same view from the
        // dashboard / REST without scraping Prometheus.
        Map< String, Object > healthBlock = new LinkedHashMap<>();
        healthBlock.put( "keepaliveScheduled", keepAliveScheduler.isScheduled() );
        healthBlock.put( "lastKeepaliveRttMs",
                         keepAliveScheduler.lastRtt().map( d -> ( Object ) d.toMillis() ).orElse( null ) );
        healthBlock.put( "lastKeepaliveResponseAt",
                         keepAliveScheduler.lastResponseAt().map( Object::toString ).orElse( null ) );
        healthBlock.put( "lastHandshakeDurationMs",
                         sessionTracker.lastHandshakeDuration().map( d -> ( Object ) d.toMillis() ).orElse( null ) );

        Map< String, Object > root = new LinkedHashMap<>();
        root.put( "timestamp", Instant.now().toString() );
        root.put( "miniserver", miniserver );
        root.put( "bootstrap", bootstrapBlock );
        root.put( "session", sessionBlock );
        root.put( "broker", broker );
        root.put( "health", healthBlock );
        // loxmq's own build version — lets an operator confirm which binding
        // release produced this snapshot.
        root.put( "version", bindingVersion );
        return root;
    }

    /**
     * {@code POST /api/v1/bootstrap} — run the Miniserver HTTP bootstrap synchronously.
     * <p>
     * Calls {@code bootstrap.run()} (hits {@code jdev/cfg/apiKey} + {@code jdev/sys/getPublicKey},
     * populates MiniserverState and loads the RSA public key). Idempotent — re-runs the full
     * sequence each time. On success: HTTP 200 with the parsed identity (serial, version,
     * generation, httpsStatus) + {@code effectiveMode} + {@code durationMs}. On
     * {@link BootstrapException}: HTTP 502 ({@code BAD_GATEWAY}) with the error message (logged WARN).
     */
    @POST
    @Path( "/bootstrap" )
    @Operation( summary = "Run the Miniserver HTTP bootstrap",
                description = "Synchronously hits jdev/cfg/apiKey + jdev/sys/getPublicKey, " +
                              "populates MiniserverState with the parsed identity (so the " +
                              "ConnectionModeResolver can re-evaluate secure vs plain), and " +
                              "loads the RSA public key into the crypto service so the " +
                              "keyexchange step can run. Returns 200 with the parsed identity on " +
                              "success, 502 with the underlying error on failure. Idempotent " +
                              "— re-runs the full sequence each time." )
    public Response runBootstrap()
    {
        try
        {
            MiniserverIdentity    identity = bootstrap.run();
            Map< String, Object > body     = new LinkedHashMap<>();
            body.put( "status", "success" );
            body.put( "durationMs", bootstrapTracker.lastDuration().map( d -> ( Object ) d.toMillis() ).orElse( -1L ) );
            body.put( "serial", identity.serial() );
            body.put( "version", identity.version().toString() );
            body.put( "generation", identity.generation().name() );
            body.put( "httpsStatus", identity.httpsStatus().name() );
            body.put( "effectiveMode", modeResolver.effective().name() );
            return Response.ok( body ).build();
        }
        catch ( BootstrapException e )
        {
            LOG.warnf( "POST /api/v1/bootstrap → 502: %s", e.getMessage() );
            return Response.status( Response.Status.BAD_GATEWAY )
                           .entity( Map.of(
                                   "status", "failed",
                                   "error", "bootstrap_failed",
                                   "message", e.getMessage() ) )
                           .build();
        }
    }

    private Map< String, Object > identityPayload()
    {
        return miniserverState.identity()
                              .< Map< String, Object > >map( id ->
                                                             {
                                                                 Map< String, Object > m = new HashMap<>();
                                                                 m.put( "serial", id.serial() );
                                                                 m.put( "version", id.version().toString() );
                                                                 m.put( "generation", id.generation().name() );
                                                                 m.put( "httpsStatus", id.httpsStatus().name() );
                                                                 m.put( "address", id.address() );
                                                                 m.put( "local", id.local() );
                                                                 m.put( "isInTrust", id.isInTrust() );
                                                                 return m;
                                                             } )
                              .orElse( null );
    }

    /**
     * {@code POST /api/v1/connect} — open the WebSocket session and run the handshake.
     * <p>
     * Calls {@code session.connectAndWait(timeout)} (ws/wss connect + keyexchange + getkey2 +
     * getjwt). Requires a prior bootstrap (loaded public key). On success: HTTP 200 with
     * {@code state} + acquired token metadata (expiresAt, tokenRights, unsecurePass). On
     * {@link SessionException}: HTTP 502 ({@code BAD_GATEWAY}) with the error + current state
     * (logged WARN). Opens a live WS session to the Miniserver.
     */
    @POST
    @Path( "/connect" )
    @Operation( summary = "Open the WebSocket session and run the handshake",
                description = "Connects to the Miniserver over ws/wss (per the resolved " +
                              "ConnectionMode), sends the keyexchange command, then performs " +
                              "the getkey2 (HTTP) + getjwt (encrypted WS) sequence. Returns 200 " +
                              "with the acquired JWT metadata on success, 502 on handshake " +
                              "failure, 504 on timeout. Requires bootstrap (POST /api/v1/bootstrap) " +
                              "to have run first — the orchestrator refuses to connect without a " +
                              "loaded public key." )
    public Response connect()
    {
        long timeoutSec = config.miniserver().connection().http().requestTimeout().toSeconds() + 10;
        try
        {
            MiniserverToken       token = session.connectAndWait( timeoutSec );
            Map< String, Object > body  = new LinkedHashMap<>();
            body.put( "status", "success" );
            body.put( "state", sessionTracker.state().name() );
            body.put( "expiresAt", token.expiresAt().toString() );
            body.put( "tokenRights", token.tokenRights() );
            body.put( "unsecurePass", token.unsecurePass() );
            return Response.ok( body ).build();
        }
        catch ( SessionException e )
        {
            LOG.warnf( "POST /api/v1/connect → 502: %s", e.getMessage() );
            return Response.status( Response.Status.BAD_GATEWAY )
                           .entity( Map.of(
                                   "status", "failed",
                                   "error", "handshake_failed",
                                   "message", e.getMessage(),
                                   "state", sessionTracker.state().name() ) )
                           .build();
        }
    }

    /**
     * {@code POST /api/v1/disconnect} — close the WebSocket session cleanly.
     * <p>
     * Calls {@code session.disconnect(...)} (normal-closure close frame, drops the session).
     * Idempotent — no-op if already disconnected. Always HTTP 200 with {@code status=ok} +
     * current {@code state}.
     */
    @POST
    @Path( "/disconnect" )
    @Operation( summary = "Close the WebSocket session cleanly",
                description = "Sends a normal-closure close frame and drops the session. " +
                              "Idempotent; no-op if already disconnected." )
    public Response disconnect()
    {
        session.disconnect( "operator request via /api/v1/disconnect" );
        return Response.ok( Map.of(
                "status", "ok",
                "state", sessionTracker.state().name() ) ).build();
    }

    /**
     * {@code POST /api/v1/reconnect} — force a fresh Miniserver session.
     * <p>
     * Calls {@code session.disconnect(...)} then delegates to {@link #connect()}, so the
     * response + status codes are exactly those of {@code POST /api/v1/connect} (200 on success,
     * 502 on handshake failure). Drops and re-establishes the live WS session.
     */
    @POST
    @Path( "/reconnect" )
    @Operation( summary = "Force a Miniserver reconnect — disconnect + connect in one call",
                description = "Convenience wrapper around POST /disconnect + POST /connect. " +
                              "Use it to force a fresh session after a config tweak or a " +
                              "Miniserver-side issue." )
    public Response reconnect()
    {
        session.disconnect( "operator-triggered reconnect" );
        return connect();
    }

    /**
     * {@code POST /api/v1/commands} — send a command to a Loxone control.
     * <p>
     * Encrypts and forwards {@code uuid/command} over the active WS session via
     * {@code session.sendCommand(...)} — actuates a real device. Validates the body first.
     *
     * @param req JSON body ({@link CommandRequest}); {@code uuid} and {@code command} are both required/non-blank.
     *
     * Responses: HTTP 400 ({@code BAD_REQUEST}) when uuid/command missing or blank; 200 on
     * {@code SENT} (echoes uuid+command); 409 ({@code CONFLICT}) on {@code NOT_RUNNING} (with
     * current state); 502 ({@code BAD_GATEWAY}) on {@code SEND_FAILED}.
     */
    @POST
    @Path( "/commands" )
    @Consumes( MediaType.APPLICATION_JSON )
    @Operation( summary = "Send a command to a Loxone control",
                description = "Encrypts and forwards {uuid}/{command} to the Miniserver over the active " +
                              "WebSocket session (synchronous — returns the send outcome). Backs the " +
                              "Commands page. NOTE: this actuates a real device." )
    public Response sendCommand( CommandRequest req )
    {
        if ( req == null || req.uuid() == null || req.uuid().isBlank()
             || req.command() == null || req.command().isBlank() )
        {
            return Response.status( Response.Status.BAD_REQUEST )
                           .entity( Map.of( "status", "error", "reason", "uuid and command are required" ) )
                           .build();
        }
        SessionOrchestrator.SendResult result = session.sendCommand( req.uuid().trim(), req.command().trim() );
        return switch ( result )
        {
            case SENT        -> Response.ok( Map.of( "status", "ok",
                                                     "uuid", req.uuid().trim(),
                                                     "command", req.command().trim() ) ).build();
            case NOT_RUNNING -> Response.status( Response.Status.CONFLICT )
                                        .entity( Map.of( "status", "error",
                                                         "reason", "session not RUNNING",
                                                         "state", sessionTracker.state().name() ) ).build();
            case SEND_FAILED -> Response.status( Response.Status.BAD_GATEWAY )
                                        .entity( Map.of( "status", "error",
                                                         "reason", "send failed (see commands.log)" ) ).build();
        };
    }

    /**
     * {@code POST /api/v1/system-command} — run a Miniserver SYSTEM / PLC /
     * CONFIG / CAN-bus diagnostic command and return the Miniserver's raw
     * {@code LL} JSON reply verbatim.
     * <p>
     * The command must appear in the curated catalogue
     * ({@code system-commands.json}) — the backend {@link SystemCommandRegistry}
     * is the single allow-list, so user-management, token and crypto commands
     * can never be routed here even though they share the {@code jdev/sys/}
     * namespace. Sent via
     * {@link MiniserverAdminCommandClient#sendResolvedPath} (authenticated HTTPS
     * GET on the session token). 400 on a blank / unlisted command, 409 when the
     * session is not RUNNING, 502 on a Miniserver / transport error.
     */
    @POST
    @Path( "/system-command" )
    @Consumes( MediaType.APPLICATION_JSON )
    @Operation( summary = "Run a catalogued jdev diagnostic command; return the raw LL reply" )
    public Response systemCommand( SystemCommandRequest req )
    {
        String cmd = req != null && req.command() != null ? req.command().trim() : "";
        if ( cmd.isBlank() )
        {
            return Response.status( Response.Status.BAD_REQUEST )
                           .entity( Map.of( "status", "error", "reason", "command is required" ) ).build();
        }
        // The curated catalogue is the authority: only commands listed in
        // system-commands.json (exact, or the <cmd>/<value> form of a
        // parameterised one) may run — nothing else, by construction.
        if ( !systemCommands.isAllowed( cmd ) )
        {
            return Response.status( Response.Status.BAD_REQUEST )
                           .entity( Map.of( "status", "error",
                                            "reason", "command is not in the system-command catalogue",
                                            "command", cmd ) ).build();
        }
        try
        {
            JsonNode ll = adminClient.sendResolvedPath( cmd, Duration.ofSeconds( 8 ) );
            return Response.ok( ll ).build();
        }
        catch ( IllegalStateException e )
        {
            return Response.status( Response.Status.CONFLICT )
                           .entity( Map.of( "status", "error", "reason", String.valueOf( e.getMessage() ),
                                            "state", sessionTracker.state().name() ) ).build();
        }
        catch ( AdminCommandException e )
        {
            return Response.status( Response.Status.BAD_GATEWAY )
                           .entity( Map.of( "status", "error", "reason", String.valueOf( e.getMessage() ),
                                            "command", cmd ) ).build();
        }
    }

    /** Request body for {@code POST /api/v1/system-command}. */
    public record SystemCommandRequest( String command )
    {
    }

    /** Request body for {@code POST /api/v1/commands}. */
    public record CommandRequest( String uuid, String command )
    {
    }

    /**
     * {@code GET /api/v1/commands/inbound} — report whether inbound MQTT command processing is enabled.
     * <p>
     * Read-only; reads the in-memory {@link InboundCommandGate}. Always HTTP 200 with
     * {@code {"enabled": <bool>}}.
     */
    @GET
    @Path( "/commands/inbound" )
    @Operation( summary = "Whether inbound (MQTT) command processing is enabled" )
    public Response inboundStatus()
    {
        return Response.ok( Map.of( "enabled", inboundGate.isEnabled() ) ).build();
    }

    /**
     * {@code PUT /api/v1/commands/inbound} — toggle inbound MQTT command processing.
     * <p>
     * Sets the in-memory {@link InboundCommandGate} (resets to enabled on restart). When disabled,
     * commands on the inbound topics are dropped instead of forwarded; does not affect this
     * resource's own {@code POST /commands}.
     *
     * @param req JSON body ({@link InboundRequest}); {@code enabled} (Boolean) is required.
     *
     * Responses: HTTP 400 ({@code BAD_REQUEST}) when body/enabled missing; 200 echoing the new
     * {@code {"enabled": <bool>}} state.
     */
    @PUT
    @Path( "/commands/inbound" )
    @Consumes( MediaType.APPLICATION_JSON )
    @Operation( summary = "Enable/disable inbound (MQTT) command processing",
                description = "When disabled, commands received on …/command and …/api are dropped " +
                              "(DROP-DISABLED) instead of being forwarded to the Miniserver. In-memory — " +
                              "resets to enabled on restart. Does NOT affect the Commands page's own sends." )
    public Response setInbound( InboundRequest req )
    {
        if ( req == null || req.enabled() == null )
        {
            return Response.status( Response.Status.BAD_REQUEST )
                           .entity( Map.of( "status", "error", "reason", "enabled (boolean) is required" ) )
                           .build();
        }
        inboundGate.setEnabled( req.enabled() );
        return Response.ok( Map.of( "enabled", inboundGate.isEnabled() ) ).build();
    }

    /** Request body for {@code PUT /api/v1/commands/inbound}. */
    public record InboundRequest( Boolean enabled )
    {
    }

    /**
     * {@code POST /api/v1/sdcard/test} — run the SD-card self-test on demand.
     * <p>
     * Calls {@code sdCardHealth.runSelfTest()} (triggers the Miniserver self-test). On empty
     * result: HTTP 502 ({@code BAD_GATEWAY}) with a failure reason. On success: HTTP 200 with
     * {@code healthy}, {@code detail} and {@code checkedAt}.
     */
    @POST
    @Path( "/sdcard/test" )
    @Operation( summary = "Run the SD-card self-test now (jdev/sys/sdtest)",
                description = "Triggers an on-demand SD-card self-test on the Miniserver and returns the " +
                              "fresh result. Lets the operator re-run it any time without restarting LoxMQ " +
                              "(it otherwise runs once automatically on each RUNNING transition)." )
    public Response runSdCardTest()
    {
        var result = sdCardHealth.runSelfTest();
        if ( result.isEmpty() )
        {
            return Response.status( Response.Status.BAD_GATEWAY )
                           .entity( Map.of( "status", "error",
                                            "reason", "SD-card self-test failed or was rejected (see logs)" ) )
                           .build();
        }
        var status = result.get();
        return Response.ok( Map.of( "status", "ok",
                                    "healthy", status.healthy(),
                                    "detail", status.detail(),
                                    "checkedAt", status.checkedAt().toString() ) ).build();
    }

    /**
     * {@code POST /api/v1/firmware/check} — run the firmware-version check on demand.
     * <p>
     * Calls {@code firmwareUpdateService.runCheck()} (fetches Loxone's public {@code updatecheck.xml}).
     * On empty result (check failed or no Miniserver identity yet): HTTP 502 ({@code BAD_GATEWAY})
     * with a reason. On success: HTTP 200 with {@code upToDate}, {@code latest} and {@code checkedAt}.
     */
    @POST
    @Path( "/firmware/check" )
    @Operation( summary = "Check the latest available Miniserver firmware now (updatecheck.xml)",
                description = "Triggers an on-demand firmware-version check against Loxone's public " +
                              "updatecheck.xml and returns the fresh result. Lets the operator re-run it " +
                              "any time without restarting LoxMQ (it otherwise runs once per RUNNING transition)." )
    public Response runFirmwareCheck()
    {
        var result = firmwareUpdateService.runCheck();
        if ( result.isEmpty() )
        {
            return Response.status( Response.Status.BAD_GATEWAY )
                           .entity( Map.of( "status", "error",
                                            "reason", "firmware check failed or no Miniserver identity yet (see logs)" ) )
                           .build();
        }
        var status = result.get();
        return Response.ok( Map.of( "status", "ok",
                                    "upToDate", status.upToDate(),
                                    "latest", status.latest().toString(),
                                    "checkedAt", status.checkedAt().toString() ) ).build();
    }

    /**
     * {@code POST /api/v1/reboot} — reboot the Miniserver.
     * <p>
     * Sends {@code jdev/sps/restart} over the HTTPS + autht admin channel via
     * {@code adminClient.sendAndAwait("restart", 8s)} + {@code requireOk}. Gated on
     * {@code SessionState.RUNNING} (a valid JWT is needed to sign the autht). Restarts the device
     * (~30–60 s downtime); the session auto-reconnects afterwards. Logs INFO on acceptance.
     *
     * Responses: HTTP 409 ({@code CONFLICT}) when state != RUNNING; 200 on acceptance; 502
     * ({@code BAD_GATEWAY}) on {@link AdminCommandException} (e.g. user lacks Sys-WS rights, logged WARN).
     */
    @POST
    @Path( "/reboot" )
    @Operation( summary = "Reboot the Miniserver",
                description = "Sends jdev/sps/restart over the HTTPS + autht admin channel " +
                              "(the same path used by /schedules and /users). The Miniserver " +
                              "restarts — expect ~30–60 s of downtime: the WebSocket session " +
                              "drops and ReconnectScheduler re-establishes it automatically " +
                              "once the Miniserver is back. Requires the configured user to " +
                              "hold the Sys-WS permission (bit 0x00000100) in Loxone Config. " +
                              "Refuses with 409 if the session is not RUNNING (no valid JWT to " +
                              "sign the autht), and 502 if the Miniserver rejects the command " +
                              "(e.g. the user lacks Sys-WS rights)." )
    public Response reboot()
    {
        // The admin client needs a valid JWT (held only in RUNNING) to compute
        // the autht HMAC. Gate explicitly so the operator gets a clear 409
        // instead of a cryptic autht failure from deep in sendAndAwait.
        if ( sessionTracker.state() != SessionState.RUNNING )
        {
            return Response.status( Response.Status.CONFLICT )
                           .entity( Map.of(
                                   "status", "refused",
                                   "error", "not_running",
                                   "message", "Session must be RUNNING to reboot — current state: "
                                              + sessionTracker.state().name(),
                                   "state", sessionTracker.state().name() ) )
                           .build();
        }
        try
        {
            // jdev/sps/restart — restarts the Miniserver. The reply normally
            // lands before the restart actually kicks in; requireOk surfaces a
            // non-200 Code (e.g. 403 when the user lacks Sys-WS) as an
            // AdminCommandException, caught below.
            JsonNode ll = adminClient.sendAndAwait( "restart", Duration.ofSeconds( 8 ) );
            AdminCommandResponses.requireOk( ll, "restart" );
            LOG.info( "POST /api/v1/reboot → jdev/sps/restart accepted; Miniserver restarting" );
            return Response.ok( Map.of(
                    "status", "ok",
                    "message", "reboot command accepted — Miniserver restarting; "
                               + "the session will auto-reconnect once it is back",
                    "state", sessionTracker.state().name() ) ).build();
        }
        catch ( AdminCommandException e )
        {
            LOG.warnf( "POST /api/v1/reboot → 502: %s", e.getMessage() );
            return Response.status( Response.Status.BAD_GATEWAY )
                           .entity( Map.of(
                                   "status", "failed",
                                   "error", "miniserver-error",
                                   "message", e.getMessage(),
                                   "state", sessionTracker.state().name() ) )
                           .build();
        }
    }

    /**
     * {@code POST /api/v1/token/kill} — invalidate the current Miniserver JWT.
     * <p>
     * If no token is held locally: HTTP 200 with {@code "no token to kill"}, no network call.
     * Otherwise calls {@code session.killToken()} (sends {@code jdev/sys/killtoken/...} over the
     * encrypted WS to invalidate server-side, then closes the local WS) and returns HTTP 200 with
     * the current {@code state}. The next reconnect re-runs the full handshake.
     */
    @POST
    @Path( "/token/kill" )
    @Operation( summary = "Invalidate the current miniserver JWT",
                description = "Sends jdev/sys/killtoken/<hash>/<user> to the miniserver (encrypted " +
                              "WS frame) to invalidate the token server-side, then closes the local " +
                              "WS. The next reconnect re-runs the full key-exchange + token-request " +
                              "handshake from scratch. If no token is held locally, returns 200 + " +
                              "\"no token to kill\" without any network call." )
    public Response killToken()
    {
        if ( sessionTracker.token().isEmpty() )
        {
            return Response.ok( Map.of( "status", "ok", "message", "no token to kill" ) ).build();
        }
        session.killToken();
        return Response.ok( Map.of(
                "status", "ok",
                "message", "sent killtoken to miniserver + closed local WS",
                "state", sessionTracker.state().name() ) ).build();
    }

    /**
     * {@code POST /api/v1/token/refresh} — manually trigger a JWT refresh.
     * <p>
     * Gated on {@code SessionState.RUNNING} and a locally-held token. Calls
     * {@code session.refreshToken()} (sends {@code jdev/sys/refreshjwt/...} over the encrypted WS);
     * the reply lands async and updates {@code token.expiresAt}. Returns immediately after the send
     * (does not wait for the reply) — poll {@code /api/v1/state} for the new expiry.
     *
     * Responses: HTTP 409 ({@code CONFLICT}) when state != RUNNING ({@code not_running}) or no token
     * held ({@code no_token}); 200 after the WS send.
     */
    @POST
    @Path( "/token/refresh" )
    @Operation( summary = "Manually trigger a JWT refresh",
                description = "Operator-driven equivalent of the periodic refresh fired by " +
                              "TokenRefreshScheduler (24h). Sends jdev/sys/refreshjwt/" +
                              "<hash>/<user> (encrypted) over the WS ; the reply lands async via " +
                              "onTokenRefreshReply and updates token.expiresAt in the session " +
                              "tracker. Returns 200 immediately after the WS send (does NOT wait " +
                              "for the reply). Operator polls /api/v1/state to see the new " +
                              "expiresAt. Refuses with 409 if state != RUNNING or no token is " +
                              "held locally." )
    public Response refreshToken()
    {
        if ( sessionTracker.state() != SessionState.RUNNING )
        {
            return Response.status( Response.Status.CONFLICT )
                           .entity( Map.of(
                                   "status", "refused",
                                   "error", "not_running",
                                   "message", "Session must be RUNNING to refresh — current state: "
                                              + sessionTracker.state().name(),
                                   "state", sessionTracker.state().name() ) )
                           .build();
        }
        if ( sessionTracker.token().isEmpty() )
        {
            return Response.status( Response.Status.CONFLICT )
                           .entity( Map.of(
                                   "status", "refused",
                                   "error", "no_token",
                                   "message", "No token held locally — connect first.",
                                   "state", sessionTracker.state().name() ) )
                           .build();
        }
        session.refreshToken();
        return Response.ok( Map.of(
                "status", "ok",
                "message", "refreshjwt sent — reply lands async, poll /api/v1/state for the new expiresAt",
                "state", sessionTracker.state().name() ) ).build();
    }

    /**
     * {@code POST /api/v1/connect-with-bootstrap} — run bootstrap then open the WS session.
     * <p>
     * Step 1 {@code bootstrap.run()} (idempotent, re-runs the full sequence); on
     * {@link BootstrapException} returns HTTP 502 ({@code BAD_GATEWAY}) with {@code step=bootstrap}
     * and the connect step is NOT attempted (logged WARN). Step 2 {@code session.connectAndWait(...)};
     * on success HTTP 200 with the bootstrap identity + token metadata, on {@link SessionException}
     * HTTP 502 with {@code step=connect} + current state + the already-successful bootstrap status
     * (logged WARN). Backs the dashboard "Connect to Miniserver" button.
     */
    @POST
    @Path( "/connect-with-bootstrap" )
    @Operation( summary = "Atomically run HTTP bootstrap + open WS session",
                description = "Convenience for the dashboard's 'Connect to Miniserver' button. " +
                              "Runs jdev/cfg/apiKey + jdev/sys/getPublicKey, then if that " +
                              "succeeds opens the WS and runs the handshake (keyexchange + getkey2 " +
                              "+ getjwt). If bootstrap fails, the connect step is NOT attempted " +
                              "and the response surfaces the bootstrap error. If connect fails after " +
                              "a successful bootstrap, the response surfaces the connect error and " +
                              "the bootstrap status is included so the operator sees the partial " +
                              "progress." )
    public Response connectWithBootstrap()
    {
        // Step 1 — bootstrap. Re-runs the full sequence every time.
        MiniserverIdentity identity;
        try
        {
            identity = bootstrap.run();
        }
        catch ( BootstrapException e )
        {
            LOG.warnf( "POST /api/v1/connect-with-bootstrap → bootstrap step failed: %s", e.getMessage() );
            return Response.status( Response.Status.BAD_GATEWAY )
                           .entity( Map.of(
                                   "status", "failed",
                                   "step", "bootstrap",
                                   "error", "bootstrap_failed",
                                   "message", e.getMessage() ) )
                           .build();
        }

        // Step 2 — connect. Bootstrap success guarantees the public key is
        // loaded, so connect() can proceed straight to the handshake.
        long timeoutSec = config.miniserver().connection().http().requestTimeout().toSeconds() + 10;
        try
        {
            MiniserverToken       token = session.connectAndWait( timeoutSec );
            Map< String, Object > body  = new LinkedHashMap<>();
            body.put( "status", "success" );
            body.put( "state", sessionTracker.state().name() );
            body.put( "bootstrap", Map.of(
                    "durationMs", bootstrapTracker.lastDuration().map( d -> ( Object ) d.toMillis() ).orElse( -1L ),
                    "serial", identity.serial(),
                    "version", identity.version().toString(),
                    "generation", identity.generation().name(),
                    "httpsStatus", identity.httpsStatus().name() ) );
            body.put( "token", Map.of(
                    "expiresAt", token.expiresAt().toString(),
                    "tokenRights", token.tokenRights(),
                    "unsecurePass", token.unsecurePass() ) );
            return Response.ok( body ).build();
        }
        catch ( SessionException e )
        {
            LOG.warnf( "POST /api/v1/connect-with-bootstrap → connect step failed: %s", e.getMessage() );
            return Response.status( Response.Status.BAD_GATEWAY )
                           .entity( Map.of(
                                   "status", "failed",
                                   "step", "connect",
                                   "error", "handshake_failed",
                                   "message", e.getMessage(),
                                   "state", sessionTracker.state().name(),
                                   "bootstrap", Map.of(
                                           "status", "success",
                                           "durationMs", bootstrapTracker.lastDuration().map( d -> ( Object ) d.toMillis() ).orElse( -1L ) ) ) )
                           .build();
        }
    }

    // ==========================================================================
    //  Transport (MQTT broker)
    // ==========================================================================

    /**
     * {@code POST /api/v1/transport/connect} — connect to the MQTT broker.
     * <p>
     * Idempotent: if {@code mqtt.isConnected()} returns HTTP 200 with {@code status=already-connected}
     * (no round-trip). Otherwise calls {@code mqtt.connect()} (opens the HiveMQ MQTT v5 connection,
     * subscribes input topics, publishes 'online' presence). On success: HTTP 200 with
     * {@code status=connected} + broker + scheme. On {@link TransportException}: HTTP 502
     * ({@code BAD_GATEWAY}) with the error (logged WARN).
     */
    @POST
    @Path( "/transport/connect" )
    @Operation( summary = "Connect to the MQTT broker",
                description = "Opens the broker connection (HiveMQ MQTT v5), subscribes to the " +
                              "configured input topics, publishes the 'online' presence message. " +
                              "Returns 200 with status=connected on success, 502 with the broker " +
                              "error on failure. Idempotent: if the binding is already connected, " +
                              "returns 200 with status=already-connected (no broker round-trip)." )
    public Response transportConnect()
    {
        if ( mqtt.isConnected() )
        {
            return Response.ok( Map.of( "status", "already-connected" ) ).build();
        }
        try
        {
            mqtt.connect();
            return Response.ok( Map.of(
                    "status", "connected",
                    "broker", config.transport().connection().host() + ":" + config.transport().connection().port(),
                    "scheme", transportResolver.effectiveProtocol() ) ).build();
        }
        catch ( TransportException e )
        {
            LOG.warnf( "POST /api/v1/transport/connect → 502: %s", e.getMessage() );
            return Response.status( Response.Status.BAD_GATEWAY )
                           .entity( Map.of(
                                   "status", "failed",
                                   "error", "broker_connect_failed",
                                   "message", e.getMessage() ) )
                           .build();
        }
    }

    /**
     * {@code POST /api/v1/transport/disconnect} — disconnect from the MQTT broker.
     * <p>
     * Idempotent: if not connected returns HTTP 200 with {@code status=already-disconnected}.
     * Otherwise calls {@code mqtt.disconnect()} (publishes 'offline' presence then closes cleanly)
     * and returns HTTP 200 with {@code status=disconnected}.
     */
    @POST
    @Path( "/transport/disconnect" )
    @Operation( summary = "Disconnect from the MQTT broker",
                description = "Publishes the 'offline' presence message then closes cleanly. " +
                              "Idempotent — returns 200 with status=already-disconnected if the " +
                              "broker connection is already down." )
    public Response transportDisconnect()
    {
        if ( !mqtt.isConnected() )
        {
            return Response.ok( Map.of( "status", "already-disconnected" ) ).build();
        }
        mqtt.disconnect();
        return Response.ok( Map.of( "status", "disconnected" ) ).build();
    }

    /**
     * {@code GET /api/v1/transport/status} — lightweight broker connection probe.
     * <p>
     * Read-only; returns HTTP 200 with {@code connected} ({@code mqtt.isConnected()}), {@code broker}
     * (host:port) and the resolved {@code scheme}. Cheaper than {@code /state} — no session/cache
     * aggregation.
     */
    @GET
    @Path( "/transport/status" )
    @Operation( summary = "Broker connection status",
                description = "Lightweight probe: returns whether the binding believes the MQTT " +
                              "transport is up + the resolved scheme. Cheaper than /state because " +
                              "it doesn't aggregate session / cache / handshake info." )
    public Response transportStatus()
    {
        return Response.ok( Map.of(
                "connected", mqtt.isConnected(),
                "broker", config.transport().connection().host() + ":" + config.transport().connection().port(),
                "scheme", transportResolver.effectiveProtocol() ) ).build();
    }
}
