/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.message;

import java.nio.ByteBuffer;
import java.util.HexFormat;

/**
 * Low-level read helpers for the Loxone binary event-tables: UUID parsing
 * and bounded byte reads with a checked failure mode.
 *
 * <p>UUID wire layout per V17.0 §"WsBinHdr / EvData":
 * <pre>
 *   uint32 LE  data1
 *   uint16 LE  data2
 *   uint16 LE  data3
 *   byte[8]    data4
 * </pre>
 * String form: {@code "%08x-%04x-%04x-%02x%02x%02x%02x%02x%02x%02x%02x"} —
 * 36 chars, lower-case hex, three dashes.
 */
final class MessageHelper
{
    /** Lower-case hex codec. {@link HexFormat#toHexDigits(int)} and friends are
     *  substantially cheaper than {@link String#format} on a {@code "%08x-%04x-…"}
     *  template — no {@code Formatter} setup, no varargs allocation, no autoboxing
     *  per call. Important: this path runs once per decoded state, which can be
     *  hundreds of states per Miniserver event-table broadcast. */
    private static final HexFormat HEX = HexFormat.of();

    private MessageHelper() { throw new AssertionError(); }

    /**
     * Read a 16-byte UUID at the given absolute position in {@code message}.
     * <p>
     * Side effect: leaves the buffer's position at {@code position + 16}.
     */
    static String getUUID( ByteBuffer message, int position ) throws BinaryStatesDecodingException
    {
        if ( null == message )
        {
            throw new BinaryStatesDecodingException( "Can NOT getUUID from null ByteBuffer message." );
        }
        if ( ( position < 0 ) || ( position >= message.limit() ) )
        {
            throw new IllegalArgumentException(
                    "Can NOT get UUID because position " + position
                    + " in ByteBuffer can NOT be >= at its limit " + message.limit() );
        }
        message.position( position );
        return getUUID( message );
    }

    /**
     * Read a 16-byte UUID at the buffer's current position. The position
     * advances by 16 bytes on success.
     */
    static String getUUID( ByteBuffer message ) throws BinaryStatesDecodingException
    {
        if ( message.remaining() < 16 )
        {
            throw new BinaryStatesDecodingException(
                    "Can NOT read UUID (16 bytes) from buffer because it remains only "
                    + message.remaining() + " bytes." );
        }

        int   data1   = message.getInt();
        short data2   = message.getShort();
        short data3   = message.getShort();
        byte  data4_1 = message.get();
        byte  data4_2 = message.get();
        byte  data4_3 = message.get();
        byte  data4_4 = message.get();
        byte  data4_5 = message.get();
        byte  data4_6 = message.get();
        byte  data4_7 = message.get();
        byte  data4_8 = message.get();

        StringBuilder sb = new StringBuilder( 36 );
        sb.append( HEX.toHexDigits( data1 ) ).append( '-' );
        sb.append( HEX.toHexDigits( data2 ) ).append( '-' );
        sb.append( HEX.toHexDigits( data3 ) ).append( '-' );
        sb.append( HEX.toHexDigits( data4_1 ) );
        sb.append( HEX.toHexDigits( data4_2 ) );
        sb.append( HEX.toHexDigits( data4_3 ) );
        sb.append( HEX.toHexDigits( data4_4 ) );
        sb.append( HEX.toHexDigits( data4_5 ) );
        sb.append( HEX.toHexDigits( data4_6 ) );
        sb.append( HEX.toHexDigits( data4_7 ) );
        sb.append( HEX.toHexDigits( data4_8 ) );
        return sb.toString();
    }

    /**
     * Read {@code length} bytes at the buffer's current position. Used by the
     * Text-States decoder to extract the UTF-8 payload.
     */
    static byte[] getBytes( ByteBuffer message, int length ) throws BinaryStatesDecodingException
    {
        if ( length > message.remaining() )
        {
            throw new BinaryStatesDecodingException(
                    "Trying to read " + length + " bytes but remaining only "
                    + message.remaining() + " bytes." );
        }
        byte[] bytes = new byte[ length ];
        message.get( bytes, 0, length );
        return bytes;
    }
}
