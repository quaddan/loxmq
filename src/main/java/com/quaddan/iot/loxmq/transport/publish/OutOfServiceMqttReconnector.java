/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.transport.publish;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quaddan.iot.loxmq.config.LoxoneConfig;
import com.quaddan.iot.loxmq.config.LoxoneConfigHolder;
import com.quaddan.iot.loxmq.miniserver.message.MiniserverOutOfServiceEvent;
import com.quaddan.iot.loxmq.transport.MqttClient;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Single owner of the "Miniserver out-of-service" → MQTT side-effects:
 * publishes a one-shot notification on the configured {@code out-of-service}
 * topic (JSON: timestamp, source app id, reason).
 *
 * <h3>Why MQTT is NOT disconnected on OOS</h3>
 * Originally this observer also called
 * {@code MqttReconnectScheduler.triggerOutOfServiceReconnect()} which
 * closed the broker session and scheduled a reconnect 30 s later. The
 * intent was to "synchronise MQTT with the miniserver" — flush a LWT-style
 * {@code …/status=offline} so downstream consumers had a clean signal.
 *
 * <p>That turned out to be the wrong abstraction.
 * MQTT and Miniserver are <b>independent dependencies</b>:
 * <ul>
 *   <li>The broker keeps running fine while the miniserver firmware reboots.</li>
 *   <li>Disconnecting MQTT triggered a 30 s service hole for downstream
 *       consumers — they saw {@code status=offline} and assumed the
 *       <i>entire</i> binding was down, when only the miniserver leg was.</li>
 *   <li>Any {@code …/command} / {@code …/api} messages published during the
 *       reconnect window were lost (broker doesn't queue them, the
 *       binding subscribes with {@code cleanStart=true}).</li>
 * </ul>
 *
 * <p>Current behaviour: OOS is treated as a <b>miniserver-only</b>
 * transient. The MQTT session stays up; we just publish the OOS
 * notification so downstream consumers can branch their own state
 * machine (e.g. "stop polling sensors for a moment"). The miniserver
 * reconnect loop ({@code ReconnectScheduler} in the session package,
 * which also handles the sync-fail edge case) re-establishes the WS
 * once the firmware is back, and steady-state resumes — no MQTT trip.
 *
 * <h3>{@code @Singleton} + holder</h3>
 * Same workaround as {@link StatesPublisher} — ArC's synthetic-bean code
 * generation for {@code @ConfigMapping} fails when the bean has
 * {@code @ObservesAsync} methods. See {@code StatesPublisher} javadoc
 * and {@code LoxoneConfigHolder} javadoc for the full story.
 */
@Singleton
public class OutOfServiceMqttReconnector
{
    private static final Logger LOG = Logger.getLogger( OutOfServiceMqttReconnector.class );

    @Inject
    LoxoneConfigHolder configHolder;
    @Inject
    MqttClient         mqtt;
    @Inject
    ObjectMapper       jsonMapper;

    /** Shorthand for the cached config (see class javadoc). */
    private LoxoneConfig config() { return configHolder.get(); }

    public void onOutOfService( @ObservesAsync MiniserverOutOfServiceEvent event )
    {
        LOG.infof( "MiniserverOutOfServiceEvent observed (ts=%d) — publishing notification (MQTT session left intact)",
                   ( Long ) event.timestamp() );

        // Single side-effect: notify subscribers via a non-retained one-shot
        // on the configured `out-of-service` topic. The MQTT session itself
        // stays UP — the miniserver reconnect is the session orchestrator's
        // job (ReconnectScheduler in the session package), and the broker
        // has no business being torn down for a remote miniserver glitch.
        publishOutOfServiceNotification( event );
    }

    private void publishOutOfServiceNotification( MiniserverOutOfServiceEvent event )
    {
        var spec = config().transport().topics().publish().outOfService();
        if ( !mqtt.isConnected() )
        {
            LOG.debug( "OOS notification skipped — MQTT not connected" );
            return;
        }
        Map< String, Object > payload = new LinkedHashMap<>();
        payload.put( "timestamp", Instant.ofEpochMilli( event.timestamp() ).toString() );
        payload.put( "source", config().miniserver().app().id() );
        payload.put( "reason", "miniserver-out-of-service" );
        try
        {
            byte[] body = jsonMapper.writeValueAsBytes( payload );
            mqtt.publish( spec.topic(), spec.qos(), spec.retain(), body );
            LOG.infof( "🚂 OOS notification published ⇨ Topic ⇨ %s ⏏ %d bytes",
                       spec.topic(), ( Integer ) body.length );
        }
        catch ( JsonProcessingException e )
        {
            LOG.warnf( e, "OOS notification serialisation failed" );
        }
    }
}
