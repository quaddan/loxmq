/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.transport;

import com.quaddan.iot.loxmq.config.LoxoneConfig;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;

/**
 * Wires the MQTT client lifecycle to Quarkus's {@code StartupEvent} /
 * {@code ShutdownEvent}.
 *
 * <h3>Startup</h3>
 * If {@code loxone.transport.auto-connect=true}, opens the broker
 * connection. Failures are logged but don't fail the boot — the operator
 * can fix the broker side and re-trigger via the management API. Same
 * "fail soft on boot" stance as the miniserver session.
 *
 * <h3>Shutdown — strict ordering</h3>
 * Two cleanup steps that MUST run sequentially, in this order :
 * <ol>
 *   <li>{@link MqttReconnectScheduler#shutdownAndAwait} — drains any in-flight
 *       reconnect attempt so no {@code mqtt.connect()} runs concurrently
 *       with the disconnect.</li>
 *   <li>{@link MqttClient#disconnect()} — publishes the offline status
 *       and sends a clean DISCONNECT packet (no LWT triggered).</li>
 * </ol>
 *
 * <p><b>Why not two independent {@code @Observes ShutdownEvent} handlers</b> —
 * CDI does not guarantee ordering between observers of the same event.
 * Running scheduler-shutdown and broker-disconnect in parallel races on
 * the HiveMQ client state and leaves the broker with a phantom session
 * (TCP not FIN-acked before the JVM exits) — at the next process start,
 * the new {@code CONNECT} with the same {@code clientId} collides with
 * the broker's view of the still-alive session. Centralising the
 * ordering here is the only robust fix.
 */
@ApplicationScoped
public class TransportLifecycle
{
    private static final Logger LOG = Logger.getLogger( TransportLifecycle.class );

    /** Grace period given to the reconnect scheduler to drain its
     *  in-flight tasks before we force the shutdown. Slightly longer
     *  than the {@code mqtt.connect()} timeout (3 s by default) so a
     *  task that started just before the SIGTERM gets the chance to
     *  finish gracefully. */
    private static final Duration SCHEDULER_DRAIN_TIMEOUT = Duration.ofSeconds( 5 );

    @Inject
    LoxoneConfig           config;
    @Inject
    MqttClient             mqtt;
    @Inject
    MqttReconnectScheduler scheduler;

    void onStart( @Observes StartupEvent event )
    {
        // When the master switch boot.auto-start=true, the BootAutoStarter
        // bean owns the boot chain — MQTT, bootstrap and session in strict
        // order. Skip our own per-service auto-connect to avoid a double
        // connect attempt on the broker.
        if ( config.boot().autoStart() )
        {
            LOG.debug( "transport.auto-connect skipped — boot.auto-start=true orchestrates MQTT" );
            return;
        }

        if ( !config.transport().autoConnect() )
        {
            LOG.info( "transport.auto-connect=false — call POST /api/v1/transport/connect to bring MQTT up" );
            return;
        }
        try
        {
            mqtt.connect();
        }
        catch ( TransportException e )
        {
            // Don't fail boot — operator can retry via management API
            // after fixing the broker / network / credentials.
            LOG.warnf( "MQTT auto-connect failed: %s. The binding stays up; fix the broker side and POST /api/v1/transport/connect.",
                       e.getMessage() );
        }
    }

    void onStop( @Observes ShutdownEvent event )
    {
        LOG.debug( "ShutdownEvent → draining MqttReconnectScheduler before mqtt.disconnect()" );
        scheduler.shutdownAndAwait( SCHEDULER_DRAIN_TIMEOUT );
        mqtt.disconnect();
    }
}
