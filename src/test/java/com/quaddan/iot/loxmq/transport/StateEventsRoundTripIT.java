/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.transport;

import com.hivemq.client.mqtt.MqttGlobalPublishFilter;
import com.hivemq.client.mqtt.mqtt5.Mqtt5BlockingClient;
import com.hivemq.client.mqtt.mqtt5.Mqtt5Client;
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish;
import com.quaddan.iot.loxmq.testresources.FakeMiniserverFullResource;
import com.quaddan.iot.loxmq.testresources.MosquittoTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * State-event round-trip IT.
 * <p>
 * Drives the binding to RUNNING against {@link FakeMiniserverFullResource},
 * then asserts that the canned Value-State binary frame the fake pushes
 * right after acking {@code enablebinstatusupdate} comes out the other
 * end of the pipeline as a JSON publish on
 * {@code …/states/type_2/{uuid}} via {@link MosquittoTestResource}.
 *
 * <h3>Chain exercised</h3>
 * <ol>
 *   <li>Bootstrap + connect — orchestrator reaches {@code RUNNING},
 *       sends {@code enablebinstatusupdate}.</li>
 *   <li>Fake replies with the {@code 200} ack, then immediately writes
 *       two binary WS frames: an 8-byte {@code WsBinHdr} announcing
 *       identifier=2 + 24 bytes payload, then the 24-byte payload
 *       (16-byte UUID + 8-byte LE double).</li>
 *   <li>{@code MiniserverWSTextDecoder}'s binary-frame handler hands
 *       the bytes to {@code BinaryStatesDecoder}, which emits a
 *       {@code ValueStatesEvent}.</li>
 *   <li>{@code StatesPublisher.onValueStates} sees mode=SINGLE
 *       (dev profile default) and publishes JSON on
 *       {@code …/states/type_2/{AUTO_VALUE_STATE_UUID}}.</li>
 *   <li>The test observer (independent HiveMQ client against the same
 *       Mosquitto) sees the publish.</li>
 * </ol>
 *
 * <h3>What this catches that unit tests can't</h3>
 * <ul>
 *   <li>Wire-format correctness of the binary frame pair end-to-end
 *       through the real {@code Tyrus}/{@code Vert.x WebSockets Next}
 *       binary path inside the packaged binding. Unit tests stub the
 *       decoder input and never exercise the actual WS reader path.</li>
 *   <li>{@code @ObservesAsync} on {@code ValueStatesEvent} resolves in
 *       the packaged classpath. ArC's bean-resolution rules differ
 *       slightly between {@code @QuarkusTest} (full discovery) and the
 *       subprocess fast-jar (resolved at build); this is exactly the
 *       category of bug that {@code SmallRye + @Observes} resolution
 *       mismatches produce.</li>
 *   <li>The {@code …/states/type_2/{uuid}} topic template is wired
 *       correctly post-config-expansion in the packaged artifact.</li>
 * </ul>
 *
 * <h3>What this deliberately doesn't cover</h3>
 * Crypto round-trip on the fake's side, Text/DayTimer/Weather states,
 * BATCH mode publish. The first is the 🟢 crypto-round-trip TODO item
 * (~300 LOC standalone); the others are unit-tested in
 * {@code StatesPublisherTest} and {@code BinaryStatesDecoderTest}.
 */
@QuarkusIntegrationTest
@QuarkusTestResource( value = MosquittoTestResource.class, restrictToAnnotatedClass = true )
@QuarkusTestResource( value = FakeMiniserverFullResource.class, restrictToAnnotatedClass = true )
@DisplayName( "StateEventsRoundTripIT — fake WS binary frame → BinaryStatesDecoder → MQTT states/type_2" )
class StateEventsRoundTripIT
{
    /** Same dev-profile-resolved app-id as the other ITs. The binding's
     *  state-event topic template is {@code root}/{@code appId}/states/type_2 —
     *  see application.yaml (loxone.transport.topics). */
    private static final String APP_ID      = "7b66ce4a-c00a-453c-8da3-314e971db14d";
    private static final String STATE_TOPIC =
            "iot/loxmq/" + APP_ID + "/states/type_2/"
            + FakeMiniserverFullResource.AUTO_VALUE_STATE_UUID;

