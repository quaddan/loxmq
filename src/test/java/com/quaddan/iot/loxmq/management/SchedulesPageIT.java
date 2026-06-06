/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.management;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * IT for the {@code /schedules} HTML page and the negative paths of
 * {@code /api/v1/schedules} (when session is not RUNNING).
 *
 * <h3>Scope</h3>
 * The IT subprocess boots with {@code loxone.boot.auto-start=false}
 * (set by the failsafe configuration in the pom), so the session never
 * reaches RUNNING. That's intentional here :
 * <ul>
 *   <li>The {@code /schedules} <em>page</em> doesn't depend on the
 *       Miniserver — it must render HTML even when the session is
 *       DISCONNECTED.</li>
 *   <li>The {@code /api/v1/schedules} <em>REST</em> endpoint requires a
 *       RUNNING session — we verify the 503 error path is properly
 *       mapped to the configured error envelope.</li>
 * </ul>
 *
 * <p>The happy-path scenario for {@code /api/v1/schedules} is exercised
 * by {@link AdminHappyPathIT}; this IT focuses on the page render +
 * negative paths.
 */
@QuarkusIntegrationTest
@DisplayName( "SchedulesPageIT — /schedules HTML + /api/v1/schedules error paths" )
class SchedulesPageIT
{
    @Test
    @DisplayName( "GET /schedules — renders HTML page even without RUNNING session" )
    void pageRenders()
    {
        given().when()
               .get( "/schedules" )
               .then()
               .statusCode( 200 )
               .contentType( containsString( "text/html" ) )
               .body( containsString( "Schedules" ) )
               .body( containsString( "/api/v1/schedules" ) )       // REST endpoint referenced in JS
               .body( containsString( "Add a schedule entry" ) );
    }

    @Test
    @DisplayName( "GET /api/v1/schedules without RUNNING session → 503 session-not-running" )
    void listReturns503WhenNotRunning()
    {
        given().when()
               .get( "/api/v1/schedules" )
               .then()
               .statusCode( 503 )
               .contentType( containsString( "application/json" ) )
               .body( "status", equalTo( "error" ) )
               .body( "code", equalTo( "session-not-running" ) );
    }

    @Test
    @DisplayName( "POST /api/v1/schedules with missing body → 400 missing-body" )
    void createRejects400WhenNoBody()
    {
        given().when()
               .header( "Content-Type", "application/json" )
               .body( "" )
               .post( "/api/v1/schedules" )
               .then()
               .statusCode( 400 )
               .body( "status", equalTo( "error" ) )
               .body( "code", equalTo( "missing-body" ) );
    }

    @Test
    @DisplayName( "DELETE /api/v1/schedules/{uuid} with bad uuid → 503 or 400 (depending on order)" )
    void deleteBubblesError()
    {
        // Session not RUNNING → 503 takes precedence over UUID validation.
        // (The IllegalStateException is checked first in
        // MiniserverAdminCommandClient.sendAndAwait.)
        given().when()
               .delete( "/api/v1/schedules/some-fake-uuid-that-is-long-enough-12345" )
               .then()
               .statusCode( 503 )
               .body( "status", equalTo( "error" ) );
    }
}
