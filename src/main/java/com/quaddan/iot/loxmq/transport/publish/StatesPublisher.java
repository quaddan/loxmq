/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.transport.publish;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quaddan.iot.loxmq.config.LoxoneConfig;
import com.quaddan.iot.loxmq.config.LoxoneConfigHolder;
import com.quaddan.iot.loxmq.miniserver.message.DayTimerStatesEvent;
import com.quaddan.iot.loxmq.miniserver.message.DecodedMessages;
import com.quaddan.iot.loxmq.miniserver.message.TextStatesEvent;
import com.quaddan.iot.loxmq.miniserver.message.ValueStatesEvent;
import com.quaddan.iot.loxmq.miniserver.message.WeatherStatesEvent;
import com.quaddan.iot.loxmq.transport.MqttClient;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Bridge between the binary-states decoder and the MQTT
 * transport. Subscribes asynchronously to the four state event types
 * emitted by the decoder and publishes each on the topic configured under
 * {@code loxone.transport.topics.publish.*}.
 *
 * <h3>Dispatch pattern</h3>
 * For the two state types that <i>can</i> decompose into per-UUID
 * publishes (value-states and text-states), the mode is resolved <b>once</b>
 * at {@link #init} time into a {@link Consumer} per event type:
 *
 * <pre>
 *   onValueStates  → valueConsumer.accept(event)
 *                  → onValueStatesBatch | onValueStatesSingle
 *
 *   onTextStates   → textConsumer.accept(event)
 *                  → onTextStatesBatch | onTextStatesSingle
 * </pre>
 *
 * <p>The {@code @ObservesAsync} method stays trivial (connectivity gate +
 * delegate), and the mode branch is resolved once at startup rather than
 * per-event. Cheaper, and the variant methods stay flat — easy to grep,
 * easy to test in isolation.
 *
 * <p>DayTimer (identifier 4) and Weather (identifier 7) are <b>always</b>
 * published as a single JSON payload — these event-tables don't
 * decompose into per-UUID values meaningfully (one UUID for the whole
 * timer / station, with structured per-slot data). One observer method
 * each, no dispatch needed.
 *
 * <h3>SINGLE vs BATCH payload shapes</h3>
 * <ul>
 *   <li><b>{@code SINGLE} (default)</b> — one publish per state on
 *       {@code {topic}/{uuid}}. Raw payload:
 *       <ul>
 *         <li>type_2 (value): {@code String.valueOf(value)} as UTF-8</li>
 *         <li>type_3 (text): the text bytes as UTF-8</li>
 *       </ul>
 *       Downstream consumers subscribe directly to a UUID.</li>
 *   <li><b>{@code BATCH}</b> — one publish per event-table on
 *       {@code {topic}} with the full JSON envelope
 *       ({@code {header:…, values:[…]}}).</li>
 * </ul>
 *
 * <h3>QoS / retain</h3>
 * Per-event-type config under
 * {@code loxone.transport.topics.publish.{value,text,day-timer,weather}-states.*}.
 *
 * <h3>Out-of-service</h3>
 * Handled in {@link OutOfServiceMqttReconnector} (single observer that
 * publishes the notification then triggers the OOS-specific MQTT
 * reconnect schedule).
 *
 * <h3>Why {@code @Singleton} + {@link LoxoneConfigHolder} indirection</h3>
 * ArC has a code-generation bug we hit on 2026-05-19: any bean that
 * combines {@code @ObservesAsync} methods AND {@code @Inject}s a
 * {@code @ConfigMapping} interface fails on every event notification
 * with
 * <pre>
 *   IllegalArgumentException: A synthetic injection point was not declared
 *   for required type [InjectionPoint]
 *     at io.quarkus.arc.runtime.ConfigMappingCreator.create:21
 * </pre>
 * Two failed workarounds before landing on this one:
 * <ul>
 *   <li>Switching scope to {@code @Singleton} → same error.
 *   <li>Programmatic lookup via {@code ConfigProvider.getConfig()
 *       .unwrap(SmallRyeConfig.class).getConfigMapping(...)} →
 *       {@code ServiceConfigurationError: SmallRyeConfigFactory:
 *       QuarkusConfigFactory not a subtype} (Quarkus replaces the
 *       MicroProfile factory and the ServiceLoader fallback breaks).
 * </ul>
 *
 * <p>The fix that works: inject {@link LoxoneConfigHolder} (a plain
 * {@code @Singleton} with no async observers) and call
 * {@link LoxoneConfigHolder#get()}. The holder's own injection of
 * {@link LoxoneConfig} goes through ArC's normal-path code generation
 * where {@code InjectionPoint} is correctly synthesised. One extra
 * field lookup per config access — negligible.
 *
 * <p>{@code @Singleton} here (rather than {@code @ApplicationScoped})
 * because we don't need normal-scope proxy semantics — the publisher
 * is a leaf bean called only from CDI event dispatch.
 */
@Singleton
public class StatesPublisher
{
    private static final Logger LOG = Logger.getLogger( StatesPublisher.class );

    @Inject
    LoxoneConfigHolder configHolder;
    @Inject
    MqttClient         mqtt;
    @Inject
    ObjectMapper       jsonMapper;

    /** Shorthand for the cached config — see class javadoc for the
     *  indirection rationale. */
    private LoxoneConfig config() { return configHolder.get(); }

    private Consumer< ValueStatesEvent > valueConsumer;
    private Consumer< TextStatesEvent >  textConsumer;

    @PostConstruct
    void init()
    {
        TransportMode mode = TransportMode.parse( config().transport().mode() );
        switch ( mode )
        {
            case BATCH ->
            {
                valueConsumer = this::onValueStatesBatch;
                textConsumer  = this::onTextStatesBatch;
            }
            case SINGLE ->
            {
                valueConsumer = this::onValueStatesSingle;
                textConsumer  = this::onTextStatesSingle;
            }
        }
        LOG.infof( "StatesPublisher initialised in %s mode", mode );
    }

    /** Local enum so the switch above gets exhaustive checking + a clean
     *  parse with a sensible default. SmallRye Config's {@code mode}
     *  property is a {@code String} (with the regex constraint
     *  {@code BATCH|SINGLE}); we lift it to a typed value here. */
    private enum TransportMode
    {
        BATCH,
        SINGLE;

        static TransportMode parse( String raw )
        {
            return "BATCH".equalsIgnoreCase( raw ) ? BATCH : SINGLE;
        }
    }

    // =====================================================================
    //  Identifier 2 — Value states (SINGLE / BATCH dispatch)
    // =====================================================================

    public void onValueStates( @ObservesAsync ValueStatesEvent event )
    {
        if ( !mqtt.isConnected() )
        {
            LOG.debug( "MQTT not connected — value-states event dropped" );
            return;
        }
        valueConsumer.accept( event );
    }

    public void onValueStatesBatch( ValueStatesEvent event )
    {
        var spec = config().transport().topics().publish().valueStates();
        publishJsonBatch( "value-states[2]", event.valueStates(),
                          spec.topic(), spec.qos(), spec.retain() );
    }

    public void onValueStatesSingle( ValueStatesEvent event )
    {
        var     spec   = config().transport().topics().publish().valueStates();
        String  prefix = spec.topic();
        int     qos    = spec.qos();
        boolean ret    = spec.retain();

        int weight = event.valueStates().header().weight();
        for ( int i = 0; i < weight; i++ )
        {
            DecodedMessages.ValueState state = event.valueStates().values().get( i );
            String                     topic = prefix + "/" + state.uuid();
            mqtt.publish( topic, qos, ret,
                          String.valueOf( state.value() ).getBytes( StandardCharsets.UTF_8 ) );
            // DEBUG — 🔢 wrapper marks this as a numeric (value-state) payload.
            LOG.debugf( "🚂#%d/%d ⇨ Published ⇨ 🔢%s🔢 ⏏ Topic ⇨ %s ⏏ QoS ⇨ %d ⏏ Retained ⇨ %s",
                        ( Integer ) ( i + 1 ), ( Integer ) weight,
                        state.value(), topic, ( Integer ) qos, ( Boolean ) ret );
        }
    }

    // =====================================================================
    //  Identifier 3 — Text states (SINGLE / BATCH dispatch)
    // =====================================================================

    public void onTextStates( @ObservesAsync TextStatesEvent event )
    {
        if ( !mqtt.isConnected() )
        {
            LOG.debug( "MQTT not connected — text-states event dropped" );
            return;
        }
        textConsumer.accept( event );
    }

    public void onTextStatesBatch( TextStatesEvent event )
    {
        var spec = config().transport().topics().publish().textStates();
        publishJsonBatch( "text-states[3]", event.textStates(),
                          spec.topic(), spec.qos(), spec.retain() );
    }

    public void onTextStatesSingle( TextStatesEvent event )
    {
        var     spec   = config().transport().topics().publish().textStates();
        String  prefix = spec.topic();
        int     qos    = spec.qos();
        boolean ret    = spec.retain();

        int weight = event.textStates().header().weight();
        for ( int i = 0; i < weight; i++ )
        {
            DecodedMessages.TextState state = event.textStates().values().get( i );
            String                    topic = prefix + "/" + state.uuid();
            mqtt.publish( topic, qos, ret,
                          state.value().getBytes( StandardCharsets.UTF_8 ) );
            // DEBUG — 🔡 wrapper marks this as a textual (text-state) payload.
            LOG.debugf( "🚂#%d/%d ⇨ Published ⇨ 🔡%s🔡 ⏏ Topic ⇨ %s ⏏ QoS ⇨ %d ⏏ Retained ⇨ %s",
                        ( Integer ) ( i + 1 ), ( Integer ) weight,
                        state.value(), topic, ( Integer ) qos, ( Boolean ) ret );
        }
    }

    // =====================================================================
    //  Identifier 4 — DayTimer states (single observer: always JSON batch)
    //  SINGLE mode maps to the same batch implementation — the payload
    //  doesn't decompose into per-UUID values (one timer = one UUID +
    //  structured slot list).
    // =====================================================================

    public void onDayTimerStates( @ObservesAsync DayTimerStatesEvent event )
    {
        if ( !mqtt.isConnected() )
        {
            LOG.debug( "MQTT not connected — daytimer-states event dropped" );
            return;
        }
        var spec = config().transport().topics().publish().dayTimerStates();
        publishJsonBatch( "day-timer-states[4]", event.dayTimerStates(),
                          spec.topic(), spec.qos(), spec.retain() );
    }

    // =====================================================================
    //  Identifier 7 — Weather states (single observer: always JSON batch)
    //  Same rationale as DayTimer — one weather station = one UUID,
    //  payload is structured and doesn't split per-UUID.
    // =====================================================================

    public void onWeatherStates( @ObservesAsync WeatherStatesEvent event )
    {
        if ( !mqtt.isConnected() )
        {
            LOG.debug( "MQTT not connected — weather-states event dropped" );
            return;
        }
        var spec = config().transport().topics().publish().weatherStates();
        publishJsonBatch( "weather-states[7]", event.weatherStates(),
                          spec.topic(), spec.qos(), spec.retain() );
    }

    // =====================================================================
    //  Shared JSON-batch helper
    // =====================================================================

    private void publishJsonBatch( String label, Object payload,
                                   String topic, int qos, boolean retain )
    {
        try
        {
            byte[] body = jsonMapper.writeValueAsBytes( payload );
            mqtt.publish( topic, qos, retain, body );
            // DEBUG — BATCH publishes get the generic 🚂 line; the
            // outer JSON envelope already conveys the per-state values,
            // no per-type wrapper needed at this level.
            LOG.debugf( "🚂 %s BATCH published ⇨ Topic ⇨ %s ⏏ QoS ⇨ %d ⏏ Retained ⇨ %s ⏏ %d bytes",
                        label, topic, ( Integer ) qos, ( Boolean ) retain, ( Integer ) body.length );
        }
        catch ( JsonProcessingException e )
        {
            LOG.warnf( e, "%s serialisation failed — dropping event", label );
        }
    }
}
