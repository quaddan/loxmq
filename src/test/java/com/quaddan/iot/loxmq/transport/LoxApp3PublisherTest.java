/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.transport;

import com.quaddan.iot.loxmq.config.LoxoneConfig;
import com.quaddan.iot.loxmq.miniserver.session.LoxApp3Cache;
import com.quaddan.iot.loxmq.miniserver.session.MiniserverConnectedEvent;
import com.quaddan.iot.loxmq.miniserver.session.SessionState;
import com.quaddan.iot.loxmq.miniserver.session.SessionTracker;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fires {@link MiniserverConnectedEvent} and asserts the cached
 * {@code LoxAPP3.json} content was published verbatim on the
 * {@code lox-app3} topic.
 */
@QuarkusTest
@DisplayName( "LoxApp3Publisher — RUNNING → cached LoxAPP3.json retained" )
class LoxApp3PublisherTest
{
    @Inject
    LoxoneConfig                      config;
    @Inject
    LoxApp3Cache                      cache;
    @Inject
    Event< MiniserverConnectedEvent > bus;
    @Inject
    Event< MqttConnectedEvent >       mqttBus;
    @Inject
    SessionTracker                    sessionTracker;

    private FakeMqttClient fake;

    @BeforeEach
    void install()
    {
        fake = new FakeMqttClient();
        QuarkusMock.installMockForType( fake, HiveMqClient.class );
        fake.connect();
        cache.clear();
    }

    @AfterEach
    void cleanup()
    {
        cache.clear();
    }

    @Test
    @DisplayName( "cache populated → LoxAPP3 published verbatim on lox-app3 topic" )
    @Timeout( value = 5, unit = TimeUnit.SECONDS )
    void firesLoxApp3OnConnected() throws Exception
    {
        String fixture = "{\"lastModified\":\"2026-05-19 13:00:00\",\"fixture\":\"loxapp3 body\"}";
        cache.store( fixture, "2026-05-19 13:00:00" );    // signature: (json, lastModified)

        bus.fireAsync( new MiniserverConnectedEvent( Instant.now(), Optional.empty() ) );

        String                 topic = config.transport().topics().publish().loxApp3().topic();
        FakeMqttClient.Publish p     = awaitPublishOn( topic );
        assertThat( p ).as( "publish on %s", topic ).isNotNull();
        assertThat( p.retain() ).isEqualTo( config.transport().topics().publish().loxApp3().retain() );
        assertThat( new String( p.payload(), StandardCharsets.UTF_8 ) ).isEqualTo( fixture );
    }

    @Test
    @DisplayName( "cache empty → publish skipped, WARN log only (defensive)" )
    void cacheEmptySkipsPublish() throws Exception
    {
        // No cache.store — load() returns Optional.empty.
        bus.fireAsync( new MiniserverConnectedEvent( Instant.now(), Optional.empty() ) );
        Thread.sleep( 200 );
        String topic = config.transport().topics().publish().loxApp3().topic();
        assertThat( fake.lastOn( topic ) ).isNull();
    }

    @Test
    @DisplayName( "MQTT not connected → publish dropped" )
    void dropWhenDisconnected() throws Exception
    {
        cache.store( "body", "ts" );    // signature: (json, lastModified)
        fake.disconnect();
        bus.fireAsync( new MiniserverConnectedEvent( Instant.now(), Optional.empty() ) );
        Thread.sleep( 200 );
        assertThat( fake.publishes() ).isEmpty();
    }

    @Test
    @DisplayName( "MQTT reconnect with session RUNNING + cache populated → LoxAPP3 republished" )
    @Timeout( value = 5, unit = TimeUnit.SECONDS )
    void republishOnMqttReconnectWhenRunning() throws Exception
    {
        String fixture = "{\"lastModified\":\"2026-05-19 13:00:00\",\"fixture\":\"reconnect-republish\"}";
        cache.store( fixture, "2026-05-19 13:00:00" );

        sessionTracker.transition( SessionState.CONNECTING );
        sessionTracker.transition( SessionState.RUNNING );

        mqttBus.fire( new MqttConnectedEvent() );

        String                 topic = config.transport().topics().publish().loxApp3().topic();
        FakeMqttClient.Publish p     = awaitPublishOn( topic );
        assertThat( p ).as( "republish on %s", topic ).isNotNull();
        assertThat( new String( p.payload(), StandardCharsets.UTF_8 ) ).isEqualTo( fixture );
    }

    @Test
    @DisplayName( "MQTT reconnect with session NOT RUNNING → no republish (defer to MiniserverConnected)" )
    void noRepublishWhenSessionNotRunning() throws Exception
    {
        cache.store( "body", "ts" );
        sessionTracker.transition( SessionState.DISCONNECTED );

        mqttBus.fire( new MqttConnectedEvent() );
        Thread.sleep( 200 );

        String topic = config.transport().topics().publish().loxApp3().topic();
        assertThat( fake.lastOn( topic ) ).as( "no publish while DISCONNECTED" ).isNull();
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
