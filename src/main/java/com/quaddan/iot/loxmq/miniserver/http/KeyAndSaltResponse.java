/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.http;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Envelope of {@code GET jdev/sys/getkey2/{user}}.
 * <pre>{@code
 * { "LL": {
 *     "control": "dev/sys/getkey2/<user>",
 *     "value":   { "key": "<hex>", "salt": "<hex>", "hashAlg": "SHA256" },
 *     "code":    "200"   // lowercase 'code' here — Loxone alternates casing
 *   }
 * }
 * }</pre>
 *
 * Inner {@code value} is a structured object (NOT a single-quoted JSON string
 * like {@code cfgApi} is — Loxone reserved that quirk for the bootstrap
 * response). Standard Jackson parses it directly.
 */
@JsonIgnoreProperties( ignoreUnknown = true )
public record KeyAndSaltResponse(@JsonProperty( "LL" ) LL ll)
{
    @JsonIgnoreProperties( ignoreUnknown = true )
    public record LL(
            @JsonProperty( "control" ) String control,
            @JsonProperty( "value" ) Value value,
            @JsonProperty( "Code" ) @JsonAlias( "code" ) String code
    )
    {
    }

    @JsonIgnoreProperties( ignoreUnknown = true )
    public record Value(
            @JsonProperty( "key" ) String key,
            @JsonProperty( "salt" ) String salt,
            @JsonProperty( "hashAlg" ) String hashAlg
    )
    {
    }

    public boolean ok()
    {
        return ll != null && "200".equals( ll.code() );
    }
}
