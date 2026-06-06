/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.transport.publish;

import com.quaddan.iot.loxmq.config.LoxoneConfig;
import com.quaddan.iot.loxmq.config.LoxoneConfigHolder;
import com.quaddan.iot.loxmq.miniserver.session.LoxApp3Cache;
import com.quaddan.iot.loxmq.miniserver.session.MiniserverConnectedEvent;
import com.quaddan.iot.loxmq.miniserver.session.SessionState;
import com.quaddan.iot.loxmq.miniserver.session.SessionTracker;
import com.quaddan.iot.loxmq.transport.MqttClient;
import com.quaddan.iot.loxmq.transport.MqttConnectedEvent;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Republishes the cached {@code LoxAPP3.json} structure file on the
 * configured {@code lox-app3} topic when the session reaches RUNNING.
 * Retained — downstream consumers (e.g. Home Assistant integrations
 * that auto-generate entities from the Loxone control catalogue) can
 * subscribe once and immediately see the current topology without
 * having to call any binding REST endpoint.
 *
 * <p>The payload is the verbatim JSON bytes from the cache — same shape
 * the Miniserver served to us during the handshake (after the
 * {@code data/LoxAPP3.json} fetch + cache-by-lastModified path).
 * Typical size on a real install: ~250 KB.
 *
 * <h3>What if the cache is empty</h3>
 * Shouldn't happen — the session orchestrator only reaches RUNNING after
 * either fetching the structure file (cache miss) or loading from cache
 * (cache hit). Both paths populate {@link LoxApp3Cache}. Defensive:
 * we log a WARN and skip the publish if cache.load() returns empty.
 *
 * <h3>Why {@code @Singleton} + holder</h3>
 * Same workaround as {@link StatesPublisher} / {@link AppInfoPublisher}:
 * ArC's @ConfigMapping synthetic-bean generation fails for beans that
 * combine {@code @ObservesAsync} + {@code @Inject @ConfigMapping}.
 */
@Singleton
public class LoxApp3Publisher
{
    private static final Logger LOG = Logger.getLogger( LoxApp3Publisher.class );

    @Inject
    LoxoneConfigHolder configHolder;
    @Inject
    MqttClient         mqtt;
    @Inject
    LoxApp3Cache       cache;
    @Inject
    SessionTracker     sessionTracker;

    private LoxoneConfig config() { return configHolder.get(); }

    public void onMiniserverConnected( @ObservesAsync MiniserverConnectedEvent event )
    {
        publish( "MiniserverConnected" );
    }

    /**
     * Also republish on MQTT reconnect.
     *
     * <p>Same rationale as
     * {@code AppInfoPublisher#onMqttConnected} : after a broker restart
     * (or any auto-reconnect cycle), the retained {@code lox_app3} message
     * may be gone, and the Miniserver session staying up means no fresh
     * {@link MiniserverConnectedEvent} fires.
     *
     * <p>Republishing a ~250 KB JSON on each reconnect is a measurable
     * cost — but reconnects are rare events (broker restart, network
     * blip) so the amortised cost is low. The retained-message safety
     * for downstream consumers (HA auto-discovery etc.) is worth it.
     *
     * <p>Guard against firing before the session is up — at boot, the
     * MQTT CONNACK may land before the Miniserver handshake completes ;
     * the cache is empty then, the normal MiniserverConnected path will
     * publish once the session reaches RUNNING.
     */
    public void onMqttConnected( @Observes MqttConnectedEvent event )
    {
        if ( sessionTracker.state() != SessionState.RUNNING )
        {
            LOG.debug( "MQTT (re)connected but session not RUNNING — defer LoxAPP3 republish" );
            return;
        }
        publish( "MqttReconnect" );
    }

    private void publish( String trigger )
    {
        if ( !mqtt.isConnected() )
        {
            LOG.debugf( "MQTT not connected (trigger=%s) — LoxAPP3 publish skipped", trigger );
            return;
        }
        Optional< String > content = cache.load();
        if ( content.isEmpty() )
        {
            LOG.warnf( "LoxApp3 cache is empty (trigger=%s) — skipping retained publish " +
                       "(orchestrator shouldn't reach RUNNING without populating it; investigate if you see this)",
                       trigger );
            return;
        }

        var    spec = config().transport().topics().publish().loxApp3();
        byte[] body = content.get().getBytes( StandardCharsets.UTF_8 );
        mqtt.publish( spec.topic(), spec.qos(), spec.retain(), body );
        LOG.infof( "🚂 LoxAPP3 published (trigger=%s) ⇨ Topic ⇨ %s ⏏ %d bytes ⏏ Retained ⇨ %s",
                   trigger, spec.topic(), ( Integer ) body.length, ( Boolean ) spec.retain() );
    }
}
