/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.boot;

import com.quaddan.iot.loxmq.transport.TransportLifecycle;
import com.quaddan.iot.loxmq.config.LoxoneConfig;
import com.quaddan.iot.loxmq.miniserver.bootstrap.BootstrapOrchestrator;
import com.quaddan.iot.loxmq.miniserver.session.SessionOrchestrator;
import com.quaddan.iot.loxmq.transport.MqttClient;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.function.IntConsumer;

/**
 * Master boot orchestrator.
 * <p>
 * When {@code loxone.boot.auto-start=true}, runs the production boot chain
 * <strong>MQTT → Miniserver bootstrap → Miniserver session</strong> at
 * {@link StartupEvent}, in strict order. Each step is synchronous; a
 * failure at any step logs WARN and aborts the rest of the chain — the
 * binding stays up so the operator can recover via the management API.
 *
 * <h3>Why a dedicated bean?</h3>
 * CDI gives no ordering guarantee between {@code @Observes StartupEvent}
 * handlers. {@link TransportLifecycle}
 * (MQTT) and {@link Application} (Miniserver) each observe
 * {@code StartupEvent} independently — with {@code transport.auto-connect=true}
 * AND {@code miniserver.auto-connect=true} they would fire in arbitrary
 * order, sometimes producing the "session ready / MQTT not yet" race that
 * silently drops {@code app_info} + {@code loxapp3} retained publishes.
 *
 * <p>This bean consolidates the chain into a single observer. When
 * {@code boot.auto-start=true}, the other two observers short-circuit
 * themselves (they check the same flag) and yield to us — guaranteeing
 * a deterministic boot sequence.
 *
 * <h3>Failure semantics</h3>
 * <ul>
 *   <li><b>MQTT fails</b> — bootstrap + session are skipped. The
 *       miniserver side can still be triggered manually
 *       ({@code POST /api/v1/bootstrap} + {@code /api/v1/connect}); state
 *       publishes will be dropped until {@code /api/v1/transport/connect}
 *       brings MQTT up.</li>
 *   <li><b>Bootstrap fails</b> — session is skipped (bootstrap supplies
 *       the miniserver public key + identity, both required for the WS
 *       handshake). Operator path:
 *       {@code POST /api/v1/bootstrap} once the miniserver is reachable,
 *       then {@code /api/v1/connect}.</li>
 *   <li><b>Session fails</b> — chain done. The miniserver-side
 *       reconnect scheduler (see {@code loxone.miniserver.reconnect.*})
 *       keeps retrying in the background.</li>
 * </ul>
 *
 * <h3>Priority</h3>
 * Higher-priority observers run later for {@code StartupEvent} (Quarkus
 * docs §ArC lifecycle). We pick {@code Integer.MAX_VALUE - 100} so we
 * fire after every other startup observer has run its synchronous
 * setup — minimises the chance of racing against a not-yet-built bean.
 * We do not need to run before any specific bean.
 *
 * @see LoxoneConfig.Boot
 */
@ApplicationScoped
public class BootAutoStarter
{
    private static final Logger LOG = Logger.getLogger( BootAutoStarter.class );

    /** MQTT step timeout — connect() is sync, this is just a sanity log
     *  budget. The HiveMQ client carries its own connect timeout from
     *  {@code loxone.transport.connection.connect-timeout}. */
    private static final int SESSION_HANDSHAKE_TIMEOUT_SECONDS = 30;

    @Inject
    LoxoneConfig          config;
    @Inject
    MqttClient            mqtt;
    @Inject
    BootstrapOrchestrator bootstrap;
    @Inject
    SessionOrchestrator   session;

    /** Hook for first-attempt boot failure. Default delegates to
     *  {@link Quarkus#asyncExit(int)} so systemd's
     *  {@code Restart=on-failure} policy kicks in. Tests override by
     *  assigning a recording {@link IntConsumer} — they MUST NOT trigger
     *  a real JVM exit during a unit run. Package-private to keep the
     *  surface narrow. */
    IntConsumer shutdownTrigger = Quarkus::asyncExit;

    /** Dev mode override. {@link Quarkus#asyncExit} in
     *  {@link LaunchMode#DEVELOPMENT} stops the application but keeps the
     *  JVM alive for hot-reload (port stays bound, dev-mode prompt
     *  remains). We detect that and log clearly instead of misleading
     *  the operator with "exiting JVM with code 1" which doesn't happen
     *  in dev. Package-private for the same testability reason. */
    java.util.function.Supplier< LaunchMode > launchModeSupplier = LaunchMode::current;

