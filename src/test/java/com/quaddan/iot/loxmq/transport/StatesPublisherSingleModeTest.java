/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quaddan.iot.loxmq.transport.publish.StatesPublisher;
import com.quaddan.iot.loxmq.config.LoxoneConfig;
import com.quaddan.iot.loxmq.miniserver.message.DayTimerStatesEvent;
import com.quaddan.iot.loxmq.miniserver.message.DecodedMessages;
import com.quaddan.iot.loxmq.miniserver.message.TextStatesEvent;
import com.quaddan.iot.loxmq.miniserver.message.ValueStatesEvent;
import com.quaddan.iot.loxmq.miniserver.message.WeatherStatesEvent;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SINGLE-mode tests for {@link StatesPublisher}.
 *
 * <p>SINGLE is the default mode, so
 * no {@code @TestProfile} override is needed — just the default
 * {@code loxone.transport.mode=SINGLE} from {@code application.yml}.
 *
 * <p>Assertions focus on the per-UUID publish behaviour:
 * <ul>
 *   <li>N value-states → N publishes on {@code {topic}/{uuid}} with the
 *       value as UTF-8 decimal text.</li>
 *   <li>N text-states → N publishes with the text as UTF-8 bytes.</li>
 *   <li>1 daytimer event → 1 publish on {@code {topic}/{uuid}} with JSON
 *       body (no header wrapper).</li>
 *   <li>1 weather event → 1 publish on {@code {topic}/{uuid}} with JSON
 *       body (no header wrapper).</li>
 * </ul>
 */
@QuarkusTest
@DisplayName( "StatesPublisher (SINGLE mode) — events → per-UUID publishes" )
class StatesPublisherSingleModeTest
{
    @Inject
    LoxoneConfig                 config;
    @Inject
    ObjectMapper                 jsonMapper;
    @Inject
    Event< ValueStatesEvent >    valueBus;
    @Inject
    Event< TextStatesEvent >     textBus;
    @Inject
    Event< DayTimerStatesEvent > dayTimerBus;
    @Inject
    Event< WeatherStatesEvent >  weatherBus;

    private FakeMqttClient fake;

    @BeforeEach
    void installFake()
    {
        fake = new FakeMqttClient();
        QuarkusMock.installMockForType( fake, HiveMqClient.class );
        fake.connect();
    }

    // ---------------------------------------------------------------------
    //  Value states
    // ---------------------------------------------------------------------

    @Test
    @DisplayName( "value-states (2 entries) → 2 publishes, each {topic}/{uuid} with raw double" )
    @Timeout( value = 5, unit = TimeUnit.SECONDS )
    void valueStates_singlePublishPerUuid() throws Exception
    {
        var states = new DecodedMessages.ValueStates(
                new DecodedMessages.Header( "1.0", 0L, config.miniserver().app().id(), 2, 2 ),
                List.of(
                        new DecodedMessages.ValueState( "11111111-2222-3333-aaaaaaaaaaaaaaaa", 42.5 ),
                        new DecodedMessages.ValueState( "11111111-2222-3333-bbbbbbbbbbbbbbbb", -17.25 ) ) );

        valueBus.fireAsync( new ValueStatesEvent( states ) );
        awaitPublishCount( 2 );

        String prefix = config.transport().topics().publish().valueStates().topic();
        var    p1     = fake.lastOn( prefix + "/11111111-2222-3333-aaaaaaaaaaaaaaaa" );
        var    p2     = fake.lastOn( prefix + "/11111111-2222-3333-bbbbbbbbbbbbbbbb" );

        assertThat( p1 ).isNotNull();
        assertThat( new String( p1.payload(), StandardCharsets.UTF_8 ) ).isEqualTo( "42.5" );
        assertThat( p2 ).isNotNull();
        assertThat( new String( p2.payload(), StandardCharsets.UTF_8 ) ).isEqualTo( "-17.25" );

        // QoS + retain inherited from the value-states publish spec.
        assertThat( p1.qos() ).isEqualTo( config.transport().topics().publish().valueStates().qos() );
        assertThat( p1.retain() ).isEqualTo( config.transport().topics().publish().valueStates().retain() );
    }

    // ---------------------------------------------------------------------
    //  Text states
    // ---------------------------------------------------------------------

