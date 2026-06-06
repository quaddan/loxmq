/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.session;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Inner {@code LL.value} of the {@code jdev/sys/getjwt/...} response.
 * <pre>{@code
 * { "token":       "<jwt>",
 *   "key":         "<hex hmac key>",
 *   "validUntil":  481420800,
 *   "tokenRights": 4,
 *   "unsecurePass": false }
 * }</pre>
 * Translated to {@link MiniserverToken} by the orchestrator.
 */
@JsonIgnoreProperties( ignoreUnknown = true )
public record TokenValue(
        @JsonProperty( "token" ) String token,
        @JsonProperty( "key" ) String key,
        @JsonProperty( "validUntil" ) Long validUntil,
        @JsonProperty( "tokenRights" ) Integer tokenRights,
        @JsonProperty( "unsecurePass" ) Boolean unsecurePass
)
{
}
