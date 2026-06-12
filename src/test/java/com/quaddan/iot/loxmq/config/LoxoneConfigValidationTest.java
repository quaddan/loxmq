/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.config;

import io.smallrye.config.ConfigValidationException;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import io.smallrye.config.validator.BeanValidationConfigValidatorImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Negative-path tests for the {@link LoxoneConfig} validation rules.
 * <p>
 * Builds a {@link SmallRyeConfig} directly (no Quarkus boot needed) with a
 * minimal valid baseline, then mutates individual properties to violate one
 * constraint at a time. The assertion is that the violation surfaces as a
 * {@link ConfigValidationException} carrying a message that points at the
 * specific property (so the operator can locate the typo).
 *
 * <p>Why not {@code @QuarkusTest}: each test would need a separate Quarkus
 * boot (~2 s each) and a way to inject a tailored {@code application.yaml}
 * per test. Using SmallRye Config directly runs at ~10 ms per test and
 * exercises exactly the same validation code path Quarkus uses at startup.
 */
@DisplayName( "LoxoneConfig — validation rules" )
class LoxoneConfigValidationTest
{
    /**
     * Minimal valid property set — every {@code @NotBlank} / {@code @Min}
     * field is populated with something reasonable. Tests mutate a copy.
     */
    private static Map< String, String > minimalValid()
    {
        Map< String, String > p = new HashMap<>();
        // Miniserver
        p.put( "loxone.miniserver.connection.host", "miniserver.example.test" );
        p.put( "loxone.miniserver.connection.port", "443" );
        p.put( "loxone.miniserver.app.id", "11111111-2222-3333-4444-555555555555" );
        p.put( "loxone.miniserver.app.info", "loxmq-test" );
        p.put( "loxone.miniserver.security.credentials.user", "dXNlcg==" );
        p.put( "loxone.miniserver.security.credentials.password", "cGFzcw==" );
        p.put( "loxone.miniserver.crypto.hash-password.algo", "SHA-256" );
        p.put( "loxone.miniserver.crypto.hash-user-password.algo", "HmacSHA256" );
        // Transport
        p.put( "loxone.transport.connection.protocol", "tcp" );
        p.put( "loxone.transport.connection.host", "broker.example.test" );
        p.put( "loxone.transport.connection.port", "1883" );
        p.put( "loxone.transport.connection.client-id", "test-client" );
        p.put( "loxone.transport.topics.root", "iot/test" );
        // Will (LWT) — topic + qos required.
        p.put( "loxone.transport.topics.will.topic", "iot/test/status" );
        p.put( "loxone.transport.topics.will.qos", "2" );
        // Publish endpoints — topic + qos required for every TopicSpec.
        for ( String name : new String[]{
                "app-info", "command-response", "lox-app3", "out-of-service",
                "value-states", "text-states", "day-timer-states", "weather-states" } )
        {
            p.put( "loxone.transport.topics.publish." + name + ".topic", "iot/test/" + name );
            p.put( "loxone.transport.topics.publish." + name + ".qos", "2" );
        }
        p.put( "loxone.transport.topics.subscribe.command.topic", "iot/test/command" );
        p.put( "loxone.transport.topics.subscribe.command.qos", "2" );
        p.put( "loxone.transport.topics.subscribe.api.topic", "iot/test/api" );
        p.put( "loxone.transport.topics.subscribe.api.qos", "2" );
        return p;
    }

    /**
     * Build a config AND materialise the mapping. SmallRye defers validation
     * to {@code getConfigMapping(...)} rather than running it eagerly at
     * {@code build()} — so tests that expect validation failures must call
     * both. Wrapping the pair in one helper keeps each test compact and
     * makes the expected throw-point obvious.
     */
    private static LoxoneConfig buildMapping( Map< String, String > properties )
    {
        // BeanValidationConfigValidatorImpl wires Hibernate Validator into the
        // SmallRye Config pipeline. Without it, only the SmallRye-internal
        // checks (@NotBlank presence) fire; @Pattern, @Min, etc. silently pass.
        // Quarkus auto-wires this via quarkus-hibernate-validator at runtime;
        // standalone tests have to register it explicitly.
        SmallRyeConfig cfg = new SmallRyeConfigBuilder()
                                     .addDefaultInterceptors()
                                     .withValidator( new BeanValidationConfigValidatorImpl() )
                                     .withMapping( LoxoneConfig.class )
                                     .withSources( new PropertiesConfigSource( properties, "test-overrides", 100 ) )
                                     .build();
        return cfg.getConfigMapping( LoxoneConfig.class );
    }

    // -------- happy path baseline --------

    @Test
    @DisplayName( "minimal valid config maps cleanly" )
    void minimal_valid_works()
    {
        LoxoneConfig c = buildMapping( minimalValid() );
        assertThat( c.miniserver().connection().host() ).isEqualTo( "miniserver.example.test" );
        assertThat( c.miniserver().connection().port() ).isEqualTo( 443 );
        assertThat( c.miniserver().connection().secure() ).isTrue();   // @WithDefault("true")
        assertThat( c.transport().mode() ).isEqualTo( "SINGLE" );      // @WithDefault("SINGLE")
        assertThat( c.miniserver().statesToDecode() ).containsExactly( 2, 3, 4 ); // @WithDefault("2,3,4")
    }

    // -------- @NotBlank --------

    @Test
    @DisplayName( "blank miniserver.connection.host fails @NotBlank" )
    void blank_miniserver_host_fails()
    {
        Map< String, String > p = minimalValid();
        p.put( "loxone.miniserver.connection.host", "" );

        assertThatThrownBy( () -> buildMapping( p ) )
                .isInstanceOf( ConfigValidationException.class )
                .hasMessageContaining( "loxone.miniserver.connection.host" );
    }

