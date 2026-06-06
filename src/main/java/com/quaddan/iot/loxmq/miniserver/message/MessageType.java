/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.message;

/**
 * On-wire identifier for the second byte of a Loxone {@code WsBinHdr} (the
 * 8-byte binary message header). Values come straight from the V17.0
 * "Communicating with the Miniserver" spec, §"Message Header" p. 18.
 *
 * <p>The enum order matches the on-wire identifier (ordinals 0..7) so the
 * {@link MessageHeader} parser can map a byte to a {@link MessageType} by
 * indexing into {@link #values()} — with the exception of {@link #UNKNOWN},
 * which is a forward-compatibility sentinel for unrecognised firmware values
 * (e.g. an identifier added by a future Miniserver release).
 */
enum MessageType
{
    /** Identifier 0 — plain JSON text frame. */
    TEXT_MESSAGE( 0 ),
    /** Identifier 1 — binary file frame (e.g. {@code data/LoxAPP3.json}). */
    BINARY_FILE( 1 ),
    /** Identifier 2 — event-table of Value-States. Payload = N × 24 bytes. */
    EVENT_TABLE_OF_VALUE_STATES( 2 ),
    /** Identifier 3 — event-table of Text-States. Payload = variable, padded to multiples of 4. */
    EVENT_TABLE_OF_TEXT_STATES( 3 ),
    /** Identifier 4 — event-table of DayTimer-States. */
    EVENT_TABLE_OF_DAYTIMER_STATES( 4 ),
    /** Identifier 5 — Miniserver is going out of service (about to reboot or stop). */
    OUT_OF_SERVICE_INDICATOR( 5 ),
    /** Identifier 6 — response to the binding's {@code keepalive} command. */
    KEEP_ALIVE_RESPONSE( 6 ),
    /** Identifier 7 — event-table of Weather-States. */
    EVENT_TABLE_OF_WEATHER_STATES( 7 ),
    /** Sentinel for any byte outside [0..7] — see {@link MessageHeader} javadoc. */
    UNKNOWN( -1 );

    private final int value;

    MessageType( int value )
    {
        this.value = value;
    }

    public int getValue()
    {
        return value;
    }
}
