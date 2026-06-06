/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quaddan.iot.loxmq.config.LoxoneConfig;
import com.quaddan.iot.loxmq.miniserver.message.DayTimerStatesEvent;
import com.quaddan.iot.loxmq.miniserver.message.DecodedMessages;
import com.quaddan.iot.loxmq.miniserver.message.TextStatesEvent;
import com.quaddan.iot.loxmq.miniserver.message.ValueStatesEvent;
import com.quaddan.iot.loxmq.miniserver.message.WeatherStatesEvent;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test of the publish pipeline: fires each of the
 * decoded-state CDI events and asserts that {@link FakeMqttClient}
 * received the right publish (topic, QoS, retain, JSON-decodable body).
 *
 * <p>The fake client is installed via {@link QuarkusMock} before any test
 * method runs, replacing the {@link HiveMqClient} bean. {@link Event}
 * dispatch is asynchronous ({@code @ObservesAsync} on the publisher), so
 * each assertion polls up to 2 s — generous for CI, sub-millisecond on a
 * quiet dev box.
 */
@QuarkusTest
@TestProfile( StatesPublisherTest.BatchModeProfile.class )
@DisplayName( "StatesPublisher (BATCH mode) — events → single JSON envelope publish" )
class StatesPublisherTest
{
    /** Forces BATCH so the default-SINGLE wiring doesn't apply — tests assert
     *  the single-envelope publish shape. SINGLE-mode coverage lives in
     *  {@link StatesPublisherSingleModeTest}. */
    public static class BatchModeProfile implements QuarkusTestProfile
    {
        @Override
        public Map< String, String > getConfigOverrides()
        {
            return Map.of( "loxone.transport.mode", "BATCH" );
        }
    }

    @Inject
    LoxoneConfig config;
    @Inject
    ObjectMapper jsonMapper;

    @Inject
    Event< ValueStatesEvent >    valueBus;
    @Inject
    Event< TextStatesEvent >     textBus;
    @Inject
    Event< DayTimerStatesEvent > dayTimerBus;
    @Inject
    Event< WeatherStatesEvent >  weatherBus;

    /** Mocks installed in {@code @BeforeEach} are uninstalled at the end
     *  of each test method (Quarkus contract). Fresh instance every test;
     *  no static caching. */
    private FakeMqttClient fake;

    @BeforeEach
    void installFake()
    {
        fake = new FakeMqttClient();
        QuarkusMock.installMockForType( fake, HiveMqClient.class );
        fake.connect();          // publisher checks isConnected() before sending
    }

    // ---------------------------------------------------------------------
    //  Value-states
    // ---------------------------------------------------------------------

    @Test
    @DisplayName( "ValueStatesEvent → publish on value-states topic with JSON body" )
    void valueStatesEvent_publishesJsonOnConfiguredTopic() throws Exception
    {
        var states = new DecodedMessages.ValueStates(
                new DecodedMessages.Header( "1.0", 1_700_000_000_000L, config.miniserver().app().id(), 2, 1 ),
                List.of( new DecodedMessages.ValueState( "11111111-2222-3333-0102030405060708", 42.5 ) ) );

        valueBus.fireAsync( new ValueStatesEvent( states ) );

        String                 expectedTopic = config.transport().topics().publish().valueStates().topic();
        FakeMqttClient.Publish p             = awaitPublishOn( expectedTopic );

        assertThat( p ).as( "publish on %s", expectedTopic ).isNotNull();
        assertThat( p.qos() ).isEqualTo( config.transport().topics().publish().valueStates().qos() );
        assertThat( p.retain() ).isEqualTo( config.transport().topics().publish().valueStates().retain() );

        JsonNode body = jsonMapper.readTree( p.payload() );
        assertThat( body.path( "values" ).isArray() ).isTrue();
        assertThat( body.path( "values" ).get( 0 ).path( "uuid" ).asText() ).isEqualTo( "11111111-2222-3333-0102030405060708" );
        assertThat( body.path( "values" ).get( 0 ).path( "value" ).asDouble() ).isEqualTo( 42.5 );
    }

    // ---------------------------------------------------------------------
    //  Text-states
    // ---------------------------------------------------------------------

