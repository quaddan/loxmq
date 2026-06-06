/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.connection;

import com.quaddan.iot.loxmq.miniserver.identity.MiniserverIdentity;
import com.quaddan.iot.loxmq.miniserver.identity.MiniserverVersion;
import com.quaddan.iot.loxmq.miniserver.state.MiniserverState;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * URI assembly checks: confirms {@link EndpointResolver} returns the right
 * scheme (http/https/ws/wss) for the active {@link ConnectionMode}, and that
 * the host/port/path components come straight from the config.
 */
@QuarkusTest
@TestProfile( EndpointResolverTest.SecurePreferredProfile.class )
@DisplayName( "EndpointResolver — URI assembly per mode" )
class EndpointResolverTest
{
    @Inject
    EndpointResolver endpointResolver;

    @Inject
    MiniserverState state;

    public static class SecurePreferredProfile implements QuarkusTestProfile
    {
        @Override
        public Map< String, String > getConfigOverrides()
        {
            return Map.of( "loxone.miniserver.connection.secure", "true" );
        }
    }

    @AfterEach
    void resetState()
    {
        state.clear();
    }

    @Test
    @DisplayName( "without identity, secure preference resolves PLAIN ⇒ http:// + ws://" )
    void plainWhenUnresolved()
    {
        Endpoint http = endpointResolver.httpEndpoint();
        Endpoint ws   = endpointResolver.wsEndpoint();

        assertThat( http.scheme() ).isEqualTo( "http" );
        assertThat( ws.scheme() ).isEqualTo( "ws" );
        // The test profile sets host=127.0.0.1, port=80, ws.path=/ws/rfc6455.
        assertThat( http.host() ).isEqualTo( "127.0.0.1" );
        assertThat( http.port() ).isEqualTo( 80 );
        assertThat( ws.path() ).isEqualTo( "/ws/rfc6455" );
    }

    @Test
    @DisplayName( "with Gen2 + SUPPORTED identity, resolves SECURE ⇒ https:// + wss://" )
    void secureWhenSupported()
    {
        state.update( MiniserverIdentity.from(
                "50:4F:94:AA:BB:CC",
                MiniserverVersion.parse( "15.6.5.11" ),
                "deadbeef",
                false, true, "127.0.0.1",
                Optional.of( 1 ) ) );

        Endpoint http = endpointResolver.httpEndpoint();
        Endpoint ws   = endpointResolver.wsEndpoint();

        assertThat( http.scheme() ).isEqualTo( "https" );
        assertThat( ws.scheme() ).isEqualTo( "wss" );
    }

    @Test
    @DisplayName( "with Gen1 identity, secure preference downgrades ⇒ http:// + ws://" )
    void plainOnGen1()
    {
        state.update( MiniserverIdentity.from(
                "50:4F:94:10:54:1B",
                MiniserverVersion.parse( "12.2.11.5" ),
                "deadbeef",
                false, true, "127.0.0.1",
                Optional.empty() ) );

        Endpoint http = endpointResolver.httpEndpoint();
        Endpoint ws   = endpointResolver.wsEndpoint();

        assertThat( http.scheme() ).isEqualTo( "http" );
        assertThat( ws.scheme() ).isEqualTo( "ws" );
    }

    @Test
    @DisplayName( "Endpoint.toUri() produces a parseable URI with port + path" )
    void uriRoundTrip()
    {
        Endpoint e = new Endpoint( "wss", "miniserver.example.com", 443, "/ws/rfc6455" );
        assertThat( e.toUri().toString() )
                .isEqualTo( "wss://miniserver.example.com:443/ws/rfc6455" );
    }

    @Test
    @DisplayName( "Endpoint with empty path renders without trailing slash" )
    void uriWithoutPath()
    {
        Endpoint e = new Endpoint( "http", "192.0.2.10", 80, "" );
        assertThat( e.toUri().toString() ).isEqualTo( "http://192.0.2.10:80" );
    }
}
