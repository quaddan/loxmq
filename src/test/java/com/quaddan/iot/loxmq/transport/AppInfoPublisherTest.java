/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quaddan.iot.loxmq.config.LoxoneConfig;
import com.quaddan.iot.loxmq.miniserver.identity.HttpsStatus;
import com.quaddan.iot.loxmq.miniserver.identity.MiniserverGeneration;
import com.quaddan.iot.loxmq.miniserver.identity.MiniserverIdentity;
import com.quaddan.iot.loxmq.miniserver.identity.MiniserverVersion;
import com.quaddan.iot.loxmq.miniserver.session.MiniserverConnectedEvent;
import com.quaddan.iot.loxmq.miniserver.session.SessionState;
import com.quaddan.iot.loxmq.miniserver.session.SessionTracker;
import com.quaddan.iot.loxmq.miniserver.state.MiniserverState;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fires {@link MiniserverConnectedEvent} and asserts the
 * {@code AppInfoPublisher} produced the expected retained JSON on the
 * {@code app_info} topic.
 */
@QuarkusTest
@DisplayName( "AppInfoPublisher — RUNNING → app_info JSON retained" )
class AppInfoPublisherTest
{
    @Inject
    LoxoneConfig                      config;
    @Inject
    ObjectMapper                      jsonMapper;
    @Inject
    Event< MiniserverConnectedEvent > bus;
    @Inject
    Event< MqttConnectedEvent >       mqttBus;
    @Inject
    SessionTracker                    sessionTracker;
    @Inject
    MiniserverState                   miniserverState;

    private FakeMqttClient fake;

    @BeforeEach
    void install()
    {
        fake = new FakeMqttClient();
        QuarkusMock.installMockForType( fake, HiveMqClient.class );
        fake.connect();
    }

    @Test
    @DisplayName( "fire MiniserverConnectedEvent → app_info publish with binding + identity metadata" )
    @Timeout( value = 5, unit = TimeUnit.SECONDS )
    void firesAppInfoOnConnected() throws Exception
    {
        MiniserverIdentity identity = new MiniserverIdentity(
                "50:4F:94:AA:BB:CC",
                MiniserverVersion.parse( "17.0.3.31" ),
                null,                       // sessionKey — not used by AppInfoPublisher
                false,                      // isInTrust
                true,                       // local
                "192.0.2.10",
                HttpsStatus.SUPPORTED,
                MiniserverGeneration.GEN2 );

        Instant now = Instant.parse( "2026-05-19T13:00:00Z" );
        bus.fireAsync( new MiniserverConnectedEvent( now, Optional.of( identity ) ) );

        String                 topic = config.transport().topics().publish().appInfo().topic();
        FakeMqttClient.Publish p     = awaitPublishOn( topic );
        assertThat( p ).as( "publish on %s", topic ).isNotNull();
        assertThat( p.retain() ).isEqualTo( config.transport().topics().publish().appInfo().retain() );

        JsonNode body = jsonMapper.readTree( p.payload() );
        // appInfo envelope: name / version / start.
        JsonNode appInfo = body.path( "appInfo" );
        assertThat( appInfo.path( "name" ).asText() ).isEqualTo( config.miniserver().app().info() );
        assertThat( appInfo.path( "version" ).asText() ).isNotEmpty();
        // start is formatted "EEE dd/MM/yyyy HH:mm:ss:SSS zzz xxxx" —
        // just sanity-check the shape (3-letter day prefix + date).
        String start = appInfo.path( "start" ).asText();
        assertThat( start ).matches( "[A-Z][a-z]{2} \\d{2}/\\d{2}/\\d{4} \\d{2}:\\d{2}:\\d{2}:\\d{3} .+" );

        // Optional miniserver identity block at root.
        JsonNode mini = body.path( "miniserver" );
        assertThat( mini.path( "serial" ).asText() ).isEqualTo( "50:4F:94:AA:BB:CC" );
        assertThat( mini.path( "version" ).asText() ).isEqualTo( "17.0.3.31" );
        assertThat( mini.path( "generation" ).asText() ).isEqualTo( "GEN2" );
        assertThat( mini.path( "httpsStatus" ).asText() ).isEqualTo( "SUPPORTED" );
        assertThat( mini.path( "address" ).asText() ).isEqualTo( "192.0.2.10" );
        assertThat( mini.path( "local" ).asBoolean() ).isTrue();
    }

