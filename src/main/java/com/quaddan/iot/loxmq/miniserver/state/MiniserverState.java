/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.state;

import com.quaddan.iot.loxmq.miniserver.identity.MiniserverIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runtime state of the Miniserver session. Single source of truth shared
 * across the binding (readiness checks, dashboard, crypto layer, connection
 * mode resolver, future handshake state machine).
 * <p>
 * This models the {@link MiniserverIdentity} slot — i.e. "what we learned
 * about the miniserver from the bootstrap call". The live session state (the
 * handshake step, the JWT token, the last keepalive timestamp) lives in
 * {@code SessionTracker}; this split lets the resolver / dashboard / health
 * checks consume {@code identity()} independently.
 *
 * <h3>Thread-safety</h3>
 * The identity is held in an {@link AtomicReference}: the bootstrap fetch
 * writes it once, every other thread reads concurrently
 * (dashboard request, health probe, outbound encrypt path). No locks
 * needed — the field is intentionally write-once-then-stable for the
 * lifetime of a session; a reconnect that detects a different miniserver
 * SHOULD call {@link #clear()} explicitly before re-populating.
 */
@ApplicationScoped
public class MiniserverState
{
    private static final Logger LOG = Logger.getLogger( MiniserverState.class );

    private final AtomicReference< MiniserverIdentity > identityRef = new AtomicReference<>();

    /** The current detected identity, or empty until the bootstrap call lands. */
    public Optional< MiniserverIdentity > identity()
    {
        return Optional.ofNullable( identityRef.get() );
    }

    /**
     * Replace the current identity. Called by the bootstrap orchestrator
     * once {@code jdev/cfg/apiKey} has been parsed. Logging here keeps
     * the identity transition observable from a single point — the resolver,
     * dashboard and readiness check do NOT log on every read.
     */
    public void update( MiniserverIdentity identity )
    {
        MiniserverIdentity previous = identityRef.getAndSet( identity );
        if ( previous == null )
        {
            LOG.infof( "Miniserver identity established: serial=%s version=%s gen=%s httpsStatus=%s",
                       identity.serial(), identity.version(), identity.generation(), identity.httpsStatus() );
        }
        else if ( !previous.serial().equals( identity.serial() ) )
        {
            LOG.warnf( "Miniserver identity CHANGED across reconnect: %s → %s. This is unusual — " +
                       "either DNS now points elsewhere, or the cached identity belonged to a different unit.",
                       previous.serial(), identity.serial() );
        }
        else
        {
            LOG.debugf( "Miniserver identity refreshed (same serial %s)", identity.serial() );
        }
    }

    /**
     * Wipe the identity. Called on session-end / reconnect-with-new-host
     * scenarios so the readiness checks immediately reflect "no session yet"
     * until the next bootstrap completes.
     */
    public void clear()
    {
        if ( identityRef.getAndSet( null ) != null )
        {
            LOG.debug( "Miniserver identity cleared" );
        }
    }
}
