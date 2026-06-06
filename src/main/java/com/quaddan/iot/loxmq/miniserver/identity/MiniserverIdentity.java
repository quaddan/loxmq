/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.identity;

import java.util.Optional;

/**
 * Everything the binding learns about the Miniserver from the single
 * {@code jdev/cfg/apiKey} bootstrap call.
 * <p>
 * The miniserver answers with a JSON-in-string blob (yes, the value is a
 * string that itself contains JSON with SINGLE quotes — Loxone never
 * updated this quirk, so {@code LoxoneJsonParser} parses the inner value
 * with a dedicated mapper that enables {@code ALLOW_SINGLE_QUOTES}). Once
 * parsed it carries:
 *
 * <pre>{@code
 * Gen1 example:
 *   {"snr":"50:4F:94:10:54:1B","version":"12.2.11.5",
 *    "key":"3137...3745","isInTrust":false,"local":true,"address":"192.0.2.10"}
 *
 * Gen2 example (cert installed):
 *   {"snr":"50:4F:94:AA:BB:CC","version":"12.2.11.5",
 *    "key":"3635...3443","isInTrust":false,"local":true,"address":"192.0.2.10",
 *    "httpsStatus":1}
 * }</pre>
 *
 * The {@code key} field is the miniserver's <i>session/api key</i>
 * (used by the salt-rotation protocol). It is NOT the RSA public key — that
 * comes from a separate {@code jdev/sys/getPublicKey} call (part of the
 * handshake state machine).
 *
 * <h3>Population</h3>
 * The bootstrap orchestrator populates this record from the parsed
 * {@code jdev/cfg/apiKey} response. The crypto layer and the connection-mode
 * resolver consume it.
 *
 * @param serial      hardware serial / MAC string, e.g. {@code "50:4F:94:AA:BB:CC"}
 * @param version     parsed firmware version
 * @param sessionKey  the {@code key} field (opaque session/api key, hex string)
 * @param isInTrust   whether the miniserver is part of a trust group
 * @param local       whether the request reached the miniserver over its LAN
 *                    address (false if proxied via Loxone CloudDNS)
 * @param address     the IP the miniserver thinks it answered from
 * @param httpsStatus parsed TLS readiness (see {@link HttpsStatus})
 * @param generation  derived hardware generation (see {@link MiniserverGeneration})
 */
public record MiniserverIdentity(
        String serial,
        MiniserverVersion version,
        String sessionKey,
        boolean isInTrust,
        boolean local,
        String address,
        HttpsStatus httpsStatus,
        MiniserverGeneration generation
)
{
    /**
     * Convenience factory that derives the {@link HttpsStatus} and
     * {@link MiniserverGeneration} from the raw {@code httpsStatus} field
     * (Optional because Gen1 omits it). Use this when wiring up the JSON
     * deserialiser — it keeps the derivation rule in one place.
     */
    public static MiniserverIdentity from(
            String serial,
            MiniserverVersion version,
            String sessionKey,
            boolean isInTrust,
            boolean local,
            String address,
            Optional< Integer > rawHttpsStatus )
    {
        HttpsStatus          status     = HttpsStatus.from( rawHttpsStatus );
        MiniserverGeneration generation = MiniserverGeneration.from( status );
        return new MiniserverIdentity( serial, version, sessionKey, isInTrust, local, address, status, generation );
    }
}
