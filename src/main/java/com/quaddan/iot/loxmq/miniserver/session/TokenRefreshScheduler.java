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
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Optional;
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
 * {@code PT24H}) and {@code ...refresh.delay-time} (default {@code 04:30:00}).
 * Each refresh is anchored to {@code delay-time} (local wall-clock) on the
 * calendar day that {@code now + period} lands on — a 24h period fires tomorrow
 * at {@code delay-time}, a 36h period the day after, etc. Re-scheduled after
 * each successful refresh, far ahead of the ~3-month token expiry. Conservative
 * on purpose: a refresh failure (network blip, miniserver restart) won't
 * immediately put the token in jeopardy.
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
    private final AtomicReference< ScheduledFuture< ? > > pendingRef       = new AtomicReference<>();
    private final AtomicReference< Instant >              nextRefreshAtRef = new AtomicReference<>();

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
     * Schedule the next refresh at the configured {@code delay-time} (local
     * wall-clock time of day) on the calendar day that {@code now + period}
     * lands on: with a 24h period the refresh fires tomorrow at
     * {@code delay-time}; with 36h it fires the day after, at {@code delay-time};
     * and so on. Cancels any previously-scheduled refresh; only one is ever
     * pending at a time. Returns the computed delay until that moment.
     */
    public Duration scheduleNext( Runnable refresh )
    {
        cancel();
        var       refreshCfg = config.miniserver().security().token().refresh();
        Duration  period     = refreshCfg.period();
        LocalTime delayTime  = refreshCfg.delayTime();

        // Anchor the refresh to delay-time on the day where now+period falls,
        // rather than firing at an arbitrary instant `period` after the last one.
        LocalDateTime now       = LocalDateTime.now();
        LocalDateTime scheduled = LocalDateTime.of( now.plus( period ).toLocalDate(), delayTime );
        // Defensive: with a short period the snapped time could already be past —
        // never schedule in the past; push to the next day at delay-time.
        if ( !scheduled.isAfter( now ) )
        {
            scheduled = scheduled.plusDays( 1 );
        }
        Duration delay = Duration.between( now, scheduled );
        nextRefreshAtRef.set( scheduled.atZone( ZoneId.systemDefault() ).toInstant() );

        LOG.infof( "Token refresh scheduled for %s (in %s) — period=%s, delay-time=%s",
                   scheduled, delay, period, delayTime );
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
                                                         }, delay.toMillis(), TimeUnit.MILLISECONDS );
        pendingRef.set( future );
        return delay;
    }

    /** Cancel the pending refresh, if any. Idempotent. */
    public void cancel()
    {
        nextRefreshAtRef.set( null );
        ScheduledFuture< ? > prev = pendingRef.getAndSet( null );
        if ( prev != null )
        {
            prev.cancel( false );
            LOG.debug( "Token refresh cancelled" );
        }
    }

    /** The instant the next refresh is scheduled to fire, if one is pending. */
    public Optional< Instant > nextRefreshAt()
    {
        return Optional.ofNullable( nextRefreshAtRef.get() );
    }

    /** True if a refresh is scheduled but not yet fired. */
    public boolean isRefreshPending()
    {
        ScheduledFuture< ? > p = pendingRef.get();
        return p != null && !p.isDone();
    }
}
