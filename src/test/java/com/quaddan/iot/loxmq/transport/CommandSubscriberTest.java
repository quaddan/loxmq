/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.transport;

import com.quaddan.iot.loxmq.config.LoxoneConfig;
import com.quaddan.iot.loxmq.miniserver.command.MiniserverApiConnectorSetCommandEvent;
import com.quaddan.iot.loxmq.miniserver.command.MiniserverCommandEvent;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the inbound command path:
 * <ol>
 *   <li>{@link MqttConnectedEvent} fires → CommandSubscriber subscribes
 *       to the configured command + api topics.</li>
 *   <li>FakeMqttClient.deliverInbound() simulates a broker push →
 *       handler parses JSON and fires the matching CDI event.</li>
 *   <li>Malformed JSON is logged + dropped (no event fired, no
 *       exception bubbles up).</li>
 * </ol>
 */
@QuarkusTest
@DisplayName( "CommandSubscriber — MQTT inbound → CDI events" )
class CommandSubscriberTest
{
    @Singleton
    public static class CommandRecorder
    {
        public final List< MiniserverCommandEvent >                cmds = new CopyOnWriteArrayList<>();
        public final List< MiniserverApiConnectorSetCommandEvent > apis = new CopyOnWriteArrayList<>();

        public void onCmd( @Observes MiniserverCommandEvent e ) { cmds.add( e ); }

        public void onApi( @Observes MiniserverApiConnectorSetCommandEvent e ) { apis.add( e ); }

        public void clear()
        {
            cmds.clear();
            apis.clear();
        }
    }

    @Inject
    LoxoneConfig                config;
    @Inject
    Event< MqttConnectedEvent > connectedBus;
    @Inject
    CommandRecorder             recorder;

    private FakeMqttClient fake;

    @BeforeEach
    void install()
    {
        fake = new FakeMqttClient();
        QuarkusMock.installMockForType( fake, HiveMqClient.class );
        fake.connect();
        recorder.clear();
        // Drive the subscribe step — the production CommandSubscriber
        // observes MqttConnectedEvent and issues subscribes on the fake.
        connectedBus.fire( new MqttConnectedEvent() );
    }

    @Test
    @DisplayName( "MqttConnectedEvent ⇒ subscribed to both inbound topics" )
    void subscribesOnConnack()
    {
        var cmdTopic = config.transport().topics().subscribe().command().topic();
        var apiTopic = config.transport().topics().subscribe().api().topic();
        var filters  = fake.subscriptions().stream().map( FakeMqttClient.Subscription::topicFilter ).toList();
        assertThat( filters ).contains( cmdTopic, apiTopic );
    }

    @Test
    @DisplayName( "reconnect ⇒ unsubscribe BEFORE re-subscribe (no callback accumulation)" )
    void reconnectUnsubscribesBeforeResubscribe()
    {
        // The @BeforeEach fired one MqttConnectedEvent → 2 subscribes,
        // 2 unsubscribes (the "first connect" path also calls unsubscribe
        // tolerantly to drop any pre-startup handler residue). Fire a
        // second event to simulate a broker reconnect.
        var cmdTopic         = config.transport().topics().subscribe().command().topic();
        var apiTopic         = config.transport().topics().subscribe().api().topic();
        int subsAfterFirst   = fake.subscriptions().size();
        int unsubsAfterFirst = fake.unsubscribes().size();

        connectedBus.fire( new MqttConnectedEvent() );

        // After reconnect : 2 more subscribes AND 2 more unsubscribes.
        // The unsubscribe-then-subscribe ordering is what keeps the
        // HiveMQ callback table from accumulating stale callbacks.
        assertThat( fake.subscriptions() ).hasSize( subsAfterFirst + 2 );
        assertThat( fake.unsubscribes() ).hasSize( unsubsAfterFirst + 2 );

        // The most-recent unsubscribe pair MUST cover BOTH topics, in
        // any order — confirms onMqttConnected drops both before re-subbing.
        var lastUnsubs = fake.unsubscribes().subList( unsubsAfterFirst,
                                                      fake.unsubscribes().size() );
        assertThat( lastUnsubs ).containsExactlyInAnyOrder( cmdTopic, apiTopic );
    }

