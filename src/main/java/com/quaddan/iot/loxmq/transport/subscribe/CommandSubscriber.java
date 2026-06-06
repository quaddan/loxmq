/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.transport.subscribe;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quaddan.iot.loxmq.config.LoxoneConfig;
import com.quaddan.iot.loxmq.miniserver.command.MiniserverApiConnectorSetCommand;
import com.quaddan.iot.loxmq.miniserver.command.MiniserverApiConnectorSetCommandEvent;
import com.quaddan.iot.loxmq.miniserver.command.MiniserverCommand;
import com.quaddan.iot.loxmq.miniserver.command.MiniserverCommandEvent;
import com.quaddan.iot.loxmq.transport.MqttClient;
import com.quaddan.iot.loxmq.transport.MqttConnectedEvent;
import com.quaddan.iot.loxmq.transport.MqttMetrics;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;

/**
 * Subscribes to the two inbound MQTT topics on every broker CONNACK and
 * translates incoming payloads into CDI events the
 * {@code SessionOrchestrator} can act on.
 *
 * <h3>Topics</h3>
 * <ul>
 *   <li>{@code loxone.transport.topics.subscribe.command.topic} —
 *       carries {@link MiniserverCommand} JSON payloads
 *       (UUID + Loxone command string).</li>
 *   <li>{@code loxone.transport.topics.subscribe.api.topic} —
 *       carries {@link MiniserverApiConnectorSetCommand} JSON payloads
 *       (Virtual Input Text + function block / input / value).</li>
 * </ul>
 *
 * <h3>Why subscribe on every CONNACK</h3>
 * The HiveMQ client builder is configured with
 * {@code clean-start=true} (the default; see
 * {@code loxone.transport.session.clean-start}). The broker drops the
 * binding's subscription state on each disconnect, so subscriptions
 * must be reissued on every reconnect. {@link MqttConnectedEvent} is
 * fired on initial connect AND on every HiveMQ auto-reconnect cycle —
 * this observer hooks both.
 *
 * <h3>JSON parsing failures</h3>
 * Bad inbound payloads are logged at {@code WARN} and dropped. We don't
 * disconnect or escalate — a typo from a downstream consumer shouldn't
 * tear down the broker connection. The bad message stays on the broker
 * (un-retained for this topic, default config) only as long as it takes
 * MQTT to deliver it once.
 *
 * <h3>Retained-message guard</h3>
 * The {@code …/command} and {@code …/api} topics are <strong>not</strong>
 * intended to carry retained messages. If something upstream
 * accidentally publishes with {@code retained=true}, the broker would
 * replay the payload on every CONNACK — pushing the same command into
 * the miniserver at each reconnect. Both handlers inspect the retained
 * flag and drop with a WARN log; the operator gets a clear actionable
 * signal in the console without the binding executing an unwanted
 * command.
 *
 * <p>This guard is critical for production safety. The originating bug
 * was caught during the initial deployment when a misconfigured
 * automation pushed a retained command and the binding cheerfully
 * triggered the matching VTI every time it reconnected to the broker.
 *
 * <h3>Payload-size guard</h3>
 * Inbound payloads larger than
 * {@code loxone.transport.security.max-inbound-payload-bytes} (default
 * 4 KB) are dropped before the JSON parse. A typical command payload
 * runs under 200 bytes; even a verbose API connector SET is well
 * under 1 KB. The cap defends against a malicious or buggy publisher
 * trying to flood the binding with a multi-MB blob: parsing such a
 * payload would burn CPU + memory for nothing (it could not be a
 * valid {@code MiniserverCommand} anyway).
 *
 * <p>Order of guards: <em>retained → size → JSON parse</em>. A
 * retained 10 MB payload is still rejected for being retained
 * (single WARN line, no size-check noise). The retained log is the
 * higher-priority operator signal — a replay loop is far more harmful
 * than a single oversized drop.
 */
@ApplicationScoped
public class CommandSubscriber
{
    private static final Logger LOG = Logger.getLogger( CommandSubscriber.class );

