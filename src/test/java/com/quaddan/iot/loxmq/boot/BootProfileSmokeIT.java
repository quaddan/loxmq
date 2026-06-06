/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.boot;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Packaging validation — verifies the packaged fast-jar boots
 * cleanly under the {@code staging} and {@code prod} profiles. The
 * existing {@link ApplicationSmokeIT} already covers {@code dev} via
 * Quarkus's {@code @QuarkusIntegrationTest} mechanism (which is bound
 * to a single Failsafe-level profile — see {@code pom.xml}'s
 * {@code quarkus.test.integration-test-profile=dev}); this IT extends
 * coverage to the other two by managing the subprocess directly.
 *
 * <h3>Why a separate IT</h3>
 * Quarkus's {@code @QuarkusIntegrationTest} launches the artifact once
 * per test class with a fixed profile baked into the launcher args.
 * Testing multiple profiles in one Failsafe execution requires manual
 * subprocess management — which is what this class does. The trade-off
 * is no HTTP smoke (we don't curl the running process), only "the JAR
 * boots cleanly" — which is exactly what the packaging-validation
 * TODO asks for.
 *
 * <h3>The TLS-cert juggling</h3>
 * The {@code staging} and {@code prod} profiles pin the TLS server
 * cert paths to {@code /etc/letsencrypt/live/example.com/...} —
 * production locations that don't exist on dev/CI boxes. We bypass
 * them at runtime by generating a self-signed PEM cert + key into
 * {@code target/test-certs/} via {@code openssl} (in {@code @BeforeAll})
 * and passing the paths as {@code -D} system properties to the
 * launched JVM. The {@code quarkus-tls-registry} extension reads
 * these at runtime (not build), so the override works against the
 * already-packaged jar.
 *
 * <h3>What this IT validates</h3>
 * <ul>
 *   <li>The packaged {@code target/quarkus-app/quarkus-run.jar} contains
 *       all the resources for both profiles (Qute templates,
 *       application-{staging,prod}.yml, OpenAPI schema).</li>
 *   <li>The TLS registry accepts cert-path overrides via {@code -D}
 *       — guards against a future refactor that would freeze the
 *       paths at build time.</li>
 *   <li>SmallRye Config parses each profile without
 *       {@code ConfigValidationException} — bean validation rules
 *       hold under each overlay.</li>
 *   <li>CDI / ArC startup completes — every bean creates without
 *       circular-dep / Unsatisfied issues under the profile-specific
 *       wiring.</li>
 * </ul>
 *
 * <h3>What this IT does NOT validate</h3>
 * Live HTTP / MQTT / miniserver round-trip under staging/prod — those
 * require real infrastructure (real broker, real miniserver, real
 * certs). The 24h staging deploy covers that.
 *
 * <h3>Prerequisites</h3>
 * <ul>
 *   <li>{@code mvn package -DskipTests} must have run (Failsafe phase
 *       comes AFTER {@code package}, so this is the default ordering).</li>
 *   <li>{@code openssl} on PATH (universally available on Linux CI;
 *       the test bails out with a clear message if missing).</li>
 * </ul>
 */
@DisplayName( "Packaged fast-jar boots under staging + prod profiles" )
class BootProfileSmokeIT
{
    /** Path to the packaged uberjar — produced by `mvn package` */
    private static final Path QUARKUS_RUN = Paths.get( "target", "quarkus-app", "quarkus-run.jar" );

    /** Where the self-signed test certs live. Under target/ → wiped by
     *  `mvn clean`. Not gitignored explicitly — the parent target/ is. */
    private static final Path TEST_CERT_DIR = Paths.get( "target", "test-certs" );
    private static       Path testCertPem;
    private static       Path testKeyPem;

    @BeforeAll
    static void generateSelfSignedCert() throws Exception
    {
        Files.createDirectories( TEST_CERT_DIR );
        testCertPem = TEST_CERT_DIR.resolve( "test-cert.pem" );
        testKeyPem  = TEST_CERT_DIR.resolve( "test-key.pem" );

        if ( Files.exists( testCertPem ) && Files.exists( testKeyPem ) )
        {
            // Reuse from a previous run within the same `mvn clean cycle` —
            // openssl is slow enough (~3 s) that caching matters when
            // running this IT class multiple times during development.
            return;
        }

        // `openssl req -x509 -newkey rsa:2048 -nodes -keyout key -out cert
        //               -days 36500 -subj /CN=loxone-binding-it`
        //
        // -nodes: no password on the private key (Quarkus TLS Registry
        //         expects it that way).
        // -days 36500: 100-year cert. Self-signed, only seen by the test
        //              subprocess — no real trust relationship at stake.
        // /CN=loxone-binding-it: minimal subject, no SANs needed.
        ProcessBuilder pb = new ProcessBuilder(
                "openssl", "req", "-x509",
                "-newkey", "rsa:2048", "-nodes",
                "-keyout", testKeyPem.toString(),
                "-out", testCertPem.toString(),
                "-days", "36500",
                "-subj", "/CN=loxone-binding-it" );
        pb.redirectErrorStream( true );
        Process p;
        try
        {
            p = pb.start();
        }
        catch ( java.io.IOException e )
        {
            throw new IllegalStateException(
                    "openssl not found on PATH — required for BootProfileSmokeIT cert generation. "
                    + "Install it (apt install openssl / brew install openssl) or skip with -Dit.test=!BootProfileSmokeIT.",
                    e );
        }
        boolean finished = p.waitFor( 30, TimeUnit.SECONDS );
        if ( !finished )
        {
            p.destroyForcibly();
            throw new IllegalStateException( "openssl timed out generating test cert" );
        }
        if ( p.exitValue() != 0 )
        {
            String output = new String( p.getInputStream().readAllBytes(), StandardCharsets.UTF_8 );
            throw new IllegalStateException( "openssl failed with exit " + p.exitValue() + ": " + output );
        }
        assertThat( testCertPem ).exists();
        assertThat( testKeyPem ).exists();
    }

    @AfterAll
    static void cleanup()
    {
        // Intentionally NOT deleting the test certs — they're under target/
        // (gitignored, cleaned by `mvn clean`) and re-using them across IT
        // class invocations saves ~3 s of openssl invocation.
    }

    @Test
    @DisplayName( "staging profile — fast-jar boots without errors (cert override OK, config valid)" )
    @Timeout( value = 60, unit = TimeUnit.SECONDS )
    void stagingBoots() throws Exception
    {
        bootArtifactWithProfile( "staging" );
    }

    @Test
    @DisplayName( "prod profile — fast-jar boots without errors (cert override OK, config valid)" )
    @Timeout( value = 60, unit = TimeUnit.SECONDS )
    void prodBoots() throws Exception
    {
        bootArtifactWithProfile( "prod" );
    }

    /** Launches {@code java -jar quarkus-run.jar} with the given profile +
     *  cert/key overrides, waits for the "Installed features:" line that
     *  signals successful Quarkus boot, asserts no "Failed to start
     *  application" was emitted along the way. */
    private void bootArtifactWithProfile( String profile ) throws Exception
    {
        assertThat( QUARKUS_RUN )
                .as( "Run `mvn package -DskipTests` before this IT" )
                .exists();

        String javaBin = Paths.get( System.getProperty( "java.home" ), "bin", "java" ).toString();

        ProcessBuilder pb = new ProcessBuilder(
                javaBin,
                "-Dquarkus.profile=" + profile,
                "-Dquarkus.tls.server.key-store.pem.0.cert=" + testCertPem,
                "-Dquarkus.tls.server.key-store.pem.0.key=" + testKeyPem,
                // No real broker / miniserver in this test — auto-start off so
                // boot completes quickly and doesn't burn 30 s in retries.
                "-Dloxone.boot.auto-start=false",
                // Random ephemeral ports so parallel test runs don't collide
                // on 8080/8443.
                "-Dquarkus.http.port=0",
                "-Dquarkus.http.ssl-port=0",
                "-jar", QUARKUS_RUN.toString() );
        pb.redirectErrorStream( true );

        Process                   process               = pb.start();
        List< String >            capturedLines         = new ArrayList<>();
        AtomicReference< String > installedFeaturesLine = new AtomicReference<>();
        AtomicReference< String > fatalLine             = new AtomicReference<>();

        try ( BufferedReader reader = new BufferedReader(
                new InputStreamReader( process.getInputStream(), StandardCharsets.UTF_8 ) ) )
        {
            String line;
            long   deadlineNs = System.nanoTime() + Duration.ofSeconds( 45 ).toNanos();
            while ( ( line = reader.readLine() ) != null )
            {
                capturedLines.add( line );

                if ( line.contains( "Installed features:" ) )
                {
                    installedFeaturesLine.set( line );
                    break;
                }
                // Quarkus's specific boot-failure signal — different from
                // routine ERROR-level logs that might appear later in steady
                // state. Catches DeploymentException, ConfigValidationException,
                // missing-cert, port-already-in-use, etc.
                if ( line.contains( "Failed to start application" )
                     || line.contains( "Failed to start quarkus" ) )
                {
                    fatalLine.set( line );
                }
                if ( System.nanoTime() > deadlineNs )
                {
                    break;
                }
            }
        }
        finally
        {
            process.destroyForcibly().waitFor( 5, TimeUnit.SECONDS );
        }

        if ( installedFeaturesLine.get() == null )
        {
            // Boot didn't complete — emit captured tail for debugging.
            String tail = capturedLines.stream()
                                       .skip( Math.max( 0, capturedLines.size() - 30 ) )
                                       .reduce( "", ( a, b ) -> a + "\n" + b );
            throw new AssertionError(
                    "Profile " + profile + " did not reach 'Installed features:' within 45 s. "
                    + "Fatal line: " + fatalLine.get() + "\n--- last 30 log lines ---" + tail );
        }
        assertThat( fatalLine.get() )
                .as( "Profile %s logged a Quarkus boot failure", profile )
                .isNull();
    }
}