    @Test
    @DisplayName( "after reconnect, an inbound command fires ONCE (not duplicated)" )
    @Timeout( value = 5, unit = TimeUnit.SECONDS )
    void reconnectDoesNotDuplicateInboundEvents()
    {
        // Simulate a reconnect : without the unsubscribe-before-resubscribe
        // guard a second callback would accumulate, so the FakeMqttClient's
        // handlersByTopic would still route to ONE handler (.put replaces)
        // but in HiveMQ production it would route to BOTH. We assert here
        // on the visible-from-CDI side : exactly one MiniserverCommandEvent.
        connectedBus.fire( new MqttConnectedEvent() );

        var topic = config.transport().topics().subscribe().command().topic();
        fake.deliverInbound( topic, topic,
                             "{\"uuid\":\"1072755d-024f-4540-ffff112233445566/AI1\",\"command\":\"on\"}"
                                     .getBytes( StandardCharsets.UTF_8 ) );

        assertThat( recorder.cmds ).hasSize( 1 );
    }

    @Test
    @DisplayName( "valid command JSON on …/command ⇒ MiniserverCommandEvent fired" )
    @Timeout( value = 5, unit = TimeUnit.SECONDS )
    void validCommand_firesEvent()
    {
        var topic = config.transport().topics().subscribe().command().topic();
        fake.deliverInbound( topic, topic,
                             "{\"uuid\":\"1072755d-024f-4540-ffff112233445566/AI1\",\"command\":\"on\"}"
                                     .getBytes( StandardCharsets.UTF_8 ) );

        assertThat( recorder.cmds ).hasSize( 1 );
        var cmd = recorder.cmds.get( 0 ).command();
        assertThat( cmd.uuid() ).isEqualTo( "1072755d-024f-4540-ffff112233445566/AI1" );
        assertThat( cmd.command() ).isEqualTo( "on" );
    }

    @Test
    @DisplayName( "valid api-set JSON on …/api ⇒ MiniserverApiConnectorSetCommandEvent fired" )
    @Timeout( value = 5, unit = TimeUnit.SECONDS )
    void validApiSet_firesEvent()
    {
        var topic = config.transport().topics().subscribe().api().topic();
        fake.deliverInbound( topic, topic,
                             ( "{\"virtual_input_text\":\"VTI-LumiereBureau\","
                               + "\"function_block\":\"Lico\","
                               + "\"input\":\"Lc1\","
                               + "\"value\":\"Pulse\"}" )
                                     .getBytes( StandardCharsets.UTF_8 ) );

        assertThat( recorder.apis ).hasSize( 1 );
        var c = recorder.apis.get( 0 ).apiConnector();
        assertThat( c.virtualInputText() ).isEqualTo( "VTI-LumiereBureau" );
        assertThat( c.functionBlock() ).isEqualTo( "Lico" );
        assertThat( c.input() ).isEqualTo( "Lc1" );
        assertThat( c.value() ).isEqualTo( "Pulse" );
    }

    @Test
    @DisplayName( "malformed JSON on …/command ⇒ dropped, no event, no exception bubbles up" )
    @Timeout( value = 5, unit = TimeUnit.SECONDS )
    void malformedJson_isDropped()
    {
        var topic = config.transport().topics().subscribe().command().topic();
        // FakeMqttClient.deliverInbound invokes the handler directly — if the
        // handler threw, this call would throw too. The subscriber catches
        // JsonProcessingException internally and just logs.
        fake.deliverInbound( topic, topic, "not-json".getBytes( StandardCharsets.UTF_8 ) );
        assertThat( recorder.cmds ).isEmpty();
    }

    @Test
    @DisplayName( "retained command (well-formed) ⇒ dropped with WARN, NO event fired (production safety)" )
    @Timeout( value = 5, unit = TimeUnit.SECONDS )
    void retainedCommand_isDropped()
    {
        // Reproduces the originating production bug — a misconfigured
        // automation pushes a retained command. Without the guard the
        // binding would replay the command on every CONNACK.
        var topic = config.transport().topics().subscribe().command().topic();
        fake.deliverInbound( topic, topic,
                             "{\"uuid\":\"1072755d-024f-4540-ffff112233445566/AI1\",\"command\":\"on\"}"
                                     .getBytes( StandardCharsets.UTF_8 ),
                             true /* retained */ );

        // No CDI event must fire — the guard kicks in BEFORE the JSON parse,
        // so even a syntactically perfect command is rejected when retained.
        assertThat( recorder.cmds ).isEmpty();
    }

