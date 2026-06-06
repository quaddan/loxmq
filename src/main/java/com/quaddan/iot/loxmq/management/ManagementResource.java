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
import com.quaddan.iot.loxmq.miniserver.state.MiniserverState;
import com.quaddan.iot.loxmq.transport.MqttClient;
import com.quaddan.iot.loxmq.transport.TransportException;
import com.quaddan.iot.loxmq.transport.connection.TransportConnectionResolver;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
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

    /** loxmq's own artifact version, injected by Quarkus from the Maven build.
     *  Surfaced at the root of {@code GET /api/v1/state} so an operator can
     *  confirm which binding release produced a given snapshot. The
     *  {@code defaultValue} is defensive — same pattern as
     *  {@code AppInfoPublisher#bindingVersion}. */
    @ConfigProperty( name = "quarkus.application.version", defaultValue = "unknown" )
    String bindingVersion;

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
