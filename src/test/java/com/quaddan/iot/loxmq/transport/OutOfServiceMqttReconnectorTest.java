/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quaddan.iot.loxmq.config.LoxoneConfig;
import com.quaddan.iot.loxmq.miniserver.message.MiniserverOutOfServiceEvent;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end test of the OutOfService → MQTT side-effects pipeline:
 * <ol>
 *   <li>Decoder fires {@link MiniserverOutOfServiceEvent} (async).</li>
 *   <li>{@code OutOfServiceMqttReconnector} observes it and publishes a
 *       one-shot notification to the broker.</li>
 *   <li>The MQTT session is <b>NOT</b> disconnected (independence
 *       between MQTT and Miniserver).</li>
 * </ol>
 *
 * <p>The test uses {@link FakeMqttClient} to capture publishes and assert
 * that {@code isConnected()} stays {@code true} throughout.
 *
 * <h3>Why this guard matters</h3>
 * A previous implementation also called
 * {@code MqttReconnectScheduler.triggerOutOfServiceReconnect()} which
 * disconnected MQTT for ~30 s on every miniserver reboot. The current
 * test enforces the contract: a miniserver OOS leaves the broker
 * session untouched, and only a notification message is published.
 */
@QuarkusTest
@DisplayName( "OutOfServiceMqttReconnector — publish only, MQTT session stays UP" )
class OutOfServiceMqttReconnectorTest
{
    @Inject
    LoxoneConfig                         config;
    @Inject
    ObjectMapper                         jsonMapper;
    @Inject
    Event< MiniserverOutOfServiceEvent > oosBus;

    private FakeMqttClient fake;

    @BeforeEach
    void installFake()
    {
        fake = new FakeMqttClient();
        QuarkusMock.installMockForType( fake, HiveMqClient.class );
        fake.connect();
    }

    @Test
    @DisplayName( "OOS event → notification published, MQTT stays connected" )
    @Timeout( value = 5, unit = TimeUnit.SECONDS )
    void oosEventPublishesWithoutDisconnect() throws Exception
    {
        oosBus.fireAsync( new MiniserverOutOfServiceEvent( 1_700_000_000_000L ) );

        // Wait until the async observer runs to completion (publish only).
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos( 2 );
        while ( System.nanoTime() < deadline && fake.publishes().isEmpty() )
        {
            Thread.sleep( 5 );
        }

        String                 topic = config.transport().topics().publish().outOfService().topic();
        FakeMqttClient.Publish p     = fake.lastOn( topic );
        assertThat( p ).as( "OOS notification publish on %s", topic ).isNotNull();

        JsonNode body = jsonMapper.readTree( p.payload() );
        assertThat( body.path( "reason" ).asText() ).isEqualTo( "miniserver-out-of-service" );
        assertThat( body.path( "source" ).asText() ).isEqualTo( config.miniserver().app().id() );
        assertThat( body.path( "timestamp" ).asText() ).isNotEmpty();

        // MQTT session must remain UP. Wait a beat to catch any race
        // where a stale call to triggerOutOfServiceReconnect would have
        // flipped isConnected; the assertion below MUST hold.
        // (FakeMqttClient sets isConnected=false on disconnect(), so this
        // single check covers both "no disconnect was called" and "no race
        // where a follow-up call closes the session after the publish".)
        Thread.sleep( 100 );
        assertThat( fake.isConnected() )
                .as( "MQTT session stays connected during miniserver OOS" )
                .isTrue();
    }
}