    @Test
    @DisplayName( "TextStatesEvent → publish on text-states topic" )
    void textStatesEvent_publishes() throws Exception
    {
        var states = new DecodedMessages.TextStates(
                new DecodedMessages.Header( "1.0", 0L, config.miniserver().app().id(), 3, 1 ),
                List.of( new DecodedMessages.TextState( "aaaaaaaa-bbbb-cccc-0908070605040302", "hello" ) ) );

        textBus.fireAsync( new TextStatesEvent( states ) );

        String                 topic = config.transport().topics().publish().textStates().topic();
        FakeMqttClient.Publish p     = awaitPublishOn( topic );

        assertThat( p ).isNotNull();
        JsonNode body = jsonMapper.readTree( p.payload() );
        assertThat( body.path( "values" ).get( 0 ).path( "value" ).asText() ).isEqualTo( "hello" );
    }

    // ---------------------------------------------------------------------
    //  DayTimer-states
    // ---------------------------------------------------------------------

    @Test
    @DisplayName( "DayTimerStatesEvent → publish on day-timer-states topic" )
    void dayTimerStatesEvent_publishes() throws Exception
    {
        var states = new DecodedMessages.DayTimerStates(
                new DecodedMessages.Header( "1.0", 0L, config.miniserver().app().id(), 4, 1 ),
                "11223344-5566-7788-0102030405060708",
                19.0, 1,
                List.of( new DecodedMessages.DayTimerState( 5, 360, 1320, true, 21.5 ) ) );

        dayTimerBus.fireAsync( new DayTimerStatesEvent( states ) );

        String                 topic = config.transport().topics().publish().dayTimerStates().topic();
        FakeMqttClient.Publish p     = awaitPublishOn( topic );

        assertThat( p ).isNotNull();
        JsonNode body = jsonMapper.readTree( p.payload() );
        assertThat( body.path( "uuid" ).asText() ).isEqualTo( "11223344-5566-7788-0102030405060708" );
        assertThat( body.path( "defaultValue" ).asDouble() ).isEqualTo( 19.0 );
    }

    // ---------------------------------------------------------------------
    //  Weather-states
    // ---------------------------------------------------------------------

    @Test
    @DisplayName( "WeatherStatesEvent → publish on weather-states topic" )
    void weatherStatesEvent_publishes() throws Exception
    {
        var states = new DecodedMessages.WeatherStates(
                new DecodedMessages.Header( "1.0", 0L, config.miniserver().app().id(), 7, 1 ),
                "c0ffee00-1234-5678-0b0c0d0e0f101112",
                1_700_000_000, 1,
                List.of( new DecodedMessages.WeatherState( 1_700_000_100, 7, 180, 450, 60,
                                                           22.5, 21.8, 14.0, 0.0, 3.5, 1013.25 ) ) );

        weatherBus.fireAsync( new WeatherStatesEvent( states ) );

        String                 topic = config.transport().topics().publish().weatherStates().topic();
        FakeMqttClient.Publish p     = awaitPublishOn( topic );

        assertThat( p ).isNotNull();
        JsonNode body = jsonMapper.readTree( p.payload() );
        assertThat( body.path( "values" ).get( 0 ).path( "temperature" ).asDouble() ).isEqualTo( 22.5 );
        assertThat( body.path( "values" ).get( 0 ).path( "barometricPressure" ).asDouble() ).isEqualTo( 1013.25 );
    }

    // ---------------------------------------------------------------------
    //  Out-of-service: tested in OutOfServiceMqttReconnectorTest (the
    //  reconnector owns both the notification publish AND the scheduled
    //  reconnect — single observer, single test target).
    // ---------------------------------------------------------------------

    // ---------------------------------------------------------------------
    //  Disconnect gating
    // ---------------------------------------------------------------------

    @Test
    @DisplayName( "publishes are dropped when MQTT is disconnected (no exception)" )
    void publishDropped_whenNotConnected() throws Exception
    {
        fake.disconnect();
        var states = new DecodedMessages.ValueStates(
                new DecodedMessages.Header( "1.0", 0L, config.miniserver().app().id(), 2, 0 ),
                List.of() );
        valueBus.fireAsync( new ValueStatesEvent( states ) );
        // No publish expected — give the async dispatcher a moment.
        Thread.sleep( 150 );
        assertThat( fake.publishes() ).isEmpty();
    }

    // ---------------------------------------------------------------------
    //  Helpers
    // ---------------------------------------------------------------------

    private FakeMqttClient.Publish awaitPublishOn( String topic ) throws InterruptedException
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos( 2 );
        while ( System.nanoTime() < deadline )
        {
            FakeMqttClient.Publish p = fake.lastOn( topic );
            if ( p != null )
            {
                return p;
            }
            Thread.sleep( 5 );
        }
        return fake.lastOn( topic );
    }
}
