/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.boot;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Full-surface smoke test against the PACKAGED artifact.
 * <p>
 * Activated by the {@code integration} Maven profile (`mvn verify -Pintegration`).
 * Failsafe builds the JVM fast-jar — or the native binary if {@code -Pnative}
 * is also active — then launches it in a subprocess. RestAssured then walks
 * the 10 management endpoints, asserting only on stable surface so the IT
 * survives version bumps (the unit-level {@link ApplicationSmokeTest}
 * lives at the same level of stability and asserts the same way).
 *
 * <h3>Why this matters in addition to {@link ApplicationSmokeTest}</h3>
 * The sister class boots Quarkus in the same JVM as the test (fast, with full
 * test-classpath visibility). The IT runs the actual packaged artefact in a
 * SUBPROCESS — so it catches issues the in-JVM test cannot see:
 * <ul>
 *   <li>missing resources in {@code target/quarkus-app/} (templates, OpenAPI
 *       schema, config files not copied by the build)</li>
 *   <li>classloader split bugs (CDI bean visible in tests but not in the
 *       packaged jar)</li>
 *   <li>profile selection at startup (the JAR defaults to {@code prod} unless
 *       overridden — we pass {@code -Dquarkus.profile=dev} via
 *       {@code quarkus.test.arg-line} in the Failsafe config because the
 *       {@code dev} overlay is the only packaged profile without TLS)</li>
 *   <li>{@code -Pnative}-mode reflection / resource-inclusion regressions
 *       (when Failsafe runs against the native binary, this same test exercises
 *       the AOT-compiled image)</li>
 * </ul>
 *
 * <h3>Endpoint walk (10)</h3>
 * <ol>
 *   <li>{@code GET  /}                    — Qute dashboard renders</li>
 *   <li>{@code GET  /api/v1/state}        — JSON config + runtime snapshot</li>
 *   <li>{@code POST /api/v1/reconnect}    — 502 handshake_failed (no bootstrap done in the IT)</li>
 *   <li>{@code POST /api/v1/token/kill}   — 200 idempotent no-op (no token held)</li>
 *   <li>{@code GET  /q/health}            — aggregate 503 (readiness DOWN — no live miniserver/broker)</li>
 *   <li>{@code GET  /q/health/live}       — 200 UP</li>
 *   <li>{@code GET  /q/health/ready}      — 503 DOWN with stage-specific reasons</li>
 *   <li>{@code GET  /q/metrics}           — Prometheus exposition</li>
 *   <li>{@code GET  /q/openapi}           — OpenAPI 3 schema (YAML)</li>
 *   <li>{@code GET  /q/swagger-ui}        — Swagger UI (dev profile opens it)</li>
 * </ol>
 *
 * <h3>What this does NOT cover</h3>
 * No real miniserver or broker round-trip — those live in the deeper
 * integration tests that bring up a Testcontainers Mosquitto + a fake WS
 * Loxone server to exercise the full session lifecycle end-to-end against
 * the packaged artifact. This file stays focused on the boot surface.
 */
@QuarkusIntegrationTest
@DisplayName( "Application — packaged-artifact smoke (10 endpoints)" )
class ApplicationSmokeIT
{
    // -------- 1. Dashboard --------

    @Test
    @DisplayName( "1/10 — GET / renders the Qute dashboard" )
    void dashboard()
    {
        // We assert just on the two stable structural panels
        // ("Miniserver" / "MQTT Broker") + the page content-type, which
        // don't churn across releases.
        given().when()
               .get( "/" )
               .then()
               .statusCode( 200 )
               .contentType( containsString( "text/html" ) )
               .body( containsString( "Miniserver" ) )
               .body( containsString( "MQTT Broker" ) );
    }

    // -------- 2. State snapshot --------

    @Test
    @DisplayName( "2/10 — GET /api/v1/state returns the config + runtime snapshot" )
    void stateSnapshot()
    {
        // "version" is loxmq's own build version (from the Maven artifact),
        // surfaced at the root of the snapshot. Assert it's present rather
        // than pinning a literal so this IT survives version bumps. The shape
        // comes from ManagementResource.buildState() — see also the matching
        // unit test in ApplicationSmokeTest#apiStateReturnsConfig.
        //
        // sessionEstablished + broker.connected are both false here because
        // the packaged-artifact IT boots with loxone.boot.auto-start=false
        // (overridden in pom.xml's Failsafe arg-line — the base default is
        // true, but the IT deliberately exercises the boot SURFACE without
        // depending on whether the build host can reach the LAN
        // miniserver/broker). The operator triggers connect via
        // POST /api/v1/connect + POST /api/v1/transport/connect.
        given().when()
               .get( "/api/v1/state" )
               .then()
               .statusCode( 200 )
               .contentType( containsString( "application/json" ) )
               .body( "version", notNullValue() )
               .body( "miniserver.host", notNullValue() )
               .body( "miniserver.uuid", notNullValue() )
               .body( "miniserver.sessionEstablished", equalTo( false ) )
               .body( "broker.host", notNullValue() )
               .body( "broker.connected", equalTo( false ) );
    }

    // -------- 3. Reconnect stub --------