    /** Fire after the other StartupEvent observers — see class javadoc. */
    void onStart( @Observes @Priority( Integer.MAX_VALUE - 100 ) StartupEvent event )
    {
        if ( !config.boot().autoStart() )
        {
            LOG.info( "boot.auto-start=false — startup chain idle. Use the management API or the dashboard." );
            return;
        }

        LOG.info( "boot.auto-start=true → orchestrating MQTT → Bootstrap → Miniserver" );

        boolean haltOnFailure = config.boot().haltOnFailure();

        // ── Step 1/3: MQTT ────────────────────────────────────────────
        // TransportException is a RuntimeException — catching the
        // superclass also covers any unexpected unchecked failure from
        // the underlying HiveMQ client (e.g. native networking).
        try
        {
            mqtt.connect();
            LOG.info( "Boot 1/3 ✓ MQTT connected" );
        }
        catch ( RuntimeException e )
        {
            LOG.warnf( e, "Boot 1/3 ✗ MQTT connect failed — aborting the chain. "
                          + "Recover via POST /api/v1/transport/connect once the broker is reachable." );
            haltIfConfigured( haltOnFailure, "MQTT connect" );
            return;
        }

        // ── Step 2/3: HTTP bootstrap (apiKey + getPublicKey) ──────────
        // BootstrapException extends RuntimeException — single catch.
        try
        {
            bootstrap.run();
            LOG.info( "Boot 2/3 ✓ Miniserver bootstrap complete" );
        }
        catch ( RuntimeException e )
        {
            LOG.warnf( e, "Boot 2/3 ✗ Bootstrap failed — session not attempted. "
                          + "Recover via POST /api/v1/bootstrap then POST /api/v1/connect." );
            haltIfConfigured( haltOnFailure, "Bootstrap" );
            return;
        }

        // ── Step 3/3: WebSocket session handshake ─────────────────────
        // SessionException extends RuntimeException — single catch.
        try
        {
            session.connectAndWait( SESSION_HANDSHAKE_TIMEOUT_SECONDS );
            LOG.info( "Boot 3/3 ✓ Miniserver session RUNNING — binding fully up" );
        }
        catch ( RuntimeException e )
        {
            LOG.warnf( e, "Boot 3/3 ✗ Session handshake failed. "
                          + "The miniserver reconnect scheduler will keep retrying (see loxone.miniserver.reconnect.*)." );
            haltIfConfigured( haltOnFailure, "Session handshake" );
        }
    }

    /**
     * Fail-fast on first-attempt boot failure.
     * <p>
     * Once the binding has reached Boot 3/3 ✓ RUNNING, this method is
     * never called again — subsequent WS drops go through
     * {@code ReconnectScheduler} which retries forever and never
     * triggers a JVM exit. The asymmetry is by design: first-attempt
     * failures are almost always config / infrastructure errors that
     * a retry loop couldn't fix; in-flight drops are usually transient
     * network blips that retry handles cleanly.
     */
    private void haltIfConfigured( boolean haltOnFailure, String stepName )
    {
        if ( !haltOnFailure )
        {
            LOG.debugf( "halt-on-failure=false → binding stays up after %s failure. "
                        + "Use the management API to retry.", stepName );
            return;
        }
        // Quarkus.asyncExit() in DEVELOPMENT mode only triggers a soft
        // application stop; the dev-mode JVM keeps running so hot-reload
        // can re-launch after the operator fixes the config. The port
        // stays bound and `systemctl restart` semantics don't apply.
        // Log clearly instead of misleading the operator.
        LaunchMode mode = launchModeSupplier.get();
        if ( mode == LaunchMode.DEVELOPMENT )
        {
            LOG.warnf( "halt-on-failure=true but running in DEV mode → JVM exit suppressed. "
                       + "The Quarkus app has stopped after %s failure (offline retained published, "
                       + "MQTT disconnected). The dev-mode JVM stays alive for hot-reload — fix the "
                       + "config and dev mode will rebuild + relaunch. To test the real exit(1) "
                       + "behaviour, run the packaged JAR: "
                       + "`mvn -DskipTests package && java -jar target/quarkus-app/quarkus-run.jar`",
                       stepName );
            return;
        }
        LOG.errorf( "halt-on-failure=true → exiting JVM with code 1 after %s failure. "
                    + "systemd's Restart=on-failure will pick up; "
                    + "fix the config before the StartLimitBurst gives up.",
                    stepName );
        shutdownTrigger.accept( 1 );
    }
}