    @Test
    @DisplayName( "text-states (3 entries) → 3 publishes, raw text bytes" )
    @Timeout( value = 5, unit = TimeUnit.SECONDS )
    void textStates_singlePublishPerUuid() throws Exception
    {
        var states = new DecodedMessages.TextStates(
                new DecodedMessages.Header( "1.0", 0L, config.miniserver().app().id(), 3, 3 ),
                List.of(
                        new DecodedMessages.TextState( "aaaaaaaa-1111-2222-cccccccccccccccc", "Pas de pluie" ),
                        new DecodedMessages.TextState( "aaaaaaaa-1111-2222-dddddddddddddddd", "Vent fort" ),
                        new DecodedMessages.TextState( "aaaaaaaa-1111-2222-eeeeeeeeeeeeeeee", "Off" ) ) );

        textBus.fireAsync( new TextStatesEvent( states ) );
        awaitPublishCount( 3 );

        String prefix = config.transport().topics().publish().textStates().topic();
        assertThat( new String( fake.lastOn( prefix + "/aaaaaaaa-1111-2222-cccccccccccccccc" ).payload(),
                                StandardCharsets.UTF_8 ) ).isEqualTo( "Pas de pluie" );
        assertThat( new String( fake.lastOn( prefix + "/aaaaaaaa-1111-2222-dddddddddddddddd" ).payload(),
                                StandardCharsets.UTF_8 ) ).isEqualTo( "Vent fort" );
        assertThat( new String( fake.lastOn( prefix + "/aaaaaaaa-1111-2222-eeeeeeeeeeeeeeee" ).payload(),
                                StandardCharsets.UTF_8 ) ).isEqualTo( "Off" );
    }

    // ---------------------------------------------------------------------
    //  DayTimer states — ALWAYS JSON batch (single observer, no per-UUID
    //  decomposition; a DayTimer payload is one UUID per timer with
    //  structured slots, so splitting per-UUID doesn't gain anything).
    // ---------------------------------------------------------------------

    @Test
    @DisplayName( "daytimer-states → 1 JSON envelope publish on {topic} (same in SINGLE and BATCH)" )
    @Timeout( value = 5, unit = TimeUnit.SECONDS )
    void dayTimerStates_alwaysJsonBatchOnConfiguredTopic() throws Exception
    {
        var states = new DecodedMessages.DayTimerStates(
                new DecodedMessages.Header( "1.0", 0L, config.miniserver().app().id(), 4, 1 ),
                "11223344-5566-7788-9999aaaabbbbcccc",
                19.0, 1,
                List.of( new DecodedMessages.DayTimerState( 5, 360, 1320, true, 21.5 ) ) );

        dayTimerBus.fireAsync( new DayTimerStatesEvent( states ) );
        awaitPublishCount( 1 );

        String topic = config.transport().topics().publish().dayTimerStates().topic();
        var    pub   = fake.lastOn( topic );
        assertThat( pub ).as( "publish on %s", topic ).isNotNull();

        JsonNode body = jsonMapper.readTree( pub.payload() );
        // Full DayTimerStates record serialised, header included.
        assertThat( body.path( "uuid" ).asText() ).isEqualTo( "11223344-5566-7788-9999aaaabbbbcccc" );
        assertThat( body.path( "defaultValue" ).asDouble() ).isEqualTo( 19.0 );
        assertThat( body.path( "header" ).path( "type" ).asInt() ).isEqualTo( 4 );
    }

    // ---------------------------------------------------------------------
    //  Weather states — ALWAYS JSON batch (same rationale as DayTimer)
    // ---------------------------------------------------------------------

    @Test
    @DisplayName( "weather-states → 1 JSON envelope publish on {topic} (same in SINGLE and BATCH)" )
    @Timeout( value = 5, unit = TimeUnit.SECONDS )
    void weatherStates_alwaysJsonBatchOnConfiguredTopic() throws Exception
    {
        var states = new DecodedMessages.WeatherStates(
                new DecodedMessages.Header( "1.0", 0L, config.miniserver().app().id(), 7, 1 ),
                "c0ffee00-1234-5678-eeeeffff00001111",
                1_700_000_000, 1,
                List.of( new DecodedMessages.WeatherState( 1_700_000_100, 7, 180, 450, 60,
                                                           22.5, 21.8, 14.0, 0.0, 3.5, 1013.25 ) ) );

        weatherBus.fireAsync( new WeatherStatesEvent( states ) );
        awaitPublishCount( 1 );

        String topic = config.transport().topics().publish().weatherStates().topic();
        var    pub   = fake.lastOn( topic );
        assertThat( pub ).as( "publish on %s", topic ).isNotNull();

        JsonNode body = jsonMapper.readTree( pub.payload() );
        assertThat( body.path( "uuid" ).asText() ).isEqualTo( "c0ffee00-1234-5678-eeeeffff00001111" );
        assertThat( body.path( "lastUpdate" ).asInt() ).isEqualTo( 1_700_000_000 );
        assertThat( body.path( "values" ).get( 0 ).path( "temperature" ).asDouble() ).isEqualTo( 22.5 );
        assertThat( body.path( "header" ).path( "type" ).asInt() ).isEqualTo( 7 );
    }

    // ---------------------------------------------------------------------
    //  Helpers
    // ---------------------------------------------------------------------

    /** Poll until the fake captures at least {@code n} publishes. */
    private void awaitPublishCount( int n ) throws InterruptedException
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos( 2 );
        while ( System.nanoTime() < deadline )
        {
            if ( fake.publishes().size() >= n )
            {
                return;
            }
            Thread.sleep( 5 );
        }
    }
}
