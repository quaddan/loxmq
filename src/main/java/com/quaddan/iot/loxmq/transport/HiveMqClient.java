/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.transport;

import com.hivemq.client.mqtt.MqttClientSslConfig;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.lifecycle.MqttClientConnectedContext;
import com.hivemq.client.mqtt.lifecycle.MqttClientDisconnectedContext;
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient;
import com.hivemq.client.mqtt.mqtt5.Mqtt5Client;
import com.hivemq.client.mqtt.mqtt5.Mqtt5ClientBuilder;
import com.hivemq.client.mqtt.mqtt5.message.disconnect.Mqtt5DisconnectReasonCode;
import com.quaddan.iot.loxmq.miniserver.session.JdkMiniserverWebSocket;
import com.quaddan.iot.loxmq.config.LoxoneConfig;
import com.quaddan.iot.loxmq.transport.connection.TransportConnectionResolver;
import io.quarkus.arc.DefaultBean;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Production {@link MqttClient} backed by HiveMQ MQTT Client 1.3.x. MQTT v5
 * over Netty, native-image friendly, with built-in auto-reconnect.
 *
 * <h3>Why HiveMQ (vs. Paho)</h3>
 * HiveMQ exposes the disconnect source (USER / CLIENT / SERVER) so we can
 * tell broker-initiated drops apart from routine TCP hiccups and not panic
 * on either. Paho's flat "lost connection" callback always escalated
 * everything to the supervisor, which killed the bridge on harmless network
 * glitches.
 *
 * <h3>Why @{@link DefaultBean}</h3>
 * Same pattern as {@link JdkMiniserverWebSocket}:
 * the production code injects {@link MqttClient}; tests provide a fake via
 * Quarkus {@code QuarkusMock} or an {@code @Alternative} bean. Production
 * stays unaware of test wiring.
 *
 * <h3>Synchronous connect, async runtime</h3>
 * {@link #connect()} blocks until CONNACK + initial subscriptions complete:
 * callers (management API, lifecycle observer) want a clean success/failure
 * to surface to the operator.
 * Once {@link #isConnected()} flips true, publishes are fire-and-forget
 * (the HiveMQ async client buffers + dispatches without blocking).
 *
 * <h3>Auto-reconnect</h3>
 * Configured at build time via {@link Mqtt5ClientBuilder#automaticReconnect()}.
 * The reconnect listener republishes the "online" presence message on every
 * CONNACK so subscribers reliably observe the binding's lifecycle even after
 * a network blip.
 */
@ApplicationScoped
@DefaultBean
public class HiveMqClient implements MqttClient
{
    private static final Logger LOG = Logger.getLogger( HiveMqClient.class );

    @Inject
    LoxoneConfig                   config;
    @Inject
    TransportConnectionResolver    transportResolver;
    @Inject
    Event< MqttConnectedEvent >    connectedEvent;
    @Inject
    Event< MqttDisconnectedEvent > disconnectedEvent;
    @Inject
    MqttMetrics                    metrics;

    private Mqtt5AsyncClient client;

    /** True between a successful {@link #connect()} and the next clean
     *  shutdown. The HiveMQ {@code isConnected()} flips back and forth on
     *  reconnect blips — we expose our own flag for "the binding believes
     *  the transport is up", which is more stable. */
    private final AtomicBoolean operatorAlive = new AtomicBoolean( false );

    @PostConstruct
    void buildClient()
    {
        String host     = config.transport().connection().host();
        int    port     = config.transport().connection().port();
        String protocol = transportResolver.effectiveProtocol();   // tcp / ssl / ws / wss
        String clientId = config.transport().connection().clientId();
        String path     = config.transport().connection().path().orElse( "" );

        Mqtt5ClientBuilder builder = Mqtt5Client.builder()
                                                .identifier( clientId )
                                                .serverHost( host )
                                                .serverPort( port )
                                                .addConnectedListener( this::onConnected )
                                                .addDisconnectedListener( this::onDisconnected );

        boolean isWebSocket = "ws".equalsIgnoreCase( protocol ) || "wss".equalsIgnoreCase( protocol );
        if ( isWebSocket )
        {
            if ( path.isBlank() )
            {
                throw new TransportException(
                        "transport.connection.protocol=" + protocol
                        + " but transport.connection.path is blank — required for WebSocket transport" );
            }
            builder = builder.webSocketConfig().serverPath( path ).applyWebSocketConfig();
        }

        boolean isSecure = "ssl".equalsIgnoreCase( protocol ) || "wss".equalsIgnoreCase( protocol );
        if ( isSecure )
        {
            // Default JVM trust store. Operators with a self-signed broker
            // cert can pre-import it via `keytool` at deploy time; we don't
            // accept blind-trust here on purpose.
            builder = builder.sslConfig( MqttClientSslConfig.builder().build() );
        }

        if ( config.transport().reconnection().automatic() )
        {
            builder = builder.automaticReconnect()
                             .initialDelay( config.transport().reconnection().minDelay().toMillis(), TimeUnit.MILLISECONDS )
                             .maxDelay( config.transport().reconnection().maxDelay().toMillis(), TimeUnit.MILLISECONDS )
                             .applyAutomaticReconnect();
        }

        // ----------------------------------------------------------------
        //  simpleAuth + willPublish — set HERE, at builder level — NOT
        //  on the per-call connectWith() down in connect().
        //
        //  Rationale: HiveMQ's automaticReconnect() rebuilds the
        //  CONNECT message on every retry from the **client's default
        //  config**. Per-call options passed via
        //  {@code client.connectWith()} are honoured ONLY for that
        //  specific call ; the reconnect loop doesn't reuse them. So if
        //  auth is configured at connect time only, the initial CONNECT
        //  carries the credentials and succeeds, the broker accepts the
        //  session, but **every subsequent auto-reconnect (e.g. after a
        //  broker restart) re-emits a CONNECT without auth → CONNACK
        //  NOT_AUTHORIZED in a loop**. Bug observed in prod: broker
        //  restarted, binding stuck in a NOT_AUTHORIZED loop until the
        //  binding process itself was restarted (initial connect
        //  re-runs → SUCCESS again).
        //
        //  Same root cause for willPublish — without builder-level LWT,
        //  the broker has no will to fire on subsequent ungraceful
        //  disconnects after a reconnect cycle.
        // ----------------------------------------------------------------
        if ( config.transport().security().credentials().enable() )
        {
            String user = decodeBase64Opt( config.transport().security().credentials().user(), "user" );
            String pass = decodeBase64Opt( config.transport().security().credentials().password(), "password" );
            builder = builder.simpleAuth()
                             .username( user )
                             .password( pass.getBytes( StandardCharsets.UTF_8 ) )
                             .applySimpleAuth();
        }

        if ( config.transport().topics().will().enable() )
        {
            builder = builder.willPublish()
                             .topic( config.transport().topics().will().topic() )
                             .qos( MqttQos.fromCode( config.transport().topics().will().qos() ) )
                             .retain( config.transport().topics().will().retain() )
                             .payload( config.transport().topics().will().messageOffline().getBytes( StandardCharsets.UTF_8 ) )
                             .applyWillPublish();
        }

        client = builder.buildAsync();
        LOG.infof( "HiveMQ client built: %s://%s:%d%s (clientId=%s)",
                   protocol, host, ( Integer ) port, isWebSocket ? path : "", clientId );
    }

    // ==========================================================================
    //  Lifecycle
    // ==========================================================================

    @Override
    public synchronized void connect()
    {
        if ( operatorAlive.get() )
        {
            LOG.debug( "MQTT connect requested but already connected — no-op" );
            return;
        }

        long timeoutSec = config.transport().connection().connectTimeout().toSeconds();
        LOG.infof( "Connecting to MQTT broker (timeout=%ds)...", ( Long ) timeoutSec );

        try
        {
            // simpleAuth + willPublish are baked into the client at builder
            // time so HiveMQ's auto-reconnect picks them up on every retry.
            // The per-call options below (cleanStart, expiry, keepAlive,
            // restrictions) ARE per-call and must stay here — they're
            // cached and replayed by HiveMQ on subsequent reconnects.
            var connectBuilder = client.connectWith()
                                       .cleanStart( config.transport().session().cleanStart() )
                                       .sessionExpiryInterval( config.transport().session().expiryInterval().toSeconds() )
                                       .keepAlive( ( int ) config.transport().connection().keepaliveInterval().toSeconds() )
                                       .restrictions()
                                       .requestProblemInformation( config.transport().connection().requestProblemInformation() )
                                       .applyRestrictions();

            connectBuilder.send().get( timeoutSec, TimeUnit.SECONDS );
            operatorAlive.set( true );
            LOG.info( "CONNACK received — MQTT session up" );

            // Online presence message — fire as soon as we have a session.
            // The auto-reconnect listener will redo this on every subsequent
            // CONNACK (onConnected).
            publishOnlineStatus();
        }
        catch ( TimeoutException e )
        {
            throw new TransportException( "MQTT connect timed out after " + timeoutSec + "s", e );
        }
        catch ( InterruptedException e )
        {
            Thread.currentThread().interrupt();
            throw new TransportException( "MQTT connect interrupted", e );
        }
        catch ( ExecutionException | CompletionException e )
        {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new TransportException( "MQTT connect failed: " + cause.getMessage(), cause );
        }
    }

    @Override
    public synchronized void disconnect()
    {
        if ( !operatorAlive.compareAndSet( true, false ) )
        {
            LOG.debug( "MQTT disconnect requested but not connected — no-op" );
            return;
        }
        LOG.info( "Disconnecting from MQTT broker..." );
        try
        {
            // Best-effort offline status. We can't rely on the LWT here:
            // the broker only fires the LWT on UNgraceful disconnect, not on
            // a clean DISCONNECT — so we publish it explicitly first.
            //
            // CAREFUL: do NOT route through our publish() helper. The helper
            // checks isConnected() which now returns false (operatorAlive is
            // false since the compareAndSet above), so the publish would be
            // silently dropped — and the broker would end up keeping "online"
            // retained forever after a clean shutdown. Use the HiveMQ async
            // client directly: the underlying MQTT session is still alive at
            // this point, and we .get() with a short timeout to make sure the
            // PUBACK lands before we send the DISCONNECT packet.
            //
            // Symptom we were seeing in MQTT Explorer: status retained as
            // "online" even after Quarkus dev had stopped — the offline
            // payload never reached the broker on clean shutdown.
            if ( config.transport().topics().will().enable() )
            {
                try
                {
                    var willSpec = config.transport().topics().will();
                    client.publishWith()
                          .topic( willSpec.topic() )
                          .qos( MqttQos.fromCode( willSpec.qos() ) )
                          .retain( willSpec.retain() )
                          .payload( willSpec.messageOffline().getBytes( StandardCharsets.UTF_8 ) )
                          .send()
                          .get( 2, TimeUnit.SECONDS );
                    LOG.debugf( "Published 'offline' on %s before DISCONNECT", willSpec.topic() );
                }
                catch ( Exception e )
                {
                    // Don't block the shutdown if the publish hangs — we still
                    // want the DISCONNECT to go out. Note: the LWT will NOT
                    // cover this case because we're about to send a clean
                    // DISCONNECT (the broker only fires LWT on ungraceful
                    // close). Result: status stays "online" retained until
                    // the next connect's CONNACK refreshes it. Acceptable
                    // trade-off vs. blocking shutdown.
                    LOG.warnf( "Could not publish offline status before disconnect: %s — status may stay retained as 'online' until next connect",
                               e.getMessage() );
                }
            }
            // sessionExpiryInterval(0) explicit here: forces the broker
            // to drop the session immediately on DISCONNECT, regardless
            // of the value configured at CONNECT. If we omit this flag
            // and the CONNECT had sessionExpiryInterval > 0, the broker
            // keeps the session "present but disconnected" for the
            // expiry duration — at the next CONNECT with the same
            // clientId, the new connection may be seen as a session
            // resumption (collision if the old TCP isn't FIN-acked).
            // With explicit 0, we guarantee a clean broker state: the
            // session disappears as soon as the DISCONNECT is processed.
            client.disconnectWith()
                  .reasonCode( Mqtt5DisconnectReasonCode.NORMAL_DISCONNECTION )
                  .sessionExpiryInterval( 0 )
                  .send()
                  .get( 5, TimeUnit.SECONDS );
        }
        catch ( InterruptedException e )
        {
            Thread.currentThread().interrupt();
            LOG.warnf( "MQTT disconnect interrupted: %s", e.getMessage() );
        }
        catch ( Exception e )
        {
            // Disconnect-time failures are logged but not propagated —
            // the binding is shutting down anyway, and the broker will
            // see the dropped TCP connection on its side.
            LOG.warnf( "MQTT disconnect did not complete cleanly: %s", e.getMessage() );
        }
    }

    @Override
    public boolean isConnected()
    {
        return operatorAlive.get() && client != null && client.getState().isConnected();
    }

    // ==========================================================================
    //  Pub / Sub
    // ==========================================================================

    @Override
    public void publish( String topic, int qos, boolean retain, byte[] payload )
    {
        if ( topic == null || topic.isBlank() )
        {
            throw new TransportException( "publish() called with blank topic — likely a config mistake" );
        }
        if ( !isConnected() )
        {
            LOG.debugf( "publish on '%s' dropped — MQTT not connected", topic );
            return;
        }
        // Per-publish DEBUG log removed — it duplicated the typed lines
        // from the publishers (StatesPublisher/CommandResponsePublisher/
        // AppInfoPublisher/LoxApp3Publisher) which already cover each
        // publish with their semantic wrapper (🔢/🔡/🕹/etc.). The
        // Micrometer counter below stays for the "publishes attempted"
        // metric.
        //
        // Push the publish-counter increment BEFORE the actual send. The
        // counter measures "publishes attempted" — a network blip that
        // makes the HiveMQ async pipeline error out still counts (the
        // operator wants to see attempt-rate, not just success-rate; the
        // success-rate is implicit via the err log line at the bottom of
        // this method).
        metrics.recordPublish();
        client.publishWith()
              .topic( topic )
              .qos( MqttQos.fromCode( qos ) )
              .retain( retain )
              .payload( payload )
              .send()
              .whenComplete( ( ack, err ) ->
                             {
                                 if ( err != null )
                                 {
                                     LOG.warnf( err, "publish on '%s' failed", topic );
                                 }
                             } );
    }

    @Override
    public void subscribe( String topicFilter, int qos, MqttMessageHandler handler )
    {
        if ( client == null )
        {
            throw new TransportException( "subscribe() called before client init" );
        }
        client.subscribeWith()
              .topicFilter( topicFilter )
              .qos( MqttQos.fromCode( qos ) )
              .callback( publish ->
                         {
                             byte[] payload = new byte[ publish.getPayload().map( bb -> bb.remaining() ).orElse( 0 ) ];
                             publish.getPayload().ifPresent( bb -> bb.get( payload ) );
                             // HiveMQ exposes the broker's retained flag directly on
                             // the Mqtt5Publish. We surface it to the handler so the
                             // CommandSubscriber (and any future inbound subscriber)
                             // can decide what to do with retained payloads on its
                             // topic — typically drop them with a WARN log.
                             boolean retained = publish.isRetain();
                             try { handler.accept( publish.getTopic().toString(), payload, retained ); }
                             catch ( RuntimeException e ) { LOG.warnf( e, "subscribe handler for %s threw", topicFilter ); }
                         } )
              .send()
              .whenComplete( ( ack, err ) ->
                             {
                                 if ( err != null )
                                 {
                                     LOG.warnf( err, "subscribe on '%s' failed", topicFilter );
                                 }
                                 else
                                 {
                                     LOG.infof( "subscribed to '%s' (qos=%d)", topicFilter, ( Integer ) qos );
                                 }
                             } );
    }

    /**
     * Best-effort unsubscribe. Fires the UNSUBSCRIBE to the broker AND
     * removes the local publish-stream callbacks tied to the
     * subscription. Both sides matter:
     * <ul>
     *   <li>broker side: the broker stops delivering messages on this
     *       filter to this client (clean state after a reconnect that
     *       inherited subscriptions).</li>
     *   <li>client side: the HiveMQ async client maintains an internal
     *       set of publish-stream callbacks. Re-{@link #subscribe} on
     *       the same filter would otherwise <em>add</em> a new callback
     *       alongside the existing one → every message routed to the
     *       filter fires both callbacks → duplicated CDI events / QoS-1
     *       flow-control confusion. Unsubscribing releases the previous
     *       stream's callback.</li>
     * </ul>
     *
     * <p>Errors are logged at WARN but not propagated — a no-prior-sub
     * is the common case (first connect of the binding) and shouldn't
     * be surfaced as a fault.
     */
    @Override
    public void unsubscribe( String topicFilter )
    {
        if ( client == null )
        {
            LOG.warnf( "unsubscribe('%s') called before client init — ignored", topicFilter );
            return;
        }
        client.unsubscribeWith()
              .topicFilter( topicFilter )
              .send()
              .whenComplete( ( ack, err ) ->
                             {
                                 if ( err != null )
                                 {
                                     // Common no-op: "no subscription existed" on first
                                     // CONNACK before any subscribe was ever issued.
                                     // Stay quiet at DEBUG so error.log isn't polluted.
                                     LOG.debugf( err, "unsubscribe on '%s' completed with error (often benign)",
                                                 topicFilter );
                                 }
                                 else
                                 {
                                     LOG.debugf( "unsubscribed from '%s'", topicFilter );
                                 }
                             } );
    }

    // ==========================================================================
    //  HiveMQ lifecycle listeners
    // ==========================================================================

    /** Called on every CONNACK — initial + every auto-reconnect cycle. */
    private void onConnected( MqttClientConnectedContext ctx )
    {
        LOG.info( "MQTT CONNACK received" );
        // operatorAlive is set by connect() on the first call. Auto-reconnect
        // cycles also pass through here — make sure the online status is
        // republished so retained presence stays accurate after a blip.
        if ( operatorAlive.get() && config.transport().topics().will().enable() )
        {
            publishOnlineStatus();
        }
        // Fire an event so subscribers (CommandSubscriber, future bootstrap
        // publishers) can (re-)subscribe + re-publish retained context.
        // With clean-start=true the broker drops subscriptions on every
        // disconnect, so we must reissue them on every CONNACK.
        connectedEvent.fire( new MqttConnectedEvent() );
    }

    /** Called by HiveMQ on every disconnect — user-initiated, broker-side,
     *  or transient network failure. Auto-reconnect (when enabled) is
     *  applied <b>by HiveMQ itself</b> before this listener fires; we just
     *  log so the operator sees the source. */
    private void onDisconnected( MqttClientDisconnectedContext ctx )
    {
        String src    = ctx.getSource().name();
        String reason = ctx.getCause() != null ? ctx.getCause().getMessage() : "";
        // Escalate to ERROR when the disconnect is NOT
        // operator-initiated. HiveMQ's MqttDisconnectSource has 3
        // values:
        //   USER   — application called client.disconnect() — clean,
        //            stay INFO so we don't pollute error.log on a
        //            normal shutdown / restart.
        //   SERVER — broker sent DISCONNECT or closed the TCP socket.
        //            Unexpected, surfaces as ERROR.
        //   CLIENT — client-side fault (keepalive timeout, etc.).
        //            Unexpected, surfaces as ERROR.
        // error.log is wired to ERROR-only, so this bubbles into the
        // dedicated error file too.
        boolean cleanOperator = "USER".equals( src );
        Boolean reconnect     = ( Boolean ) ctx.getReconnector().isReconnect();
        if ( cleanOperator )
        {
            LOG.infof( "MQTT disconnected (source=%s, reconnect=%s): %s",
                       src, reconnect, reason );
        }
        else
        {
            LOG.errorf( "MQTT disconnected (source=%s, reconnect=%s): %s",
                        src, reconnect, reason );
        }
        // Fire the typed event so the SSE broadcaster can push a
        // notification to the dashboard. Symmetric to
        // connectedEvent.fire in onConnected.
        disconnectedEvent.fire(
                new MqttDisconnectedEvent( java.time.Instant.now(), src, reason ) );
    }

    // ==========================================================================
    //  Helpers
    // ==========================================================================

    private void publishOnlineStatus()
    {
        publishStatus( config.transport().topics().will().messageOnline() );
    }

    private void publishStatus( String payload )
    {
        publish( config.transport().topics().will().topic(),
                 config.transport().topics().will().qos(),
                 config.transport().topics().will().retain(),
                 payload.getBytes( StandardCharsets.UTF_8 ) );
    }

    /** Decode a base64-encoded transport credential. Throws clearly if the
     *  config marks credentials enabled but the field is empty. */
    private static String decodeBase64Opt( java.util.Optional< String > raw, String field )
    {
        String value = raw.orElseThrow( () -> new TransportException(
                "transport.security.credentials.enable=true but " + field + " is empty" ) );
        try
        {
            return new String( Base64.getDecoder().decode( value ), StandardCharsets.UTF_8 );
        }
        catch ( IllegalArgumentException e )
        {
            throw new TransportException( "transport.security.credentials." + field
                                          + " is not valid base64", e );
        }
    }
}
