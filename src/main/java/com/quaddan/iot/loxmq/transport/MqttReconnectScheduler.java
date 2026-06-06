/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.transport;

import com.quaddan.iot.loxmq.config.LoxoneConfig;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Schedules MQTT reconnect attempts with a custom timing profile, used by
 * the binding's out-of-service handler. Sits alongside HiveMQ's built-in
 * auto-reconnect (which keeps handling transient broker drops on its own).
 *
 * <h3>⚠️ NOT INVOKED FROM PRODUCTION CODE</h3>
 * Originally fired by {@code OutOfServiceMqttReconnector} when a
 * {@code MiniserverOutOfServiceEvent} arrived — to flush a LWT-style
 * "offline" and reconnect 30 s later. That call site was later removed:
 * MQTT and Miniserver are independent dependencies, and disconnecting
 * the broker for a remote miniserver reboot left downstream consumers
 * (Home Assistant, etc.) seeing {@code status=offline} for 30 s + losing
 * any {@code …/command}+{@code …/api} messages published during the
 * reconnect window. The bean is left in the codebase (and its tests
 * still pass) so an operator can re-enable the behaviour later without
 * digging through git history, but no production path currently calls
 * {@link #triggerOutOfServiceReconnect()}.
 *
 * <h3>Trigger (historical)</h3>
 * On a {@code MiniserverOutOfServiceEvent}: the binding closed the MQTT
 * session cleanly (so the LWT-style "offline" presence was published),
 * waited {@code initial-delay} (default 30 s — typical miniserver reboot
 * time), then attempted {@code mqtt.connect()}. On {@link TransportException}
 * the scheduler retried every {@code retry-interval} (default 15 s) until
 * the binding either reconnected or the operator manually disabled it.
 *
 * <h3>Why a dedicated scheduler (instead of HiveMQ auto-reconnect)</h3>
 * HiveMQ's {@code automaticReconnect()} is governed by a fixed builder-time
 * exponential profile. We want a <i>different</i> profile (long initial wait,
 * fixed retry interval) for one specific cause — OutOfService — without
 * touching the routine reconnect path. The cleanest separation is to
 * suppress HiveMQ's auto-reconnect for our explicit {@code disconnect()}
 * (which it does by default: a user-initiated disconnect never triggers
 * auto-reconnect) and run our own loop on top.
 *
 * <h3>Single-thread daemon</h3>
 * Same pattern as {@code ReconnectScheduler} for the miniserver side:
 * single-thread {@link ScheduledExecutorService}, daemon thread so the JVM
 * can shut down even if a retry is pending. Concurrent {@code trigger}
 * calls coalesce on the {@code pending} field — only one reconnect chain
 * runs at a time.
 */
@ApplicationScoped
@Startup
public class MqttReconnectScheduler
{
    private static final Logger LOG = Logger.getLogger( MqttReconnectScheduler.class );

    @Inject
    LoxoneConfig config;
    @Inject
    MqttClient   mqtt;

    private          ScheduledExecutorService executor;
    /** The pending reconnect (single-flight). Null when idle. */
    private volatile ScheduledFuture< ? >     pending;
    /** How many retry attempts the current chain has made (telemetry-only). */
    private final    AtomicInteger            attemptCount = new AtomicInteger();

    @PostConstruct
    void init()
    {
        executor = Executors.newSingleThreadScheduledExecutor( r ->
                                                               {
                                                                   Thread t = new Thread( r, "loxone-mqtt-reconnect" );
                                                                   t.setDaemon( true );
                                                                   return t;
                                                               } );
        LOG.debug( "MqttReconnectScheduler ready" );
    }

    /**
     * Graceful shutdown : cancel any pending reconnect, then wait for the
     * executor to drain. Called by {@link TransportLifecycle#onStop} BEFORE
     * {@code mqtt.disconnect()} to guarantee no in-flight {@code tryReconnect()}
     * runs concurrently with the disconnect — which would leave HiveMQ
     * in a torn state (connect + disconnect racing on the same client
     * instance) and a phantom session on the broker side at next boot.
     *
     * @param timeout  max wait for in-flight tasks to finish before
     *                 forcing {@link ScheduledExecutorService#shutdownNow()}.
     *                 5 s is a reasonable default (a single
     *                 {@code mqtt.connect()} times out at 3 s in the
     *                 baseline config).
     */
    public void shutdownAndAwait( Duration timeout )
    {
        cancel();
        if ( executor == null )
        {
            return;
        }
        executor.shutdown();    // refuse new tasks, let in-flight finish
        try
        {
            if ( !executor.awaitTermination( timeout.toMillis(), TimeUnit.MILLISECONDS ) )
            {
                LOG.warnf( "MqttReconnectScheduler executor did not terminate in %s — forcing shutdownNow",
                           timeout );
                executor.shutdownNow();
            }
        }
        catch ( InterruptedException ie )
        {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    /**
     * Disconnects MQTT (best-effort) then schedules a reconnect chain:
     * one attempt after {@link LoxoneConfig.Transport.Reconnection.OutOfService#initialDelay()},
     * then retries every {@link LoxoneConfig.Transport.Reconnection.OutOfService#retryInterval()}
     * on each failure. Idempotent: if a chain is already running, this is
     * logged and ignored.
     */
    public synchronized void triggerOutOfServiceReconnect()
    {
        if ( pending != null && !pending.isDone() )
        {
            LOG.debug( "MQTT out-of-service reconnect already scheduled — ignoring duplicate trigger" );
            return;
        }
        Duration initial = config.transport().reconnection().outOfService().initialDelay();
        Duration retry   = config.transport().reconnection().outOfService().retryInterval();

        // Best-effort clean MQTT disconnect: the LWT-style "offline" gets
        // published, downstream consumers see the binding go dark for the
        // duration of the reboot.
        try { mqtt.disconnect(); }
        catch ( RuntimeException e ) { LOG.debugf( "MQTT disconnect during OOS handler threw: %s", e.getMessage() ); }

        attemptCount.set( 0 );
        LOG.infof( "OOS detected — MQTT will reconnect in %s, retry every %s on failure",
                   initial, retry );
        pending = executor.schedule( () -> tryReconnect( retry ),
                                     initial.toMillis(), TimeUnit.MILLISECONDS );
    }

    /** Cancel any pending reconnect chain. Called on shutdown or by the
     *  operator (e.g. through a future management endpoint). */
    public synchronized void cancel()
    {
        if ( pending != null )
        {
            pending.cancel( false );
            pending = null;
        }
    }

    public int attemptCount()
    {
        return attemptCount.get();
    }

    public boolean isPending()
    {
        return pending != null && !pending.isDone();
    }

    // ---------------------------------------------------------------------
    //  Internal: the actual reconnect loop
    // ---------------------------------------------------------------------

    private void tryReconnect( Duration retryInterval )
    {
        attemptCount.incrementAndGet();
        try
        {
            mqtt.connect();
            LOG.infof( "MQTT OOS-reconnect succeeded after %d attempt(s)", attemptCount() );
            synchronized ( this )
            {
                pending = null;
            }
        }
        catch ( TransportException e )
        {
            LOG.warnf( "MQTT OOS-reconnect attempt #%d failed: %s — retrying in %s",
                       attemptCount(), e.getMessage(), retryInterval );
            // Reschedule another attempt.
            synchronized ( this )
            {
                pending = executor.schedule( () -> tryReconnect( retryInterval ),
                                             retryInterval.toMillis(), TimeUnit.MILLISECONDS );
            }
        }
        catch ( Exception e )
        {
            // Defensive: don't let an unexpected error kill the chain.
            LOG.warnf( e, "MQTT OOS-reconnect attempt #%d threw unexpectedly — retrying in %s",
                       attemptCount(), retryInterval );
            synchronized ( this )
            {
                pending = executor.schedule( () -> tryReconnect( retryInterval ),
                                             retryInterval.toMillis(), TimeUnit.MILLISECONDS );
            }
        }
    }
}
