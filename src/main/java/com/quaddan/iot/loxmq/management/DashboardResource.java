/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.management;

import com.quaddan.iot.loxmq.util.templates.DashboardTemplateExtensions;
import com.quaddan.iot.loxmq.config.LoxoneConfig;
import com.quaddan.iot.loxmq.miniserver.bootstrap.BootstrapTracker;
import com.quaddan.iot.loxmq.miniserver.connection.ConnectionModeResolver;
import com.quaddan.iot.loxmq.miniserver.connection.EndpointResolver;
import com.quaddan.iot.loxmq.miniserver.identity.MiniserverIdentity;
import com.quaddan.iot.loxmq.miniserver.session.KeepAliveScheduler;
import com.quaddan.iot.loxmq.miniserver.session.SessionTracker;
import com.quaddan.iot.loxmq.miniserver.session.TokenRefreshScheduler;
import com.quaddan.iot.loxmq.miniserver.state.FirmwareUpdateService;
import com.quaddan.iot.loxmq.miniserver.state.MiniserverState;
import com.quaddan.iot.loxmq.miniserver.state.SdCardHealthService;
import com.quaddan.iot.loxmq.miniserver.state.SdCardStatus;
import com.quaddan.iot.loxmq.transport.MqttClient;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.Instant;
import java.util.Optional;

/**
 * Server-rendered HTML dashboard for the operator.
 * <p>
 * Mounted at {@code /} (root). One page, served by a Qute template
 * ({@code src/main/resources/templates/dashboard.html}). The page polls
 * {@code /api/v1/state} via HTMX to keep its panels live without full reloads.
 *
 * <p>Compile-time-safe templates via {@link CheckedTemplate}: the template name
 * + parameters are validated by the Quarkus build, so renaming or removing a
 * parameter without updating the HTML fails the build instead of failing at
 * runtime.
 */
@Path( "/" )
@Tag( name = "Dashboard", description = "Server-rendered HTML dashboard for the operator." )
public class DashboardResource
{
    @Inject
    LoxoneConfig config;

    @Inject
    MiniserverState miniserverState;

    @Inject
    ConnectionModeResolver modeResolver;

    @Inject
    EndpointResolver endpointResolver;

    @Inject
    BootstrapTracker bootstrapTracker;

    @Inject
    SessionTracker sessionTracker;

    @Inject
    MqttClient mqtt;

    @Inject
    KeepAliveScheduler keepAliveScheduler;

    @Inject
    TokenRefreshScheduler tokenRefreshScheduler;

    @Inject
    SdCardHealthService sdCardHealth;

    @Inject
    FirmwareUpdateService firmwareUpdateService;

    @ConfigProperty( name = "quarkus.application.name" )
    String applicationName;

    @ConfigProperty( name = "quarkus.application.version" )
    String applicationVersion;

    @ConfigProperty( name = "quarkus.profile" )
    String activeProfile;

    @CheckedTemplate
    static class Templates
    {
        // Enum-typed parameters are passed as their String name(), not as
        // the enum itself: Qute's value-resolver chain in native-image
        // mode cannot reach Enum.name() (the method is inherited from
        // java.lang.Enum, not declared on our enum, so neither
        // @TemplateData nor @RegisterForReflection give Qute a usable
        // accessor). Pre-computing the names in Java avoids the runtime
        // reflection dance entirely. Same reasoning for the nested
        // identity.httpsStatus and bootstrap.status enums — exposed as
        // sibling String parameters rather than via the bean's getter.
        public static native TemplateInstance dashboard( String appName,
                                                         String appVersion,
                                                         String activeProfile,
                                                         String renderedAt,
                                                         LoxoneConfig config,
                                                         String effectiveMode,             // ConnectionMode.name()
                                                         String effectiveEndpoint,
                                                         String wsEndpoint,
                                                         Optional< String > downgradeReason,
                                                         Optional< MiniserverIdentity > identity,
                                                         Optional< String > identityHttpsStatus,  // HttpsStatus.name() when identity present
                                                         BootstrapTracker bootstrap,
                                                         String bootstrapStatus,           // BootstrapStatus.name()
                                                         String sessionState,              // SessionState.name()
                                                         boolean brokerConnected,
                                                         // Health panel
                                                         boolean keepaliveScheduled,
                                                         Optional< Long > lastKeepaliveRttMs,
                /* CSS badge class derived from the RTT bucket
                 * ("up" / "warn" / "down" / empty). Computed in
                 * Java because Qute's @CheckedTemplate rejects
                 * inline `&lt;` comparisons. */
                                                         String rttBadgeClass,
                                                         Optional< Instant > lastKeepaliveResponseAt,
                                                         Optional< Long > lastHandshakeDurationMs,
                                                         // Token block surfaced for the dashboard
                                                         // Miniserver column. The 4 fields exposed
                                                         // as siblings (rather than
                                                         // Optional<MiniserverToken>) so that the
                                                         // template can render them without poking
                                                         // through Qute's value-resolver chain in
                                                         // native image (same pattern as identity /
                                                         // bootstrap above).
                                                         boolean tokenPresent,
                                                         Optional< Instant > tokenExpiresAt,
                                                         Optional< Boolean > tokenExpired,
                                                         Optional< Instant > nextTokenRefreshAt,
                                                         // SD-card self-test (jdev/sys/sdtest) — Miniserver
                                                         // Identity panel. sdCardPresent gates the row's
                                                         // OK/ERROR badge vs a "pending" placeholder; detail
                                                         // is the verbatim performance report.
                                                         boolean sdCardPresent,
                                                         boolean sdCardHealthy,
                                                         String sdCardDetail,
                                                         // Firmware up-to-date check (Loxone updatecheck.xml).
                                                         // firmwareChecked gates the badge; latest is the newest
                                                         // published Release version for this generation.
                                                         boolean firmwareChecked,
                                                         boolean firmwareUpToDate,
                                                         Optional< String > firmwareLatest );
    }

