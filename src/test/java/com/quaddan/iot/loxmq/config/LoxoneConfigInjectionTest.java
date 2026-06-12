/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.config;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Injection / mapping smoke for {@link LoxoneConfig}.
 * <p>
 * Boots a full Quarkus container with the {@code %test} profile and verifies
 * that every nested interface of the config tree is reachable and populated
 * with sane values. Sister test to {@code LoxoneConfigValidationTest} (which
 * exercises the negative paths without booting Quarkus).
 *
 * <h3>What this catches</h3>
 * <ul>
 *   <li>A new sub-interface added without a matching property — surfaces as
 *       a {@code ConfigValidationException} at startup.</li>
 *   <li>A property renamed in {@code application.yaml} but not in the
 *       interface (or vice-versa) — the injected value becomes
 *       {@code null}/default and the assertions below trip.</li>
 *   <li>A {@code @WithDefault} that doesn't parse (e.g. an invalid
 *       {@code Duration} literal) — surfaces at startup.</li>
 * </ul>
 *
 * <h3>What this does NOT catch</h3>
 * Validation constraint failures (negative ports, invalid protocols, etc.)
 * are covered by {@code LoxoneConfigValidationTest}.
 */
@QuarkusTest
@DisplayName( "LoxoneConfig — full-tree injection smoke" )
class LoxoneConfigInjectionTest
{
    @Inject
    LoxoneConfig config;

    @Test
    @DisplayName( "miniserver.connection branch is populated with test values" )
    void miniserverConnection()
    {
        var conn = config.miniserver().connection();

        assertEquals( "127.0.0.1", conn.host() );
        assertEquals( 80, conn.port() );
        assertFalse( conn.secure(), "test profile sets secure=false" );

        assertNotNull( conn.http(), "Http sub-block must be a value, not null" );
        assertEquals( Duration.ofSeconds( 3 ), conn.http().connectTimeout() );
        assertEquals( Duration.ofSeconds( 3 ), conn.http().requestTimeout() );
        // Semaphore cap of in-flight admin commands to the Miniserver.
        // 4 = default value, 30s = wait timeout so it fail-fasts instead
        // of hanging indefinitely.
        assertEquals( 4, conn.http().adminMaxConcurrent() );
        assertEquals( Duration.ofSeconds( 30 ), conn.http().adminWaitTimeout() );

        assertNotNull( conn.ws(), "Ws sub-block must be a value, not null" );
        assertEquals( "/ws/rfc6455", conn.ws().path() );
        assertEquals( Duration.ofSeconds( 60 ), conn.ws().keepaliveInterval() );
    }

    @Test
    @DisplayName( "miniserver.app branch is populated" )
    void miniserverApp()
    {
        var app = config.miniserver().app();
        assertEquals( "00000000-0000-0000-0000-000000000001", app.id() );
        assertEquals( "loxmq TEST", app.info() );
        // Default is 4095 (= 0xFFF = bits 0-11 inclusive). Covers App +
        // Op-Modes + User-Mgmt + all non-destructive bits.
        assertEquals( 4095, app.permission() );
    }

    @Test
    @DisplayName( "miniserver.security branch — credentials + token refresh" )
    void miniserverSecurity()
    {
        var sec = config.miniserver().security();

        // Base64 strings — kept as-is, decoded later by the crypto layer.
        assertEquals( "ZmFrZV91c2Vy", sec.credentials().user() );
        assertEquals( "ZmFrZV9wYXNz", sec.credentials().password() );

        var refresh = sec.token().refresh();
        assertEquals( LocalTime.of( 4, 30 ), refresh.delayTime() );
        assertEquals( Duration.ofHours( 24 ), refresh.period() );
    }

    @Test
    @DisplayName( "miniserver.cmd defaults match the Loxone V17.0 protocol stems" )
    void miniserverCmdDefaults()
    {
        var cmd = config.miniserver().cmd();
        assertEquals( "jdev/cfg/apiKey", cmd.getCfgApi() );
        assertEquals( "jdev/sys/getPublicKey", cmd.getPublicKey() );
        assertEquals( "jdev/sys/keyexchange/", cmd.keyExchange() );
        assertEquals( "jdev/sys/getkey2", cmd.getKeyAndSalt() );
        assertEquals( "jdev/sys/getjwt/", cmd.requestToken() );
        assertEquals( "jdev/sys/refreshjwt", cmd.refreshToken() );
        assertEquals( "jdev/sys/killtoken/", cmd.killToken() );
        assertEquals( "jdev/sps/enablebinstatusupdate", cmd.requestStatusUpdate() );
        assertEquals( "data/LoxAPP3.json", cmd.requestStructureFile() );
        assertEquals( "jdev/sps/LoxAPPversion3", cmd.requestStructureFileVersion() );
        assertEquals( "jdev/sys/enc/", cmd.encrypt() );
        assertEquals( "keepalive", cmd.keepalive() );

        assertEquals( "jdev/sps/io/", cmd.prefix().root() );
        assertEquals( "salt/", cmd.prefix().salt() );
        assertEquals( "nextSalt/", cmd.prefix().nextSalt() );
    }

