/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.message;

import java.nio.ByteBuffer;

/**
 * Parses the 8-byte binary message header that prefixes every state-update
 * frame emitted by the Miniserver. Layout per
 * <em>Communicating with the Miniserver V17.0, §"Message Header" p. 18</em>:
 *
 * <pre>
 *   byte 0 :  binType   — always 0x03 ("Loxone binary header marker").
 *   byte 1 :  identifier — message type, see {@link MessageType}.
 *   byte 2 :  info flags. Bit 0 set ⇒ next message length is estimated, not exact.
 *   byte 3 :  reserved (currently unused).
 *   bytes 4-7: uint32 little-endian, length in bytes of the payload that follows
 *             in the next WebSocket binary frame (or 0 for header-only messages
 *             like keep-alive responses and out-of-service indicators).
 * </pre>
 *
 * <p>An unknown identifier (i.e. a byte not in {@code [0..7]}, or any byte the
 * binding hasn't been taught about yet) maps to {@link MessageType#UNKNOWN} so
 * a future firmware revision can't crash the binding with an
 * {@link ArrayIndexOutOfBoundsException}; the upstream decoder's
 * {@code statesToDecode} filter then silently drops the matching payload.
 */
final class MessageHeader
{
    /** A Message Header is an 8-byte binary message. */
    private static final int MESSAGE_HEADER_LENGTH = 8;

    /** A MessageHeader always starts with 0x03 (Loxone protocol constant). */
    private static final int DEFAULT_BIN_TYPE = 0x03;

    private final byte        binType;
    private final MessageType nextMessageType;
    private final boolean     isNextMessageSizeEstimated;
    private final byte        reserved;
    /** Unsigned 32-bit length of the upcoming payload, stored as a signed int
     *  (Loxone frames never exceed Integer.MAX_VALUE bytes in practice). */
    private final int         nextMessageLength;

    public MessageHeader( ByteBuffer message )
    {
        int remaining = message.remaining();

        if ( MESSAGE_HEADER_LENGTH != remaining )
        {
            throw new IllegalArgumentException( String.format(
                    "ByteBuffer must be 8 bytes long to create Message Header. Current size ⇨ %d",
                    remaining ) );
        }

        binType = message.get();
        if ( binType != DEFAULT_BIN_TYPE )
        {
            throw new IllegalArgumentException( String.format(
                    "First byte of a Loxone Message Header must be %d. Found ⇨ %d",
                    DEFAULT_BIN_TYPE, binType ) );
        }

        nextMessageType            = readMessageType( message.get() );
        isNextMessageSizeEstimated = ( message.get() != 0 );
        reserved                   = message.get();
        nextMessageLength          = message.getInt();
    }

    /**
     * Maps the on-wire {@code byte} type identifier to a {@link MessageType}
     * ordinal-safely.
     * <p>
     * Doing {@code MessageType.values()[byte]} would throw
     * {@link ArrayIndexOutOfBoundsException} if the miniserver ever sends a
     * type identifier outside the documented range — e.g. if a future firmware
     * version introduces a new identifier. Mapping unknown bytes to
     * {@link MessageType#UNKNOWN} keeps the connection alive; the decoder
     * downstream already filters on {@code statesToDecode} so unknown types
     * are silently dropped — the desired behaviour for forward compatibility.
     */
    private static MessageType readMessageType( byte typeByte )
    {
        int           idx = typeByte & 0xFF;          // treat as unsigned to be safe
        MessageType[] all = MessageType.values();
        // Values 0..N-2 of the enum match the on-wire ordinals; UNKNOWN is the
        // last entry and is reserved for the sentinel path below.
        if ( idx >= 0 && idx < all.length && all[ idx ] != MessageType.UNKNOWN )
        {
            return all[ idx ];
        }
        return MessageType.UNKNOWN;
    }

    public static int getDefaultBinType() { return DEFAULT_BIN_TYPE; }

    public static int getMessageHeaderLength() { return MESSAGE_HEADER_LENGTH; }

    public MessageType getNextMessageType() { return nextMessageType; }

    public int getNextMessageLength() { return nextMessageLength; }

    public boolean isOutOfServiceMessage()
    {
        return nextMessageType == MessageType.OUT_OF_SERVICE_INDICATOR;
    }

    public boolean isKeepaliveResponse()
    {
        return nextMessageType == MessageType.KEEP_ALIVE_RESPONSE;
    }

    @Override
    public String toString()
    {
        return String.format(
                "MessageHeader:[binType ⇨ %d ⏏ nextMessageType ⇨ %s ⏏ isNextMessageSizeEstimated ⇨ %s ⏏ reserved ⇨ %d ⏏ nextMessageLength ⇨ %d⏏",
                binType,
                nextMessageType,
                isNextMessageSizeEstimated,
                reserved,
                nextMessageLength );
    }
}
