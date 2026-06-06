/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.identity;

/**
 * Loxone Miniserver hardware generation, inferred from the bootstrap response.
 * <p>
 * There is no explicit field in the {@code jdev/cfg/apiKey} JSON that names
 * the generation. The empirical rule (documented in
 * {@code docs/Ask Miniserver if TLS protocol is supported.txt} and verified
 * against real units) is:
 *
 * <ul>
 *   <li><b>{@link HttpsStatus#ABSENT}</b> ⇒ {@link #GEN1}. The field was not
 *       added until Gen2 firmware, so its absence is a reliable Gen1 marker.</li>
 *   <li><b>Any other {@link HttpsStatus} value</b> (SUPPORTED / EXPIRED /
 *       UNKNOWN) ⇒ {@link #GEN2}. Only Gen2 hardware emits the field, even
 *       when the cert isn't installed yet.</li>
 * </ul>
 *
 * Gen1 hardware does not support TLS at all — there is no certificate slot
 * and no firmware path to enable HTTPS. A secure-preferred config on a Gen1
 * miniserver will therefore always fall back to plain HTTP + WS.
 */
public enum MiniserverGeneration
{
    /** Original Miniserver hardware (no TLS support). */
    GEN1,

    /** Second-generation Miniserver hardware (TLS capable, cert may or may not be installed). */
    GEN2;

    public static MiniserverGeneration from( HttpsStatus status )
    {
        return ( status == HttpsStatus.ABSENT ) ? GEN1 : GEN2;
    }
}