    @GET
    @Produces( MediaType.TEXT_HTML )
    public TemplateInstance dashboard()
    {
        // renderedAt is shown in the header meta — format locally here
        // since the value is born as a fresh Instant and doesn't pass
        // through the template-extension path the other timestamps use.
        String renderedAtLocal =
                DashboardTemplateExtensions
                        .formatLocal( Instant.now() );

        // Extract token block once for the dashboard Token section.
        // tokenPresent gates the whole panel ; the three Optionals
        // carry the data when present, all empty otherwise.
        var tokenOpt = sessionTracker.token();
        // SD-card self-test result (jdev/sys/sdtest, run once per RUNNING
        // transition). Exploded into present/healthy/detail siblings so the
        // template renders without poking the Optional through Qute's
        // value-resolver chain in native image (same pattern as token block).
        var sdOpt = sdCardHealth.status();
        var fwOpt = firmwareUpdateService.status();
        return Templates.dashboard( applicationName,
                                    applicationVersion,
                                    activeProfile,
                                    renderedAtLocal,
                                    config,
                                    modeResolver.effective().name(),
                                    endpointResolver.httpEndpoint().toString(),
                                    endpointResolver.wsEndpoint().toString(),
                                    modeResolver.downgradeReason(),
                                    miniserverState.identity(),
                                    miniserverState.identity().map( id -> id.httpsStatus().name() ),
                                    bootstrapTracker,
                                    bootstrapTracker.status().name(),
                                    sessionTracker.state().name(),
                                    mqtt.isConnected(),
                                    keepAliveScheduler.isScheduled(),
                                    keepAliveScheduler.lastRtt().map( d -> d.toMillis() ),
                                    rttBadgeClass( keepAliveScheduler.lastRtt().map( d -> d.toMillis() ).orElse( null ) ),
                                    keepAliveScheduler.lastResponseAt(),
                                    sessionTracker.lastHandshakeDuration().map( d -> d.toMillis() ),
                                    tokenOpt.isPresent(),
                                    tokenOpt.map( t -> t.expiresAt() ),
                                    tokenOpt.map( t -> t.expired() ),
                                    tokenRefreshScheduler.nextRefreshAt(),
                                    sdOpt.isPresent(),
                                    sdOpt.map( SdCardStatus::healthy ).orElse( false ),
                                    sdOpt.map( SdCardStatus::detail ).orElse( "" ),
                                    fwOpt.isPresent(),
                                    fwOpt.map( FirmwareUpdateService.Status::upToDate ).orElse( false ),
                                    fwOpt.map( s -> s.latest().toString() ) );
    }

    /** Bucket the RTT (ms) into a CSS badge class. {@code "up"} for snappy
     *  links (&lt;100 ms), {@code "warn"} for noticeable lag (&lt;500 ms),
     *  {@code "down"} for poor (≥500 ms). Empty string when there's no
     *  measurement yet — the template suppresses the badge entirely. */
    private static String rttBadgeClass( Long rttMs )
    {
        if ( rttMs == null )
        { return ""; }
        if ( rttMs < 100 )
        { return "up"; }
        if ( rttMs < 500 )
        { return "warn"; }
        return "down";
    }
}
