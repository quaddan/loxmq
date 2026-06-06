/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.transport;

import com.quaddan.iot.loxmq.miniserver.session.KeepAliveScheduler;
import com.quaddan.iot.loxmq.miniserver.session.MiniserverConnectedEvent;
import com.quaddan.iot.loxmq.miniserver.session.SessionTracker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;

/**
 * Exposes the binding's runtime behaviour as Prometheus-scrapeable
 * Micrometer meters at {@code /q/metrics}.
 *
 * <h3>Meters registered</h3>
 * <table>
 *   <tr><th>Type</th><th>Name</th><th>Meaning</th></tr>
 *   <tr><td>Counter</td><td>{@code binding.mqtt.publishes}</td>
 *       <td>Total outbound MQTT publishes from the binding (any topic).
 *       Pushed by {@link HiveMqClient#publish}.</td></tr>
 *   <tr><td>Counter</td><td>{@code binding.mqtt.inbound.dropped{reason}}</td>
 *       <td>Inbound messages on {@code …/command} or {@code …/api} that
 *       the binding refused to act on. {@code reason} ∈
 *       {@code retained|oversized|malformed}. Pushed by
 *       {@code CommandSubscriber}.</td></tr>
 *   <tr><td>Gauge</td><td>{@code binding.session.state.ordinal}</td>
 *       <td>Numeric ordinal of {@code SessionTracker.state()}. Maps to
 *       {@code SessionState.values()} order — 0=DISCONNECTED, etc.
 *       Polled at scrape time.</td></tr>
 *   <tr><td>Gauge</td><td>{@code binding.broker.connected}</td>
 *       <td>1 if MQTT broker connected, 0 otherwise. Polled.</td></tr>
 *   <tr><td>Gauge</td><td>{@code binding.keepalive.scheduled}</td>
 *       <td>1 if the keepalive scheduler is armed, 0 otherwise.
 *       Polled.</td></tr>
 *   <tr><td>Timer</td><td>{@code binding.miniserver.handshake.duration}</td>
 *       <td>Duration of CONNECTING → RUNNING. Observed on every
 *       {@link MiniserverConnectedEvent}, read from
 *       {@link SessionTracker#lastHandshakeDuration()}.</td></tr>
 *   <tr><td>Timer</td><td>{@code binding.miniserver.keepalive.rtt}</td>
 *       <td>Round-trip time of the {@code keepalive} command. Pushed
 *       by {@link KeepAliveScheduler} after the response observer
 *       computes {@code arrived - sent}. Three percentiles published
 *       (50/95/99) so Grafana can show a useful latency profile.</td></tr>
 * </table>
 *
 * <h3>Why push (most) instead of observe</h3>
 * Counters and Timers are push-style: the producer (e.g. the publisher
 * code site) tells the meter to {@code increment()} / {@code record(d)}.
 * Gauges are pull-style: the {@code MeterRegistry} polls a supplier on
 * each Prometheus scrape — perfect for "current state" values
 * (session state, broker connected, keepalive armed) where the
 * authoritative source already holds the value (atomic refs in
 * {@code SessionTracker}, {@code MqttClient}, {@code KeepAliveScheduler}).
 *
 * <h3>Cardinality</h3>
 * No per-topic / per-UUID tags. A miniserver with 1200 controls would
 * blow up Prometheus storage if every state-publish carried its UUID
 * as a tag. The drop counter tag {@code reason} has 3 fixed values —
 * safe. Future per-topic-category counters can be added when the
 * dashboard / Grafana panels actually need them.
 *
 * <h3>{@link Startup} — eager initialization</h3>
 * The meters are registered in {@code @PostConstruct}, which means
 * the bean must be created at startup. Without {@code @Startup}
 * Quarkus would create it lazily on first injection — and the first
 * use is likely a publish on a non-Quarkus thread (HiveMQ worker),
 * which can race against the dev-mode classloader (same pattern as
 * {@code LoxApp3Cache}, {@code StatesPublisher}).
 */
@ApplicationScoped
@Startup
public class MqttMetrics
{
    private static final Logger LOG = Logger.getLogger( MqttMetrics.class );

    static final String M_PUBLISHES        = "binding.mqtt.publishes";
    static final String M_DROPPED          = "binding.mqtt.inbound.dropped";
    static final String M_SESSION_STATE    = "binding.session.state.ordinal";
    static final String M_BROKER_CONNECTED = "binding.broker.connected";
    static final String M_KEEPALIVE_ARMED  = "binding.keepalive.scheduled";
    static final String M_HANDSHAKE_TIMER  = "binding.miniserver.handshake.duration";
    static final String M_RTT_TIMER        = "binding.miniserver.keepalive.rtt";

    public static final String REASON_RETAINED  = "retained";
    public static final String REASON_OVERSIZED = "oversized";
    public static final String REASON_MALFORMED = "malformed";