    /**
     * Dedicated audit logger for inbound commands — routed to the file
     * {@code logs/commands-<timestamp>-<env>.log} via the handler named
     * {@code commands} configured in {@code application.yml}
     * ({@code quarkus.log.handler.file.commands.*}). The routing uses
     * {@code use-parent-handlers=false}, so these lines <strong>do not
     * appear</strong> in the console or the main log — only in the
     * dedicated file.
     *
     * <p>Line format ({@code |} separator for easy grep):
     * <pre>
     *   &lt;timestamp&gt; | &lt;topic&gt; | &lt;verdict&gt; | &lt;payload-or-preview&gt; [ | &lt;err&gt; ]
     * </pre>
     * where {@code verdict} ∈ {@code ACCEPT}, {@code DROP-RETAINED},
     * {@code DROP-OVERSIZED}, {@code DROP-MALFORMED}.
     *
     * <p>Use cases: ops audit "who sent which command and when",
     * Home Assistant automation debugging, incident post-mortem without
     * grepping the noisy main log.
     */
    private static final Logger COMMANDS_LOG = Logger.getLogger( "commands" );

    @Inject
    LoxoneConfig config;
    @Inject
    MqttClient   mqtt;
    @Inject
    ObjectMapper jsonMapper;
    @Inject
    MqttMetrics  metrics;

    @Inject
    Event< MiniserverCommandEvent >                commandEvent;
    @Inject
    Event< MiniserverApiConnectorSetCommandEvent > apiSetEvent;

    /** (Re-)subscribe to the inbound topics each time MQTT establishes a
     *  session with the broker. Sync observer — runs on the firing thread
     *  (the HiveMQ worker that handled the CONNACK callback), so the
     *  subscriptions are in place before the broker can dispatch any
     *  command published during the disconnected window.
     *
     *  <p>Unsubscribe first to avoid callback accumulation. HiveMQ's
     *  async {@code subscribeWith().callback()} <em>adds</em> a callback
     *  per call; without an explicit unsubscribe in between, a reconnect
     *  leaves 2 callbacks live for the same filter, 3 after the next
     *  reconnect, etc. The bug surfaces as "1st command after reconnect
     *  works, 2nd is dropped" — QoS-1 flow-control gets confused by
     *  parallel dispatch. The unsubscribe is best-effort: the very first
     *  CONNACK has no prior subscription to drop, the broker just replies
     *  "no subscription existed" and HiveMqClient swallows it at DEBUG. */
    public void onMqttConnected( @Observes MqttConnectedEvent event )
    {
        LoxoneConfig.SubscribeSpec cmdSpec = config.transport().topics().subscribe().command();
        LoxoneConfig.SubscribeSpec apiSpec = config.transport().topics().subscribe().api();

        LOG.infof( "Resubscribing to inbound topics — command=%s (qos=%d) api=%s (qos=%d)",
                   cmdSpec.topic(), ( Integer ) cmdSpec.qos(),
                   apiSpec.topic(), ( Integer ) apiSpec.qos() );

        // Drop any stale callback from a previous CONNACK before
        // registering the new one.
        mqtt.unsubscribe( cmdSpec.topic() );
        mqtt.unsubscribe( apiSpec.topic() );

        mqtt.subscribe( cmdSpec.topic(), cmdSpec.qos(), this::onIncomingCommand );
        mqtt.subscribe( apiSpec.topic(), apiSpec.qos(), this::onIncomingApi );
    }

    /** MQTT handler for the command topic. Runs on a HiveMQ worker
     *  thread; CDI event dispatch is sync, so the SessionOrchestrator's
     *  observer runs here too — quick enough (string format + AES
     *  encrypt + ws send) that we don't bother handing off.
     *
     *  <p>Retained messages are dropped — see class javadoc § Retained
     *  -message guard. The check happens BEFORE the JSON parse so an
     *  accidentally retained <em>well-formed</em> command is also
     *  rejected. */
    void onIncomingCommand( String topic, byte[] payload, boolean retained )
    {
        if ( retained )
        {
            LOG.warnf( "Retained message dropped on command topic %s — the …/command topic must NOT carry retained payloads "
                       + "(would replay on every CONNACK and re-trigger the miniserver command). "
                       + "Publish with retain=false. Preview: %s",
                       topic, previewBody( payload ) );
            COMMANDS_LOG.infof( "%s|DROP-RETAINED|%s", topic, previewBody( payload ) );
            metrics.recordDrop( MqttMetrics.REASON_RETAINED );
            return;
        }
        if ( exceedsInboundLimit( payload ) )
        {
            LOG.warnf( "Oversized payload dropped on command topic %s — size=%d bytes exceeds "
                       + "loxone.transport.security.max-inbound-payload-bytes=%d. "
                       + "Preview: %s",
                       topic, ( Integer ) payload.length,
                       ( Integer ) config.transport().security().maxInboundPayloadBytes(),
                       previewBody( payload ) );
            COMMANDS_LOG.infof( "%s|DROP-OVERSIZED|size=%d|%s",
                                topic, ( Integer ) payload.length, previewBody( payload ) );
            metrics.recordDrop( MqttMetrics.REASON_OVERSIZED );
            return;
        }
        String body = new String( payload, StandardCharsets.UTF_8 );
        // TRACE — ⏏ between fields, ⇨ between key/value.
        LOG.tracef( "Command arrived. Topic ⇨ %s ⏏ Message ⇨ %s", topic, body );
        try
        {
            MiniserverCommand cmd = jsonMapper.readValue( body, MiniserverCommand.class );
            commandEvent.fire( new MiniserverCommandEvent( cmd ) );
            COMMANDS_LOG.infof( "%s|ACCEPT|%s", topic, body );
        }
        catch ( JsonProcessingException e )
        {
            LOG.warnf( "Could not parse command JSON on %s — dropped (payload=%s, error=%s)",
                       topic, body, e.getOriginalMessage() );
            COMMANDS_LOG.infof( "%s|DROP-MALFORMED|%s|err=%s",
                                topic, body, e.getOriginalMessage() );
            metrics.recordDrop( MqttMetrics.REASON_MALFORMED );
        }
    }

