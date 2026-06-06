/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.transport.publish;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quaddan.iot.loxmq.config.LoxoneConfig;
import com.quaddan.iot.loxmq.config.LoxoneConfigHolder;
import com.quaddan.iot.loxmq.miniserver.identity.MiniserverIdentity;
import com.quaddan.iot.loxmq.miniserver.session.MiniserverConnectedEvent;
import com.quaddan.iot.loxmq.miniserver.session.SessionState;
import com.quaddan.iot.loxmq.miniserver.session.SessionTracker;
import com.quaddan.iot.loxmq.miniserver.state.MiniserverState;
import com.quaddan.iot.loxmq.transport.MqttClient;
import com.quaddan.iot.loxmq.transport.MqttConnectedEvent;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Republishes binding + miniserver metadata on the configured
 * {@code app_info} topic when the session reaches RUNNING. Retained by
 * default — downstream consumers can subscribe to
 * {@code …/<app.id>/app_info} and immediately see the bridge's
 * identity, miniserver hardware info, and profile.
 *
 * <h3>Payload shape</h3>
 * Stable schema consumed downstream (e.g. Home Assistant integrations that
 * read {@code .appInfo.name} / {@code .appInfo.version} /
 * {@code .appInfo.start}):
 * <pre>{@code
 * {
 *   "appInfo": {
 *     "name":    "loxmq PRODUCTION",
 *     "version": "1.0.0",
 *     "start":   "Tue 19/05/2026 13:00:00:000 CEST +0200"
 *   },
 *   "miniserver": {       // full identity block. Consumers ignore
 *     "serial":      "50:4F:94:AA:BB:CC",         // unknown top-level keys,
 *     "version":     "17.0.3.31",                 // so adding this stays
 *     "generation":  "GEN2",                      // backward-compatible.
 *     "httpsStatus": "SUPPORTED",
 *     "address":     "192.0.2.10",
 *     "local":       true
 *   }
 * }
 * }</pre>
 * Fields:
 * <ul>
 *   <li>{@code appInfo.name} — the operator-set
 *       {@code loxone.miniserver.app.info} (per-profile descriptive name,
 *       e.g. "loxmq PRODUCTION"). NOT the Quarkus
 *       application name — operators want the environment qualifier in
 *       the name, which only the app.info config carries.</li>
 *   <li>{@code appInfo.version} — Quarkus {@code application.version}
 *       (e.g. {@code 1.0.0}).</li>
 *   <li>{@code appInfo.start} — session-start wall-clock time formatted
 *       as {@code EEE dd/MM/yyyy HH:mm:ss:SSS zzz xxxx} in the JVM's
 *       default timezone. Locale forced to ENGLISH so day abbreviations
 *       are stable ("Sat" not "sam.").</li>
 *   <li>{@code miniserver} — full identity block.
 *       Useful for monitoring dashboards that want serial/version/
 *       generation without parsing the {@code app.info} string.</li>
 * </ul>
 *
 * <h3>Why {@code @Singleton} + {@code @Startup} + holder</h3>
 * Two stacked ArC + SmallRye workarounds, both root-caused to the same
 * class of bug:
 *
 * <ol>
 *   <li><b>{@code @ConfigMapping}</b> ({@link LoxoneConfig}) — synthetic
 *       producer bean generation breaks when combined with
 *       {@code @ObservesAsync}. We go through {@link LoxoneConfigHolder}
 *       (itself eagerly created with {@code @Startup}) instead of
 *       injecting {@code LoxoneConfig} directly. Same workaround as
 *       {@link StatesPublisher} / {@link LoxApp3Publisher}.</li>
 *   <li><b>{@code @ConfigProperty}</b> ({@link #bindingVersion}) —
 *       this bean had been failing silently in dev with
 *       {@code SRCFG00015: No configuration is available for this class
 *       loader}, observed in a stack trace from
 *       {@code DefaultAsyncObserverExceptionHandler}. Root cause: ArC
 *       lazy-creates the bean on the executor-thread that delivers the
 *       async event, and on that thread the {@code
 *       SmallRyeConfigProviderResolver} isn't bound to the thread
 *       context classloader yet. {@code @Startup} forces creation on
 *       the Quarkus main thread during boot, where the resolver IS
 *       bound; once {@link #bindingVersion} is set in the field, the
 *       async-thread path just reads it.</li>
 * </ol>
 *
 * {@link LoxApp3Publisher} doesn't need {@code @Startup} because it has
 * no {@code @ConfigProperty} — only {@code @Inject} dependencies, which
 * ArC resolves through its own machinery without SmallRye.
 */
@Singleton
@Startup
public class AppInfoPublisher
{
    private static final Logger LOG = Logger.getLogger( AppInfoPublisher.class );

    /** Date format kept stable so downstream consumers that parse
     *  {@code .appInfo.start} see a consistent shape. Example output:
     *  {@code "Tue 19/05/2026 13:00:00:000 CEST +0200"}.
     *  <p>Locale forced to English so day abbreviations are "Sat" /
     *  "Sun" / etc. regardless of JVM locale. */
    private static final DateTimeFormatter START_FORMAT =
            DateTimeFormatter.ofPattern( "EEE dd/MM/yyyy HH:mm:ss:SSS zzz xxxx", Locale.ENGLISH );

    @Inject
    LoxoneConfigHolder configHolder;
    @Inject
    MqttClient         mqtt;
    @Inject
    ObjectMapper       jsonMapper;
    @Inject
    SessionTracker     sessionTracker;
    @Inject
    MiniserverState    miniserverState;

    /** Quarkus auto-injects this from the Maven artifact version at
     *  build time. The {@code defaultValue} is purely defensive — in dev
     *  mode after a class-reload, occasional resolution glitches have
     *  been observed where the property comes back unresolved; "unknown"
     *  is far less harmful than a startup deployment failure or a silent
     *  NPE later in {@link #buildPayload}. */
    @ConfigProperty( name = "quarkus.application.version", defaultValue = "unknown" )
    String bindingVersion;

    private LoxoneConfig config() { return configHolder.get(); }

    public void onMiniserverConnected( @ObservesAsync MiniserverConnectedEvent event )
    {
        publish( event.timestamp(), event.identity(), "MiniserverConnected" );
    }

    /**
     * Also republish on MQTT reconnect.
     *
     * <p>When the broker restarts (and persistence is off, or even just
     * to be safe for downstream consumers caching their own copy), the
     * retained {@code app_info} message is lost. The binding's Miniserver
     * session typically stays up across an MQTT blip — so no fresh
     * {@link MiniserverConnectedEvent} fires, and without this hook the
     * app_info would never be re-pushed until the next full session
     * restart.
     *
     * <p>Re-publishing on every {@link MqttConnectedEvent} (initial CONNACK
     * + every auto-reconnect CONNACK) is cheap and idempotent : the payload
     * is small (< 1 KB), the {@code start} timestamp comes from
     * {@link SessionTracker#connectedAt()} so it stays anchored on the
     * original session-establish moment, not the MQTT reconnect moment.
     *
     * <p>Skip if the Miniserver session isn't RUNNING — there's nothing
     * meaningful to publish yet; the regular MiniserverConnected path
     * will catch it as soon as the handshake completes.
     */
    public void onMqttConnected( @Observes MqttConnectedEvent event )
    {
        if ( sessionTracker.state() != SessionState.RUNNING )
        {
            LOG.debug( "MQTT (re)connected but session not RUNNING — defer app-info republish" );
            return;
        }
        // Use the original session-establish timestamp so the
        // {@code .appInfo.start} downstream parsers see remains stable
        // across MQTT blips. orElse(now) is defensive — if connectedAt
        // is somehow null while state is RUNNING (shouldn't happen) we
        // don't crash the reconnect path.
        Instant start = sessionTracker.connectedAt().orElse( Instant.now() );
        publish( start, miniserverState.identity(), "MqttReconnect" );
    }

    /** Common publish path — shared by the MiniserverConnected
     *  (first-establish) and MqttConnected (reconnect republish) entry
     *  points. */
    private void publish( Instant connectedAt,
                          Optional< MiniserverIdentity > identity,
                          String trigger )
    {
        if ( !mqtt.isConnected() )
        {
            LOG.debugf( "MQTT not connected (trigger=%s) — app-info publish skipped", trigger );
            return;
        }
        var spec = config().transport().topics().publish().appInfo();
        try
        {
            byte[] body = jsonMapper.writeValueAsBytes( buildPayload( connectedAt, identity ) );
            mqtt.publish( spec.topic(), spec.qos(), spec.retain(), body );
            LOG.infof( "🚂 app-info published (trigger=%s) ⇨ Topic ⇨ %s ⏏ %d bytes ⏏ Retained ⇨ %s",
                       trigger, spec.topic(), ( Integer ) body.length, ( Boolean ) spec.retain() );
        }
        catch ( JsonProcessingException e )
        {
            LOG.warnf( e, "app-info serialisation failed (trigger=%s) — dropping", trigger );
        }
        // Catch-all: any unexpected RuntimeException in buildPayload (NPE
        // from a half-resolved config tree, DateTimeException from the
        // formatter on an exotic zone, etc.) would otherwise propagate
        // to ArC's async observer exception handler and be logged at
        // DEBUG with no class context — i.e. effectively swallowed.
        // Log it loudly here with the spec we were trying to publish on,
        // so any future regression shows up in the same place as the
        // success line above.
        catch ( RuntimeException e )
        {
            LOG.errorf( e, "app-info publish failed unexpectedly (trigger=%s) on %s — dropping",
                        trigger, spec.topic() );
        }
    }

    private Map< String, Object > buildPayload( Instant connectedAt,
                                                Optional< MiniserverIdentity > identity )
    {
        // appInfo block: name + version + start. Downstream consumers
        // parsing .appInfo.* keep working.
        Map< String, Object > appInfo = new LinkedHashMap<>();
        appInfo.put( "name", config().miniserver().app().info() );
        appInfo.put( "version", bindingVersion );
        appInfo.put( "start", connectedAt
                                      .atZone( ZoneId.systemDefault() )
                                      .format( START_FORMAT ) );

        Map< String, Object > payload = new LinkedHashMap<>();
        payload.put( "appInfo", appInfo );
        // Optional miniserver identity block — consumers can opt in to it.
        identity.ifPresent( id -> payload.put( "miniserver", identityPayload( id ) ) );
        return payload;
    }

    private static Map< String, Object > identityPayload( MiniserverIdentity id )
    {
        Map< String, Object > m = new LinkedHashMap<>();
        m.put( "serial", id.serial() );
        m.put( "version", id.version().toString() );
        m.put( "generation", id.generation().name() );
        m.put( "httpsStatus", id.httpsStatus().name() );
        m.put( "address", id.address() );
        m.put( "local", id.local() );
        return m;
    }
}