    @Inject
    MeterRegistry      registry;
    @Inject
    SessionTracker     sessionTracker;
    @Inject
    MqttClient         mqtt;
    @Inject
    KeepAliveScheduler keepAlive;

    // Push-style meters held as fields for hot-path performance: the
    // builder lookup is O(meter name + tags hash) — fine on a scrape,
    // wasteful on every publish.
    private Counter publishCounter;
    private Counter dropRetainedCounter;
    private Counter dropOversizedCounter;
    private Counter dropMalformedCounter;
    private Timer   handshakeTimer;
    private Timer   keepaliveRttTimer;

    @PostConstruct
    void register()
    {
        publishCounter = Counter.builder( M_PUBLISHES )
                                .description( "Total outbound MQTT publishes from the binding (all topics)" )
                                .baseUnit( "messages" )
                                .register( registry );

        dropRetainedCounter  = dropCounter( REASON_RETAINED );
        dropOversizedCounter = dropCounter( REASON_OVERSIZED );
        dropMalformedCounter = dropCounter( REASON_MALFORMED );

        handshakeTimer = Timer.builder( M_HANDSHAKE_TIMER )
                              .description( "Miniserver session handshake duration (CONNECTING → RUNNING)" )
                              .publishPercentiles( 0.5, 0.95, 0.99 )
                              .register( registry );

        keepaliveRttTimer = Timer.builder( M_RTT_TIMER )
                                 .description( "KeepAlive command round-trip time (link-quality indicator)" )
                                 .publishPercentiles( 0.5, 0.95, 0.99 )
                                 .register( registry );

        // Gauges via supplier — polled on each Prometheus scrape. Using
        // MeterRegistry.gauge() shortcut would mask binder failures; the
        // Gauge.builder().register() form gives us a typed reference if
        // we ever need to remove or replace at runtime.
        Gauge.builder( M_SESSION_STATE, sessionTracker, t -> ( double ) t.state().ordinal() )
             .description( "Session state ordinal (0=DISCONNECTED, …, RUNNING=7)" )
             .register( registry );

        Gauge.builder( M_BROKER_CONNECTED, mqtt, m -> m.isConnected() ? 1d : 0d )
             .description( "1 if MQTT broker connected, 0 otherwise" )
             .register( registry );

        Gauge.builder( M_KEEPALIVE_ARMED, keepAlive, ka -> ka.isScheduled() ? 1d : 0d )
             .description( "1 if the keepalive scheduler is armed, 0 otherwise" )
             .register( registry );

        LOG.infof( "MqttMetrics registered — counters=%s, timers=%s, gauges=%s",
                   "publishes/dropped",
                   "handshake/keepalive-rtt",
                   "session-state/broker-connected/keepalive-armed" );
    }

    private Counter dropCounter( String reason )
    {
        return Counter.builder( M_DROPPED )
                      .description( "Inbound MQTT messages dropped by guards (retained / oversized / malformed)" )
                      .tag( "reason", reason )
                      .baseUnit( "messages" )
                      .register( registry );
    }

    // -------------------------------------------------------------------------
    //  Push-style entry points
    // -------------------------------------------------------------------------

    /** Called by {@link HiveMqClient#publish} on every outbound publish. */
    public void recordPublish()
    {
        publishCounter.increment();
    }

    /** Called by {@code CommandSubscriber} from each of its three drop
     *  paths. Use the {@code REASON_*} constants — typos would create a
     *  fresh (unbacked) counter and silently break the metric. */
    public void recordDrop( String reason )
    {
        switch ( reason )
        {
            case REASON_RETAINED -> dropRetainedCounter.increment();
            case REASON_OVERSIZED -> dropOversizedCounter.increment();
            case REASON_MALFORMED -> dropMalformedCounter.increment();
            default -> LOG.warnf( "Unknown drop reason '%s' — counter NOT incremented. "
                                  + "Use one of: %s, %s, %s.",
                                  reason, REASON_RETAINED, REASON_OVERSIZED, REASON_MALFORMED );
        }
    }

    /** Called by {@code KeepAliveScheduler#onKeepAliveResponse} once the
     *  RTT has been computed (negative clamping already applied). */
    public void recordRtt( Duration rtt )
    {
        keepaliveRttTimer.record( rtt );
    }

    /** Sync observer on {@code MiniserverConnectedEvent} — {@code SessionOrchestrator}
     *  fires this AFTER it transitions the tracker to RUNNING, so reading
     *  {@code sessionTracker.lastHandshakeDuration()} here always sees the
     *  fresh value. {@code @ObservesAsync} (default project convention)
     *  keeps the WS reader thread unblocked. */
    void onMiniserverConnected( @ObservesAsync MiniserverConnectedEvent event )
    {
        sessionTracker.lastHandshakeDuration().ifPresent( handshakeTimer::record );
    }
}
