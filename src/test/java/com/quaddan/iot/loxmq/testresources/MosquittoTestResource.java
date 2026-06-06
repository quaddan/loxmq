/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.testresources;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Stands up a real Mosquitto MQTT v5 broker in Docker for the
 * integration tests, and rewires the binding to point at it.
 *
 * <h3>What this overrides</h3>
 * Only the broker location + auth posture — anything related to topics
 * (root, will, publish.*) keeps its profile default, so the IT exercises
 * the exact topology the binding ships with.
 * <pre>
 *   loxone.transport.connection.protocol            → tcp
 *   loxone.transport.connection.secure              → false
 *   loxone.transport.connection.host                → &lt;mapped from Docker&gt;
 *   loxone.transport.connection.port                → &lt;mapped from Docker&gt;
 *   loxone.transport.security.credentials.enable    → false
 * </pre>
 *
 * <h3>Why TCP (1883) and not WS (8083)</h3>
 * Mosquitto's WS listener requires extra config (a separate
 * {@code listener 8083 protocol websockets} block plus
 * {@code http_dir}). For an IT that just wants a working broker we
 * use plain TCP and override the protocol on the binding side. The
 * production code path (ws / wss) is unit-tested separately via
 * {@code TransportConnectionResolver} tests.
 *
 * <h3>Why anonymous</h3>
 * Same reason as TCP-vs-WS: the alternative is mounting a Mosquitto
 * {@code password_file} with a hashed entry, which doubles the
 * test-resource surface area for zero added coverage of the binding
 * (which only knows "send these creds" — fully tested in unit). We
 * also turn the binding's {@code credentials.enable} off so it doesn't
 * try to send creds the broker would just ignore.
 *
 * <h3>Lifecycle</h3>
 * Started once per {@code @QuarkusIntegrationTest} class that wires
 * it via {@code @QuarkusTestResource(MosquittoTestResource.class)}.
 * Failsafe re-runs the resource between IT classes unless
 * {@code restrictToAnnotatedClass=false} is set on the annotation —
 * we keep it restricted so each IT class gets a fresh broker
 * (clean retained store, no test cross-talk).
 */
public final class MosquittoTestResource implements QuarkusTestResourceLifecycleManager
{
    /** Pinned to a current stable Mosquitto 2.x. Bump in lockstep
     *  with whatever the operator's prod broker runs to keep the IT
     *  representative of production. */
    private static final String IMAGE         = "eclipse-mosquitto:2.0.18";
    private static final int    MQTT_TCP_PORT = 1883;

    /** Minimal Mosquitto config — bind to all interfaces on 1883
     *  (the default config binds to 127.0.0.1 only, which doesn't
     *  work from outside the container) and accept anonymous.
     *  See class-level javadoc for why anonymous is acceptable here. */
    private static final String MOSQUITTO_CONF = """
                                                 listener 1883
                                                 allow_anonymous true
                                                 log_type all
                                                 log_dest stdout
                                                 """;

    private GenericContainer< ? > mosquitto;

    /** Live broker location, set in {@link #start()} and exposed via
     *  {@link #brokerHost()} so IT classes can stand up an independent
     *  MQTT observer against the same broker. The Map returned from
     *  {@code start()} is pushed as system properties to the launched
     *  Quarkus subprocess (for binding-side config) but NOT to this
     *  test JVM — the test class can't reach it via
     *  {@code System.getProperty(...)} reliably. Static fields are the
     *  simplest cross-JVM-boundary channel. */
    private static volatile String  brokerHost;
    private static volatile Integer brokerPort;

    /** Mosquitto host as visible from the test JVM (localhost on a typical
     *  Linux + rootless Docker setup; can differ on Docker Desktop where
     *  Testcontainers may use {@code host.docker.internal}). */
    public static String brokerHost() { return brokerHost; }

    /** Random high port mapped by Docker to {@link #MQTT_TCP_PORT}. */
    public static int brokerPort() { return brokerPort; }

