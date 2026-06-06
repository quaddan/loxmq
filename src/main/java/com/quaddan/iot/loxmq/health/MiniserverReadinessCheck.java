/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.health;

import com.quaddan.iot.loxmq.config.LoxoneConfig;
import com.quaddan.iot.loxmq.miniserver.bootstrap.BootstrapTracker;
import com.quaddan.iot.loxmq.miniserver.connection.ConnectionModeResolver;
import com.quaddan.iot.loxmq.miniserver.identity.MiniserverIdentity;
import com.quaddan.iot.loxmq.miniserver.session.SessionState;
import com.quaddan.iot.loxmq.miniserver.session.SessionTracker;
import com.quaddan.iot.loxmq.miniserver.state.MiniserverState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;

/**
 * Readiness probe for the miniserver-side session.
 * <p>
 * Returns {@code UP} once the WebSocket handshake has reached the
 * {@link SessionState#RUNNING} step (token acquired, structure file loaded,
 * status-update subscription acknowledged). Returns {@code DOWN} during
 * reconnect attempts or before the first successful handshake. The
 * {@code data} payload always exposes the resolved connection mode, the
 * identity, the bootstrap status, the session state, and the downgrade
 * reason if any — monitoring can alert on specific stuck transitions or on
 * "operator wanted SECURE but we fell back to PLAIN".
 */
@Readiness
@ApplicationScoped
public class MiniserverReadinessCheck implements HealthCheck
{
    @Inject
    LoxoneConfig config;

    @Inject
    MiniserverState miniserverState;

    @Inject
    ConnectionModeResolver modeResolver;

    @Inject
    BootstrapTracker bootstrapTracker;

    @Inject
    SessionTracker sessionTracker;

    @Override
    public HealthCheckResponse call()
    {
        // The probe is UP once the session reaches RUNNING. Any other state
        // (DISCONNECTED through AWAITING_*, plus FAILED/CLOSED) is DOWN. The
        // data payload always exposes the rich context so a monitoring stack
        // can alert on specific stuck transitions.
        boolean isUp = sessionTracker.state() == SessionState.RUNNING;
        HealthCheckResponseBuilder b = HealthCheckResponse.named( "miniserver-session" )
                                                          .status( isUp )
                                                          .withData( "miniserver.host", config.miniserver().connection().host() )
                                                          .withData( "miniserver.uuid", config.miniserver().app().id() )
                                                          .withData( "preferredMode", config.miniserver().connection().secure() ? "SECURE" : "PLAIN" )
                                                          .withData( "effectiveMode", modeResolver.effective().name() )
                                                          .withData( "bootstrapStatus", bootstrapTracker.status().name() )
                                                          .withData( "sessionState", sessionTracker.state().name() )
                                                          .withData( "reason", isUp
                                                                               ? "session RUNNING — handshake complete, binary state events flowing"
                                                                               : "handshake not complete — see sessionState + lastError; POST /api/v1/connect to start" );

        modeResolver.downgradeReason().ifPresent( r -> b.withData( "downgradeReason", r ) );
        bootstrapTracker.lastError().ifPresent( err -> b.withData( "bootstrapLastError", err ) );
        bootstrapTracker.completedAt().ifPresent( ts -> b.withData( "bootstrapCompletedAt", ts.toString() ) );
        sessionTracker.lastError().ifPresent( err -> b.withData( "sessionLastError", err ) );
        sessionTracker.connectedAt().ifPresent( ts -> b.withData( "sessionConnectedAt", ts.toString() ) );
        sessionTracker.token().ifPresent( t -> b.withData( "tokenExpiresAt", t.expiresAt().toString() ) );

        // Identity is null until the bootstrap orchestrator populates it.
        // When that happens the dashboard, /api/v1/state, and this probe all
        // light up with the same data at once — single source of truth in
        // MiniserverState.
        miniserverState.identity().ifPresent( ( MiniserverIdentity id ) ->
                                              {
                                                  b.withData( "identity.serial", id.serial() );
                                                  b.withData( "identity.version", id.version().toString() );
                                                  b.withData( "identity.generation", id.generation().name() );
                                                  b.withData( "identity.httpsStatus", id.httpsStatus().name() );
                                              } );

        return b.build();
    }
}