    @Test
    @DisplayName( "missing transport.connection.client-id fails @NotBlank" )
    void missing_client_id_fails()
    {
        Map< String, String > p = minimalValid();
        p.remove( "loxone.transport.connection.client-id" );

        assertThatThrownBy( () -> buildMapping( p ) )
                .isInstanceOf( ConfigValidationException.class )
                .hasMessageContaining( "loxone.transport.connection.client-id" );
    }

    // -------- @Pattern --------

    @Test
    @DisplayName( "invalid transport.connection.protocol fails @Pattern" )
    void invalid_protocol_fails()
    {
        Map< String, String > p = minimalValid();
        p.put( "loxone.transport.connection.protocol", "carrier-pigeon" );

        assertThatThrownBy( () -> buildMapping( p ) )
                .isInstanceOf( ConfigValidationException.class )
                .hasMessageContaining( "transport.connection.protocol must be one of" );
    }

    @Test
    @DisplayName( "invalid transport.mode fails @Pattern" )
    void invalid_mode_fails()
    {
        Map< String, String > p = minimalValid();
        p.put( "loxone.transport.mode", "BURST" );

        assertThatThrownBy( () -> buildMapping( p ) )
                .isInstanceOf( ConfigValidationException.class )
                .hasMessageContaining( "transport.mode must be BATCH or SINGLE" );
    }

    @Test
    @DisplayName( "non-UUID miniserver.app.id fails @Pattern" )
    void invalid_app_id_fails()
    {
        Map< String, String > p = minimalValid();
        p.put( "loxone.miniserver.app.id", "not-a-uuid" );

        assertThatThrownBy( () -> buildMapping( p ) )
                .isInstanceOf( ConfigValidationException.class )
                .hasMessageContaining( "must be a hyphenated UUID" );
    }

    // -------- @Min --------

    @Test
    @DisplayName( "zero port fails @Min(1)" )
    void zero_port_fails()
    {
        Map< String, String > p = minimalValid();
        p.put( "loxone.miniserver.connection.port", "0" );

        assertThatThrownBy( () -> buildMapping( p ) )
                .isInstanceOf( ConfigValidationException.class )
                .hasMessageContaining( "loxone.miniserver.connection.port" );
    }

    @Test
    @DisplayName( "negative qos fails @Min(0)" )
    void negative_qos_fails()
    {
        Map< String, String > p = minimalValid();
        p.put( "loxone.transport.topics.qos", "-1" );

        assertThatThrownBy( () -> buildMapping( p ) )
                .isInstanceOf( ConfigValidationException.class )
                .hasMessageContaining( "loxone.transport.topics.qos" );
    }

    @Test
    @DisplayName( "AES key-size below 128 fails @Min(128)" )
    void aes_key_size_too_small_fails()
    {
        Map< String, String > p = minimalValid();
        p.put( "loxone.miniserver.crypto.encrypt-command.key-size", "64" );

        // Hibernate Validator reports the violation path with the Java method
        // names (camelCase: encryptCommand.keySize), NOT the kebab-case wire
        // names (encrypt-command.key-size). Quirk of how SmallRye builds the
        // path for nested mappings. Test against what the operator will
        // actually see in the exception message.
        assertThatThrownBy( () -> buildMapping( p ) )
                .isInstanceOf( ConfigValidationException.class )
                .hasMessageContaining( "encryptCommand.key-size" )
                .hasMessageContaining( "must be greater than or equal to 128" );
    }

    // -------- Optional<String> semantics --------

    @Test
    @DisplayName( "transport.connection.path absent ⇨ Optional.empty()" )
    void path_absent_is_empty_optional()
    {
        Map< String, String > p = minimalValid();
        // path not set
        LoxoneConfig c = buildMapping( p );
        assertThat( c.transport().connection().path() ).isEmpty();
    }

    @Test
    @DisplayName( "transport.connection.path present ⇨ Optional.of(value)" )
    void path_present_is_some()
    {
        Map< String, String > p = minimalValid();
        p.put( "loxone.transport.connection.protocol", "ws" );
        p.put( "loxone.transport.connection.path", "/mqtt" );

        LoxoneConfig c = buildMapping( p );
        assertThat( c.transport().connection().path() ).contains( "/mqtt" );
    }

    // -------- @WithDefault sanity (one rep per type) --------

    @Test
    @DisplayName( "@WithDefault on protocol stems is applied when the property is absent" )
    void protocol_stem_defaults_applied()
    {
        LoxoneConfig c = buildMapping( minimalValid() );
        assertThat( c.miniserver().cmd().getCfgApi() ).isEqualTo( "jdev/cfg/apiKey" );
        assertThat( c.miniserver().cmd().prefix().nextSalt() ).isEqualTo( "nextSalt/" );
    }

    @Test
    @DisplayName( "@WithDefault Duration parses ISO-8601" )
    void duration_default_parses()
    {
        LoxoneConfig c = buildMapping( minimalValid() );
        assertThat( c.miniserver().reconnect().maxDelay() ).hasHours( 2 );
        assertThat( c.miniserver().crypto().salt().maxAge() ).hasHours( 1 );
    }

    @Test
    @DisplayName( "@WithDefault LocalTime parses HH:mm:ss" )
    void local_time_default_parses()
    {
        LoxoneConfig c = buildMapping( minimalValid() );
        assertThat( c.miniserver().security().token().refresh().delayTime() )
                .hasHour( 4 ).hasMinute( 30 ).hasSecond( 0 );
    }
}
