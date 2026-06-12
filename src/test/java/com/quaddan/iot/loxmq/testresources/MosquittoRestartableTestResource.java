/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.testresources;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;

/**
 * Sibling of {@link MosquittoTestResource} dedicated to broker crash /
 * recovery scenarios. Two differences from the plain resource:
 *
 * <ol>
 *   <li><b>Fixed host port</b> — pinned at startup via
 *       {@code withCreateContainerCmdModifier}. A random free port is grabbed
 *       once and reused across the lifecycle, so when the binding's HiveMQ
 *       auto-reconnect retries against the host:port it cached at startup,
 *       a freshly-spawned replacement broker is reachable at the same
 *       address.</li>
 *   <li><b>Static {@link #crashBroker()} / {@link #reviveBroker()}</b> —
 *       the IT can drive the lifecycle in mid-test (the {@code stop()} +
 *       fresh container pattern, rather than just stop). The mapped port
 *       persists because we pinned it.</li>
 * </ol>
 *
 * <h3>Why pin the port instead of restarting in place</h3>
 * Calling {@code container.start()} again on a stopped {@code GenericContainer}
 * lets Testcontainers create a NEW container with NEW random port mappings —
 * the binding would then be stuck retrying the old port forever. Pinning the
 * host port + creating a fresh container on the same port at revive time is
 * the simplest way to model a broker crash that the binding can recover from.
 *
 * <h3>Why not just {@code docker pause}</h3>
 * Pause sends SIGSTOP — TCP connections hang rather than receiving RST. The
 * binding's HiveMQ keepalive would eventually time out, but only after the
 * configured keepalive interval (60 s default in HiveMQ client 1.3.x). That
 * makes the IT either slow or flaky. A real {@code stop} returns SIGKILL,
 * the kernel sends RST on the half-open sockets, HiveMQ detects the drop
 * within milliseconds and starts the {@code automaticReconnect()} retry
 * cycle immediately — which is also closer to what a real broker crash
 * looks like.
 *
 * <h3>Why not a single shared resource (parameterized by mode)</h3>
 * Keeping {@link MosquittoTestResource} untouched lets the existing
 * {@code LiveBrokerIT} stay on the simple random-port path with zero risk
 * of regression. The ~30 lines of duplication here are acceptable cost.
 *
 * <p>Same Mosquitto image + config + anonymous-auth choices as the
 * sibling resource — see its javadoc for the rationale.
 */
public final class MosquittoRestartableTestResource implements QuarkusTestResourceLifecycleManager
{
    private static final String IMAGE         = "eclipse-mosquitto:2.0.18";
    private static final int    MQTT_TCP_PORT = 1883;

    /**
     * Auth is <b>required</b>. The matching credentials come from the
     * dev profile ({@code application-dev.yaml}) which is what
     * the IT runs under (cf. {@code quarkus.test.integration-test-profile=dev}
     * in {@code pom.xml}).
     * <p>
     * Pinning the credentials in the test broker exercises the
     * auto-reconnect auth path — anonymous-only test brokers cannot
     * surface regressions where simpleAuth ends up on the per-call
     * connect builder instead of the client builder (reconnect CONNECT
     * then carries no credentials → {@code NOT_AUTHORIZED} loop after
     * broker restart).
     *
     * <p>Non-prod test credentials — generated specifically for the IT
     * suite, never reused outside this fixture. The Mosquitto password
     * file ({@link #PASSWD_FILE}) is a PBKDF2-SHA512 hash of these.
     */
    public static final String AUTH_USER = "loxmq_test";
    public static final String AUTH_PASS = "loxmq_test_pwd";

    private static String base64( String s )
    {
        return Base64.getEncoder().encodeToString( s.getBytes( StandardCharsets.UTF_8 ) );
    }

    /**
     * Pre-baked mosquitto_passwd output for {@link #AUTH_USER}:{@link #AUTH_PASS}.
     *
     * <p>Generated once via :
     * <pre>
     *   docker run --rm eclipse-mosquitto:2.0.18 sh -c \
     *     "mosquitto_passwd -b -c /tmp/p loxmq_test 'loxmq_test_pwd' &amp;&amp; cat /tmp/p"
     * </pre>
     *
     * <p>The previous {@link #spawnContainer()} used
     * {@code withCommand("/bin/sh", "-c", "mosquitto_passwd … &amp;&amp; mosquitto …")}
     * to generate the file at start time but the override didn't take
     * (Mosquitto would start without the passwd file existing). Switching
     * to a pre-baked fixture via {@link
     * org.testcontainers.images.builder.Transferable} sidesteps the
     * {@code withCommand} quirk entirely.
     *
     * <p>Any change to {@link #AUTH_USER} or {@link #AUTH_PASS} requires
     * regenerating the line with the docker command above. The salt is
     * randomised by {@code mosquitto_passwd} on each run.
     */
    private static final String PASSWD_FILE =
            "loxmq_test:$7$101$2dZ/9xxtl0n8n6Fi$UVIk8DY+xmmXhxZVH5Upb+w1+zpDMCeIgamChrY97q/F3k5VlAOc5owOkxOvxrinv1Kzh2F2vpz9P2MuEapCUA==\n";

