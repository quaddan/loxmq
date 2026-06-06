/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.message;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure JUnit tests for {@link MessageHeader} (no CDI / Quarkus boot — the
 * header parser is a value object, not a bean). Pins the binary protocol
 * behaviour of the Loxone message header against the V17.0 spec.
 *
 * <p>{@code MessageHeader} and {@code MessageType} are package-private, so
 * the test lives in the same package.
 */
@DisplayName( "MessageHeader" )
class MessageHeaderTest
{
    private static final byte BIN_TYPE = ( byte ) 0x03;

    // ---------------------------------------------------------------------
    // Happy path — well-formed headers
    // ---------------------------------------------------------------------

    @ParameterizedTest( name = "type byte {0} ⇨ {1}" )
    @CsvSource( {
            "0, TEXT_MESSAGE",
            "1, BINARY_FILE",
            "2, EVENT_TABLE_OF_VALUE_STATES",
            "3, EVENT_TABLE_OF_TEXT_STATES",
            "4, EVENT_TABLE_OF_DAYTIMER_STATES",
            "5, OUT_OF_SERVICE_INDICATOR",
            "6, KEEP_ALIVE_RESPONSE",
            "7, EVENT_TABLE_OF_WEATHER_STATES"
    } )
    @DisplayName( "documented type bytes are decoded to their enum value" )
    void knownTypeByte_isDecodedCorrectly( int typeByte, MessageType expected )
    {
        ByteBuffer    buf    = header( BIN_TYPE, ( byte ) typeByte, ( byte ) 0, ( byte ) 0, 0 );
        MessageHeader header = new MessageHeader( buf );
        assertEquals( expected, header.getNextMessageType() );
    }

    @Test
    @DisplayName( "nextMessageLength is read as a 32-bit little-endian integer" )
    void nextMessageLength_isParsedCorrectly()
    {
        // 0x00 0x01 0x00 0x00 little-endian = 256.
        ByteBuffer    buf    = header( BIN_TYPE, ( byte ) 2, ( byte ) 0, ( byte ) 0, 256 );
        MessageHeader header = new MessageHeader( buf );
        assertEquals( 256, header.getNextMessageLength() );
        assertFalse( header.isOutOfServiceMessage() );
        assertFalse( header.isKeepaliveResponse() );
    }

    @Test
    @DisplayName( "isOutOfServiceMessage returns true only for type 5" )
    void isOutOfServiceMessage_detectsType5()
    {
        MessageHeader oos = new MessageHeader( header( BIN_TYPE, ( byte ) 5, ( byte ) 0, ( byte ) 0, 0 ) );
        assertTrue( oos.isOutOfServiceMessage() );

        MessageHeader other = new MessageHeader( header( BIN_TYPE, ( byte ) 2, ( byte ) 0, ( byte ) 0, 100 ) );
        assertFalse( other.isOutOfServiceMessage() );
    }

    @Test
    @DisplayName( "isKeepaliveResponse returns true only for type 6" )
    void isKeepaliveResponse_detectsType6()
    {
        MessageHeader keepAlive = new MessageHeader( header( BIN_TYPE, ( byte ) 6, ( byte ) 0, ( byte ) 0, 0 ) );
        assertTrue( keepAlive.isKeepaliveResponse() );

        MessageHeader other = new MessageHeader( header( BIN_TYPE, ( byte ) 2, ( byte ) 0, ( byte ) 0, 100 ) );
        assertFalse( other.isKeepaliveResponse() );
    }

    // ---------------------------------------------------------------------
    // Forward-compatibility on unknown type bytes
    // ---------------------------------------------------------------------

    @ParameterizedTest( name = "unknown type byte {0} maps to UNKNOWN (no exception)" )
    @ValueSource( ints = { 8, 9, 42, 99, 127, 200, 255 } )
    @DisplayName( "unknown type byte maps to MessageType.UNKNOWN — no AIOOBE" )
    void unknownTypeByte_mapsToUnknownInsteadOfThrowing( int typeByte )
    {
        // Naive code would do MessageType.values()[byte] and crash with
        // ArrayIndexOutOfBoundsException on any byte outside [0..7]. The
        // current readMessageType() maps such bytes to UNKNOWN so the
        // upstream filter silently drops the matching payload — the
        // connection stays up.
        ByteBuffer    buf    = header( BIN_TYPE, ( byte ) typeByte, ( byte ) 0, ( byte ) 0, 0 );
        MessageHeader header = new MessageHeader( buf );
        assertEquals( MessageType.UNKNOWN, header.getNextMessageType() );
    }

    @Test
    @DisplayName( "type byte 0xFF (-1 signed) maps to UNKNOWN — byte treated as unsigned" )
    void signedNegativeByte_mapsToUnknown()
    {
        // (byte) 0xFF is -1 in signed form. readMessageType applies & 0xFF
        // before indexing, treating it as unsigned 255, which is outside
        // the known range and maps to UNKNOWN.
        ByteBuffer    buf    = header( BIN_TYPE, ( byte ) 0xFF, ( byte ) 0, ( byte ) 0, 0 );
        MessageHeader header = new MessageHeader( buf );
        assertEquals( MessageType.UNKNOWN, header.getNextMessageType() );
    }

    // ---------------------------------------------------------------------
    // Validation — malformed inputs must throw
    // ---------------------------------------------------------------------

    @Test
    @DisplayName( "first byte not 0x03 ⇨ IllegalArgumentException" )
    void wrongBinType_throws()
    {
        ByteBuffer buf = header( ( byte ) 0x42, ( byte ) 2, ( byte ) 0, ( byte ) 0, 0 );
        IllegalArgumentException ex = assertThrows( IllegalArgumentException.class,
                                                    () -> new MessageHeader( buf ) );
        assertTrue( ex.getMessage().contains( "First byte" ),
                    "exception must explain which byte was wrong; got: " + ex.getMessage() );
    }

    @ParameterizedTest( name = "buffer of {0} bytes ⇨ IllegalArgumentException" )
    @ValueSource( ints = { 0, 1, 7, 9, 16 } )
    @DisplayName( "buffer of length ≠ 8 ⇨ IllegalArgumentException" )
    void wrongBufferLength_throws( int length )
    {
        ByteBuffer buf = ByteBuffer.allocate( length );
        IllegalArgumentException ex = assertThrows( IllegalArgumentException.class,
                                                    () -> new MessageHeader( buf ) );
        assertTrue( ex.getMessage().contains( "8 bytes" ),
                    "exception must mention the 8-byte rule; got: " + ex.getMessage() );
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /**
     * Build an 8-byte header buffer ready for {@link MessageHeader}. The
     * buffer is LITTLE-ENDIAN to match the Loxone wire format for the
     * {@code nLen} field, and is flipped to position 0.
     */
    private static ByteBuffer header( byte binType, byte typeByte, byte info, byte reserved, int nextLength )
    {
        ByteBuffer buf = ByteBuffer.allocate( 8 ).order( ByteOrder.LITTLE_ENDIAN );
        buf.put( binType );
        buf.put( typeByte );
        buf.put( info );
        buf.put( reserved );
        buf.putInt( nextLength );
        buf.flip();
        return buf;
    }
}
