/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.transport;

import com.hivemq.client.mqtt.MqttGlobalPublishFilter;
import com.hivemq.client.mqtt.mqtt5.Mqtt5BlockingClient;
import com.hivemq.client.mqtt.mqtt5.Mqtt5Client;
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish;
import com.quaddan.iot.loxmq.miniserver.session.SessionOrchestrator;
import com.quaddan.iot.loxmq.transport.publish.CommandResponsePublisher;
import com.quaddan.iot.loxmq.transport.subscribe.CommandSubscriber;
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
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * MQTT command path end-to-end.
 * <p>
 * Combines {@link MosquittoTestResource} (real broker) +
 * {@link FakeMiniserverFullResource} (HTTP + WS fake miniserver) on a
 * single IT, drives the binding all the way to RUNNING, then publishes
 * a command JSON on the {@code …/command} topic and asserts the
 * round-tripped {@code command-response} appears on the
 * {@code …/command_response} topic.
 *
 * <h3>Chain exercised</h3>
 * <ol>
 *   <li>Test client → MQTT publish on {@code .../command}</li>
 *   <li>Mosquitto → binding's {@link CommandSubscriber}</li>
 *   <li>JSON parse → fires {@code MiniserverCommandEvent}</li>
 *   <li>{@link SessionOrchestrator}
 *       observer encrypts and sends a {@code jdev/sys/enc/<base64>} text
 *       frame over WS</li>
 *   <li>Fake miniserver receives it, replies with a text frame
 *       containing {@link FakeMiniserverFullResource#COMMAND_RESPONSE_MARKER}</li>
 *   <li>Orchestrator's {@code onText} case RUNNING fires
 *       {@code MiniserverCommandResponseEvent}</li>
 *   <li>{@link CommandResponsePublisher}
 *       publishes the verbatim text frame on {@code …/command_response}</li>
 *   <li>Test observer (a separate HiveMQ client) receives it from
 *       Mosquitto</li>
 * </ol>
 *
 * <p>Two test resources on one IT: Quarkus stacks
 * {@code @QuarkusTestResource} annotations and starts each one before
 * launching the subprocess. Both resources' config-overrides land in
 * the launched Quarkus JVM as system properties.
 *
 * <h3>What this catches that unit tests can't</h3>
 * <ul>
 *   <li>JSON payload shape on {@code …/command} — the unit test
 *       {@code CommandSubscriberTest} uses canned strings; this IT
 *       proves what we DOCUMENT as the contract is what the binding
 *       actually accepts.</li>
 *   <li>{@code …/command_response} retain flag, QoS, and topic
 *       construction in the packaged binding — config overlay flowing
 *       through to the subprocess.</li>
 *   <li>The full CDI event chain through {@code @ObservesAsync} +
 *       {@code @Observes} resolves correctly in the packaged classpath
 *       (the ArC + SmallRye saga had three rounds of this kind of bug;
 *       a packaged-subprocess run is the only way to be sure).</li>
 * </ul>
 */
@QuarkusIntegrationTest
@QuarkusTestResource( value = MosquittoTestResource.class, restrictToAnnotatedClass = true )
@QuarkusTestResource( value = FakeMiniserverFullResource.class, restrictToAnnotatedClass = true )
@DisplayName( "CommandRoundTripIT — MQTT command → encrypted WS → reply → MQTT command_response" )
class CommandRoundTripIT
{
    private static final String APP_ID                 = "7b66ce4a-c00a-453c-8da3-314e971db14d";
    private static final String COMMAND_TOPIC          = "iot/loxmq/" + APP_ID + "/command";
    private static final String COMMAND_RESPONSE_TOPIC = "iot/loxmq/" + APP_ID + "/command_response";

    private Mqtt5BlockingClient observer;

    @BeforeEach
    void connectObserver()
    {
        observer = Mqtt5Client.builder()
                              .identifier( "command-roundtrip-observer-" + UUID.randomUUID() )
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
    @DisplayName( "publish on /command → response on /command_response carries the fake's marker" )
    void commandRoundTrip() throws Exception
    {
        // 1. Bootstrap — load identity + RSA public key from the fake.
        given().when()
               .post( "/api/v1/bootstrap" )
               .then()
               .statusCode( 200 )
               .body( "status", equalTo( "success" ) );

        // 2. Open WS session → handshake → RUNNING. Required before
        //    the orchestrator can encrypt outgoing commands — pre-RUNNING
        //    it drops commands with a "session is FAILED
        //    (not RUNNING)" WARN.
        given().when()
               .post( "/api/v1/connect" )
               .then()
               .statusCode( 200 )
               .body( "state", equalTo( "RUNNING" ) );

        // 3. Bring MQTT up — CommandSubscriber.onMqttConnected wires the
        //    /command + /api subscriptions in this step.
        given().when()
               .post( "/api/v1/transport/connect" )
               .then()
               .statusCode( 200 )
               .body( "status", equalTo( "connected" ) );

        // 4. Open the publishes buffer BEFORE publishing the command —
        //    same lesson as LiveBrokerIT: HiveMQ drops messages
        //    arriving before publishes(filter) is set up.
        try ( Mqtt5BlockingClient.Mqtt5Publishes publishes =
                      observer.publishes( MqttGlobalPublishFilter.SUBSCRIBED ) )
        {
            observer.subscribeWith()
                    .topicFilter( COMMAND_RESPONSE_TOPIC )
                    .send();

            // 5. Publish a command. JSON shape comes from MiniserverCommand
            //    record: {"uuid": "...", "command": "..."}. Both fields
            //    required — CommandSubscriber drops malformed JSON with a
            //    WARN and never fires the event.
            String commandPayload = "{\"uuid\":\"test-uuid-1234\",\"command\":\"on\"}";
            observer.publishWith()
                    .topic( COMMAND_TOPIC )
                    .payload( commandPayload.getBytes( StandardCharsets.UTF_8 ) )
                    .send();

            // 6. Wait for the round-tripped command-response. Generous
            //    timeout — the chain has six hops, but each is sub-ms
            //    locally; 10s is just to survive a slow CI runner.
            Optional< Mqtt5Publish > pub =
                    publishes.receive( 10, TimeUnit.SECONDS );

            assertThat( pub )
                    .as( "command-response on %s within 10s of publishing on %s",
                         COMMAND_RESPONSE_TOPIC, COMMAND_TOPIC )
                    .isPresent();
            String body = new String( pub.get().getPayloadAsBytes(), StandardCharsets.UTF_8 );
            assertThat( body )
                    .as( "response body must echo the fake's sentinel string — proves the chain "
                         + "MQTT in → CommandSubscriber → CDI → Orchestrator → WS → fake → WS back "
                         + "→ Orchestrator onText → CommandResponseEvent → CommandResponsePublisher "
                         + "→ MQTT out completed end-to-end" )
                    .contains( FakeMiniserverFullResource.COMMAND_RESPONSE_MARKER );
        }

        // 7. State sanity check — session still RUNNING after the
        //    command round-trip. Catches an old bug class where a text
        //    frame in RUNNING state incorrectly drove a transition.
        given().when()
               .get( "/api/v1/state" )
               .then()
               .statusCode( 200 )
               .body( "session.state", equalTo( "RUNNING" ) )
               .body( "miniserver.sessionEstablished", is( true ) )
               .body( "broker.connected", is( true ) )
               .body( "session.token.expired", is( false ) )
               .body( "session.token.expiresAt", notNullValue() );
    }
}
