/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.transport;

import com.hivemq.client.mqtt.MqttGlobalPublishFilter;
import com.hivemq.client.mqtt.mqtt5.Mqtt5BlockingClient;
import com.hivemq.client.mqtt.mqtt5.Mqtt5Client;
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish;
import com.quaddan.iot.loxmq.testresources.MosquittoRestartableTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
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
 * Asserts that the binding survives a broker crash mid-session and
 * recovers cleanly when the broker comes back. Drives the
 * {@code HiveMQ.automaticReconnect()} loop end-to-end against a real
 * Mosquitto, including a fresh "online" presence republish after the
 * outage.
 *
 * <h3>Scenario</h3>
 * <ol>
 *   <li>Mosquitto up on a fixed host port (see
 *       {@link MosquittoRestartableTestResource} for the pinning trick).</li>
 *   <li>{@code POST /api/v1/transport/connect} → binding connects,
 *       publishes {@code online} retained on the status topic. The
 *       observer attached to the broker sees the message live.</li>
 *   <li>{@link MosquittoRestartableTestResource#crashBroker()} — the
 *       container is stopped (SIGKILL). HiveMQ on the binding side
 *       detects the socket RST instantly and enters its
 *       {@code automaticReconnect()} backoff (3 s → 2 m exponential —
 *       see {@code loxone.transport.reconnection.min/max-delay}).</li>
 *   <li>Brief wait so the disconnect is visible end-to-end (and to
 *       give HiveMQ a chance to fail at least one retry attempt against
 *       the dead port — which is the actual scenario this test models,
 *       not "broker hot-swap").</li>
 *   <li>{@link MosquittoRestartableTestResource#reviveBroker()} —
 *       a fresh Mosquitto container starts on the SAME fixed port.
 *       The binding's next HiveMQ retry succeeds.</li>
 *   <li>A second observer attaches to the revived broker, subscribes
 *       to the status topic, and waits up to 60 s for {@code online}
 *       to reappear (either as a retained delivery from the binding's
 *       republish, or live if we got there first — either is a valid
 *       proof that the binding recovered).</li>
 * </ol>
 *
 * <h3>What this catches that unit tests can't</h3>
 * The full HiveMQ auto-reconnect path lives inside HiveMQ's own client
 * machinery — we don't drive it from our code, we just configure it at
 * build time and hand off. That means unit tests against
 * {@link HiveMqClient} can verify configuration but can't reproduce the
 * end-to-end disconnect → backoff → reconnect → republish chain. This
 * IT exercises that machinery against a real broker that actually goes
 * down. If a regression sneaks in (some destruction observer firing
 * during reconnect, for instance) this test fails loudly with a
 * no-message timeout on step 6.
 *
 * <h3>What this deliberately doesn't cover</h3>
 * <ul>
 *   <li>Behavior of the {@code MqttReconnectScheduler} (the OOS-specific
 *       scheduler) — that's triggered by a {@code MiniserverOutOfServiceEvent},
 *       not a broker drop. Covered by {@code MqttReconnectSchedulerTest}
 *       at the unit level.</li>
 *   <li>The miniserver session — same as {@code LiveBrokerIT}, no fake
 *       Loxone WS server up.</li>
 *   <li>Queued publishes during the outage. The binding fires
 *       state-publish events into HiveMQ's async send queue; whether
 *       HiveMQ buffers or drops during a disconnect depends on its own
 *       buffer policy and is HiveMQ's problem, not ours.</li>
 * </ul>
 */
@QuarkusIntegrationTest
@QuarkusTestResource( value = MosquittoRestartableTestResource.class, restrictToAnnotatedClass = true )
@DisplayName( "BrokerCrashRecoveryIT — binding reconnects + republishes online after a broker crash" )
class BrokerCrashRecoveryIT
{
    /** Same dev-profile-resolved topic as {@code LiveBrokerIT} —
     *  the IT subprocess runs the dev profile (see pom.xml
     *  {@code quarkus.test.integration-test-profile}). If anyone renames
     *  the will topic this test fails loudly on the timeout, which is
     *  the right outcome. */
    private static final String STATUS_TOPIC =
            "iot/loxmq/7b66ce4a-c00a-453c-8da3-314e971db14d/status";

    @Test
    @DisplayName( "crash → revive on same port → binding republishes online within 60s" )
    void reconnectsAfterBrokerCrash() throws Exception
    {
        // ----------------------------------------------------------------
        //  Step 1+2 — bring the binding's broker session up and confirm
        //             the live "online" publish arrives.
        //  Mqtt5BlockingClient is not AutoCloseable, so we manage its
        //  disconnect manually in a try/finally.
        // ----------------------------------------------------------------
        Mqtt5BlockingClient initialObserver = newObserver();
        try ( Mqtt5BlockingClient.Mqtt5Publishes pubs =
                      initialObserver.publishes( MqttGlobalPublishFilter.SUBSCRIBED ) )
        {
            initialObserver.subscribeWith().topicFilter( STATUS_TOPIC ).send();

            given().when()
                   .post( "/api/v1/transport/connect" )
                   .then()
                   .statusCode( 200 )
                   .body( "status", equalTo( "connected" ) );

            Optional< Mqtt5Publish > first = pubs.receive( 10, TimeUnit.SECONDS );
            assertThat( first )
                    .as( "initial 'online' on %s within 10s of /transport/connect", STATUS_TOPIC )
                    .isPresent();
            assertThat( payload( first.get() ) ).isEqualTo( "online" );
        }
        finally
        {
            disconnectQuietly( initialObserver );
        }

        // ----------------------------------------------------------------
        //  Step 3 — kill the broker. From the binding's side, the TCP
        //           sockets are RST and HiveMQ's automaticReconnect()
        //           loop starts retrying immediately.
        // ----------------------------------------------------------------
        MosquittoRestartableTestResource.crashBroker();

        // Wait for the broker to be fully down, AND for HiveMQ to have
        // failed at least one retry attempt against the dead port. 5 s
        // covers the HiveMQ initial-delay (3 s) + slack.
        Thread.sleep( 5_000 );
        assertThat( MosquittoRestartableTestResource.isRunning() )
                .as( "broker should be down after crashBroker()" )
                .isFalse();

        // ----------------------------------------------------------------
        //  Step 4 — revive on the same port. HiveMQ's next retry should
        //           succeed within min-delay seconds (3 s) of broker
        //           readiness, give or take the backoff curve. The
        //           call is synchronous — it blocks until the container's
        //           Wait.forListeningPort() reports the broker ready.
        // ----------------------------------------------------------------
        MosquittoRestartableTestResource.reviveBroker();
        assertThat( MosquittoRestartableTestResource.isRunning() )
                .as( "broker should be back up after reviveBroker()" )
                .isTrue();

        // ----------------------------------------------------------------
        //  Step 5 — attach a NEW observer (the previous one's session
        //           died with the old broker container) and wait for
        //           "online" to reappear. Either:
        //             - retained delivery on subscribe (if binding's
        //               republish landed before we subscribed), or
        //             - live publish (if we got there first).
        //           Both prove the binding reconnected.
        // ----------------------------------------------------------------
        Mqtt5BlockingClient recoveryObserver = newObserver();
        try ( Mqtt5BlockingClient.Mqtt5Publishes pubs =
                      recoveryObserver.publishes( MqttGlobalPublishFilter.ALL ) )
        {
            recoveryObserver.subscribeWith().topicFilter( STATUS_TOPIC ).send();

            Optional< Mqtt5Publish > pub = pubs.receive( 60, TimeUnit.SECONDS );
            assertThat( pub )
                    .as( "binding republishes 'online' on %s within 60s of broker revive", STATUS_TOPIC )
                    .isPresent();
            assertThat( payload( pub.get() ) )
                    .as( "republished payload must be the presence message" )
                    .isEqualTo( "online" );
        }
        finally
        {
            disconnectQuietly( recoveryObserver );
        }
    }

    // ------------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------------

    private static Mqtt5BlockingClient newObserver()
    {
        // The test broker requires auth. Same credentials baked into the
        // Mosquitto container by MosquittoRestartableTestResource —
        // duplicated here as constants because the test resource keeps
        // them private to its own start() wiring.
        Mqtt5BlockingClient c = Mqtt5Client.builder()
                                           .identifier( "broker-crash-it-observer-" + UUID.randomUUID() )
                                           .serverHost( MosquittoRestartableTestResource.brokerHost() )
                                           .serverPort( MosquittoRestartableTestResource.brokerPort() )
                                           .simpleAuth()
                                           .username( MosquittoRestartableTestResource.AUTH_USER )
                                           .password( MosquittoRestartableTestResource.AUTH_PASS.getBytes( StandardCharsets.UTF_8 ) )
                                           .applySimpleAuth()
                                           .buildBlocking();
        c.connect();
        return c;
    }

    private static String payload( Mqtt5Publish pub )
    {
        return new String( pub.getPayloadAsBytes(), StandardCharsets.UTF_8 );
    }

    /** Best-effort disconnect — the broker may already be down, so we
     *  swallow any failure. Mirrors {@code LiveBrokerIT}'s @AfterEach
     *  pattern. */
    private static void disconnectQuietly( Mqtt5BlockingClient client )
    {
        if ( client == null )
        { return; }
        try { client.disconnect(); } catch ( Exception ignored ) { /* best-effort */ }
    }
}
