/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.session;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shared session state — current {@link SessionState}, last transition time,
 * acquired JWT, last error message. Read by the dashboard,
 * {@code /api/v1/state}, the readiness probe and the orchestrator itself.
 *
 * <h3>Thread-safety</h3>
 * All slots are held in {@link AtomicReference}. The state machine has
 * exactly ONE writer thread at a time (the JDK WebSocket listener callback,
 * or the orchestrator's connect-initiator thread) so per-field atomic
 * publishes are sufficient — no compound read-modify-writes need locking.
 */
@ApplicationScoped
public class SessionTracker
{
    private static final Logger LOG = Logger.getLogger( SessionTracker.class );

    private final AtomicReference< SessionState >    stateRef       = new AtomicReference<>( SessionState.DISCONNECTED );
    private final AtomicReference< MiniserverToken > tokenRef       = new AtomicReference<>();
    private final AtomicReference< Instant >         stateChangedAt = new AtomicReference<>( Instant.now() );
    private final AtomicReference< String >          lastErrorRef   = new AtomicReference<>();
    private final AtomicReference< Instant >         connectedAtRef = new AtomicReference<>();

    /** Wall-clock instant the binding entered {@link SessionState#CONNECTING}.
     *  Reset to null on RUNNING / FAILED so a stale value never bleeds into
     *  the next attempt. Internal — read by {@link #transition(SessionState)}
     *  to compute {@link #lastHandshakeDurationRef} when RUNNING is reached. */
    private final AtomicReference< Instant >  handshakeStartedAtRef    = new AtomicReference<>();
    /** Most recent measured CONNECTING → RUNNING duration. Exposed read-only
     *  via {@link #lastHandshakeDuration()} for the future metrics layer
     *  + the dashboard Health panel. */
    private final AtomicReference< Duration > lastHandshakeDurationRef = new AtomicReference<>();

    /** Fired on every actual transition (prev != next). Plumbed via CDI
     *  so the dashboard SSE broadcaster can stream changes
     *  to the operator's browser without polling. {@code fireAsync} —
     *  the orchestrator/WS callback thread must not block on event
     *  consumers. */
    @Inject
    Event< SessionStateChangedEvent > stateChangedEvent;

    public SessionState state()
    {
        return stateRef.get();
    }

    public Instant stateChangedAt()
    {
        return stateChangedAt.get();
    }

    public Optional< MiniserverToken > token()
    {
        return Optional.ofNullable( tokenRef.get() );
    }

    public Optional< String > lastError()
    {
        return Optional.ofNullable( lastErrorRef.get() );
    }

    public Optional< Instant > connectedAt()
    {
        return Optional.ofNullable( connectedAtRef.get() );
    }

    /** Most recent CONNECTING → RUNNING duration. {@link Optional#empty()}
     *  until the first successful handshake completes. Refreshed on every
     *  successful RUNNING transition; never cleared (the operator can
     *  reference the last-known-good duration even after a disconnect). */
    public Optional< Duration > lastHandshakeDuration()
    {
        return Optional.ofNullable( lastHandshakeDurationRef.get() );
    }

    // ---------- writes (orchestrator + WS callbacks) ----------

    /**
     * Transition to a new state. Clears the lastError when entering
     * {@link SessionState#CONNECTING} (fresh attempt) or
     * {@link SessionState#RUNNING} (success); preserves it on
     * {@link SessionState#FAILED} / {@link SessionState#DISCONNECTED} so the
     * operator can see WHY the previous attempt failed.
     * <p>
     * {@code connectedAt} is set when we reach RUNNING — that's the
     * operator-meaningful "session established" moment, after the LoxAPP3
     * fetch and the status-update subscription are both confirmed.
     */
    public void transition( SessionState next )
    {
        SessionState prev = stateRef.getAndSet( next );
        Instant      now  = Instant.now();
        stateChangedAt.set( now );
        if ( prev != next )
        {
            LOG.infof( "Session state: %s → %s", prev, next );
            if ( next == SessionState.CONNECTING || next == SessionState.RUNNING )
            {
                lastErrorRef.set( null );
            }
            if ( next == SessionState.CONNECTING )
            {
                // Mark the moment we started — used to compute the full
                // handshake duration when RUNNING is reached.
                handshakeStartedAtRef.set( now );
            }
            if ( next == SessionState.RUNNING )
            {
                connectedAtRef.set( now );
                // Compute CONNECTING → RUNNING. handshakeStartedAtRef may
                // be null if the state machine was driven directly into
                // RUNNING in a test or restored from an unexpected source —
                // we just skip the measurement rather than emit a garbage
                // duration.
                Instant started = handshakeStartedAtRef.getAndSet( null );
                if ( started != null )
                {
                    lastHandshakeDurationRef.set( Duration.between( started, now ) );
                }
            }
            if ( next == SessionState.FAILED )
            {
                // Failed mid-handshake — discard the start instant so the
                // next CONNECTING attempt starts a fresh measurement window.
                handshakeStartedAtRef.set( null );
            }
            if ( next == SessionState.DISCONNECTED || next == SessionState.CLOSED )
            {
                connectedAtRef.set( null );
                tokenRef.set( null );
                handshakeStartedAtRef.set( null );
            }

            // Emit the transition. fireAsync so a slow SSE
            // subscriber can't back-pressure the orchestrator/WS thread.
            // Read lastErrorRef AFTER the mutations above so the snapshot
            // matches what /api/v1/state would return at the same instant.
            // Null-check protects the unit-test path that constructs
            // SessionTracker directly with `new SessionTracker()` — CDI
            // never produces a null Event.
            if ( stateChangedEvent != null )
            {
                stateChangedEvent.fireAsync(
                        new SessionStateChangedEvent( prev, next, now, lastErrorRef.get() ) );
            }
        }
    }

    public void fail( String reason )
    {
        lastErrorRef.set( reason );
        transition( SessionState.FAILED );
    }

    public void setToken( MiniserverToken token )
    {
        tokenRef.set( token );
    }
}