    @Test
    @DisplayName( "miniserver.crypto defaults match the Loxone handshake algorithm names" )
    void miniserverCryptoDefaults()
    {
        var crypto = config.miniserver().crypto();
        assertEquals( "AES", crypto.encryptCommand().algo() );
        assertEquals( "AES/CBC/PKCS5Padding", crypto.encryptCommand().transformation() );
        assertEquals( 256, crypto.encryptCommand().keySize() );
        assertEquals( 16, crypto.encryptCommand().saltLength() );

        assertEquals( "RSA", crypto.encryptKey().algo() );
        assertEquals( "RSA/ECB/PKCS1Padding", crypto.encryptKey().transformation() );

        assertEquals( "SHA-256", crypto.hashPassword().algo() );
        assertEquals( "HmacSHA256", crypto.hashUserPassword().algo() );

        assertEquals( Duration.ofHours( 1 ), crypto.salt().maxAge() );
        assertEquals( 16, crypto.sessionKey().initVectorLength() );
    }

    @Test
    @DisplayName( "miniserver.reconnect defaults are non-zero" )
    void miniserverReconnect()
    {
        var rec = config.miniserver().reconnect();
        assertTrue( rec.enable() );
        assertEquals( Duration.ofSeconds( 1 ), rec.initialDelay() );
        assertEquals( Duration.ofHours( 2 ), rec.maxDelay() );
        assertEquals( 2.0, rec.multiplier() );
        assertEquals( 0.2, rec.jitterFactor() );
    }

    @Test
    @DisplayName( "miniserver.cache + subscription + states-to-decode" )
    void miniserverAux()
    {
        assertEquals( "cache", config.miniserver().cache().directory() );
        // ttl default = P7D — TTL safety net for the LoxAPP3 cache.
        assertEquals( java.time.Duration.ofDays( 7 ), config.miniserver().cache().ttl() );
        assertFalse( config.miniserver().subscription().weather() );
        assertEquals( List.of( 2, 3, 4 ), config.miniserver().statesToDecode() );
    }

    @Test
    @DisplayName( "transport.connection branch — test overlay points at 127.0.0.1:1883 TCP" )
    void transportConnection()
    {
        var conn = config.transport().connection();
        assertEquals( "tcp", conn.protocol() );
        assertEquals( "127.0.0.1", conn.host() );
        assertEquals( 1883, conn.port() );
        // The base config carries `path=/mqtt` (for the WSS broker case); the
        // test overlay does NOT clear it, so the merged Optional<String> still
        // holds /mqtt even though the test transport is plain TCP. The HiveMQ
        // wiring layer is the one responsible for ignoring the path
        // when the protocol is not ws/wss — the config mapping is just a
        // surface.
        assertEquals( Optional.of( "/mqtt" ), conn.path() );
        assertNotNull( conn.clientId() );
        assertEquals( Duration.ofSeconds( 3 ), conn.connectTimeout() );
        assertEquals( Duration.ofSeconds( 60 ), conn.keepaliveInterval() );
        assertTrue( conn.requestProblemInformation() );
    }

    @Test
    @DisplayName( "transport.security.credentials — enable=false in test, user/pwd present" )
    void transportSecurity()
    {
        var creds = config.transport().security().credentials();
        assertFalse( creds.enable(), "test overlay disables broker auth" );
        assertEquals( Optional.of( "ZmFrZV91c2Vy" ), creds.user() );
        assertEquals( Optional.of( "ZmFrZV9wYXNz" ), creds.password() );
    }

    @Test
    @DisplayName( "transport.topics — root, will, 4 state-type topics, 2 subscribe topics" )
    void transportTopics()
    {
        var topics = config.transport().topics();
        assertEquals( "iot/loxmq", topics.root() );
        assertEquals( 2, topics.qos() );

        // Will
        assertTrue( topics.will().enable() );
        assertTrue( topics.will().retain() );
        assertEquals( "online", topics.will().messageOnline() );
        assertEquals( "offline", topics.will().messageOffline() );

        // Publish — every endpoint has a topic + qos. Topic strings are derived
        // from ${loxone.transport.topics.root}/${loxone.miniserver.app.id}/… so
        // they end with the test UUID.
        String expectedPrefix = "iot/loxmq/00000000-0000-0000-0000-000000000001";

        assertEquals( expectedPrefix + "/app_info", topics.publish().appInfo().topic() );
        assertEquals( expectedPrefix + "/command_response", topics.publish().commandResponse().topic() );
        assertEquals( expectedPrefix + "/loxapp3", topics.publish().loxApp3().topic() );
        assertEquals( expectedPrefix + "/out_of_service", topics.publish().outOfService().topic() );
        // Topic schema uses the type_<N> form
        // where N is the Loxone identifier (2 value, 3 text, 4 daytimer, 7 weather).
        assertEquals( expectedPrefix + "/states/type_2", topics.publish().valueStates().topic() );
        assertEquals( expectedPrefix + "/states/type_3", topics.publish().textStates().topic() );
        assertEquals( expectedPrefix + "/states/type_4", topics.publish().dayTimerStates().topic() );
        assertEquals( expectedPrefix + "/states/type_7", topics.publish().weatherStates().topic() );

        // Subscribe
        assertEquals( expectedPrefix + "/command", topics.subscribe().command().topic() );
        assertEquals( expectedPrefix + "/api", topics.subscribe().api().topic() );
        assertEquals( 2, topics.subscribe().command().qos() );
        assertEquals( 2, topics.subscribe().api().qos() );
    }

    @Test
    @DisplayName( "transport.session + reconnection defaults" )
    void transportSessionAndReconnection()
    {
        assertTrue( config.transport().session().cleanStart() );
        assertEquals( Duration.ZERO, config.transport().session().expiryInterval() );

        assertTrue( config.transport().reconnection().automatic() );
        assertEquals( Duration.ofSeconds( 3 ), config.transport().reconnection().minDelay() );
        assertEquals( Duration.ofMinutes( 2 ), config.transport().reconnection().maxDelay() );
    }
}
