/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.management;

import com.quaddan.iot.loxmq.testresources.FakeMiniserverFullResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * Happy-path IT for the admin commands
 * ({@code /api/v1/schedules} + {@code /api/v1/users-snapshot}).
 *
 * <h3>The gap we close</h3>
 *
 * <ul>
 *   <li>{@link SchedulesPageIT} only covers the error paths (503
 *       without a session, 400 on invalid inputs) → no guarantee the
 *       end-to-end happy path keeps working after a refactor.</li>
 *   <li>{@link UsersPageIT} same on the users side.</li>
 *   <li>{@link LiveStatesPageIT}
 *       validates the native Topology serialization but does not
 *       exercise the admin commands path (HTTP GET with autht, parsing
 *       a {@code calendargetentries}/{@code getuserlist2}/etc.
 *       response).</li>
 * </ul>
 *
 * <h3>How it works</h3>
 * <ol>
 *   <li>{@link FakeMiniserverFullResource} has an admin HTTP router that
 *       catches
 *       {@code GET /jdev/sps/{commandPath}?autht=...&user=...} and
 *       returns canned fixtures keyed by {@code commandPath}:
 *       {@code calendargetentries}, {@code getuserlist2},
 *       {@code getgrouplist}, {@code getuser/{uuid}}. Mutations →
 *       generic 200 ok.</li>
 *   <li>It also routes {@code GET /jdev/sys/getkey/{user}} (without the
 *       2), which MiniserverAdminCommandClient invokes to fetch the
 *       HMAC key that signs admin commands.</li>
 *   <li>This IT drives bootstrap + connect to RUNNING, then calls the
 *       dashboard REST endpoints ({@code /api/v1/schedules} and
 *       {@code /api/v1/users-snapshot}) and asserts on the expected
 *       payload.</li>
 * </ol>
 *
 * <h3>Run modes</h3>
 *
 * <pre>
 *   # JVM — validates the REST → admin client → fake HTTP chain.
 *   mvn verify -Pintegration -Dit.test=AdminHappyPathIT
 *
 *   # Native — guarantees Jackson reflection on the response records
 *   # ({@code Schedule}, {@code User}, {@code Group}, {@code UserDetail}).
 *   mvn verify -Pnative,integration -Dit.test=AdminHappyPathIT
 * </pre>
 *
 * @see FakeMiniserverFullResource
 */
@QuarkusIntegrationTest
@QuarkusTestResource( value = FakeMiniserverFullResource.class, restrictToAnnotatedClass = true )
@DisplayName( "AdminHappyPathIT — /api/v1/schedules + /api/v1/users-snapshot end-to-end" )
class AdminHappyPathIT
{
    /** Poll budget to absorb the async delay between {@code RUNNING} and
     *  the LoxAPP3 cache being populated / admin commands becoming
     *  available. */
    private static final Duration POLL_BUDGET = Duration.ofSeconds( 5 );

    private static final Duration POLL_STEP = Duration.ofMillis( 100 );

    // ==========================================================================
    //  /api/v1/schedules
    // ==========================================================================

    @Test
    @DisplayName( "bootstrap → RUNNING → GET /api/v1/schedules returns the 2 fixture entries" )
    void schedulesHappyPath() throws InterruptedException
    {
        driveToRunning();

        // The fake returns a calendargetentries payload with 2 entries
        // ("Vacances été" calMode=4 + "Noel 2026" calMode=2). The admin
        // client parses it, ScheduleService maps to ScheduleEntry
        // records, REST returns it as JSON.
        String body = pollUntilContains( "/api/v1/schedules", "Vacances" );

        assertThat( body )
                .as( "GET /api/v1/schedules must reflect the admin fixtures" )
                .contains( "Vacances été" )
                .contains( "Noel 2026" )
                // operatingMode + calMode + calModeAttrs present in the mapping
                .contains( "\"operatingMode\":10" )
                .contains( "\"calMode\":4" )
                .contains( "\"calMode\":2" );
    }

    // ==========================================================================
    //  /api/v1/users-snapshot
    // ==========================================================================

    @Test
    @DisplayName( "bootstrap → RUNNING → GET /api/v1/users-snapshot returns users + groups" )
    void usersSnapshotHappyPath() throws InterruptedException
    {
        driveToRunning();

        // Snapshot = {users: [...], groups: [...]} built on the
        // UserService.snapshot() side from listUsers() + listGroups()
        // (= 2 distinct admin calls).
        String body = pollUntilContains( "/api/v1/users-snapshot", "admin" );

        assertThat( body )
                .as( "snapshot doit contenir les 2 users de la fixture" )
                .contains( "\"name\":\"admin\"" )
                .contains( "\"name\":\"alice\"" )
                .as( "snapshot doit contenir les 2 groupes de la fixture" )
                .contains( "\"name\":\"Administrators\"" )
                .contains( "\"name\":\"Famille\"" );
    }

    // ==========================================================================
    //  /api/v1/users/{uuid}
    // ==========================================================================

    @Test
    @DisplayName( "bootstrap → RUNNING → GET /api/v1/users/{adminUuid} parses usergroups (lowercase fix)" )
    void getUserHappyPathWithUsergroups() throws InterruptedException
    {
        driveToRunning();

        // The fixture returns usergroups (lowercase 'g') per V17 spec
        // p.5-6. That is exactly the payload that caused
        // UserDetail.userGroups to come back empty under the previous
        // case-sensitive parse. This IT verifies the lowercase parse
        // works end-to-end: REST → UserService → extractStringListTolerant.
        String body = pollUntilContains(
                "/api/v1/users/12eebb90-00a1-3073-ffff88c561c84c44", "admin" );

        // The UserDetail record exposes userGroups (camelCase Java),
        // which is populated from the usergroups (lowercase) field in
        // the response. A regression here would surface as
        // userGroups: [] in the output.
        assertThat( body )
                .as( "UserDetail.userGroups must be populated from V17 lowercase 'usergroups'" )
                .contains( "0fa5b50c-0181-12e1-ffff112233445566" );  // UUID of the Administrators group
    }

    // ==========================================================================
    //  Helpers
    // ==========================================================================

    /**
     * Drives the binding to the {@code RUNNING} state via the 2
     * bootstrap + connect endpoints. Blocking. Idempotent — if already
     * RUNNING (previous run of the same test class), connect returns
     * 200 immediately.
     */
    private static void driveToRunning()
    {
        given().when()
               .post( "/api/v1/bootstrap" )
               .then()
               .statusCode( 200 );

        given().when()
               .post( "/api/v1/connect" )
               .then()
               .statusCode( 200 )
               .body( "state", equalTo( "RUNNING" ) );
    }

    /**
     * Polls {@code GET path} until {@code expectedSubstring} appears in
     * the body, or until {@link #POLL_BUDGET} times out. Returns the
     * last body fetched — the IT inspects it with its detailed
     * assertions.
     */
    private static String pollUntilContains( String path, String expectedSubstring )
            throws InterruptedException
    {
        Instant deadline = Instant.now().plus( POLL_BUDGET );
        String  body     = "";
        while ( Instant.now().isBefore( deadline ) )
        {
            body = given().when()
                          .get( path )
                          .then()
                          .extract().body().asString();
            if ( body.contains( expectedSubstring ) )
            {
                return body;
            }
            Thread.sleep( POLL_STEP.toMillis() );
        }
        return body;
    }
}