    @Test
    @DisplayName( "3/10 — POST /api/v1/reconnect returns 502 without a reachable miniserver" )
    void reconnect502()
    {
        // Reconnect is wired to a real handshake. The packaged jar
        // boots in dev profile pointing at 192.0.2.10 (the operator's LAN
        // miniserver) which isn't reachable from the CI build host; either
        // way bootstrap hasn't run inside this IT, so the public key isn't
        // loaded and the orchestrator refuses to connect — 502 with a clear
        // message either way.
        given().when()
               .post( "/api/v1/reconnect" )
               .then()
               .statusCode( 502 )
               .body( "error", equalTo( "handshake_failed" ) );
    }

    // -------- 4. Token-kill (idempotent no-op without a session) --------

    @Test
    @DisplayName( "4/10 — POST /api/v1/token/kill is idempotent (200) when no token is held" )
    void killTokenNoOp()
    {
        given().when()
               .post( "/api/v1/token/kill" )
               .then()
               .statusCode( 200 )
               .body( "status", equalTo( "ok" ) )
               .body( "message", containsString( "no token" ) );
    }

    // -------- 5. Health aggregate --------

    @Test
    @DisplayName( "5/10 — GET /q/health is DOWN (auto-start=false → no session, no broker)" )
    void healthAggregate()
    {
        // /q/health = liveness + readiness combined. Liveness is UP (the JVM
        // is alive), but both readiness probes are DOWN because the IT
        // subprocess boots with loxone.boot.auto-start=false (pom.xml's
        // Failsafe arg-line) — no session, no broker connection. Combined
        // status is DOWN, HTTP 503.
        given().when()
               .get( "/q/health" )
               .then()
               .statusCode( 503 )
               .body( "status", equalTo( "DOWN" ) );
    }

    // -------- 6. Liveness --------

    @Test
    @DisplayName( "6/10 — GET /q/health/live is UP (process alive)" )
    void liveness()
    {
        given().when()
               .get( "/q/health/live" )
               .then()
               .statusCode( 200 )
               .body( "status", equalTo( "UP" ) );
    }

    // -------- 7. Readiness --------

    @Test
    @DisplayName( "7/10 — GET /q/health/ready is DOWN with named-check structure" )
    void readiness()
    {
        // Both readiness checks (miniserver-session, mqtt-broker) report
        // DOWN because the IT subprocess runs with auto-start=false and the
        // operator hasn't POSTed /connect / /transport/connect yet. We
        // assert the structural shape (named checks present, each DOWN)
        // rather than literal reason text — wording evolves, the shape
        // doesn't. MqttReadinessCheck reflects MqttClient.isConnected()
        // truthfully (previously it was hardcoded DOWN with a "not yet
        // implemented" reason).
        given().when()
               .get( "/q/health/ready" )
               .then()
               .statusCode( 503 )
               .body( "status", equalTo( "DOWN" ) )
               .body( "checks.find { it.name == 'miniserver-session' }.status",
                      equalTo( "DOWN" ) )
               .body( "checks.find { it.name == 'miniserver-session' }.data.sessionState",
                      notNullValue() )
               .body( "checks.find { it.name == 'mqtt-broker' }.status",
                      equalTo( "DOWN" ) )
               .body( "checks.find { it.name == 'mqtt-broker' }.data.\"broker.uri\"",
                      notNullValue() );
    }

    // -------- 8. Metrics --------

    @Test
    @DisplayName( "8/10 — GET /q/metrics exposes Prometheus output" )
    void metrics()
    {
        given().when()
               .get( "/q/metrics" )
               .then()
               .statusCode( 200 )
               .body( containsString( "jvm_memory_used_bytes" ) )
               .body( containsString( "http_server_requests_seconds" ) );
    }

    // -------- 9. OpenAPI --------

    @Test
    @DisplayName( "9/10 — GET /q/openapi advertises every management endpoint" )
    void openApi()
    {
        given().when()
               .get( "/q/openapi" )
               .then()
               .statusCode( 200 )
               .body( containsString( "/api/v1/state" ) )
               .body( containsString( "/api/v1/reconnect" ) )
               .body( containsString( "/api/v1/token/kill" ) );
    }

    // -------- 10. Swagger UI (dev profile exposes it) --------

    @Test
    @DisplayName( "10/10 — GET /q/swagger-ui is served (dev profile opens it)" )
    void swaggerUi()
    {
        // RestAssured follows redirects by default — /q/swagger-ui returns
        // 200 from the underlying index.html. In prod the same URL would
        // 404, which is the documented security posture; we test the dev
        // path here because the IT boots with -Dquarkus.profile=dev.
        //
        // Quarkus's page title is "OpenAPI UI (Powered by Quarkus ...)",
        // NOT "Swagger UI" — assert on the swagger-ui JS bundle init
        // instead, which is the actual functional marker.
        given().when()
               .get( "/q/swagger-ui/" )
               .then()
               .statusCode( 200 )
               .contentType( containsString( "text/html" ) )
               .body( containsString( "SwaggerUIBundle" ) )
               .body( containsString( "/q/openapi" ) );
    }
}
