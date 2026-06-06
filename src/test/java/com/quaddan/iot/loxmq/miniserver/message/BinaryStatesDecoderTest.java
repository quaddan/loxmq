/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.message;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Vector-based tests for {@link BinaryStatesDecoder}: assembles binary frames
 * byte by byte in LITTLE-ENDIAN (the Loxone wire format), feeds them to the
 * decoder, and asserts that the matching async CDI event is fired with the
 * right decoded payload.
 *
 * <h3>Two-frame protocol</h3>
 * The decoder is stateful — every state-update is a header frame (8 bytes,
 * binType {@code 0x03}, identifier, flags, length) followed by a payload
 * frame whose layout depends on the identifier. The {@link #primeHeader}
 * helper feeds the header and the test follows up with the payload.
 *
 * <h3>Async dispatch + AssertJ polling</h3>
 * State events are fired with {@code fireAsync} so subscribers don't block
 * the WS reader thread in production. The {@link #awaitEvents} helper polls
 * the recorder up to 2 s — generous for CI machines, fast on a quiet dev box
 * (< 5 ms). AssertJ's {@code .untilAsserted} would also work but a manual
 * poll keeps the dependency surface minimal.
 *
 * <h3>States to decode</h3>
 * The {@link AllStatesProfile} overrides {@code states-to-decode} to
 * {@code 2,3,4,7} so the weather processor is also exercised — the default
 * production list is {@code 2,3,4}.
 */
@QuarkusTest
@TestProfile( BinaryStatesDecoderTest.AllStatesProfile.class )
@DisplayName( "BinaryStatesDecoder — vector-based decoding" )
class BinaryStatesDecoderTest
{
    public static class AllStatesProfile implements QuarkusTestProfile
    {
        @Override
        public Map< String, String > getConfigOverrides()
        {
            return Map.of( "loxone.miniserver.states-to-decode", "2,3,4,7" );
        }
    }

    // ---------------------------------------------------------------------
    // Recorders — one per async event type. Quarkus / ArC discovers
    // @ApplicationScoped beans declared inside the test class automatically.
    // ---------------------------------------------------------------------

    static abstract class EventRecorder< E >
    {
        private final AtomicInteger count    = new AtomicInteger();
        private final List< E >     captured = new CopyOnWriteArrayList<>();

        protected void record( E event )
        {
            captured.add( event );
            count.incrementAndGet();
        }

        public int count() { return count.get(); }

        public List< E > events() { return List.copyOf( captured ); }

        public E lastEvent() { return captured.isEmpty() ? null : captured.getLast(); }

        public void clear()
        {
            captured.clear();
            count.set( 0 );
        }
    }

    @ApplicationScoped
    public static class ValueRecorder extends EventRecorder< ValueStatesEvent >
    {
        public void on( @ObservesAsync ValueStatesEvent event ) { record( event ); }
    }

    @ApplicationScoped
    public static class TextRecorder extends EventRecorder< TextStatesEvent >
    {
        public void on( @ObservesAsync TextStatesEvent event ) { record( event ); }
    }

    @ApplicationScoped
    public static class DayTimerRecorder extends EventRecorder< DayTimerStatesEvent >
    {
        public void on( @ObservesAsync DayTimerStatesEvent event ) { record( event ); }
    }

    @ApplicationScoped
    public static class WeatherRecorder extends EventRecorder< WeatherStatesEvent >
    {
        public void on( @ObservesAsync WeatherStatesEvent event ) { record( event ); }
    }

    /**
     * Synchronous recorder for {@link DecodingFailureEvent}. Synchronous
     * because the decoder fires this with the sync {@code .fire()} API, so
     * the test can assert on it immediately after the triggering call (no
     * polling needed for the failure path).
     */
    @ApplicationScoped
    public static class FailureRecorder
    {
        private final List< DecodingFailureEvent > events = new CopyOnWriteArrayList<>();

        public void on( @Observes DecodingFailureEvent event ) { events.add( event ); }

        public int size() { return events.size(); }

        public DecodingFailureEvent last() { return events.isEmpty() ? null : events.getLast(); }

        public void clear() { events.clear(); }
    }

    // ---------------------------------------------------------------------
    // Test wiring
    // ---------------------------------------------------------------------

    @Inject
    BinaryStatesDecoder decoder;
    @Inject
    ValueRecorder       valueRecorder;
    @Inject
    TextRecorder        textRecorder;
    @Inject
    DayTimerRecorder    dayTimerRecorder;
    @Inject
    WeatherRecorder     weatherRecorder;
    @Inject
    FailureRecorder     failureRecorder;

    @BeforeEach
    void resetAll()
    {
        valueRecorder.clear();
        textRecorder.clear();
        dayTimerRecorder.clear();
        weatherRecorder.clear();
        failureRecorder.clear();
    }

    // ---------------------------------------------------------------------
    // VALUE states (identifier 2) — 24 bytes per entry
    // ---------------------------------------------------------------------

    @Test
    @DisplayName( "value-states frame with 2 entries ⇨ ValueStatesEvent with 2 values" )
    @Timeout( value = 5, unit = TimeUnit.SECONDS )
    void valueStates_twoEntries_firesEventWithBothValues() throws Exception
    {
        // Header: identifier=2 (VALUE_STATES), length=48 (2 * 24)
        primeHeader( MessageType.EVENT_TABLE_OF_VALUE_STATES.getValue(), 48 );

        ByteBuffer payload = ByteBuffer.allocate( 48 ).order( ByteOrder.LITTLE_ENDIAN );
        writeUuid( payload, 0x11111111, ( short ) 0x2222, ( short ) 0x3333, new byte[]{ 1, 2, 3, 4, 5, 6, 7, 8 } );
        payload.putDouble( 42.5 );
        writeUuid( payload, 0xAAAAAAAA, ( short ) 0xBBBB, ( short ) 0xCCCC, new byte[]{ 9, 8, 7, 6, 5, 4, 3, 2 } );
        payload.putDouble( -17.25 );
        payload.flip();

        decoder.decode( payload );

        awaitEvents( valueRecorder, 1 );
        assertThat( valueRecorder.count() ).as( "exactly one value-states event must fire" ).isEqualTo( 1 );

        var states = valueRecorder.lastEvent().valueStates();
        assertThat( states.values() ).hasSize( 2 );
        assertThat( states.values().get( 0 ).uuid() ).isEqualTo( "11111111-2222-3333-0102030405060708" );
        assertThat( states.values().get( 0 ).value() ).isEqualTo( 42.5 );
        assertThat( states.values().get( 1 ).uuid() ).isEqualTo( "aaaaaaaa-bbbb-cccc-0908070605040302" );
        assertThat( states.values().get( 1 ).value() ).isEqualTo( -17.25 );

        assertThat( failureRecorder.size() ).as( "no decoding failure on a valid frame" ).isZero();
    }

    @Test
    @DisplayName( "value-states size not divisible by 24 ⇨ DecodingFailureEvent, no ValueStatesEvent" )
    void valueStates_malformedSize_firesFailureAndNoEvent() throws Exception
    {
        primeHeader( MessageType.EVENT_TABLE_OF_VALUE_STATES.getValue(), 30 );

        ByteBuffer payload = ByteBuffer.allocate( 30 ).order( ByteOrder.LITTLE_ENDIAN );
        for ( int i = 0; i < 30; i++ ) { payload.put( ( byte ) 0 ); }
        payload.flip();

        decoder.decode( payload );

        // DecodingFailureEvent is sync — check immediately.
        assertThat( failureRecorder.size() ).isEqualTo( 1 );
        assertThat( failureRecorder.last().reason() ).contains( "divisible by 24" );
        // Give the async dispatcher a moment to fail to deliver anything.
        Thread.sleep( 100 );
        assertThat( valueRecorder.count() ).isZero();
    }

    // ---------------------------------------------------------------------
    // TEXT states (identifier 3) — variable size + padding mod 4
    // ---------------------------------------------------------------------

    @Test
    @DisplayName( "text-states frame with one entry ⇨ TextStatesEvent with the right uuid and text" )
    @Timeout( value = 5, unit = TimeUnit.SECONDS )
    void textStates_oneEntry_firesEventWithText() throws Exception
    {
        // Per entry: 16 byte uuid + 16 byte uuidIcon (skipped) + 4 byte textLength
        //          + textLength bytes + zero-padding to next 4-byte boundary.
        // "hello" = 5 bytes ⇒ padding 3 ⇒ entry size 16+16+4+5+3 = 44.
        String text      = "hello";
        int    paddedLen = ( text.length() % 4 == 0 ) ? text.length() : text.length() + ( 4 - text.length() % 4 );
        int    entrySize = 16 + 16 + 4 + paddedLen;

        primeHeader( MessageType.EVENT_TABLE_OF_TEXT_STATES.getValue(), entrySize );

        ByteBuffer payload = ByteBuffer.allocate( entrySize ).order( ByteOrder.LITTLE_ENDIAN );
        writeUuid( payload, 0xDEADBEEF, ( short ) 0xCAFE, ( short ) 0xBABE, new byte[]{ 1, 2, 3, 4, 5, 6, 7, 8 } );
        for ( int i = 0; i < 16; i++ ) { payload.put( ( byte ) 0 ); }      // uuidIcon — skipped
        payload.putInt( text.length() );
        payload.put( text.getBytes() );
        for ( int i = text.length(); i < paddedLen; i++ ) { payload.put( ( byte ) 0 ); }
        payload.flip();

        decoder.decode( payload );

        awaitEvents( textRecorder, 1 );
        var states = textRecorder.lastEvent().textStates();
        assertThat( states.values() ).hasSize( 1 );
        assertThat( states.values().get( 0 ).uuid() ).isEqualTo( "deadbeef-cafe-babe-0102030405060708" );
        assertThat( states.values().get( 0 ).value() ).isEqualTo( "hello" );
    }

    @Test
    @DisplayName( "text-states with 4-byte-aligned text ⇨ no padding branch exercised" )
    @Timeout( value = 5, unit = TimeUnit.SECONDS )
    void textStates_alignedText_noPaddingPath() throws Exception
    {
        // "abcd" = 4 bytes ⇒ padding 0 ⇒ second branch of the padding compute
        // (nextPosition = message.position()).
        String text      = "abcd";
        int    entrySize = 16 + 16 + 4 + 4;

        primeHeader( MessageType.EVENT_TABLE_OF_TEXT_STATES.getValue(), entrySize );

        ByteBuffer payload = ByteBuffer.allocate( entrySize ).order( ByteOrder.LITTLE_ENDIAN );
        writeUuid( payload, 0x01020304, ( short ) 0x0506, ( short ) 0x0708, new byte[]{ 9, 10, 11, 12, 13, 14, 15, 16 } );
        for ( int i = 0; i < 16; i++ ) { payload.put( ( byte ) 0 ); }
        payload.putInt( 4 );
        payload.put( text.getBytes() );
        payload.flip();

        decoder.decode( payload );

        awaitEvents( textRecorder, 1 );
        assertThat( textRecorder.lastEvent().textStates().values().get( 0 ).value() ).isEqualTo( "abcd" );
    }

    // ---------------------------------------------------------------------
    // DAYTIMER states (identifier 4)
    // ---------------------------------------------------------------------

    @Test
    @DisplayName( "daytimer-states frame with one slot ⇨ DayTimerStatesEvent with the right slot" )
    @Timeout( value = 5, unit = TimeUnit.SECONDS )
    void dayTimerStates_oneSlot_firesEventWithSlot() throws Exception
    {
        // Layout: 16 uuid + 8 defaultValue + 4 numberOfDayTimerState
        //       + N × (4 mode + 4 from + 4 to + 4 needActivate + 8 value)
        int entrySize = 16 + 8 + 4 + 24;
        primeHeader( MessageType.EVENT_TABLE_OF_DAYTIMER_STATES.getValue(), entrySize );

        ByteBuffer payload = ByteBuffer.allocate( entrySize ).order( ByteOrder.LITTLE_ENDIAN );
        writeUuid( payload, 0x11223344, ( short ) 0x5566, ( short ) 0x7788, new byte[]{ 1, 2, 3, 4, 5, 6, 7, 8 } );
        payload.putDouble( 19.0 );      // defaultValue
        payload.putInt( 1 );             // numberOfDayTimerState
        payload.putInt( 5 );             // mode
        payload.putInt( 360 );           // fromTime — 06:00
        payload.putInt( 1320 );          // toTime — 22:00
        payload.putInt( 1 );             // needActivate true
        payload.putDouble( 21.5 );       // value
        payload.flip();

        decoder.decode( payload );

        awaitEvents( dayTimerRecorder, 1 );
        var states = dayTimerRecorder.lastEvent().dayTimerStates();
        assertThat( states.uuid() ).isEqualTo( "11223344-5566-7788-0102030405060708" );
        assertThat( states.defaultValue() ).isEqualTo( 19.0 );
        assertThat( states.values() ).hasSize( 1 );
        var slot = states.values().get( 0 );
        assertThat( slot.mode() ).isEqualTo( 5 );
        assertThat( slot.fromTimeMinutesSinceMidnight() ).isEqualTo( 360 );
        assertThat( slot.toTimeMinutesSinceMidnight() ).isEqualTo( 1320 );
        assertThat( slot.needActivate() ).isTrue();
        assertThat( slot.value() ).isEqualTo( 21.5 );
    }

    @Test
    @DisplayName( "daytimer-states with needActivate=0 ⇨ slot.needActivate=false" )
    @Timeout( value = 5, unit = TimeUnit.SECONDS )
    void dayTimerStates_needActivateZero_decodedAsFalse() throws Exception
    {
        int entrySize = 16 + 8 + 4 + 24;
        primeHeader( MessageType.EVENT_TABLE_OF_DAYTIMER_STATES.getValue(), entrySize );

        ByteBuffer payload = ByteBuffer.allocate( entrySize ).order( ByteOrder.LITTLE_ENDIAN );
        writeUuid( payload, 0, ( short ) 0, ( short ) 0, new byte[ 8 ] );
        payload.putDouble( 0.0 );
        payload.putInt( 1 );
        payload.putInt( 0 );
        payload.putInt( 0 );
        payload.putInt( 0 );
        payload.putInt( 0 );                  // needActivate = 0 ⇒ false
        payload.putDouble( 0.0 );
        payload.flip();

        decoder.decode( payload );

        awaitEvents( dayTimerRecorder, 1 );
        assertThat( dayTimerRecorder.lastEvent().dayTimerStates().values().get( 0 ).needActivate() ).isFalse();
    }

    // ---------------------------------------------------------------------
    // WEATHER states (identifier 7)
    // ---------------------------------------------------------------------

    @Test
    @DisplayName( "weather-states frame with one entry ⇨ WeatherStatesEvent with full fields" )
    @Timeout( value = 5, unit = TimeUnit.SECONDS )
    void weatherStates_oneEntry_firesEventWithEntry() throws Exception
    {
        // Layout: 16 uuid + 4 lastUpdate + 4 numberOfWeatherState
        //       + N × (5*int + 6*double) = 5*4 + 6*8 = 68
        int entrySize = 16 + 4 + 4 + 68;
        primeHeader( MessageType.EVENT_TABLE_OF_WEATHER_STATES.getValue(), entrySize );

        ByteBuffer payload = ByteBuffer.allocate( entrySize ).order( ByteOrder.LITTLE_ENDIAN );
        writeUuid( payload, 0xC0FFEE00, ( short ) 0x1234, ( short ) 0x5678, new byte[]{ 11, 12, 13, 14, 15, 16, 17, 18 } );
        payload.putInt( 1_700_000_000 );      // lastUpdate
        payload.putInt( 1 );                   // numberOfWeatherState
        payload.putInt( 1_700_000_100 );      // timeStamp
        payload.putInt( 7 );                   // weatherType
        payload.putInt( 180 );                 // windDirection
        payload.putInt( 450 );                 // solarRadiation
        payload.putInt( 60 );                  // relativeHumidity
        payload.putDouble( 22.5 );             // temperature
        payload.putDouble( 21.8 );             // perceivedTemperature
        payload.putDouble( 14.0 );             // dewPoint
        payload.putDouble( 0.0 );              // precipitation
        payload.putDouble( 3.5 );              // windSpeed
        payload.putDouble( 1013.25 );          // barometricPressure
        payload.flip();

        decoder.decode( payload );

        awaitEvents( weatherRecorder, 1 );
        var states = weatherRecorder.lastEvent().weatherStates();
        assertThat( states.uuid() ).isEqualTo( "c0ffee00-1234-5678-0b0c0d0e0f101112" );
        assertThat( states.lastUpdate() ).isEqualTo( 1_700_000_000 );
        assertThat( states.values() ).hasSize( 1 );
        var entry = states.values().get( 0 );
        assertThat( entry.timeStamp() ).isEqualTo( 1_700_000_100 );
        assertThat( entry.weatherType() ).isEqualTo( 7 );
        assertThat( entry.windDirection() ).isEqualTo( 180 );
        assertThat( entry.solarRadiation() ).isEqualTo( 450 );
        assertThat( entry.relativeHumidity() ).isEqualTo( 60 );
        assertThat( entry.temperature() ).isEqualTo( 22.5 );
        assertThat( entry.perceivedTemperature() ).isEqualTo( 21.8 );
        assertThat( entry.dewPoint() ).isEqualTo( 14.0 );
        assertThat( entry.precipitation() ).isEqualTo( 0.0 );
        assertThat( entry.windSpeed() ).isEqualTo( 3.5 );
        assertThat( entry.barometricPressure() ).isEqualTo( 1013.25 );
    }

    // ---------------------------------------------------------------------
    // Header dispatching
    // ---------------------------------------------------------------------

    @Test
    @DisplayName( "keep-alive header alone ⇨ no decoded event fired" )
    void keepAliveHeader_doesNotFireAnyStateEvent() throws Exception
    {
        // Just the header — no payload. binType=0x03, type=6 (KEEP_ALIVE_RESPONSE), length=0.
        decoder.decode( makeHeader( MessageType.KEEP_ALIVE_RESPONSE.getValue(), 0 ) );

        Thread.sleep( 100 );    // give async dispatch a chance — must remain empty.
        assertThat( valueRecorder.count() ).isZero();
        assertThat( textRecorder.count() ).isZero();
        assertThat( dayTimerRecorder.count() ).isZero();
        assertThat( weatherRecorder.count() ).isZero();
        assertThat( failureRecorder.size() ).isZero();
    }

    @Test
    @DisplayName( "unknown type byte (255) ⇨ MessageType.UNKNOWN, payload silently dropped" )
    void unknownTypeByte_payloadSilentlyDropped() throws Exception
    {
        // A future firmware could ship an identifier the binding doesn't recognise.
        // The header reader maps it to UNKNOWN and statesToDecode rejects it. No
        // exception fires — the connection stays up.
        decoder.decode( makeHeader( 0xFF, 16 ) );

        ByteBuffer body = ByteBuffer.allocate( 16 ).order( ByteOrder.LITTLE_ENDIAN );
        for ( int i = 0; i < 16; i++ ) { body.put( ( byte ) 0xAA ); }
        body.flip();
        decoder.decode( body );

        Thread.sleep( 100 );
        assertThat( valueRecorder.count() ).isZero();
        assertThat( textRecorder.count() ).isZero();
        assertThat( dayTimerRecorder.count() ).isZero();
        assertThat( weatherRecorder.count() ).isZero();
        assertThat( failureRecorder.size() ).as( "unknown identifier must NOT fire a failure event — connection stays up" ).isZero();
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /** Build + feed an 8-byte header frame to prime the decoder. */
    private void primeHeader( int identifier, int payloadLength )
    {
        decoder.decode( makeHeader( identifier, payloadLength ) );
    }

    /**
     * Build an 8-byte header in LITTLE_ENDIAN:
     * <pre>
     *   byte 0:    0x03 (DEFAULT_BIN_TYPE)
     *   byte 1:    identifier (cast from int)
     *   byte 2:    flags (0)
     *   byte 3:    reserved (0)
     *   bytes 4-7: payload length, little-endian
     * </pre>
     */
    private static ByteBuffer makeHeader( int identifier, int payloadLength )
    {
        ByteBuffer buf = ByteBuffer.allocate( 8 ).order( ByteOrder.LITTLE_ENDIAN );
        buf.put( ( byte ) 0x03 );
        buf.put( ( byte ) identifier );
        buf.put( ( byte ) 0 );    // flags
        buf.put( ( byte ) 0 );    // reserved
        buf.putInt( payloadLength );
        buf.flip();
        return buf;
    }

    /**
     * Write a UUID in the Loxone wire format: 4-byte int + 2-byte short
     * + 2-byte short + 8 individual bytes. In little-endian the int
     * {@code 0xDEADBEEF} round-trips to {@code 0xDEADBEEF} on read, so the
     * UUID string surfaces with the same hex digits.
     */
    private static void writeUuid( ByteBuffer buf, int data1, short data2, short data3, byte[] data4 )
    {
        if ( data4.length != 8 )
        {
            throw new AssertionError( "UUID data4 must be 8 bytes; got " + data4.length );
        }
        buf.putInt( data1 );
        buf.putShort( data2 );
        buf.putShort( data3 );
        buf.put( data4 );
    }

    /**
     * Poll the recorder until it reaches {@code expectedCount} events, up to
     * a 2-second deadline. The async dispatcher usually delivers in under a
     * millisecond on a quiet machine — the deadline only kicks in on
     * heavily-loaded CI runners.
     */
    private static void awaitEvents( EventRecorder< ? > recorder, int expectedCount )
            throws InterruptedException
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos( 2 );
        while ( System.nanoTime() < deadline )
        {
            if ( recorder.count() >= expectedCount )
            {
                return;
            }
            Thread.sleep( 5 );
        }
    }
}
