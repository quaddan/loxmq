/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.quaddan.iot.loxmq.miniserver.crypto.KeyAndSalt;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Parses the two specific Loxone HTTP responses the bootstrap consumes:
 * {@code jdev/cfg/apiKey} and {@code jdev/sys/getPublicKey}.
 *
 * <h3>Loxone JSON quirks handled here</h3>
 * <ol>
 *   <li><b>Single-quoted JSON inside a string.</b> The {@code jdev/cfg/apiKey}
 *       outer envelope is standard double-quoted JSON, but the
 *       {@code LL.value} field is a STRING that itself contains JSON written
 *       with SINGLE quotes (Loxone never updated this). We use a separate
 *       {@link JsonMapper} configured with
 *       {@link JsonReadFeature#ALLOW_SINGLE_QUOTES} for the inner parse —
 *       cleaner than a naive {@code '→"} string replacement on the whole
 *       response (which would corrupt any top-level field that ever
 *       contained a quote).</li>
 *   <li><b>CERTIFICATE markers around the public key.</b> The
 *       {@code jdev/sys/getPublicKey} response wraps the Base64-DER RSA
 *       public key in {@code -----BEGIN CERTIFICATE-----} /
 *       {@code -----END CERTIFICATE-----} — not the conventional
 *       {@code -----BEGIN PUBLIC KEY-----} markers. We strip both forms
 *       defensively so future firmware that switches markers still parses.</li>
 * </ol>
 *
 * <h3>Why CDI</h3>
 * The injected {@link ObjectMapper} is the Quarkus-managed one (configured
 * for the application's Jackson features). The inner-parse mapper is
 * private and built once at {@link PostConstruct} — same lifecycle as a
 * standard {@code @ApplicationScoped} singleton.
 */
@ApplicationScoped
public class LoxoneJsonParser
{
    @Inject
    ObjectMapper outerMapper;

    /**
     * Separate mapper for the inner {@code LL.value} string of the
     * {@code jdev/cfg/apiKey} response. Built with
     * {@code ALLOW_SINGLE_QUOTES=true} so the single-quoted Loxone JSON
     * parses without prior string substitution.
     */
    private ObjectMapper innerMapper;

    @PostConstruct
    void init()
    {
        innerMapper = JsonMapper.builder()
                                .enable( JsonReadFeature.ALLOW_SINGLE_QUOTES )
                                .build();
        // Don't choke on future firmware adding new fields to the inner JSON.
        innerMapper.configure( DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false );
    }

    /**
     * Parse the {@code jdev/cfg/apiKey} outer envelope.
     */
    public CfgApiResponse parseCfgApiOuter( String body ) throws JsonProcessingException
    {
        return outerMapper.readValue( body, CfgApiResponse.class );
    }

    /**
     * Parse the single-quoted JSON of the {@code LL.value} string into
     * {@link CfgApiValue}.
     */
    public CfgApiValue parseCfgApiInner( String singleQuotedJson ) throws JsonProcessingException
    {
        return innerMapper.readValue( singleQuotedJson, CfgApiValue.class );
    }

    /**
     * Convenience: outer parse + ok check + inner parse, returning the
     * fully-parsed inner value or throwing if anything is off.
     */
    public CfgApiValue parseCfgApi( String body ) throws JsonProcessingException
    {
        CfgApiResponse outer = parseCfgApiOuter( body );
        if ( !outer.ok() )
        {
            throw new InvalidLoxoneResponseException(
                    "cfgApi non-200 reply: Code=" + ( outer.ll() == null ? "null" : outer.ll().code() ) );
        }
        if ( outer.ll() == null || outer.ll().value() == null || outer.ll().value().isBlank() )
        {
            throw new InvalidLoxoneResponseException( "cfgApi response missing LL.value" );
        }
        return parseCfgApiInner( outer.ll().value() );
    }

    /**
     * Parse the {@code jdev/sys/getPublicKey} envelope.
     */
    public PublicKeyResponse parsePublicKeyOuter( String body ) throws JsonProcessingException
    {
        return outerMapper.readValue( body, PublicKeyResponse.class );
    }

    /**
     * Parse {@code GET jdev/sys/getkey2/{user}} into a strongly-typed
     * {@link KeyAndSalt}
     * for the crypto layer. Throws if the envelope reports non-200 or
     * lacks the expected shape.
     */
    public KeyAndSalt parseKeyAndSalt( String body )
            throws JsonProcessingException
    {
        KeyAndSaltResponse outer = outerMapper.readValue( body, KeyAndSaltResponse.class );
        if ( !outer.ok() )
        {
            throw new InvalidLoxoneResponseException(
                    "getkey2 non-200 reply: Code=" + ( outer.ll() == null ? "null" : outer.ll().code() ) );
        }
        if ( outer.ll() == null || outer.ll().value() == null )
        {
            throw new InvalidLoxoneResponseException( "getkey2 response missing LL.value" );
        }
        KeyAndSaltResponse.Value v = outer.ll().value();
        if ( v.key() == null || v.salt() == null )
        {
            throw new InvalidLoxoneResponseException( "getkey2 response missing key or salt field" );
        }
        return new KeyAndSalt(
                v.key(), v.salt(), v.hashAlg() );
    }

    /**
     * Convenience: outer parse + ok check + marker strip, returning the bare
     * Base64-DER ready for {@code LoxoneCryptoService.loadPublicKey()}.
     */
    public String parsePublicKey( String body ) throws JsonProcessingException
    {
        PublicKeyResponse outer = parsePublicKeyOuter( body );
        if ( !outer.ok() )
        {
            throw new InvalidLoxoneResponseException(
                    "getPublicKey non-200 reply: Code=" + ( outer.ll() == null ? "null" : outer.ll().code() ) );
        }
        if ( outer.ll() == null || outer.ll().value() == null || outer.ll().value().isBlank() )
        {
            throw new InvalidLoxoneResponseException( "getPublicKey response missing LL.value" );
        }
        return stripCertificateMarkers( outer.ll().value() );
    }

    /**
     * Parse {@code GET jdev/sys/getkey/{user}} into the bare hex hash key
     * carried by {@code LL.value}. Used for the
     * {@code refreshjwt} and {@code killtoken} HMAC signing.
     * <p>
     * Wire shape:
     * <pre>{@code
     *   {"LL":{"control":"jdev/sys/getkey/<user>","value":"<hex>","Code":"200"}}
     * }</pre>
     * Distinct from {@link #parseKeyAndSalt} (which expects {@code value}
     * to be a structured object with key/salt/hashAlg).
     */
    public String parseHashKey( String body ) throws JsonProcessingException
    {
        com.fasterxml.jackson.databind.JsonNode root = outerMapper.readTree( body );
        com.fasterxml.jackson.databind.JsonNode ll   = root.path( "LL" );
        String                                  code = ll.path( "Code" ).asText( ll.path( "code" ).asText( "" ) );
        if ( !"200".equals( code ) )
        {
            throw new InvalidLoxoneResponseException(
                    "getkey non-200 reply: Code=" + code );
        }
        String value = ll.path( "value" ).asText( "" );
        if ( value.isBlank() )
        {
            throw new InvalidLoxoneResponseException( "getkey response missing LL.value" );
        }
        return value;
    }

    /**
     * Strip the {@code -----BEGIN CERTIFICATE-----} / {@code -----END CERTIFICATE-----}
     * (and the conventional {@code -----BEGIN PUBLIC KEY-----} variants
     * defensively) plus all whitespace. The result is the bare Base64-DER
     * encoding of the SubjectPublicKeyInfo.
     */
    public static String stripCertificateMarkers( String pem )
    {
        return pem.replace( "-----BEGIN CERTIFICATE-----", "" )
                  .replace( "-----END CERTIFICATE-----", "" )
                  .replace( "-----BEGIN PUBLIC KEY-----", "" )
                  .replace( "-----END PUBLIC KEY-----", "" )
                  .replaceAll( "\\s+", "" );
    }
}
