/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.transport.connection;

import com.quaddan.iot.loxmq.miniserver.connection.ConnectionMode;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link TransportConnectionResolver} — the (protocol × secure)
 * matrix that derives the effective MQTT scheme.
 *
 * <h3>Two boots, four cells covered each</h3>
 * The resolver is a pure function of two config values. Rather than spin up
 * Quarkus 8 times (one per matrix cell), we cover the matrix from two
 * {@link QuarkusTestProfile}s — one with {@code protocol=ws + secure=true}
 * and one with {@code protocol=tcp + secure=false} — and use the {@link
 * TransportFamily#from(String)} helper directly for the remaining cells
 * (which is a pure function with no DI). Plus a dedicated test class for
 * the legacy-value-acceptance check.
 */
@QuarkusTest
@TestProfile( TransportConnectionResolverTest.WsSecureProfile.class )
@DisplayName( "TransportConnectionResolver — (protocol × secure) → effective protocol/URI" )
class TransportConnectionResolverTest
{
    @Inject
    TransportConnectionResolver resolver;

    public static class WsSecureProfile implements QuarkusTestProfile
    {
        @Override
        public Map< String, String > getConfigOverrides()
        {
            return Map.of(
                    "loxone.transport.connection.protocol", "ws",
                    "loxone.transport.connection.secure", "true",
                    "loxone.transport.connection.host", "broker.example.com",
                    "loxone.transport.connection.port", "8084",
                    "loxone.transport.connection.path", "/mqtt"
                         );
        }
    }

    @Test
    @DisplayName( "ws + secure=true ⇒ wss + URI wss://host:port/path" )
    void wsSecure()
    {
        assertThat( resolver.preferred() ).isTrue();
        assertThat( resolver.family() ).isEqualTo( TransportFamily.WS );
        assertThat( resolver.mode() ).isEqualTo( ConnectionMode.SECURE );
        assertThat( resolver.effectiveProtocol() ).isEqualTo( "wss" );
        assertThat( resolver.effectiveUri().toString() )
                .isEqualTo( "wss://broker.example.com:8084/mqtt" );
    }

    @Test
    @DisplayName( "TransportFamily.from() resolves all six legacy + new values to families" )
    void familyResolution()
    {
        // Tcp family — bare + legacy secure variants.
        assertThat( TransportFamily.from( "tcp" ) ).isEqualTo( TransportFamily.TCP );
        assertThat( TransportFamily.from( "ssl" ) ).isEqualTo( TransportFamily.TCP );
        assertThat( TransportFamily.from( "tls" ) ).isEqualTo( TransportFamily.TCP );
        assertThat( TransportFamily.from( "mqtts" ) ).isEqualTo( TransportFamily.TCP );
        // Ws family — bare + legacy.
        assertThat( TransportFamily.from( "ws" ) ).isEqualTo( TransportFamily.WS );
        assertThat( TransportFamily.from( "wss" ) ).isEqualTo( TransportFamily.WS );
        // Case + whitespace tolerance.
        assertThat( TransportFamily.from( "  WSS  " ) ).isEqualTo( TransportFamily.WS );
    }

    @Test
    @DisplayName( "TransportFamily.from() throws on null / unknown values" )
    void familyRejectsBadInput()
    {
        assertThatThrownBy( () -> TransportFamily.from( null ) )
                .isInstanceOf( IllegalArgumentException.class );
        assertThatThrownBy( () -> TransportFamily.from( "amqp" ) )
                .isInstanceOf( IllegalArgumentException.class )
                .hasMessageContaining( "amqp" );
    }
}
