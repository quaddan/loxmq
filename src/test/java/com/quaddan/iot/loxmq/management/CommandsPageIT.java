/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.management;

import com.quaddan.iot.loxmq.miniserver.session.LoxApp3MetadataResolver;
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
 * Native-parity IT for the {@code GET /commands} page + the
 * {@code GET /api/v1/state/current} snapshot it consumes — sibling of
 * {@link LiveStatesPageIT}.
 *
 * <h3>The gaps we close</h3>
 * <ul>
 *   <li>The {@code /commands} page embeds inline the manually serialized
 *       {@link LoxApp3MetadataResolver.CommandSurface#topology()} (same
 *       {@code Topology} / {@code ControlInfo} records as {@link LiveStatesPageIT})
 *       AND {@link LoxApp3MetadataResolver.CommandSurface#controlStates()} — a
 *       map whose values are {@link LoxApp3MetadataResolver.StateRef} records
 *       (the NEW manually-serialized surface, registered in
 *       {@code NativeReflectionConfig}). A missing native serializer would
 *       collapse it to {@code {}} and break both UUID-paste resolution and the
 *       state readout.</li>
 *   <li>{@link LiveStateSnapshot.StateValue} is returned by the typed endpoint
 *       {@code GET /api/v1/state/current} — auto-registered by Quarkus; this IT
 *       confirms the real value round-trips in native.</li>
 * </ul>
 *
 * <p>Harness: {@link FakeMiniserverFullResource} serves a LoxAPP3 with control
 * "Sonde Salon" (state {@code 0a000004-…}) and, after RUNNING, emits one binary
 * value-state frame for {@link FakeMiniserverFullResource#AUTO_VALUE_STATE_UUID}
 * = {@link FakeMiniserverFullResource#AUTO_VALUE_STATE_VALUE}.
 */
@QuarkusIntegrationTest
@QuarkusTestResource( value = FakeMiniserverFullResource.class, restrictToAnnotatedClass = true )
@DisplayName( "CommandsPageIT — /commands command surface + /api/v1/state/current after RUNNING" )
class CommandsPageIT
{
    private static final Duration POLL_BUDGET = Duration.ofSeconds( 5 );
    private static final Duration POLL_STEP   = Duration.ofMillis( 100 );

    /** The {@code value} state UUID of "Sonde Salon" in the fixture's LoxAPP3 —
     *  appears as a {@code uuid} inside the inline CONTROL_STATES. */
    private static final String FIXTURE_STATE_UUID = "0a000004-0000-0001-aaaaaaaaaaaaaaaa";

    @Test
    @DisplayName( "bootstrap → RUNNING → /commands embeds CONTROL_STATES; /api/v1/state/current exposes the live value" )
    void commandsSurfaceAndStateSnapshot() throws InterruptedException
    {
        given().when().post( "/api/v1/bootstrap" ).then().statusCode( 200 );

        given().when()
               .post( "/api/v1/connect" )
               .then()
               .statusCode( 200 )
               .body( "state", equalTo( "RUNNING" ) );

        // --- /commands page: topology + control states serialized with content.
        String body = pollUntil( "/commands", "Sonde Salon" );

        assertThat( body )
                .as( "native-reflection regression — empty topology fallback must not appear" )
                .doesNotContain( "{\"rooms\":[],\"categories\":[],\"controls\":[]}" );
        assertThat( body )
                .as( "native-reflection regression — empty control-states fallback must not appear" )
                .doesNotContain( "window.CONTROL_STATES = {}" );
        assertThat( body )
                .as( "embedded topology JSON should contain the fixture's control name" )
                .contains( "Sonde Salon" );
        assertThat( body )
                .as( "embedded CONTROL_STATES should list the fixture control's state UUID" )
                .contains( FIXTURE_STATE_UUID );

        // --- /api/v1/state/current: the emitted value is captured + served.
        String snap = pollUntil( "/api/v1/state/current", FakeMiniserverFullResource.AUTO_VALUE_STATE_UUID );
        assertThat( snap )
                .as( "snapshot should hold the emitted value state (StateValue serialized in native)" )
                .contains( FakeMiniserverFullResource.AUTO_VALUE_STATE_UUID )
                .contains( String.valueOf( FakeMiniserverFullResource.AUTO_VALUE_STATE_VALUE ) );   // "42.5"
    }

    /** Poll {@code GET path} until the body contains {@code needle} or timeout. */
    private static String pollUntil( String path, String needle ) throws InterruptedException
    {
        Instant deadline = Instant.now().plus( POLL_BUDGET );
        String  body     = "";
        while ( Instant.now().isBefore( deadline ) )
        {
            body = given().when()
                          .get( path )
                          .then()
                          .statusCode( 200 )
                          .extract().body().asString();
            if ( body.contains( needle ) )
            {
                return body;
            }
            Thread.sleep( POLL_STEP.toMillis() );
        }
        return body;
    }
}