    /** MQTT handler for the API-Connector SET topic. Same retained-drop
     *  rule as the command handler. */
    void onIncomingApi( String topic, byte[] payload, boolean retained )
    {
        if ( retained )
        {
            LOG.warnf( "Retained message dropped on API topic %s — the …/api topic must NOT carry retained payloads "
                       + "(would replay on every CONNACK and re-trigger the VTI write). "
                       + "Publish with retain=false. Preview: %s",
                       topic, previewBody( payload ) );
            COMMANDS_LOG.infof( "%s|DROP-RETAINED|%s", topic, previewBody( payload ) );
            metrics.recordDrop( MqttMetrics.REASON_RETAINED );
            return;
        }
        if ( exceedsInboundLimit( payload ) )
        {
            LOG.warnf( "Oversized payload dropped on API topic %s — size=%d bytes exceeds "
                       + "loxone.transport.security.max-inbound-payload-bytes=%d. "
                       + "Preview: %s",
                       topic, ( Integer ) payload.length,
                       ( Integer ) config.transport().security().maxInboundPayloadBytes(),
                       previewBody( payload ) );
            COMMANDS_LOG.infof( "%s|DROP-OVERSIZED|size=%d|%s",
                                topic, ( Integer ) payload.length, previewBody( payload ) );
            metrics.recordDrop( MqttMetrics.REASON_OVERSIZED );
            return;
        }
        String body = new String( payload, StandardCharsets.UTF_8 );
        // TRACE — ⏏ between fields, ⇨ between key/value.
        LOG.tracef( "API connector SET arrived. Topic ⇨ %s ⏏ Message ⇨ %s", topic, body );
        try
        {
            MiniserverApiConnectorSetCommand cmd =
                    jsonMapper.readValue( body, MiniserverApiConnectorSetCommand.class );
            apiSetEvent.fire( new MiniserverApiConnectorSetCommandEvent( cmd ) );
            COMMANDS_LOG.infof( "%s|ACCEPT|%s", topic, body );
        }
        catch ( JsonProcessingException e )
        {
            LOG.warnf( "Could not parse API SET JSON on %s — dropped (payload=%s, error=%s)",
                       topic, body, e.getOriginalMessage() );
            COMMANDS_LOG.infof( "%s|DROP-MALFORMED|%s|err=%s",
                                topic, body, e.getOriginalMessage() );
            metrics.recordDrop( MqttMetrics.REASON_MALFORMED );
        }
    }

    /** True when the payload exceeds the configured cap. A zero / negative
     *  cap disables the check (operator opt-out — not recommended). */
    private boolean exceedsInboundLimit( byte[] payload )
    {
        int max = config.transport().security().maxInboundPayloadBytes();
        return max > 0 && payload != null && payload.length > max;
    }

    /** Truncate the payload for safe WARN logging — caps at 200 chars,
     *  escapes line breaks. */
    private static String previewBody( byte[] payload )
    {
        if ( payload == null || payload.length == 0 )
        { return "(empty)"; }
        int max = 200;
        String s = new String( payload, 0, Math.min( payload.length, max ), StandardCharsets.UTF_8 )
                           .replace( "\n", "\\n" ).replace( "\r", "\\r" );
        return payload.length > max ? s + "…" : s;
    }
}
