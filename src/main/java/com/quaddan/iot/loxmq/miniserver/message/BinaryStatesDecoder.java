/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.message;

import com.quaddan.iot.loxmq.config.LoxoneConfig;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Stateful decoder for the Loxone binary protocol. Consumes the alternating
 * "header / payload" frame pairs emitted by the Miniserver WebSocket and
 * fires typed CDI events for each decoded event-table.
 *
 * <h3>Two-frame protocol</h3>
 * Every state-update broadcast arrives as two consecutive WS binary frames:
 * <ol>
 *   <li>An 8-byte {@link MessageHeader} starting with {@code 0x03} — describes
 *       the identifier and payload length of the upcoming event-table.</li>
 *   <li>The payload itself — {@code N} bytes where {@code N} is the header's
 *       {@code nextMessageLength}. Layout depends on the identifier
 *       (see {@code processXxxStates}).</li>
 * </ol>
 * The decoder remembers the last seen header in {@link #currentHeader}; the
 * next call to {@link #decode(ByteBuffer, long)} that doesn't look like a
 * header is interpreted as the payload for that header. This matches the
 * Loxone wire protocol exactly — we don't multiplex multiple in-flight types
 * because the Miniserver never interleaves.
 *
 * <h3>Filtering</h3>
 * Payloads whose identifier isn't in
 * {@code loxone.miniserver.states-to-decode} are silently dropped. This is
 * how the operator opts out of Weather (identifier 7) without subscribing.
 *
 * <h3>Error handling</h3>
 * Malformed frames (size not a multiple of 24 for value-states, truncated
 * UUIDs, etc.) fire a {@link DecodingFailureEvent} and abort the current
 * payload. The session itself stays up — the next frame pair has a fresh
 * chance.
 *
 * <h3>Threading</h3>
 * The decoder is called from the WebSocket reader thread (one per session).
 * Quarkus / ArC guarantees the {@code @ApplicationScoped} singleton, so the
 * {@code currentHeader} field is mutated by exactly one thread. The CDI
 * events are fired with {@code fireAsync} so the WS reader thread doesn't
 * block on subscribers (the MQTT publisher can take milliseconds
 * to flush a batch).
 */
@ApplicationScoped
public class BinaryStatesDecoder
{
    private static final Logger LOG     = Logger.getLogger( BinaryStatesDecoder.class );
    private static final String VERSION = "1.0";

    /** Instance field (not static — ArC's ApplicationScoped already
     *  guarantees singleton lifetime, and a static would leak across hot
     *  reloads in dev mode). */
    private MessageHeader currentHeader;

    private final HashMap< Integer, BiConsumer< ByteBuffer, Long > > availableMessageTypesFunction = HashMap.newHashMap( 4 );

    /**
     * Identifiers the operator opted into via
     * {@code loxone.miniserver.states-to-decode}, hoisted once from config into
     * an immutable {@link Set} in {@link #init()}. Lives on the hot path: a
     * payload frame arrives for every state broadcast, so we trade the per-frame
     * config-proxy resolution + linear {@code List<Integer>} scan for an O(1)
     * {@code Set} lookup. Quarkus {@code @ConfigMapping} is runtime-immutable
     * (a dev-mode config change restarts the app and re-runs {@code @PostConstruct}),
     * so this snapshot can never drift from config. The identifiers (2/3/4/7)
     * fall inside the {@link Integer} valueOf cache, so the {@code contains(int)}
     * autoboxing at the call site allocates nothing. Defaults to an empty set so
     * a frame arriving before {@link #init()} is simply dropped, never NPEs. */
    private Set< Integer > statesToDecode = Set.of();

    @Inject
    LoxoneConfig                              config;
    @Inject
    Event< ValueStatesEvent >                 valueStatesEvent;
    @Inject
    Event< TextStatesEvent >                  textStatesEvent;
    @Inject
    Event< DayTimerStatesEvent >              dayTimerStatesEvent;
    @Inject
    Event< WeatherStatesEvent >               weatherStatesEvent;
    @Inject
    Event< DecodingFailureEvent >             decodingFailureEvent;
    @Inject
    Event< MiniserverOutOfServiceEvent >      outOfServiceEvent;
    @Inject
    Event< MiniserverKeepAliveResponseEvent > keepAliveResponseEvent;

    @PostConstruct
    public void init()
    {
        availableMessageTypesFunction.put( MessageType.EVENT_TABLE_OF_VALUE_STATES.getValue(), this::processValueStates );
        availableMessageTypesFunction.put( MessageType.EVENT_TABLE_OF_TEXT_STATES.getValue(), this::processTextStates );
        availableMessageTypesFunction.put( MessageType.EVENT_TABLE_OF_DAYTIMER_STATES.getValue(), this::processDayTimerStates );
        availableMessageTypesFunction.put( MessageType.EVENT_TABLE_OF_WEATHER_STATES.getValue(), this::processWeatherStates );

        // Snapshot the decode whitelist once — see the statesToDecode field doc
        // for why caching the immutable @ConfigMapping value here is correct.
        statesToDecode = Set.copyOf( config.miniserver().statesToDecode() );
    }

    /**
     * Decode one WebSocket binary frame. Convenience overload that uses the
     * current wall-clock as the timestamp.
     */
    public void decode( ByteBuffer message )
    {
        decode( message, System.currentTimeMillis() );
    }

    /**
     * Decode one WebSocket binary frame.
     *
     * @param message   the raw frame bytes. The decoder forces little-endian
     *                  byte order, so the caller can pass either default
     *                  big-endian or little-endian buffers — what matters is
     *                  that {@code position()} is at the start and
     *                  {@code limit()} at the end of the frame.
     * @param timestamp wall-clock ms attached to the {@link DecodedMessages.Header}.
     *                  Exposed for testability — production code calls the
     *                  no-arg overload.
     */
    public void decode( ByteBuffer message, long timestamp )
    {
        message.order( ByteOrder.LITTLE_ENDIAN );

        if ( LOG.isTraceEnabled() )
        {
            LOG.tracef( "🔒 Received binary frame to decode ⏏ size=%d ⏏ buffer=%s", ( Integer ) message.capacity(), message );
        }

        // Distinguish header (8 bytes starting with 0x03) from payload by
        // peeking — never call get() blindly so we don't poison the position
        // when we end up routing to a payload processor.
        if ( ( MessageHeader.getMessageHeaderLength() == message.limit() )
             && ( message.get( 0 ) == ( byte ) MessageHeader.getDefaultBinType() ) )
        {
            currentHeader = new MessageHeader( message );
            LOG.tracef( "🔒 ⇨ %s", currentHeader );

            if ( currentHeader.isKeepaliveResponse() )
            {
                LOG.trace( "🔓 ⇨ KEEP ALIVE RESPONSE message received." );
                // Fire async so the WS reader thread isn't blocked by the
                // observer (KeepAliveScheduler computes RTT + records on
                // an executor thread). Sync would also work — the observer
                // is fast — but async is the project convention for
                // post-decode events (see ValueStatesEvent etc.).
                keepAliveResponseEvent.fireAsync(
                        new MiniserverKeepAliveResponseEvent(
                                java.time.Instant.ofEpochMilli( timestamp ) ) );
            }
            if ( currentHeader.isOutOfServiceMessage() )
            {
                // Miniserver about to reboot — connection will be closed with
                // RFC code 1000 right after. Fire an async event so the MQTT
                // publisher can notify downstream consumers; the
                // session itself is torn down by the WS close handler.
                LOG.info( "🔓 ⇨ OUT OF SERVICE message received." );
                outOfServiceEvent.fireAsync( new MiniserverOutOfServiceEvent( timestamp ) );
            }
            return;
        }

        if ( currentHeader == null )
        {
            LOG.warnf( "Payload frame received without a preceding header (size=%d) — dropped",
                       ( Integer ) message.limit() );
            return;
        }

        int identifier = currentHeader.getNextMessageType().getValue();
        if ( !statesToDecode.contains( identifier ) )
        {
            LOG.tracef( "Payload for identifier %d (%s) filtered out by states-to-decode",
                        ( Integer ) identifier, currentHeader.getNextMessageType() );
            return;
        }

        BiConsumer< ByteBuffer, Long > processor = availableMessageTypesFunction.get( identifier );
        if ( processor == null )
        {
            LOG.warnf( "No processor for identifier %d (%s) — frame dropped",
                       ( Integer ) identifier, currentHeader.getNextMessageType() );
            return;
        }
        processor.accept( message, timestamp );
    }

    // ---------------------------------------------------------------------
    //  Identifier 2 — Value-States
    //  Wire layout per entry (24 bytes):
    //    16 bytes UUID + 8 bytes double value (little-endian)
    // ---------------------------------------------------------------------
    void processValueStates( final ByteBuffer message, final long timestamp )
    {
        LOG.trace( "EVENT_TABLE_OF_VALUE_STATES message received." );

        final int capacity         = message.limit();
        final int VALUE_STATE_SIZE = 24;

        if ( ( capacity % VALUE_STATE_SIZE ) != 0 )
        {
            String reason = "EVENT_TABLE_OF_VALUE_STATES size must be divisible by 24 because that is the size in bytes of one VALUE_EVENT. But current size is NOT! -> " + capacity;
            LOG.warn( reason );
            decodingFailureEvent.fire( new DecodingFailureEvent( reason, null ) );
            return;
        }

        final int                          numberOfValueState = capacity / VALUE_STATE_SIZE;
        List< DecodedMessages.ValueState > values             = new ArrayList<>( numberOfValueState );

        for ( int index = 0, position = 0; index < numberOfValueState; index++, position += VALUE_STATE_SIZE )
        {
            try
            {
                values.add( new DecodedMessages.ValueState( MessageHelper.getUUID( message, position ),
                                                            message.getDouble() ) );
            }
            catch ( BinaryStatesDecodingException e )
            {
                LOG.warnf( e, "Could not decode value-state at position %d", ( Integer ) position );
                decodingFailureEvent.fire( new DecodingFailureEvent( e.getMessage(), e ) );
                return;
            }
        }

        valueStatesEvent.fireAsync( new ValueStatesEvent(
                new DecodedMessages.ValueStates(
                        new DecodedMessages.Header( VERSION, timestamp, config.miniserver().app().id(),
                                                    MessageType.EVENT_TABLE_OF_VALUE_STATES.getValue(),
                                                    numberOfValueState ),
                        values ) ) );
    }

    // ---------------------------------------------------------------------
    //  Identifier 3 — Text-States
    //  Wire layout per entry (variable):
    //    16 bytes UUID + 16 bytes UUID-icon (skipped) + 4 bytes textLength
    //    + textLength UTF-8 bytes + zero-padding to next multiple of 4.
    // ---------------------------------------------------------------------
    void processTextStates( final ByteBuffer message, final long timestamp )
    {
        LOG.tracef( "EVENT_TABLE_OF_TEXT_STATES message received for MessageHeader %d.",
                    ( Integer ) MessageHeader.getDefaultBinType() );

        int                               nextPosition = 0;
        String                            uuid;
        String                            text;
        int                               textLength;
        int                               padding;
        int                               weight       = 0;
        List< DecodedMessages.TextState > values       = new ArrayList<>( 30 );

        while ( nextPosition < message.limit() )
        {
            message.position( nextPosition );

            try
            {
                uuid = MessageHelper.getUUID( message );
                LOG.tracef( "Text message UUID: %s", uuid );
            }
            catch ( BinaryStatesDecodingException e )
            {
                LOG.warnf( e, "Could not decode text-state UUID at position %d", ( Integer ) nextPosition );
                decodingFailureEvent.fire( new DecodingFailureEvent( e.getMessage(), e ) );
                return;
            }

            // Skip 16 bytes of uuidIcon — not needed downstream.
            LOG.tracef( "Text message UUID %s position before uuidIcon jump: %d", uuid, ( Integer ) message.position() );
            message.position( message.position() + 16 );
            LOG.tracef( "Text message UUID %s position after uuidIcon jump : %d", uuid, ( Integer ) message.position() );

            textLength = message.getInt();
            LOG.tracef( "Text message UUID %s text length: %d bytes.", uuid, ( Integer ) textLength );

            try
            {
                text = new String( MessageHelper.getBytes( message, textLength ), StandardCharsets.UTF_8 );
            }
            catch ( BinaryStatesDecodingException e )
            {
                LOG.warnf( e, "Could not decode text-state body of length %d", ( Integer ) textLength );
                decodingFailureEvent.fire( new DecodingFailureEvent( e.getMessage(), e ) );
                return;
            }

            values.add( new DecodedMessages.TextState( uuid, text ) );
            weight++;
            padding      = textLength % 4;
            nextPosition = padding > 0
                           ? ( message.position() + 4 ) - padding
                           : message.position();
        }

        textStatesEvent.fireAsync( new TextStatesEvent(
                new DecodedMessages.TextStates(
                        new DecodedMessages.Header( VERSION, timestamp, config.miniserver().app().id(),
                                                    MessageType.EVENT_TABLE_OF_TEXT_STATES.getValue(),
                                                    weight ),
                        values ) ) );
    }

    // ---------------------------------------------------------------------
    //  Identifier 4 — DayTimer-States
    //  Wire layout:
    //    16 bytes UUID + 8 bytes defaultValue (double)
    //    + 4 bytes numberOfDayTimerState
    //    + N × ( 4 mode + 4 fromTime + 4 toTime + 4 needActivate + 8 value )
    // ---------------------------------------------------------------------
    void processDayTimerStates( final ByteBuffer message, final long timestamp )
    {
        LOG.trace( "EVENT_TABLE_OF_DAYTIMER_STATES message received." );

        String uuid;
        try
        {
            uuid = MessageHelper.getUUID( message );
        }
        catch ( BinaryStatesDecodingException e )
        {
            LOG.warn( "Could not decode daytimer-states UUID", e );
            decodingFailureEvent.fire( new DecodingFailureEvent( e.getMessage(), e ) );
            return;
        }

        final double defaultValue          = message.getDouble();
        final int    numberOfDayTimerState = message.getInt();

        int                                   mode, fromTimeMinutesSinceMidnight, toTimeMinutesSinceMidnight;
        boolean                               needActivate;
        double                                value;
        List< DecodedMessages.DayTimerState > values = new ArrayList<>( 30 );

        for ( int index = 0; index < numberOfDayTimerState; index++ )
        {
            mode                         = message.getInt();
            fromTimeMinutesSinceMidnight = message.getInt();
            toTimeMinutesSinceMidnight   = message.getInt();
            needActivate                 = message.getInt() != 0;
            value                        = message.getDouble();

            values.add( new DecodedMessages.DayTimerState( mode,
                                                           fromTimeMinutesSinceMidnight,
                                                           toTimeMinutesSinceMidnight,
                                                           needActivate,
                                                           value ) );
        }

        dayTimerStatesEvent.fireAsync( new DayTimerStatesEvent(
                new DecodedMessages.DayTimerStates(
                        new DecodedMessages.Header( VERSION, timestamp, config.miniserver().app().id(),
                                                    MessageType.EVENT_TABLE_OF_DAYTIMER_STATES.getValue(),
                                                    numberOfDayTimerState ),
                        uuid,
                        defaultValue,
                        numberOfDayTimerState,
                        values ) ) );
    }

    // ---------------------------------------------------------------------
    //  Identifier 7 — Weather-States
    //  Wire layout:
    //    16 bytes UUID + 4 bytes lastUpdate + 4 bytes numberOfWeatherState
    //    + N × ( 5 × int + 6 × double )
    // ---------------------------------------------------------------------
    void processWeatherStates( final ByteBuffer message, final long timestamp )
    {
        LOG.trace( "EVENT_TABLE_OF_WEATHER_STATES message received." );

        String uuid;
        try
        {
            uuid = MessageHelper.getUUID( message );
        }
        catch ( BinaryStatesDecodingException e )
        {
            LOG.warn( "Could not decode weather-states UUID", e );
            decodingFailureEvent.fire( new DecodingFailureEvent( e.getMessage(), e ) );
            return;
        }

        final int lastUpdate           = message.getInt();
        final int numberOfWeatherState = message.getInt();

        int                                  timeStamp, weatherType, windDirection, solarRadiation, relativeHumidity;
        double                               temperature, perceivedTemperature, dewPoint, precipitation, windSpeed, barometricPressure;
        List< DecodedMessages.WeatherState > values = new ArrayList<>( 30 );

        for ( int index = 0; index < numberOfWeatherState; index++ )
        {
            timeStamp            = message.getInt();
            weatherType          = message.getInt();
            windDirection        = message.getInt();
            solarRadiation       = message.getInt();
            relativeHumidity     = message.getInt();
            temperature          = message.getDouble();
            perceivedTemperature = message.getDouble();
            dewPoint             = message.getDouble();
            precipitation        = message.getDouble();
            windSpeed            = message.getDouble();
            barometricPressure   = message.getDouble();

            values.add( new DecodedMessages.WeatherState( timeStamp, weatherType, windDirection,
                                                          solarRadiation, relativeHumidity,
                                                          temperature, perceivedTemperature,
                                                          dewPoint, precipitation, windSpeed,
                                                          barometricPressure ) );
        }

        weatherStatesEvent.fireAsync( new WeatherStatesEvent(
                new DecodedMessages.WeatherStates(
                        new DecodedMessages.Header( VERSION, timestamp, config.miniserver().app().id(),
                                                    MessageType.EVENT_TABLE_OF_WEATHER_STATES.getValue(),
                                                    numberOfWeatherState ),
                        uuid,
                        lastUpdate,
                        numberOfWeatherState,
                        values ) ) );
    }

    @Override
    public String toString()
    {
        return "BinaryStatesDecoder:[version ⇨ %s]".formatted( VERSION );
    }
}
