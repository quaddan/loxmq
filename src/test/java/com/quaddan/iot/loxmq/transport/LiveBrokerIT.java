/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.transport;

import com.hivemq.client.mqtt.MqttGlobalPublishFilter;
import com.hivemq.client.mqtt.mqtt5.Mqtt5BlockingClient;
import com.hivemq.client.mqtt.mqtt5.Mqtt5Client;
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish;
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
 * Live-broker integration test.
 * <p>
 * Stands up a real Mosquitto via {@link MosquittoTestResource}, lets
 * Failsafe launch the packaged binding pointing at it, and asserts the
 * presence message round-trip:
 *
 * <ol>
 *   <li>{@code POST /api/v1/transport/connect} → {@link HiveMqClient}'s
 *       {@code onConnected} listener fires.</li>
 *   <li>That listener publishes the {@code message-online} payload
 *       (default {@code "online"}) on the will topic, retained.</li>
 *   <li>A second MQTT client run from this test class subscribes to
 *       the same topic and observes the retained value.</li>
 * </ol>
 *
 * <p>What we deliberately don't cover here: the miniserver session.
 * {@code POST /api/v1/connect} would 502 (this IT does not stand up a
 * fake WS Loxone server). This IT exercises the transport layer in
 * isolation, which is exactly the layer the operator has the most
 * trouble debugging in production (broker creds, port mapping, TLS).
 *
 * <h3>Why the observer is a separate HiveMQ client</h3>
 * The IT's @QuarkusIntegrationTest runs the application in a subprocess —
 * we don't have CDI access to the binding's {@link MqttClient} from
 * this test JVM. The cleanest way to inspect what hits the broker is
 * to stand up an independent MQTT v5 client side-by-side. The HiveMQ
 * library is already a production dependency, so no test-only client
 * library to manage.
 */
@QuarkusIntegrationTest
@QuarkusTestResource( value = MosquittoTestResource.class, restrictToAnnotatedClass = true )
@DisplayName( "LiveBrokerIT — transport connect → status=online retained on real Mosquitto" )
class LiveBrokerIT
{
    /** Matches {@code loxone.transport.topics.will.topic} resolved with the
     *  dev profile's {@code loxone.miniserver.app.id}. Hard-coded here on
     *  purpose: the IT subprocess sees the resolved string, but this test
     *  JVM doesn't have @ConfigProperty injection — so we cross-check
     *  against the same template. If either side drifts (e.g. someone
     *  renames the will topic) this test fails loudly with a no-message
     *  timeout, which is the right outcome. */
    private static final String STATUS_TOPIC =
            "iot/loxmq/7b66ce4a-c00a-453c-8da3-314e971db14d/status";

    private Mqtt5BlockingClient observer;

    @BeforeEach
    void connectObserver()
    {
        // The Map returned by MosquittoTestResource.start() is pushed
        // to the launched Quarkus subprocess (so the binding picks up
        // the broker host/port) but NOT into this test JVM —
        // System.getProperty( "loxone.transport.connection.host" )
        // would return null here. Static accessors on the resource are
        // the simplest cross-JVM-boundary channel.
        observer = Mqtt5Client.builder()
                              // Distinct client id so HiveMQ on the broker side never confuses
                              // the observer with the binding (same client-id => prior session
                              // takeover under MQTT v5).
                              .identifier( "live-broker-it-observer-" + UUID.randomUUID() )
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
    @DisplayName( "POST /api/v1/transport/connect → \"online\" retained on the will topic" )
    void transportConnectPublishesOnline() throws Exception
    {
        // CRITICAL ordering — Mqtt5BlockingClient.publishes(filter) must
        // be opened BEFORE any matching publish arrives, otherwise the
        // publish is dropped. HiveMQ docs are explicit about this: the
        // returned Mqtt5Publishes is a bounded buffer, populated by the
        // network thread, drained by receive(). No buffer open == no
        // store. The publishes() try-with-resources block runs the full
        // subscribe + trigger + receive sequence with the buffer alive.
        try ( Mqtt5BlockingClient.Mqtt5Publishes publishes =
                      observer.publishes( MqttGlobalPublishFilter.SUBSCRIBED ) )
        {
            observer.subscribeWith()
                    .topicFilter( STATUS_TOPIC )
                    .send();

            // Bring the binding's broker session up. The endpoint returns
            // 200 with the connected payload from ManagementResource —
            // anything else means the test resource override didn't reach
            // the subprocess, OR the broker is unreachable, OR creds are
            // still being sent.
            given().when()
                   .post( "/api/v1/transport/connect" )
                   .then()
                   .statusCode( 200 )
                   .body( "status", equalTo( "connected" ) )
                   .body( "broker", equalTo( MosquittoTestResource.brokerHost()
                                             + ":" + MosquittoTestResource.brokerPort() ) )
                   .body( "scheme", equalTo( "tcp" ) );

            // Wait for the binding's onConnected listener to publish "online".
            // The publish is fire-and-forget on the binding side, but the QoS
            // 2 handshake against a local broker completes in single-digit
            // milliseconds in practice — 10s leaves a wide margin for slow
            // CI runners.
            Optional< Mqtt5Publish > pub = publishes.receive( 10, TimeUnit.SECONDS );

            assertThat( pub )
                    .as( "presence message on %s within 10s of /transport/connect", STATUS_TOPIC )
                    .isPresent();
            // The retain flag is only set on the RETAINED COPY delivered
            // to late subscribers — not on the live broadcast to subscribers
            // who were already attached. We attach BEFORE the publish, so
            // isRetain() is expected to be false here. The retain-was-stored
            // side of the contract is exercised by the in-JVM @QuarkusTest
            // around HiveMqClient.
            assertThat( new String( pub.get().getPayloadAsBytes(), StandardCharsets.UTF_8 ) )
                    .isEqualTo( "online" );
        }
    }
}
