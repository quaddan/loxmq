/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.boot;

import com.quaddan.iot.loxmq.config.LoxoneConfig;
import com.quaddan.iot.loxmq.miniserver.bootstrap.BootstrapException;
import com.quaddan.iot.loxmq.miniserver.bootstrap.BootstrapOrchestrator;
import com.quaddan.iot.loxmq.miniserver.connection.ConnectionModeResolver;
import com.quaddan.iot.loxmq.miniserver.connection.EndpointResolver;
import com.quaddan.iot.loxmq.miniserver.session.SessionException;
import com.quaddan.iot.loxmq.miniserver.session.SessionOrchestrator;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Application entry-point CDI bean.
 * <p>
 * Quarkus's CDI container creates this bean during {@code @PostConstruct}-phase
 * processing; the {@link StartupEvent} observer fires once after every other
 * bean is wired and the HTTP server is bound — the canonical "binding ready"
 * moment. The {@link ShutdownEvent} observer fires during graceful shutdown
 * (SIGTERM, container stop, dev-mode reload) before any CDI {@code @PreDestroy}.
 * <p>
 * Quarkus owns the JVM main class; this bean only observes the lifecycle
 * events it raises.
 *
 * <h3>What this class does</h3>
 * Logs the boot/shutdown markers and prints a one-line summary of where the
 * various endpoints are reachable. The actual miniserver + MQTT lifecycle
 * lives in dedicated services (notably {@code BootAutoStarter} when
 * {@code loxone.boot.auto-start=true}); starting them from this class would
 * couple the entry-point to every downstream concern.
 */
@ApplicationScoped
public class Application
{
    private static final Logger LOG = Logger.getLogger( Application.class );

    @Inject
    LoxoneConfig config;

    @Inject
    ConnectionModeResolver modeResolver;

    @Inject
    EndpointResolver endpointResolver;

    @Inject
    BootstrapOrchestrator bootstrap;

    @Inject
    SessionOrchestrator session;

    @ConfigProperty( name = "quarkus.application.name" )
    String applicationName;

    @ConfigProperty( name = "quarkus.application.version" )
    String applicationVersion;

    @ConfigProperty( name = "quarkus.profile" )
    String activeProfile;

    @ConfigProperty( name = "quarkus.http.host", defaultValue = "0.0.0.0" )
    String httpHost;

    @ConfigProperty( name = "quarkus.http.port", defaultValue = "8080" )
    int httpPort;

    @ConfigProperty( name = "quarkus.http.ssl-port", defaultValue = "8443" )
    int httpsPort;

    void onStart( @Observes StartupEvent event )
    {
        LOG.infof( "==================== %s %s started (profile=%s) ====================",
                   applicationName, applicationVersion, activeProfile );

        LOG.infof( "Management UI    ⇨ https://%s:%d/  (or http://%s:%d/ if insecure-requests=enabled)",
                   resolvePublicHost(), httpsPort, resolvePublicHost(), httpPort );
        LOG.infof( "Health           ⇨ /q/health (live=/q/health/live, ready=/q/health/ready)" );
        LOG.infof( "Metrics          ⇨ /q/metrics  (Prometheus exposition)" );
        LOG.infof( "OpenAPI / Swagger⇨ /q/openapi  /  /q/swagger-ui" );

        // Log preferred + effective mode. Effective resolves to PLAIN
        // pre-bootstrap (we don't know the miniserver's TLS readiness yet);
        // the orchestrator re-resolves once jdev/cfg/apiKey lands.
        boolean preferSecure = config.miniserver().connection().secure();
        LOG.infof( "Miniserver       ⇨ %s  (ws ⇨ %s) — preferred=%s, effective=%s (uuid=%s)",
                   endpointResolver.httpEndpoint(),
                   endpointResolver.wsEndpoint(),
                   preferSecure ? "SECURE" : "PLAIN",
                   modeResolver.effective().name(),
                   config.miniserver().app().id() );
        modeResolver.downgradeReason().ifPresent( r ->
                                                          LOG.infof( "Miniserver mode  ⇨ downgrade reason: %s", r ) );

        LOG.infof( "MQTT broker      ⇨ %s://%s:%d%s  (mode=%s, qos=%d)",
                   config.transport().connection().protocol(),
                   config.transport().connection().host(),
                   config.transport().connection().port(),
                   config.transport().connection().path().orElse( "" ),
                   config.transport().mode(),
                   config.transport().topics().qos() );

        // When the master switch boot.auto-start=true, the BootAutoStarter
        // bean owns the boot chain — MQTT, bootstrap and session in strict
        // order. Skip our own per-service miniserver auto-connect to avoid
        // a double bootstrap+session attempt.
        if ( config.boot().autoStart() )
        {
            LOG.info( "miniserver.auto-connect deferred to boot.auto-start orchestrator (MQTT → Bootstrap → Miniserver)" );
        }
        // Optional auto-connect. Opt-in via
        // `loxone.miniserver.auto-connect=true`. Errors are logged WARN — we
        // don't fail Quarkus startup if the miniserver is unreachable, so
        // the dashboard / management API remain available for diagnosis.
        else if ( config.miniserver().autoConnect() )
        {
            LOG.info( "auto-connect=true → running bootstrap + handshake at startup" );
            try
            {
                bootstrap.run();
                session.connectAndWait( 30 );
                LOG.info( "Auto-connect → session RUNNING" );
            }
            catch ( BootstrapException e )
            {
                LOG.warnf( "Auto-connect: bootstrap failed (%s). Manual recovery: POST /api/v1/bootstrap once the miniserver is reachable.", e.getMessage() );
            }
            catch ( SessionException e )
            {
                LOG.warnf( "Auto-connect: handshake failed (%s). The reconnect scheduler will keep retrying (see loxone.miniserver.reconnect.*).", e.getMessage() );
            }
            catch ( RuntimeException e )
            {
                LOG.warnf( e, "Auto-connect: unexpected failure" );
            }
        }
        else
        {
            LOG.info( "auto-connect=false (default) — call POST /api/v1/bootstrap + POST /api/v1/connect to start the session" );
        }
    }

    void onStop( @Observes ShutdownEvent event )
    {
        LOG.infof( "==================== %s %s stopping ====================",
                   applicationName, applicationVersion );
    }

    /**
     * Resolve the host name to display in startup logs. The bind address
     * ({@code 0.0.0.0}) is not very useful for the operator — substitute the
     * configured FQDN where possible so the log line shows the URL one can
     * actually paste into a browser.
     *
     * <p>Reads the optional {@code loxone.management.public-host} config
     * knob — empty by default, the operator sets it per profile. Without
     * it, the previous hardcoded value collided when an operator reused
     * a Miniserver hostname here, sending the browser to the wrong UI.
     */
    private String resolvePublicHost()
    {
        return config.management().publicHost().orElse( httpHost );
    }
}
