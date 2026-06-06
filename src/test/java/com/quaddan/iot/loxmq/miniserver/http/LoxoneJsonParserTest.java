/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.http;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link LoxoneJsonParser} — the parser that turns raw
 * {@code jdev/cfg/apiKey} and {@code jdev/sys/getPublicKey} response
 * bodies into typed records.
 * <p>
 * The sample payloads are taken verbatim from {@code docs/Ask Miniserver if
 * TLS protocol is supported.txt} (Gen1 / Gen2) plus a synthetic getPublicKey
 * response shaped like the firmware emits one.
 */
@QuarkusTest
@DisplayName( "LoxoneJsonParser — outer + inner + PEM strip" )
class LoxoneJsonParserTest
{
    @Inject
    LoxoneJsonParser parser;

    // ------------------------------------------------------------------
    //  Gen1 vs Gen2 cfgApi
    // ------------------------------------------------------------------

    @Test
    @DisplayName( "Gen1 cfgApi (no httpsStatus field)" )
    void cfgApiGen1() throws Exception
    {
        String body = """
                      {"LL": { "control": "dev/cfg/apiKey", "value": \
                      "{'snr': '50:4F:94:10:54:1B', 'version':'12.2.11.5', \
                      'key':'31373038424544364135354536433435393734343442434243463744444643304335353133393745', \
                      'isInTrust':false, 'local':true,'address':'192.0.2.10'}", \
                      "Code": "200"}}""";

        CfgApiValue value = parser.parseCfgApi( body );

        assertThat( value.snr() ).isEqualTo( "50:4F:94:10:54:1B" );
        assertThat( value.version() ).isEqualTo( "12.2.11.5" );
        assertThat( value.key() ).startsWith( "31373038" );
        assertThat( value.isInTrust() ).isFalse();
        assertThat( value.local() ).isTrue();
        assertThat( value.address() ).isEqualTo( "192.0.2.10" );
        assertThat( value.httpsStatus() ).isNull();    // field ABSENT on Gen1
    }

    @Test
    @DisplayName( "Gen2 cfgApi with httpsStatus=1 (valid cert)" )
    void cfgApiGen2Valid() throws Exception
    {
        String body = """
                      {"LL": { "control": "dev/cfg/apiKey", "value": \
                      "{'snr': '50:4F:94:AA:BB:CC', 'version':'12.2.11.5', \
                      'key':'36353239414534434333413044313332314142463131423734313333363941383432354230344334', \
                      'isInTrust':false, 'local':true,'address':'192.0.2.10', 'httpsStatus':1}", \
                      "Code": "200"}}""";

        CfgApiValue value = parser.parseCfgApi( body );

        assertThat( value.snr() ).isEqualTo( "50:4F:94:AA:BB:CC" );
        assertThat( value.httpsStatus() ).isEqualTo( 1 );    // SUPPORTED
    }

    @Test
    @DisplayName( "Gen2 cfgApi with httpsStatus=2 (expired cert)" )
    void cfgApiGen2Expired() throws Exception
    {
        String body = """
                      {"LL":{"control":"dev/cfg/apiKey","value":\
                      "{'snr':'AA:BB:CC:DD:EE:FF','version':'15.6.5.11','key':'DEAD','isInTrust':false,'local':true,'address':'1.2.3.4','httpsStatus':2}",\
                      "Code":"200"}}""";

        CfgApiValue value = parser.parseCfgApi( body );
        assertThat( value.httpsStatus() ).isEqualTo( 2 );
    }

    @Test
    @DisplayName( "cfgApi tolerates extra unknown fields in the inner JSON" )
    void cfgApiTolerantInner() throws Exception
    {
        // Firmware may add fields without bumping a doc — must not break.
        String body = """
                      {"LL":{"control":"dev/cfg/apiKey","value":\
                      "{'snr':'AA','version':'1.2.3.4','key':'X','isInTrust':false,'local':true,'address':'1.2.3.4','httpsStatus':1,'newFutureField':'wow'}",\
                      "Code":"200"}}""";

        CfgApiValue value = parser.parseCfgApi( body );
        assertThat( value.snr() ).isEqualTo( "AA" );    // existing fields still mapped
    }

    @Test
    @DisplayName( "cfgApi rejects non-200 Code with InvalidLoxoneResponseException" )
    void cfgApiNon200()
    {
        String body = """
                      {"LL":{"control":"dev/cfg/apiKey","value":"{}","Code":"503"}}""";

        assertThatThrownBy( () -> parser.parseCfgApi( body ) )
                .isInstanceOf( InvalidLoxoneResponseException.class )
                .hasMessageContaining( "Code=503" );
    }

    @Test
    @DisplayName( "cfgApi rejects missing LL.value with InvalidLoxoneResponseException" )
    void cfgApiMissingValue()
    {
        String body = """
                      {"LL":{"control":"dev/cfg/apiKey","Code":"200"}}""";

        assertThatThrownBy( () -> parser.parseCfgApi( body ) )
                .isInstanceOf( InvalidLoxoneResponseException.class )
                .hasMessageContaining( "missing LL.value" );
    }

    // ------------------------------------------------------------------
    //  getPublicKey response + PEM stripping
    // ------------------------------------------------------------------

    @Test
    @DisplayName( "getPublicKey strips -----BEGIN/END CERTIFICATE----- and whitespace" )
    void publicKey() throws Exception
    {
        String body = """
                      {"LL":{"control":"dev/sys/getPublicKey",\
                      "value":"-----BEGIN CERTIFICATE-----MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA-----END CERTIFICATE-----",\
                      "Code":"200"}}""";

        String base64Der = parser.parsePublicKey( body );

        assertThat( base64Der ).isEqualTo( "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA" );
        assertThat( base64Der ).doesNotContain( "BEGIN", "END", "-----" );
    }

    @Test
    @DisplayName( "getPublicKey also strips -----BEGIN/END PUBLIC KEY----- (defensive)" )
    void publicKeyAlternateMarkers() throws Exception
    {
        String body = """
                      {"LL":{"control":"dev/sys/getPublicKey",\
                      "value":"-----BEGIN PUBLIC KEY-----\\nMIIBIjANB\\ngkqhkiG\\n-----END PUBLIC KEY-----",\
                      "Code":"200"}}""";

        String base64Der = parser.parsePublicKey( body );
        assertThat( base64Der ).isEqualTo( "MIIBIjANBgkqhkiG" );
    }

    @Test
    @DisplayName( "getPublicKey rejects non-200" )
    void publicKeyNon200()
    {
        String body = """
                      {"LL":{"control":"dev/sys/getPublicKey","value":"whatever","Code":"401"}}""";

        assertThatThrownBy( () -> parser.parsePublicKey( body ) )
                .isInstanceOf( InvalidLoxoneResponseException.class );
    }

    @Test
    @DisplayName( "stripCertificateMarkers is idempotent (no markers = passthrough sans whitespace)" )
    void stripIdempotent()
    {
        assertThat( LoxoneJsonParser.stripCertificateMarkers( "ABC" ) ).isEqualTo( "ABC" );
        assertThat( LoxoneJsonParser.stripCertificateMarkers( "  AB  CD  " ) ).isEqualTo( "ABCD" );
    }
}
