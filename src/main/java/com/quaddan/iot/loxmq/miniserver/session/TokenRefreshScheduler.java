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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Schedules the periodic JWT refresh.
 *
 * <h3>Why refresh</h3>
 * The token returned by {@code jdev/sys/getjwt/...} carries a {@code validUntil}
 * roughly 3 months out. On expiry the binding falls through to a full
 * reconnect (which gets a fresh token), but that's wasteful for long-running
 * sessions — the miniserver provides {@code jdev/sys/refreshjwt/{hash}/{user}}
 * to extend the same token without a full handshake.
 *
 * <h3>Cadence</h3>
 * Reads {@code loxone.miniserver.security.token.refresh.period} (default
 * {@code PT24H}). Re-scheduled after each successful refresh — so a refresh
 * goes out every 24h regardless of token expiry, far ahead of the 3-month
 * boundary. Conservative on purpose: a refresh failure (network blip, miniserver
 * restart) won't immediately put the token in jeopardy.
 *
 * <h3>{@link Startup} — eager initialization</h3>
 * Same reason as {@link ReconnectScheduler}: bean is first touched from the
 * JDK WS reader thread (via {@code SessionOrchestrator.onStatusUpdateReply})
 * which is not a Quarkus-managed thread. Eager init dodges the dev-mode race.
 *
 * <h3>Lifecycle</h3>
 * <ul>
 *   <li>Session reaches RUNNING → {@code SessionOrchestrator.onStatusUpdateReply}
 *       calls {@link #scheduleNext(Runnable)} — schedules the FIRST refresh
 *       at {@code now + period}.</li>
 *   <li>Refresh succeeds → {@code SessionOrchestrator.onTokenRefreshReply}
 *       calls {@link #scheduleNext(Runnable)} again for the next cycle.</li>
 *   <li>Session disconnects / fails / is closed → {@link #cancel()}.</li>
 * </ul>
 */
@ApplicationScoped
@Startup
public class TokenRefreshScheduler
{
    private static final Logger LOG = Logger.getLogger( TokenRefreshScheduler.class );

    @Inject
    LoxoneConfig config;

    private       ScheduledExecutorService                executor;
    private final AtomicReference< ScheduledFuture< ? > > pendingRef = new AtomicReference<>();

    @PostConstruct
    void init()
    {
        executor = Executors.newSingleThreadScheduledExecutor( r ->
                                                               {
                                                                   Thread t = new Thread( r, "loxone-token-refresh" );
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

    /**
     * Schedule the next refresh at {@code now + period}. Cancels any
     * previously-scheduled refresh; only one is ever pending at a time.
     */
    public Duration scheduleNext( Runnable refresh )
    {
        cancel();
        Duration period = config.miniserver().security().token().refresh().period();
        LOG.infof( "Token refresh scheduled in %s", period );
        ScheduledFuture< ? > future = executor.schedule( () ->
                                                         {
                                                             try
                                                             {
                                                                 refresh.run();
                                                             }
                                                             catch ( RuntimeException e )
                                                             {
                                                                 LOG.warnf( "Token refresh runnable threw: %s", e.getMessage() );
                                                             }
                                                         }, period.toMillis(), TimeUnit.MILLISECONDS );
        pendingRef.set( future );
        return period;
    }

    /** Cancel the pending refresh, if any. Idempotent. */
    public void cancel()
    {
        ScheduledFuture< ? > prev = pendingRef.getAndSet( null );
        if ( prev != null )
        {
            prev.cancel( false );
            LOG.debug( "Token refresh cancelled" );
        }
    }

    /** True if a refresh is scheduled but not yet fired. */
    public boolean isRefreshPending()
    {
        ScheduledFuture< ? > p = pendingRef.get();
        return p != null && !p.isDone();
    }
}