    private Mqtt5BlockingClient observer;

    @BeforeEach
    void connectObserver()
    {
        observer = Mqtt5Client.builder()
                              .identifier( "state-events-it-observer-" + UUID.randomUUID() )
                              .serverHost( MosquittoTestResource.brokerHost() )
                              .serverPort( MosquittoTestResource.brokerPort() )
                              .buildBlocking();
        observer.connect();
    }

    @AfterEach
    void disconnectObserver()
    {
        if ( observer != null )
        {
            try { observer.disconnect(); } catch ( Exception ignored ) { /* best-effort */ }
            observer = null;
        }
    }

    @Test
    @DisplayName( "fake pushes Value-State frame → JSON publish on …/states/type_2/{uuid}" )
    void valueStateRoundTrip() throws Exception
    {
        // ----------------------------------------------------------------
        //  Subscribe to the target topic BEFORE driving the handshake.
        //  The fake emits the binary frame immediately after acking
        //  `enablebinstatusupdate` (= the last step of the handshake), so
        //  the publish arrives just a few ms after /api/v1/connect returns
        //  200. If we subscribed AFTER, we'd lose the publish (the broker
        //  retain flag on …/states/type_2/* is profile-configured and we
        //  don't want this IT to depend on that).
        // ----------------------------------------------------------------
        try ( Mqtt5BlockingClient.Mqtt5Publishes publishes =
                      observer.publishes( MqttGlobalPublishFilter.SUBSCRIBED ) )
        {
            observer.subscribeWith().topicFilter( STATE_TOPIC ).send();

            // 1. Transport up first so StatesPublisher has a connected MqttClient
            //    when the ValueStatesEvent fires. If we order this AFTER /connect,
            //    the binding emits the decoded state before MQTT is up and
            //    StatesPublisher drops it with a "not connected" debug log.
            given().when()
                   .post( "/api/v1/transport/connect" )
                   .then()
                   .statusCode( 200 )
                   .body( "status", equalTo( "connected" ) );

            // 2. Bootstrap — load identity + RSA public key from the fake.
            given().when()
                   .post( "/api/v1/bootstrap" )
                   .then()
                   .statusCode( 200 )
                   .body( "status", equalTo( "success" ) );

            // 3. Open WS → handshake → RUNNING. enablebinstatusupdate
            //    fires as the last step; the fake's WS handler acks it
            //    AND immediately writes the canned Value-State binary
            //    frame pair (see FakeMiniserverFullResource).
            given().when()
                   .post( "/api/v1/connect" )
                   .then()
                   .statusCode( 200 )
                   .body( "state", equalTo( "RUNNING" ) );

            // 4. Wait for the JSON publish on the target topic. Generous
            //    timeout — the chain has six hops but each is sub-ms;
            //    10s covers a slow CI runner with margin.
            Optional< Mqtt5Publish > pub =
                    publishes.receive( 10, TimeUnit.SECONDS );

            assertThat( pub )
                    .as( "Value-State publish on %s within 10s of /connect → RUNNING", STATE_TOPIC )
                    .isPresent();

            // 5. Payload is the raw double-as-string in SINGLE mode
            //    (see StatesPublisher.fireValueSingle). The fake encodes
            //    42.5 — assert exact match. Locale-independent because
            //    Java's Double.toString always uses '.' as decimal point.
            String body = new String( pub.get().getPayloadAsBytes(), StandardCharsets.UTF_8 );
            assertThat( body )
                    .as( "SINGLE-mode payload should be the literal double value emitted by the fake" )
                    .isEqualTo( String.valueOf( FakeMiniserverFullResource.AUTO_VALUE_STATE_VALUE ) );
        }
    }
}