    private static final String MOSQUITTO_CONF = """
                                                 listener 1883
                                                 allow_anonymous false
                                                 password_file /mosquitto/config/passwd
                                                 log_type all
                                                 log_dest stdout
                                                 """;

    /** Host port chosen ONCE at first start, reused on every revive so the
     *  binding's cached host:port stays valid across the crash window. */
    private static volatile int                   fixedPort;
    private static volatile String                host;
    /** The live container — null between crash and revive. */
    private static volatile GenericContainer< ? > mosquitto;

    public static String brokerHost() { return host; }

    public static int brokerPort() { return fixedPort; }

    public static boolean isRunning() { return mosquitto != null && mosquitto.isRunning(); }

    @Override
    public Map< String, String > start()
    {
        detectRootlessDockerSocket();
        fixedPort = findFreePort();
        spawnContainer();
        host = mosquitto.getHost();
        // Credentials enabled — broker requires them. We override the four
        // transport.security.credentials.* props here so the fixture is
        // self-contained (no dependence on which profile the IT runs).
        // Values are Base64-encoded per Quarkus convention (the binding
        // base64-decodes before forwarding to HiveMQ).
        return Map.of(
                "loxone.transport.connection.protocol", "tcp",
                "loxone.transport.connection.secure", "false",
                "loxone.transport.connection.host", host,
                "loxone.transport.connection.port", String.valueOf( fixedPort ),
                "loxone.transport.security.credentials.enable", "true",
                "loxone.transport.security.credentials.user", base64( AUTH_USER ),
                "loxone.transport.security.credentials.password", base64( AUTH_PASS )
                     );
    }

    @Override
    public void stop()
    {
        crashBroker();
        host      = null;
        fixedPort = 0;
    }

    /**
     * Stop the broker container — simulates a broker crash from the
     * binding's point of view. After this returns, HiveMQ's
     * {@code automaticReconnect()} starts retrying against the (now
     * unreachable) host:port. Idempotent.
     */
    public static synchronized void crashBroker()
    {
        if ( mosquitto != null )
        {
            mosquitto.stop();
            mosquitto = null;
        }
    }

    /**
     * Start a fresh Mosquitto container on the same fixed host port that
     * was reserved at first {@link #start()}. The binding's next reconnect
     * attempt should succeed within {@code loxone.transport.reconnection.min-delay}
     * (default 3 s) — give it a bit more in tests.
     * <p>
     * No-op if a container is already running.
     */
    public static synchronized void reviveBroker()
    {
        if ( mosquitto != null && mosquitto.isRunning() )
        { return; }
        spawnContainer();
    }

    // ------------------------------------------------------------------------
    //  Internals
    // ------------------------------------------------------------------------

    private static void spawnContainer()
    {
        // Both the config and the password file are copied in as
        // Transferable fixtures. Auth is enabled in the conf (cf.
        // {@link #MOSQUITTO_CONF}) and the passwd file is the pre-computed
        // mosquitto_passwd output (cf. {@link #PASSWD_FILE}). No entrypoint
        // override needed — Mosquitto's default CMD just works.
        mosquitto = new GenericContainer<>( DockerImageName.parse( IMAGE ) )
                            .withExposedPorts( MQTT_TCP_PORT )
                            .withCopyToContainer( Transferable.of( MOSQUITTO_CONF.getBytes() ),
                                                  "/mosquitto/config/mosquitto.conf" )
                            .withCopyToContainer( Transferable.of( PASSWD_FILE.getBytes() ),
                                                  "/mosquitto/config/passwd" )
                            .withCreateContainerCmdModifier( cmd ->
                                                                     cmd.getHostConfig().withPortBindings(
                                                                             new PortBinding(
                                                                                     Ports.Binding.bindPort( fixedPort ),
                                                                                     new ExposedPort( MQTT_TCP_PORT ) ) ) )
                            .withLogConsumer( new Slf4jLogConsumer( LoggerFactory.getLogger( "MosquittoRestart" ) ) )
                            .waitingFor( Wait.forListeningPort() );
        mosquitto.start();
    }

    /** Grab a free local port. The port is held only until the
     *  {@code ServerSocket} closes (immediately) — Docker should be able to
     *  bind to it microseconds later. A race is theoretically possible
     *  (some other process grabs the same port in that window) but in
     *  practice fine for a single IT runner. */
    private static int findFreePort()
    {
        try ( ServerSocket s = new ServerSocket( 0 ) )
        {
            return s.getLocalPort();
        }
        catch ( Exception e )
        {
            throw new RuntimeException( "Cannot grab a free local port for Mosquitto", e );
        }
    }

    /** Same fixup as {@link MosquittoTestResource} — see its javadoc for
     *  the rootless Docker socket + API version pinning rationale. */
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
