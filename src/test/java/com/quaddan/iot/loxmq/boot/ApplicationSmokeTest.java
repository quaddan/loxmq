/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.boot;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Boot smoke test for the JVM application.
 * <p>
 * Verifies the JVM application boots end-to-end with the dev profile:
 * <ul>
 *   <li>CDI container is up</li>
 *   <li>HTTP server is listening</li>
 *   <li>Health endpoint reports DOWN (expected — readiness checks return
 *       DOWN without a live miniserver session)</li>
 *   <li>Metrics endpoint is exposed</li>
 *   <li>OpenAPI is generated and exposes the management resource</li>
 *   <li>The dashboard renders the Qute template</li>
 *   <li>The skeleton management endpoint returns 501 with a clean message</li>
 * </ul>
 * <p>
 * No external services are touched (no miniserver, no broker). The dev profile
 * sets {@code loxone.miniserver.connection.host=192.0.2.10} but no code attempts
 * the network round-trip during this boot smoke — the session orchestrator only
 * connects on an explicit bootstrap + /connect.
 */
@QuarkusTest
@DisplayName( "Application — boot smoke" )
class ApplicationSmokeTest
{
    @Test
    @DisplayName( "the binding boots and the dashboard renders" )
    void dashboardRenders()
    {
        given().when()
               .get( "/" )
               .then()
               .statusCode( 200 )
               .contentType( containsString( "text/html" ) )
               // Assert just on the stable structural panels
               // ("Miniserver" / "MQTT Broker") + the page content-type,
               // which don't churn across releases.
               .body( containsString( "Miniserver" ) )
               .body( containsString( "MQTT Broker" ) );
    }

    @Test
    @DisplayName( "/q/health reports DOWN until the orchestrator lands (readiness checks fail)" )
    void healthReportsDown()
    {
        given().when()
               .get( "/q/health" )
               .then()
               // Two readiness checks fail in the skeleton, so the aggregate is DOWN (503).
               .statusCode( 503 )
               .body( "status", equalTo( "DOWN" ) )
               .body( "checks.find { it.name == 'miniserver-session' }.status",
                      equalTo( "DOWN" ) )
               .body( "checks.find { it.name == 'mqtt-broker' }.status",
                      equalTo( "DOWN" ) )
               .body( "checks.find { it.name == 'miniserver-session-thread' }.status",
                      equalTo( "UP" ) );
    }

    @Test
    @DisplayName( "/q/health/live reports UP — process is alive even if not ready" )
    void livenessReportsUp()
    {
        given().when()
               .get( "/q/health/live" )
               .then()
               .statusCode( 200 )
               .body( "status", equalTo( "UP" ) );
    }

    @Test
    @DisplayName( "/q/metrics exposes Prometheus output" )
    void metricsExposed()
    {
        given().when()
               .get( "/q/metrics" )
               .then()
               .statusCode( 200 )
               .body( containsString( "jvm_memory_used_bytes" ) )
               .body( containsString( "http_server_requests_seconds" ) );
    }

    @Test
    @DisplayName( "/api/v1/state returns the current config snapshot" )
    void apiStateReturnsConfig()
    {
        given().when()
               .get( "/api/v1/state" )
               .then()
               .statusCode( 200 )
               .contentType( containsString( "application/json" ) )
               .body( "version", notNullValue() )
               .body( "miniserver.uuid", notNullValue() )
               // Smoke test boots the app without driving the miniserver
               // handshake or the MQTT connect — both should report unconnected.
               .body( "miniserver.sessionEstablished", is( false ) )
               .body( "broker.connected", is( false ) );
    }

    @Test
    @DisplayName( "/api/v1/reconnect returns 502 in the skeleton (no miniserver reachable + no bootstrap)" )
    void reconnectFailsWithoutBootstrap()
    {
        // Reconnect is wired to a real handshake. In the test profile
        // there's no live miniserver, so the orchestrator refuses to connect
        // because the public key has never been loaded (bootstrap not run).
        // The endpoint surfaces this as a structured 502 rather than a
        // bare 501 stub.
        given().when()
               .post( "/api/v1/reconnect" )
               .then()
               .statusCode( 502 )
               .body( "error", equalTo( "handshake_failed" ) )
               .body( "message", containsString( "Public key not loaded" ) );
    }

    @Test
    @DisplayName( "/q/openapi advertises the management endpoints" )
    void openApiAdvertisesEndpoints()
    {
        given().when()
               .get( "/q/openapi" )
               .then()
               .statusCode( 200 )
               .body( containsString( "/api/v1/state" ) )
               .body( containsString( "/api/v1/reconnect" ) )
               .body( containsString( "/api/v1/token/kill" ) );
    }
}
