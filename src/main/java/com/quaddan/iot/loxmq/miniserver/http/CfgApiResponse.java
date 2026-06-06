/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.http;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Outer JSON envelope of {@code GET jdev/cfg/apiKey}. Standard double-quoted
 * JSON; the inner {@code value} field is a STRING containing single-quoted
 * JSON that needs a separate parse pass (see {@link LoxoneJsonParser}).
 *
 * <pre>{@code
 * {
 *   "LL": {
 *     "control": "dev/cfg/apiKey",
 *     "value":   "{'snr':'50:4F:94:AA:BB:CC','version':'12.2.11.5','key':'3635...','isInTrust':false,'local':true,'address':'192.0.2.10','httpsStatus':1}",
 *     "Code":    "200"
 *   }
 * }
 * }</pre>
 *
 * The {@code Code} field is a STRING (not an int) per the Loxone convention —
 * "200" / "503" etc. {@link #ok()} centralises the comparison.
 */
@JsonIgnoreProperties( ignoreUnknown = true )
public record CfgApiResponse(@JsonProperty( "LL" ) LL ll)
{
    @JsonIgnoreProperties( ignoreUnknown = true )
    public record LL(
            @JsonProperty( "control" ) String control,
            @JsonProperty( "value" ) String value,
            @JsonProperty( "Code" ) String code
    )
    {
    }

    /** {@code "200"} per protocol. Returns false for null/missing values. */
    public boolean ok()
    {
        return ll != null && "200".equals( ll.code() );
    }
}
