/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.session;

import com.quaddan.iot.loxmq.config.LoxoneConfig;
import com.quaddan.iot.loxmq.miniserver.message.MiniserverKeepAliveResponseEvent;
import com.quaddan.iot.loxmq.transport.MqttMetrics;
import io.quarkus.runtime.Shutdown;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Sends the periodic {@code keepalive} text command to the miniserver
 * once the session reaches {@code RUNNING}, and measures the request →
 * response round-trip time for use as a link-quality indicator
 * (V17.0 §"Detecting issues").
 *
 * <h3>Why a scheduler is needed</h3>
 * Before this patch the binding had the response-detection plumbing
 * ({@code MessageType.KEEP_ALIVE_RESPONSE} + the header decode in
 * {@code BinaryStatesDecoder}) but never actually sent any
 * {@code keepalive} command — a real protocol gap. The miniserver
 * relies on the keepalive to know the client is still alive; without
 * one it eventually severs the WebSocket on its own timeout. The
 * binding stayed up only because other traffic (state events,
 * commands) kept the socket warm.
 *
 * <h3>Cadence</h3>
 * Reads {@code loxone.miniserver.connection.ws.keepalive-interval}
 * (default {@code PT60S}). A single fixed-rate schedule fires at
 * {@code interval} after the first send; each tick performs
 * {@link #sendOne()} which logs the RTT of the <em>previous</em> tick
 * (if any) and dispatches a fresh send.
 *
 * <h3>Correlation</h3>
 * Loxone carries no correlation ID — the pairing rule is positional:
 * the next {@code KEEP_ALIVE_RESPONSE} arriving after our send IS the
 * response to that send. {@link #lastSentAtRef} is overwritten on
 * every send; {@link MiniserverKeepAliveResponseEvent} observers
 * compute {@code arrivedAt - lastSentAt}. The single-threaded WS
 * reader + the spec's frame-ordering guarantee make this safe even
 * under unusually low link speeds.
 *
 * <h3>Lifecycle</h3>
 * <ul>
 *   <li><b>Start</b>: observes {@link MiniserverConnectedEvent} —
 *       session reached RUNNING. Schedules the first tick at
 *       {@code now + interval}.</li>
 *   <li><b>Stop on send failure</b>: any throwable from
 *       {@link MiniserverWebSocket#sendText(String)} (typically a
 *       closed socket) cancels the schedule. The next
 *       {@code MiniserverConnectedEvent} restarts it. This avoids
 *       a separate {@code MiniserverDisconnectedEvent} hook — the
 *       send itself is the liveness probe of last resort.</li>
 *   <li><b>Stop on shutdown</b>: {@link Shutdown} cancels the
 *       schedule and shuts down the executor.</li>
 * </ul>
 *
 * <h3>{@link Startup} — eager initialization</h3>
 * Same reason as {@link TokenRefreshScheduler}: first touched from
 * the JDK WS reader thread (the event observer), which is not a
 * Quarkus-managed thread. Eager init on the main thread dodges the
 * dev-mode classloader race.
 */
@ApplicationScoped
@Startup
public class KeepAliveScheduler
{
    private static final Logger LOG = Logger.getLogger( KeepAliveScheduler.class );

    @Inject
    LoxoneConfig                                      config;
    @Inject
    MiniserverWebSocket                               webSocket;
    /** {@link jakarta.enterprise.inject.Instance} indirection to break the
     *  eager-init cycle: {@code MqttMetrics} injects {@code this} (for a
     *  gauge supplier) and {@code this} pushes RTT samples back into
     *  {@code MqttMetrics}. Direct {@code @Inject MqttMetrics metrics}
     *  would form a strong reference cycle at bean creation time; the
     *  {@code Instance<>} lookup is lazy (resolved on first
     *  {@code get()}, by which time both beans are fully initialised). */
    @Inject
    jakarta.enterprise.inject.Instance< MqttMetrics > metricsInstance;

    private       ScheduledExecutorService                executor;
    private final AtomicReference< ScheduledFuture< ? > > pendingRef        = new AtomicReference<>();
    /** Wall-clock instant the most recent {@code keepalive} text was sent.
     *  Reset on every send; read by the response observer to compute RTT. */
    private final AtomicReference< Instant >              lastSentAtRef     = new AtomicReference<>();
    /** Most recent measured RTT — exposed for the future metrics layer
     *  and the dashboard. {@link Optional#empty()} until the first
     *  response arrives. */
    private final AtomicReference< Duration >             lastRttRef        = new AtomicReference<>();
    /** Wall-clock instant the most recent response landed. Pair with
     *  {@link #lastRttRef} for "RTT measured at …" displays. */
    private final AtomicReference< Instant >              lastResponseAtRef = new AtomicReference<>();

    @PostConstruct
    void init()
    {
        executor = Executors.newSingleThreadScheduledExecutor( r ->
                                                               {
                                                                   Thread t = new Thread( r, "loxone-keepalive" );
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

    /** Session reached RUNNING → start (or restart) the schedule. Idempotent:
     *  a second event before a disconnect cancels the previous schedule and
     *  re-installs a fresh one (no overlap, no orphan tasks). */
    void onMiniserverConnected( @ObservesAsync MiniserverConnectedEvent event )
    {
        Duration interval = config.miniserver().connection().ws().keepaliveInterval();
        if ( interval == null || interval.isZero() || interval.isNegative() )
        {
            LOG.info( "Keepalive disabled — interval is zero / negative" );
            return;
        }
        // Cancel any leftover from a previous session before scheduling fresh.
        cancel();
        LOG.infof( "KeepAlive scheduler ARMED — interval=%s, command=\"%s\"",
                   interval, config.miniserver().cmd().keepalive() );
        ScheduledFuture< ? > next = executor.scheduleAtFixedRate(
                this::sendOne,
                interval.toMillis(),
                interval.toMillis(),
                TimeUnit.MILLISECONDS );
        pendingRef.set( next );
    }

    /** Response landed → compute RTT, stash for later read. Observer fires
     *  async on Quarkus's managed pool; touching the atomics is safe. */
    void onKeepAliveResponse( @ObservesAsync MiniserverKeepAliveResponseEvent event )
    {
        Instant sentAt = lastSentAtRef.get();
        if ( sentAt == null )
        {
            // Spurious response (before our first send, or after a cancel).
            // Don't trip — just log at DEBUG so we don't drown the console.
            LOG.debugf( "KeepAlive response received but no outstanding send — ignored (arrivedAt=%s)",
                        event.arrivedAt() );
            return;
        }
        Duration rtt = Duration.between( sentAt, event.arrivedAt() );
        if ( rtt.isNegative() )
        {
            // Clock skew or pre-send response — clamp to zero to keep
            // downstream histograms sane.
            rtt = Duration.ZERO;
        }
        lastRttRef.set( rtt );
        lastResponseAtRef.set( event.arrivedAt() );
        // Push to Micrometer for /q/metrics + dashboard Health panel.
        // metricsInstance.get() is lazy — first call materialises the
        // MqttMetrics bean, subsequent calls cache it on the ArC side.
        metricsInstance.get().recordRtt( rtt );
        LOG.debugf( "KeepAlive RTT = %d ms (response arrived %s)",
                    ( Long ) rtt.toMillis(), event.arrivedAt() );
    }

    /** One scheduler tick. Sends the {@code keepalive} text command and
     *  records the send instant for RTT calculation. Any throwable
     *  cancels the schedule — typically a closed socket, in which case
     *  the next {@link MiniserverConnectedEvent} will re-arm us. */
    void sendOne()
    {
        String  cmd = config.miniserver().cmd().keepalive();
        Instant now = Instant.now();
        try
        {
            lastSentAtRef.set( now );
            webSocket.sendText( cmd );
            LOG.tracef( "KeepAlive → %s", cmd );
        }
        catch ( RuntimeException e )
        {
            LOG.warnf( "KeepAlive send failed (%s) — cancelling schedule. "
                       + "Will re-arm on the next MiniserverConnectedEvent.",
                       e.getMessage() );
            cancel();
        }
    }

    /** Cancel the running schedule (idempotent). Visible to observers
     *  on the boot side that need to tear down explicitly. Does NOT
     *  clear the last-measured RTT — the operator still sees the
     *  most recent value on the dashboard after a disconnect. */
    public void cancel()
    {
        ScheduledFuture< ? > old = pendingRef.getAndSet( null );
        if ( old != null )
        {
            old.cancel( false );
        }
    }

    /** Test-only helper: full state reset (schedule + send timestamp +
     *  RTT history). Package-private so {@code KeepAliveSchedulerTest}
     *  can isolate each {@code @Test} method, but never called from
     *  production code — clearing the last-RTT in prod would defeat
     *  the dashboard "last-known-good" display.
     *  <p>NOT marked {@code @VisibleForTesting} since the project
     *  doesn't pull in Guava; the javadoc + package-private modifier
     *  serve the same role. */
    void resetStateForTests()
    {
        cancel();
        lastSentAtRef.set( null );
        lastRttRef.set( null );
        lastResponseAtRef.set( null );
    }

    // -------------------------------------------------------------------------
    //  Read-only accessors for the dashboard + the future metrics layer
    // -------------------------------------------------------------------------

    /** Most recently measured RTT, if any. {@link Optional#empty()} when
     *  the scheduler has never received a response (e.g. before the
     *  first interval elapses, or before any session has reached
     *  RUNNING). */
    public Optional< Duration > lastRtt()
    {
        return Optional.ofNullable( lastRttRef.get() );
    }

    /** When the most recent response arrived. Pair with {@link #lastRtt()}
     *  for "RTT N ms measured at HH:mm:ss" displays. */
    public Optional< Instant > lastResponseAt()
    {
        return Optional.ofNullable( lastResponseAtRef.get() );
    }

    /** True while a schedule is active. Useful for the dashboard's
     *  "Keepalive scheduler armed" indicator. */
    public boolean isScheduled()
    {
        ScheduledFuture< ? > current = pendingRef.get();
        return current != null && !current.isDone() && !current.isCancelled();
    }
}