    @Override
    public Map< String, String > start()
    {
        detectRootlessDockerSocket();
        mosquitto = new GenericContainer<>( DockerImageName.parse( IMAGE ) )
                            .withExposedPorts( MQTT_TCP_PORT )
                            .withCopyToContainer( Transferable.of( MOSQUITTO_CONF.getBytes() ),
                                                  "/mosquitto/config/mosquitto.conf" )
                            .withLogConsumer( new Slf4jLogConsumer( LoggerFactory.getLogger( "Mosquitto" ) ) )
                            .waitingFor( Wait.forListeningPort() );
        mosquitto.start();

        brokerHost = mosquitto.getHost();
        brokerPort = mosquitto.getMappedPort( MQTT_TCP_PORT );
        return Map.of(
                "loxone.transport.connection.protocol", "tcp",
                "loxone.transport.connection.secure", "false",
                "loxone.transport.connection.host", brokerHost,
                "loxone.transport.connection.port", String.valueOf( brokerPort ),
                "loxone.transport.security.credentials.enable", "false"
                     );
    }

    @Override
    public void stop()
    {
        if ( mosquitto != null )
        {
            mosquitto.stop();
            mosquitto = null;
        }
        brokerHost = null;
        brokerPort = null;
    }

    /**
     * Make Testcontainers see the rootless Docker socket.
     * <p>
     * Two distinct mismatches between the dev workstation and
     * Testcontainers' defaults need fixing here:
     *
     * <h4>1. Rootless socket location</h4>
     * Default probe: {@code DOCKER_HOST} env var → {@code /var/run/docker.sock}.
     * Neither exists on a rootless install — the socket lives at
     * {@code $XDG_RUNTIME_DIR/docker.sock}
     * (typically {@code /run/user/<uid>/docker.sock}).
     *
     * <h4>2. Docker API version pinning</h4>
     * Docker-java 3.4.x (bundled with Testcontainers 1.20.x) defaults
     * to API version 1.32 on the request line. Docker engine 25+
     * retired 1.32 and now returns:
     * <pre>
     *   Status 400: client version 1.32 is too old.
     *   Minimum supported API version is 1.40, please upgrade your client
     * </pre>
     * Pin a current-but-conservative version (1.43 — around since
     * Docker 24 in 2023, supports everything Testcontainers does).
     *
     * <h4>How we tell Testcontainers</h4>
     * {@link System#setProperty}. docker-java's
     * {@code DefaultDockerClientConfig.createDefaultConfigBuilder()}
     * reads system properties whose names match the docker env-var
     * conventions — {@code "DOCKER_HOST"} (UPPER) and {@code "api.version"}
     * (lower.dot). Misnaming them (e.g. {@code "docker.host"}) is a
     * silent no-op, which is what made the first attempts in this
     * patch waste an hour. Watch the casing.
     * <p>
     * Done as system properties (not the {@code ~/.testcontainers.properties}
     * file used by an earlier draft) to keep the fix self-contained
     * inside the test JVM — no disk side-effects, nothing left on the
     * operator's machine after the test run.
     * <p>
     * No-ops when:
     * <ul>
     *   <li>{@code DOCKER_HOST} env var is already set — operator
     *       override wins.</li>
     *   <li>{@code /var/run/docker.sock} exists — root-mode Docker
     *       (normal CI runner) doesn't need any of this.</li>
     *   <li>The rootless socket can't be located via {@code XDG_RUNTIME_DIR}
     *       — let Testcontainers fail loudly with its own diagnostic
     *       rather than masking the real environment problem.</li>
     * </ul>
     */
    private static void detectRootlessDockerSocket()
    {
        if ( System.getenv( "DOCKER_HOST" ) != null )
        { return; }
        if ( Files.exists( Path.of( "/var/run/docker.sock" ) ) )
        { return; }

        String xdg = System.getenv( "XDG_RUNTIME_DIR" );
        if ( xdg == null || xdg.isBlank() )
        { return; }

        Path rootless = Path.of( xdg, "docker.sock" );
        if ( !Files.exists( rootless ) )
        { return; }

        System.setProperty( "DOCKER_HOST", "unix://" + rootless );
        System.setProperty( "api.version", "1.43" );
    }
}
