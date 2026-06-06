/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.identity;

import java.util.Optional;

/**
 * Miniserver TLS readiness, as advertised in the {@code httpsStatus} field of
 * the {@code jdev/cfg/apiKey} response.
 * <p>
 * The mapping is documented in {@code docs/Ask Miniserver if TLS protocol is
 * supported.txt} (verified empirically against a real Gen1 and a real Gen2):
 *
 * <ul>
 *   <li><b>Gen1 Miniserver</b> — the {@code httpsStatus} field is <i>absent</i>
 *       from the response. Gen1 hardware does not support TLS at all.</li>
 *   <li><b>Gen2 Miniserver, valid cert</b> — {@code httpsStatus: 1} →
 *       {@link #SUPPORTED}. HTTPS/WSS handshake will succeed.</li>
 *   <li><b>Gen2 Miniserver, expired cert</b> — {@code httpsStatus: 2} →
 *       {@link #EXPIRED}. HTTPS/WSS handshake will fail. Operator must reinstall.</li>
 * </ul>
 *
 * Any other observed value (including the field being present but holding an
 * undocumented number) maps to {@link #UNKNOWN}. The connection mode resolver
 * treats {@link #SUPPORTED} as the only state that allows a secure-preferred
 * config to upgrade — everything else falls back to plain.
 */
public enum HttpsStatus
{
    /** Field absent from the response — Gen1 hardware, or a defensive default. */
    ABSENT,

    /** {@code httpsStatus: 1} — Gen2 with a valid cert installed. */
    SUPPORTED,

    /** {@code httpsStatus: 2} — Gen2 with an expired cert. */
    EXPIRED,

    /** Field present with a value the binding does not recognise. */
    UNKNOWN;

    /**
     * Maps a raw {@link Optional}{@code <Integer>} from the {@code httpsStatus}
     * JSON field to a strict enum value. Use {@link Optional#empty()} when the
     * field was not present in the response (typical for Gen1).
     */
    public static HttpsStatus from( Optional< Integer > raw )
    {
        if ( raw.isEmpty() )
        {
            return ABSENT;
        }
        return switch ( raw.get() )
        {
            case 1 -> SUPPORTED;
            case 2 -> EXPIRED;
            default -> UNKNOWN;
        };
    }
}