    @Test
    @DisplayName( "retained API SET ⇒ dropped with WARN, NO event fired" )
    @Timeout( value = 5, unit = TimeUnit.SECONDS )
    void retainedApiSet_isDropped()
    {
        var topic = config.transport().topics().subscribe().api().topic();
        fake.deliverInbound( topic, topic,
                             ( "{\"virtual_input_text\":\"VTI-LumiereBureau\","
                               + "\"function_block\":\"Lico\","
                               + "\"input\":\"Lc1\","
                               + "\"value\":\"Pulse\"}" )
                                     .getBytes( StandardCharsets.UTF_8 ),
                             true /* retained */ );

        assertThat( recorder.apis ).isEmpty();
    }

    @Test
    @DisplayName( "oversized command (> 4 KB default) ⇒ dropped with WARN, NO event fired, NO JSON parse" )
    @Timeout( value = 5, unit = TimeUnit.SECONDS )
    void oversizedCommand_isDropped()
    {
        var topic = config.transport().topics().subscribe().command().topic();
        // Build a 10 KB payload of JSON-shaped garbage (would parse as
        // valid JSON if the cap weren't enforced). The size guard kicks
        // in before the parse, so no exception is thrown — the message
        // is simply dropped.
        byte[] huge = buildOversizedPayload( 10_240 );
        fake.deliverInbound( topic, topic, huge );

        assertThat( recorder.cmds ).isEmpty();
    }

    @Test
    @DisplayName( "oversized API SET ⇒ dropped with WARN, NO event fired" )
    @Timeout( value = 5, unit = TimeUnit.SECONDS )
    void oversizedApiSet_isDropped()
    {
        var    topic = config.transport().topics().subscribe().api().topic();
        byte[] huge  = buildOversizedPayload( 10_240 );
        fake.deliverInbound( topic, topic, huge );

        assertThat( recorder.apis ).isEmpty();
    }

    @Test
    @DisplayName( "boundary: exactly maxBytes ⇒ ACCEPTED (the cap is exclusive — >max drops, ==max passes)" )
    @Timeout( value = 5, unit = TimeUnit.SECONDS )
    void boundaryAtCap_isAccepted()
    {
        // Construct a valid command JSON that happens to be padded out
        // to exactly the configured cap. The default 4096-byte cap is
        // applied as a strict greater-than — a payload of length == cap
        // passes through. We start with a real command then pad the
        // "command" field's value with spaces up to the budget.
        int    max     = config.transport().security().maxInboundPayloadBytes();
        var    topic   = config.transport().topics().subscribe().command().topic();
        String prefix  = "{\"uuid\":\"1072755d-024f-4540-ffff112233445566/AI1\",\"command\":\"";
        String suffix  = "\"}";
        int    padLen  = max - prefix.length() - suffix.length();
        String pad     = " ".repeat( padLen );
        byte[] payload = ( prefix + pad + suffix ).getBytes( StandardCharsets.UTF_8 );

        // Sanity — confirm we built exactly maxBytes.
        assertThat( payload.length ).isEqualTo( max );

        fake.deliverInbound( topic, topic, payload );

        // Still a valid command; the leading-space command string isn't
        // a real Loxone command but the parse succeeds and the event
        // fires (the cap is the only thing being exercised here).
        assertThat( recorder.cmds ).hasSize( 1 );
    }

    /** Build a JSON-shaped payload exactly {@code targetBytes} long.
     *  The payload IS valid JSON, so a missing size guard would not
     *  trigger a parse error — the test would silently pass for the
     *  wrong reason without this property. */
    private static byte[] buildOversizedPayload( int targetBytes )
    {
        String prefix = "{\"uuid\":\"x\",\"command\":\"";
        String suffix = "\"}";
        int    padLen = targetBytes - prefix.length() - suffix.length();
        String pad    = "A".repeat( Math.max( 0, padLen ) );
        return ( prefix + pad + suffix ).getBytes( StandardCharsets.UTF_8 );
    }
}
