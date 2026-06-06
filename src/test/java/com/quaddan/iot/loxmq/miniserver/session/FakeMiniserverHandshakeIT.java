/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.session;

import com.quaddan.iot.loxmq.testresources.FakeMiniserverFullResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Full handshake against a fake HTTP + WebSocket miniserver, all the
 * way to {@code SessionState.RUNNING}.
 * <p>
 * Validates the entire bootstrap + session-orchestrator path in the
 * packaged binding against a real Vert.x server, end-to-end:
 *
 * <ol>
 *   <li>POST {@code /api/v1/bootstrap} → apiKey + getPublicKey
 *       round-trip → identity in {@code MiniserverState}.</li>
 *   <li>POST {@code /api/v1/connect} → WS upgrade, key exchange,
 *       getkey2 (HTTP), encrypted getjwt, LoxAPPversion3, LoxAPP3
 *       fetch, enablebinstatusupdate — all five WS commands the
 *       orchestrator sends in sequence.</li>
 *   <li>GET {@code /api/v1/state} → {@code session.state == RUNNING}.</li>
 * </ol>
 *
 * <p>If any step regresses (URL changed, JSON shape changed, state
 * transition broken) this IT fails. It's the highest-coverage IT in
 * the suite — touches the orchestrator, crypto, HTTP client, WS
 * client, JSON parsers, MiniserverState, BootstrapTracker,
 * SessionTracker, and the management REST endpoints.
 *
 * <p>Decoupled from {@code FakeMiniserverBootstrapIT}: that one
 * deliberately runs against an HTTP-only resource to verify the
 * "no-WS → /connect returns 502" surface. This IT uses
 * {@link FakeMiniserverFullResource} which DOES expose a WS endpoint,
 * so /connect succeeds.
 */
@QuarkusIntegrationTest
@QuarkusTestResource( value = FakeMiniserverFullResource.class, restrictToAnnotatedClass = true )
@DisplayName( "FakeMiniserverHandshakeIT — full bootstrap + WS handshake → RUNNING" )
class FakeMiniserverHandshakeIT
{
    @Test
    @DisplayName( "POST /bootstrap + POST /connect → session reaches RUNNING" )
    void fullHandshakeReachesRunning()
    {
        // 1. Bootstrap — establishes identity + loads public key.
        given().when()
               .post( "/api/v1/bootstrap" )
               .then()
               .statusCode( 200 )
               .body( "status", equalTo( "success" ) )
               .body( "serial", equalTo( "50:4F:94:AA:BB:CC" ) );

        // 2. Connect — drives the WS handshake to RUNNING. The endpoint
        //    blocks until either RUNNING reached OR a step fails. On
        //    success it returns 200 with the session payload from
        //    ManagementResource#connect — status="success" + state name
        //    + token metadata. 502 here would mean a step in the
        //    scripted dialog rejected — most likely the LoxAPP3 JSON
        //    shape or the getjwt fields.
        given().when()
               .post( "/api/v1/connect" )
               .then()
               .statusCode( 200 )
               .body( "status", equalTo( "success" ) )
               .body( "state", equalTo( "RUNNING" ) )
               .body( "expiresAt", notNullValue() )
               // tokenRights = 4 from the fake's canned getjwt reply
               // (matches the bit Loxone uses for "Admin").
               .body( "tokenRights", equalTo( 4 ) )
               .body( "unsecurePass", is( false ) );

        // 3. State reflects the live session.
        given().when()
               .get( "/api/v1/state" )
               .then()
               .statusCode( 200 )
               .body( "session.state", equalTo( "RUNNING" ) )
               .body( "miniserver.sessionEstablished", is( true ) )
               .body( "session.token.expired", is( false ) )
               // tokenRights = 4 from the fake's canned getjwt reply
               // (matches the bit Loxone uses for "Admin").
               .body( "session.token.tokenRights", equalTo( 4 ) );
    }
}