    @Test
    @DisplayName( "no identity present → app_info still publishes (miniserver key omitted)" )
    @Timeout( value = 5, unit = TimeUnit.SECONDS )
    void firesEvenWithoutIdentity() throws Exception
    {
        bus.fireAsync( new MiniserverConnectedEvent( Instant.now(), Optional.empty() ) );

        String                 topic = config.transport().topics().publish().appInfo().topic();
        FakeMqttClient.Publish p     = awaitPublishOn( topic );
        assertThat( p ).isNotNull();

        JsonNode body = jsonMapper.readTree( p.payload() );
        // appInfo envelope still present even without a miniserver identity.
        JsonNode appInfo = body.path( "appInfo" );
        assertThat( appInfo.path( "name" ).asText() ).isEqualTo( config.miniserver().app().info() );
        assertThat( appInfo.path( "version" ).asText() ).isNotEmpty();
        assertThat( appInfo.path( "start" ).asText() ).isNotEmpty();
        // miniserver block is a Quarkus addition, omitted when identity is absent.
        assertThat( body.has( "miniserver" ) ).isFalse();
    }

    @Test
    @DisplayName( "MQTT not connected → publish dropped, no exception" )
    void dropWhenDisconnected() throws Exception
    {
        fake.disconnect();
        bus.fireAsync( new MiniserverConnectedEvent( Instant.now(), Optional.empty() ) );
        Thread.sleep( 200 );
        assertThat( fake.publishes() ).isEmpty();
    }

    @Test
    @DisplayName( "MQTT reconnect with session RUNNING → app_info republished" )
    @Timeout( value = 5, unit = TimeUnit.SECONDS )
    void republishOnMqttReconnectWhenRunning() throws Exception
    {
        // Drive the session to RUNNING via the tracker, populate identity
        // so the published payload matches what was up before the MQTT blip.
        sessionTracker.transition( SessionState.CONNECTING );
        sessionTracker.transition( SessionState.RUNNING );
        miniserverState.update( new MiniserverIdentity(
                "50:4F:94:AA:BB:CC",
                MiniserverVersion.parse( "17.0.3.31" ),
                null, false, true, "192.0.2.10",
                HttpsStatus.SUPPORTED, MiniserverGeneration.GEN2 ) );

        // Fire MQTT (re)connect — simulates broker restart's CONNACK
        // landing while the Miniserver session stayed up.
        mqttBus.fire( new MqttConnectedEvent() );

        String                 topic = config.transport().topics().publish().appInfo().topic();
        FakeMqttClient.Publish p     = awaitPublishOn( topic );
        assertThat( p ).as( "republish on %s within timeout", topic ).isNotNull();

        JsonNode body = jsonMapper.readTree( p.payload() );
        assertThat( body.path( "appInfo" ).path( "name" ).asText() )
                .isEqualTo( config.miniserver().app().info() );
        assertThat( body.path( "miniserver" ).path( "serial" ).asText() )
                .as( "identity restored from MiniserverState" )
                .isEqualTo( "50:4F:94:AA:BB:CC" );
    }

    @Test
    @DisplayName( "MQTT reconnect with session NOT RUNNING → no app_info republish" )
    void noRepublishWhenSessionNotRunning() throws Exception
    {
        // Force the session OUT of RUNNING (default state at boot is DISCONNECTED).
        // The republish observer must defer to the regular MiniserverConnected path.
        sessionTracker.transition( SessionState.DISCONNECTED );

        mqttBus.fire( new MqttConnectedEvent() );
        Thread.sleep( 200 );

        String topic = config.transport().topics().publish().appInfo().topic();
        assertThat( fake.lastOn( topic ) ).as( "no publish on %s while session DISCONNECTED", topic ).isNull();
    }

    private FakeMqttClient.Publish awaitPublishOn( String topic ) throws InterruptedException
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos( 2 );
        while ( System.nanoTime() < deadline )
        {
            FakeMqttClient.Publish p = fake.lastOn( topic );
            if ( p != null )
            { return p; }
            Thread.sleep( 5 );
        }
        return fake.lastOn( topic );
    }
}
