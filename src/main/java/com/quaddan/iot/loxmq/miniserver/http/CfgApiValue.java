/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.http;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Inner payload of the {@code jdev/cfg/apiKey} response — the JSON object
 * encoded as a single-quoted STRING inside {@link CfgApiResponse.LL#value()}.
 *
 * <h3>Examples observed in the wild</h3>
 * <pre>{@code
 * // Gen1 — no httpsStatus field
 * { 'snr':'50:4F:94:10:54:1B',
 *   'version':'12.2.11.5',
 *   'key':'31373038...3745',
 *   'isInTrust':false,
 *   'local':true,
 *   'address':'192.0.2.10' }
 *
 * // Gen2 with valid cert
 * { 'snr':'50:4F:94:AA:BB:CC',
 *   'version':'12.2.11.5',
 *   'key':'36353239...3443',
 *   'isInTrust':false,
 *   'local':true,
 *   'address':'192.0.2.10',
 *   'httpsStatus':1 }
 * }</pre>
 *
 * @param snr         hardware serial / MAC string (Loxone calls it "serial number")
 * @param version     firmware version, dotted-quad string
 * @param key         opaque session/api key (hex string)
 * @param isInTrust   whether the miniserver participates in a trust group
 * @param local       whether the response came over the LAN interface
 * @param address     the IP the miniserver thinks it answered from
 * @param httpsStatus TLS readiness — null/absent on Gen1, {@code 1} on Gen2 with valid cert,
 *                    {@code 2} on Gen2 with expired cert. Wrapped {@link Integer} so the
 *                    "absent" case round-trips correctly through Jackson.
 */
@JsonIgnoreProperties( ignoreUnknown = true )
public record CfgApiValue(
        @JsonProperty( "snr" ) String snr,
        @JsonProperty( "version" ) String version,
        @JsonProperty( "key" ) String key,
        @JsonProperty( "isInTrust" ) Boolean isInTrust,
        @JsonProperty( "local" ) Boolean local,
        @JsonProperty( "address" ) String address,
        @JsonProperty( "httpsStatus" ) Integer httpsStatus
)
{
}
