/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.bootstrap;

import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tracks the lifecycle of bootstrap attempts: current {@link BootstrapStatus},
 * timestamps, last error. Read by the dashboard, the {@code /api/v1/state}
 * endpoint and the readiness probe.
 *
 * <h3>Why a separate bean (not folded into {@code MiniserverState})</h3>
 * {@code MiniserverState} is the slot for the parsed
 * {@code MiniserverIdentity} — pure result of a successful bootstrap. The
 * tracker holds attempt metadata that is meaningful EVEN when the bootstrap
 * fails (operator wants to see "last attempted 30s ago with error X"
 * regardless of whether the identity was updated). Splitting keeps each bean's
 * concern narrow.
 */
@ApplicationScoped
public class BootstrapTracker
{
    private final AtomicReference< BootstrapStatus > statusRef       = new AtomicReference<>( BootstrapStatus.NOT_STARTED );
    private final AtomicReference< Instant >         startedAtRef    = new AtomicReference<>();
    private final AtomicReference< Instant >         completedAtRef  = new AtomicReference<>();
    private final AtomicReference< String >          lastErrorRef    = new AtomicReference<>();
    private final AtomicReference< Duration >        lastDurationRef = new AtomicReference<>();

    public BootstrapStatus status()
    {
        return statusRef.get();
    }

    public Optional< Instant > startedAt()
    {
        return Optional.ofNullable( startedAtRef.get() );
    }

    public Optional< Instant > completedAt()
    {
        return Optional.ofNullable( completedAtRef.get() );
    }

    public Optional< Duration > lastDuration()
    {
        return Optional.ofNullable( lastDurationRef.get() );
    }

    public Optional< String > lastError()
    {
        return Optional.ofNullable( lastErrorRef.get() );
    }

    /** Called by the orchestrator when an attempt begins. */
    void markStarted()
    {
        statusRef.set( BootstrapStatus.IN_PROGRESS );
        startedAtRef.set( Instant.now() );
        completedAtRef.set( null );
        lastDurationRef.set( null );
        lastErrorRef.set( null );
    }

    /** Called by the orchestrator on success. */
    void markSucceeded()
    {
        Instant now = Instant.now();
        completedAtRef.set( now );
        startedAt().ifPresent( s -> lastDurationRef.set( Duration.between( s, now ) ) );
        lastErrorRef.set( null );
        statusRef.set( BootstrapStatus.SUCCESS );
    }

    /** Called by the orchestrator on failure. */
    void markFailed( String reason )
    {
        Instant now = Instant.now();
        completedAtRef.set( now );
        startedAt().ifPresent( s -> lastDurationRef.set( Duration.between( s, now ) ) );
        lastErrorRef.set( reason );
        statusRef.set( BootstrapStatus.FAILED );
    }
}
