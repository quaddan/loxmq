/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.session;

import com.quaddan.iot.loxmq.config.LoxoneConfig;
import io.quarkus.runtime.Shutdown;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Schedules reconnect attempts to the Miniserver after a session closes,
 * with exponential backoff + jitter and per-policy adjustments.
 *
 * <h3>Policy mapping</h3>
 * The orchestrator calls {@link #scheduleReconnect(Runnable, LoxoneCloseCode.ReconnectPolicy)}
 * with the {@link LoxoneCloseCode.ReconnectPolicy} derived from the WS close
 * code. The scheduler picks the delay:
 *
 * <ul>
 *   <li>{@link LoxoneCloseCode.ReconnectPolicy#NORMAL NORMAL}: standard
 *       exponential backoff —
 *       {@code initialDelay × multiplier^attempts}, capped at
 *       {@code maxDelay}, with ±{@code jitterFactor}×100 % random noise.</li>
 *   <li>{@link LoxoneCloseCode.ReconnectPolicy#LONG_PAUSE LONG_PAUSE}:
 *       jump straight to {@code maxDelay}, regardless of attempt count.
 *       Used when the miniserver explicitly signals "wait it out" (firmware
 *       update, slot exhaustion, login block).</li>
 *   <li>{@link LoxoneCloseCode.ReconnectPolicy#DO_NOT_RECONNECT DO_NOT_RECONNECT}:
 *       no schedule, attempts counter reset, prior schedule cancelled.
 *       Used when the binding cannot recover without operator action (user
 *       disabled, etc.).</li>
 * </ul>
 *
 * <h3>Backoff state</h3>
 * The attempt counter increments on each schedule + lookup of NORMAL or
 * LONG_PAUSE. It resets to zero via {@link #notifySuccess()} (orchestrator
 * calls this when the session reaches {@link SessionState#RUNNING}).
 *
 * <h3>Single in-flight schedule</h3>
 * Only one pending reconnect is allowed at a time. A new
 * {@code scheduleReconnect} cancels the previous one — useful when the
 * operator manually disconnects+reconnects while a backoff is pending.
 *
 * <h3>{@link Startup} — eager initialization</h3>
 * Same rationale as {@link LoxApp3Cache}: this bean is first touched
 * from the WS reader thread (inside {@code SessionOrchestrator.onClose}),
 * not a Quarkus-managed thread. Eager init at startup avoids the dev-mode
 * lazy-init race when the bean is first touched from a non-managed thread.
 */
@ApplicationScoped
@Startup
public class ReconnectScheduler
{
    private static final Logger LOG = Logger.getLogger( ReconnectScheduler.class );

    @Inject
    LoxoneConfig config;

    private       ScheduledExecutorService                executor;
    private final AtomicReference< ScheduledFuture< ? > > pendingRef = new AtomicReference<>();
    private final AtomicInteger                           attemptRef = new AtomicInteger( 0 );

    @PostConstruct
    void init()
    {
        executor = Executors.newSingleThreadScheduledExecutor( r ->
                                                               {
                                                                   Thread t = new Thread( r, "loxone-reconnect-scheduler" );
                                                                   t.setDaemon( true );
                                                                   return t;
                                                               } );
    }

    @Shutdown
    void shutdown()
    {
        cancel();
        if ( executor != null )
        {
            executor.shutdownNow();
        }
    }

    // ==========================================================================
    //  Public API
    // ==========================================================================

    /**
     * Schedules the given {@code attempt} runnable to run after a backoff
     * delay derived from the policy + current attempt count. Cancels any
     * previously-scheduled attempt.
     *
     * <p>Returns the computed delay (for logging / dashboard) — never null.
     * Returns {@link Duration#ZERO} (and does nothing) when the policy is
     * {@link LoxoneCloseCode.ReconnectPolicy#DO_NOT_RECONNECT}.
     *
     * <p>If {@code loxone.miniserver.reconnect.enable=false} the scheduler
     * is a no-op (also returns {@link Duration#ZERO}), regardless of the
     * policy. Operators who want to manage reconnects externally can
     * disable the scheduler entirely.
     */
    public Duration scheduleReconnect( Runnable attempt, LoxoneCloseCode.ReconnectPolicy policy )
    {
        if ( !config.miniserver().reconnect().enable() )
        {
            LOG.info( "Reconnect scheduler disabled by config — not scheduling" );
            return Duration.ZERO;
        }
        if ( policy == LoxoneCloseCode.ReconnectPolicy.DO_NOT_RECONNECT )
        {
            LOG.warn( "Reconnect policy is DO_NOT_RECONNECT — staying offline (operator intervention required)" );
            cancel();
            attemptRef.set( 0 );
            return Duration.ZERO;
        }

        // Cancel any prior schedule before picking up a fresh slot.
        cancel();

        int      attempts = attemptRef.incrementAndGet();
        Duration delay    = computeDelay( policy, attempts );
        LOG.infof( "Scheduling reconnect attempt #%d in %s (policy=%s)",
                   ( Integer ) attempts, delay, policy );

        ScheduledFuture< ? > future = executor.schedule( () ->
                                                         {
                                                             try
                                                             {
                                                                 attempt.run();
                                                             }
                                                             catch ( RuntimeException e )
                                                             {
                                                                 // The orchestrator's connect() returns a CompletableFuture;
                                                                 // we expect the runnable to be fire-and-forget here. Failures
                                                                 // arrive via the orchestrator's onClose path, which will
                                                                 // call scheduleReconnect again — closing the loop.
                                                                 LOG.warnf( "Reconnect runnable threw: %s", e.getMessage() );
                                                             }
                                                         }, delay.toMillis(), TimeUnit.MILLISECONDS );
        pendingRef.set( future );
        return delay;
    }

    /**
     * Reset the backoff state — typically called by the orchestrator once
     * the session reaches {@link SessionState#RUNNING}. Cancels any pending
     * schedule (defensive: no pending reconnect should exist when the session
     * is up, but a race in disconnect+reconnect could leave one).
     */
    public void notifySuccess()
    {
        if ( attemptRef.getAndSet( 0 ) > 0 )
        {
            LOG.debug( "Reconnect backoff reset (session reached RUNNING)" );
        }
        cancel();
    }

    /** Cancel the pending schedule, if any. Idempotent. */
    public void cancel()
    {
        ScheduledFuture< ? > prev = pendingRef.getAndSet( null );
        if ( prev != null )
        {
            prev.cancel( false );
        }
    }

    /** Current attempt count, exposed for the dashboard / state endpoint. */
    public int attemptCount()
    {
        return attemptRef.get();
    }

    /** True if a reconnect attempt is scheduled but not yet fired. */
    public boolean isReconnectPending()
    {
        ScheduledFuture< ? > p = pendingRef.get();
        return p != null && !p.isDone();
    }

    // ==========================================================================
    //  Delay computation (package-private for unit tests)
    // ==========================================================================

    /**
     * Visible-for-test: compute the delay for an attempt given the policy
     * + current attempt count. Pure function (modulo jitter randomness).
     *
     * <p>Formula:
     * <pre>{@code
     *  LONG_PAUSE → maxDelay (no jitter — operator should see consistent waits)
     *  NORMAL     → initialDelay × multiplier^(attempts-1)
     *               capped at maxDelay,
     *               then ±jitterFactor noise (uniform random)
     * }</pre>
     *
     * <p>The {@code attempts-1} exponent means the FIRST attempt fires after
     * {@code initialDelay} (not {@code initialDelay × multiplier}) — the usual
     * exponential-backoff convention.
     */
    Duration computeDelay( LoxoneCloseCode.ReconnectPolicy policy, int attempts )
    {
        var rc = config.miniserver().reconnect();

        if ( policy == LoxoneCloseCode.ReconnectPolicy.LONG_PAUSE )
        {
            return rc.maxDelay();
        }

        // Exponential ladder, capped.
        double initialMs = rc.initialDelay().toMillis();
        double cappedMs = Math.min(
                initialMs * Math.pow( rc.multiplier(), Math.max( 0, attempts - 1 ) ),
                rc.maxDelay().toMillis() );

        // Jitter: uniform spread in [-factor, +factor]. Random ∈ [0,1) →
        // (Random*2 - 1) ∈ [-1, 1). Multiply by factor → [-factor, factor).
        double jitterFactor = rc.jitterFactor();
        double rng          = ThreadLocalRandom.current().nextDouble() * 2 - 1;
        double jitteredMs   = cappedMs * ( 1.0 + rng * jitterFactor );

        return Duration.ofMillis( ( long ) Math.max( 0, jitteredMs ) );
    }
}
