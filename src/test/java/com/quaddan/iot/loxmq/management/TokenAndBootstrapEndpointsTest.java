/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.management;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Endpoint tests for two management routes :
 *
 * <ul>
 *   <li>{@code POST /api/v1/token/refresh} — operator-triggered JWT refresh.
 *       Pre-conditions surfaced as 409 Conflict :
 *       <ul>
 *         <li>{@code error="not_running"} when session state ≠ RUNNING</li>
 *         <li>{@code error="no_token"}     when no token held locally</li>
 *       </ul>
 *       200 success path requires a RUNNING session with a token, which
 *       only a full FakeMiniserver handshake IT can set up — left to the
 *       existing {@code FakeMiniserverHandshakeIT} chain.</li>
 *
 *   <li>{@code POST /api/v1/connect-with-bootstrap} — atomic bootstrap +
 *       connect. Failure modes surfaced as 502 Bad Gateway with a
 *       {@code step} field telling the operator where the chain broke :
 *       <ul>
 *         <li>{@code step="bootstrap"} when bootstrap.run() throws</li>
 *         <li>{@code step="connect"}   when handshake fails after a
 *             successful bootstrap (not exercised here — would need a
 *             FakeMiniserver that responds to apiKey/getPublicKey but
 *             then rejects keyexchange)</li>
 *       </ul>
 *   </li>
 * </ul>
 *
 * <h3>Why @QuarkusTest (not @QuarkusIntegrationTest)</h3>
 * The 409 + bootstrap-fail paths are pure server-side validation that
 * doesn't require a packaged JAR subprocess. @QuarkusTest boots the app
 * in-process which is ~10× faster. The default test profile
 * (src/test/resources/application.yaml) sets auto-start=false so the boot
 * leaves session=DISCONNECTED and token=empty — both 409 conditions
 * naturally fire without explicit setup.
 */
@QuarkusTest
@DisplayName( "Token + bootstrap endpoints — /token/refresh + /connect-with-bootstrap" )
class TokenAndBootstrapEndpointsTest
{
    // ==========================================================================
    //  /api/v1/token/refresh
    // ==========================================================================

    @Test
    @DisplayName( "POST /api/v1/token/refresh — 409 not_running when session ≠ RUNNING" )
    void refreshTokenRefusedWhenNotRunning()
    {
        // auto-start=false in the test profile → sessionTracker.state()
        // is DISCONNECTED at boot. The endpoint's first guard
        // (state == RUNNING) fails → 409 with error="not_running".
        // The body should also surface the current state so the
        // operator's dashboard can show actionable feedback.
        given().when()
               .post( "/api/v1/token/refresh" )
               .then()
               .statusCode( 409 )
               .body( "status", equalTo( "refused" ) )
               .body( "error", equalTo( "not_running" ) )
               .body( "message", containsString( "RUNNING" ) )
               .body( "state", notNullValue() );
    }

    @Test
    @DisplayName( "POST /api/v1/token/refresh — message surfaces actual state (whatever it is)" )
    void refreshTokenMessageHasActualState()
    {
        // Belt-and-suspenders : the message must embed the actual
        // current state so the operator sees the gap without parsing
        // the JSON state field separately.
        //
        // We don't pin the actual state value here because @QuarkusTest
        // runs ordering can leave session in different non-RUNNING
        // states (DISCONNECTED at fresh boot, FAILED if a prior test
        // tried to connect against the fake-miniserver host). The
        // contract that matters is : message echoes whatever the
        // current state is, AND it's not RUNNING (otherwise we
        // wouldn't be on the 409 path).
        String state = given().when()
                              .post( "/api/v1/token/refresh" )
                              .then()
                              .statusCode( 409 )
                              .extract().path( "state" );
        // The message must contain that state value verbatim so the
        // operator's dashboard surfaces a self-contained error line.
        given().when()
               .post( "/api/v1/token/refresh" )
               .then()
               .body( "message", containsString( state ) );
    }

    // ==========================================================================
    //  /api/v1/connect-with-bootstrap
    // ==========================================================================

    @Test
    @DisplayName( "POST /api/v1/connect-with-bootstrap — 502 step=bootstrap when miniserver unreachable" )
    void connectWithBootstrapFailsAtBootstrapStep()
    {
        // Test profile points miniserver at 127.0.0.1:80 (no real
        // server). bootstrap.run() will throw BootstrapException trying
        // to fetch jdev/cfg/apiKey. Contract : 502 with step="bootstrap"
        // — operator immediately knows the chain didn't even get to the
        // WS handshake.
        given().when()
               .post( "/api/v1/connect-with-bootstrap" )
               .then()
               .statusCode( 502 )
               .body( "status", equalTo( "failed" ) )
               .body( "step", equalTo( "bootstrap" ) )
               .body( "error", equalTo( "bootstrap_failed" ) )
               .body( "message", notNullValue() );
    }

    @Test
    @DisplayName( "POST /api/v1/connect-with-bootstrap — body omits 'state' on bootstrap failure (session untouched)" )
    void connectWithBootstrapBodyShapeOnBootstrapFailure()
    {
        // When bootstrap fails the connect step is NOT attempted, so
        // the response body has step=bootstrap + error/message but NO
        // state field (the orchestrator was never touched). Distinguishes
        // from step=connect responses which DO include state + a
        // bootstrap.status=success block.
        given().when()
               .post( "/api/v1/connect-with-bootstrap" )
               .then()
               .statusCode( 502 )
               .body( "step", equalTo( "bootstrap" ) )
               // The body must NOT have a "state" key on bootstrap
               // failure ; if it does, the contract drifted and the
               // dashboard would show stale session state from an
               // attempt that didn't actually happen.
               .body( "$", org.hamcrest.Matchers.not(
                       org.hamcrest.Matchers.hasKey( "state" ) ) );
    }
}
