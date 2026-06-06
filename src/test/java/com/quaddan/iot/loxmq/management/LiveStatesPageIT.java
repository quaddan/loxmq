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
 * Native-parity IT for the {@code GET /states} page.
 *
 * <h3>The gap we close</h3>
 * The {@code /states} page embeds inline in the HTML a JSON snapshot of
 * {@link LoxApp3MetadataResolver.Topology}
 * to prefill the cascade dropdowns (room / category / control).
 * Manual serialization via {@code ObjectMapper.writeValueAsString(topology)}
 * in {@code LiveStatesResource.java:72}.
 *
 * <p>In native mode, records not listed in {@code NativeReflectionConfig}
 * have their metadata stripped by GraalVM → Jackson falls into
 * {@code UnknownSerializer.failForEmpty(...)} → {@code InvalidDefinitionException}
 * → the catch in {@code LiveStatesResource} falls back to an empty
 * topology JSON → the page is silently degraded.
 *
 * <p>This is exactly the regression class — undetected for ~24h in prod
 * until a visitor opened {@code /states}. No JVM test could catch it
 * (the JVM keeps record reflection by default). This IT, launched in
 * native mode via {@code mvn verify -Pnative,integration}, would have
 * surfaced it immediately.
 *
 * <h3>Why it works</h3>
 * <ol>
 *   <li>{@link FakeMiniserverFullResource} serves a LoxAPP3 with
 *       non-empty {@code rooms / cats / controls} (1 room "Salon" + 1
 *       cat "Temperature" + 1 control "Sonde Salon").</li>
 *   <li>The IT drives {@code /bootstrap} + {@code /connect} until
 *       {@code RUNNING}, which fires {@code MiniserverConnectedEvent}.</li>
 *   <li>{@code LoxApp3MetadataResolver} observes the event with
 *       {@code @ObservesAsync} and indexes rooms/cats/controls into its
 *       {@code Topology}. The IT polls 5s to absorb the async hop.</li>
 *   <li>{@code GET /states} serializes the Topology, embeds it in the
 *       rendered HTML, and we assert on the body:
 *       <ul>
 *         <li><strong>negative</strong>: no fallback
 *             {@code "rooms":[],"categories":[],"controls":[]} —
 *             the exact signature of the native-reflection regression.</li>
 *         <li><strong>positive</strong>: "Salon" + "Temperature" +
 *             "Sonde Salon" present in the output.</li>
 *       </ul></li>
 * </ol>
 *
 * <h3>Run modes</h3>
 * <pre>
 *   # JVM (fast, ~3 s) — validates the page but not native reflection.
 *   mvn verify -Pintegration -Dit.test=LiveStatesPageIT
 *
 *   # Native (slow, ~10 min build + ~3 s run) — THIS mode guarantees
 *   # no regression in the native-reflection coverage.
 *   mvn verify -Pnative,integration -Dit.test=LiveStatesPageIT
 * </pre>
 *
 * <h3>Pattern to apply to every future serialized record</h3>
 * Any class / record passed to {@code jsonMapper.writeValue*} /
 * {@code readValue*} / {@code treeToValue} <strong>outside</strong> a
 * typed REST return must (a) be listed in {@code NativeReflectionConfig}
 * AND (b) be exercised by a native IT like this one. See
 * {@code TESTS.md §"Native parity"} for details.
 */
@QuarkusIntegrationTest
@QuarkusTestResource( value = FakeMiniserverFullResource.class, restrictToAnnotatedClass = true )
@DisplayName( "LiveStatesPageIT — /states embeds the populated Topology after RUNNING" )
class LiveStatesPageIT
{
    /** Poll budget to absorb the async delay between RUNNING (sync) and
     *  {@code LoxApp3MetadataResolver} being populated (@ObservesAsync).
     *  5 s amply covers the native cold path. */
    private static final Duration POLL_BUDGET = Duration.ofSeconds( 5 );

    /** Inter-attempt poll step — 100 ms = ~50 tries before timeout,
     *  plenty. */
    private static final Duration POLL_STEP = Duration.ofMillis( 100 );

    @Test
    @DisplayName( "bootstrap → RUNNING → GET /states: body contains \"Salon\" / \"Temperature\" / \"Sonde Salon\"" )
    void statesPageEmbedsTopologyAfterRunning() throws InterruptedException
    {
        // 1. Bootstrap — apiKey + getPublicKey HTTP round-trip.
        given().when()
               .post( "/api/v1/bootstrap" )
               .then()
               .statusCode( 200 );

        // 2. Connect — drive the WS handshake until RUNNING. Blocks
        //    until the orchestrator advances the state machine.
        given().when()
               .post( "/api/v1/connect" )
               .then()
               .statusCode( 200 )
               .body( "state", equalTo( "RUNNING" ) );

        // 3. Absorbed wait: MiniserverConnectedEvent is fire-async, and
        //    LoxApp3MetadataResolver observes it with @ObservesAsync.
        //    The index is therefore populated shortly after /connect
        //    returns 200, not during. Poll /states until we see a room
        //    from the fixture (a signal that the Topology is serialized).
        String body = pollStatesUntilTopologyPopulated();

        // 4. Negative assertion — exact signature of the
        //    native-reflection regression. If this fallback shows up,
        //    NativeReflectionConfig is missing the Topology / ControlInfo
        //    entries and the serialization crashed.
        assertThat( body )
                .as( "native-reflection regression — empty topology fallback must not appear" )
                .doesNotContain( "{\"rooms\":[],\"categories\":[],\"controls\":[]}" );

        // 5. Positive assertions — the 3 names from the enriched
        //    fixture (FakeMiniserverFullResource) must be in the
        //    inline-embedded JSON. No need to parse the HTML — the
        //    strings appear verbatim in the <script> that defines
        //    window.__TOPOLOGY__.
        assertThat( body )
                .as( "embedded topology JSON should contain enriched fixture's room name" )
                .contains( "Salon" );
        assertThat( body )
                .as( "embedded topology JSON should contain enriched fixture's category name" )
                .contains( "Temperature" );
        assertThat( body )
                .as( "embedded topology JSON should contain enriched fixture's control name" )
                .contains( "Sonde Salon" );
    }

    /**
     * Poll {@code GET /states} until we see a room from the fixture, or
     * timeout after {@link #POLL_BUDGET}. Returns the last body
     * fetched — the test's assertion battery inspects it and produces a
     * readable error message on failure.
     */
    private static String pollStatesUntilTopologyPopulated() throws InterruptedException
    {
        Instant deadline = Instant.now().plus( POLL_BUDGET );
        String  body     = "";
        while ( Instant.now().isBefore( deadline ) )
        {
            body = given().when()
                          .get( "/states" )
                          .then()
                          .statusCode( 200 )
                          .extract().body().asString();
            if ( body.contains( "Salon" ) )
            {
                return body;
            }
            Thread.sleep( POLL_STEP.toMillis() );
        }
        return body;
    }
}
