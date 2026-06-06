/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.http;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Outer envelope of {@code GET jdev/sys/getPublicKey}. The {@code value}
 * field carries the RSA public key wrapped in
 * {@code -----BEGIN CERTIFICATE-----}/{@code -----END CERTIFICATE-----}
 * markers (note: <b>CERTIFICATE</b>, not <b>PUBLIC KEY</b> — Loxone quirk).
 *
 * <pre>{@code
 * {
 *   "LL": {
 *     "control": "dev/sys/getPublicKey",
 *     "value":   "-----BEGIN CERTIFICATE-----MIIBIjANBg...IDAQAB-----END CERTIFICATE-----",
 *     "Code":    "200"
 *   }
 * }
 * }</pre>
 *
 * {@link LoxoneJsonParser#stripCertificateMarkers(String)} strips the wrappers
 * + any whitespace before handing the raw Base64-DER off to
 * {@code LoxoneCryptoService.loadPublicKey()}.
 */
@JsonIgnoreProperties( ignoreUnknown = true )
public record PublicKeyResponse(@JsonProperty( "LL" ) LL ll)
{
    @JsonIgnoreProperties( ignoreUnknown = true )
    public record LL(
            @JsonProperty( "control" ) String control,
            @JsonProperty( "value" ) String value,
            @JsonProperty( "Code" ) String code
    )
    {
    }

    public boolean ok()
    {
        return ll != null && "200".equals( ll.code() );
    }
}
