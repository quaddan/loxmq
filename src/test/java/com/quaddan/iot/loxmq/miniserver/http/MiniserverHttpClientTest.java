/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.http;

import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests {@link MiniserverHttpClient} against an embedded
 * {@link com.sun.net.httpserver.HttpServer} (JDK built-in, no extra deps).
 * <p>
 * The fake miniserver listens on a fixed port that's also pinned in the
 * Quarkus {@link QuarkusTestProfile} below. We use a fixed port (not an
 * ephemeral one) because {@code @TestProfile.getConfigOverrides()} runs
 * BEFORE {@code @BeforeAll} — so we cannot resolve a dynamic port at test
 * setup time and feed it back through the config layer. The fixed port
 * (19999) is in the 1024+ unprivileged range, unlikely to collide on a
 * dev/CI host.
 */
@QuarkusTest
@TestProfile( MiniserverHttpClientTest.FakeMiniserverProfile.class )
@DisplayName( "MiniserverHttpClient — GETs cfgApi + getPublicKey from an embedded HTTP server" )
class MiniserverHttpClientTest
{
    /** Fixed port — must match the value in {@link FakeMiniserverProfile}. */
    private static final int PORT = 19999;

    private static       HttpServer             server;
    private static final AtomicInteger          cfgApiCallCount    = new AtomicInteger();
    private static final AtomicInteger          publicKeyCallCount = new AtomicInteger();
    /** Per-path response body — mutated per test. */
    private static final Map< String, String >  RESPONSES          = new ConcurrentHashMap<>();
    /** Per-path HTTP status — defaults to 200 unless overridden. */
    private static final Map< String, Integer > STATUSES           = new ConcurrentHashMap<>();

    @Inject
    MiniserverHttpClient client;

    public static class FakeMiniserverProfile implements QuarkusTestProfile
    {
        @Override
        public Map< String, String > getConfigOverrides()
        {
            return Map.of(
                    "loxone.miniserver.connection.host", "127.0.0.1",
                    "loxone.miniserver.connection.port", String.valueOf( PORT ),
                    "loxone.miniserver.connection.secure", "false"
                         );
        }
    }

    @BeforeAll
    static void startServer() throws IOException
    {
        server = HttpServer.create( new InetSocketAddress( "127.0.0.1", PORT ), 0 );
        server.createContext( "/", exchange ->
        {
            String path = exchange.getRequestURI().getPath();
            if ( path.endsWith( "/jdev/cfg/apiKey" ) )
            { cfgApiCallCount.incrementAndGet(); }
            if ( path.endsWith( "/jdev/sys/getPublicKey" ) )
            { publicKeyCallCount.incrementAndGet(); }

            String body    = RESPONSES.getOrDefault( path, "default" );
            int    code    = STATUSES.getOrDefault( path, 200 );
            byte[] payload = body.getBytes( StandardCharsets.UTF_8 );
            exchange.sendResponseHeaders( code, payload.length == 0 ? -1 : payload.length );
            try ( OutputStream os = exchange.getResponseBody() )
            {
                os.write( payload );
            }
        } );
        server.start();
    }

    @AfterAll
    static void stopServer()
    {
        if ( server != null )
        {
            server.stop( 0 );
        }
    }

    @BeforeEach
    void resetServerState()
    {
        RESPONSES.clear();
        STATUSES.clear();
    }

    @Test
    @DisplayName( "fetchCfgApi returns the body when the server replies 200" )
    void cfgApiHappy()
    {
        RESPONSES.put( "/jdev/cfg/apiKey", "FAKE-CFG-API-RESPONSE" );
        STATUSES.put( "/jdev/cfg/apiKey", 200 );
        int callsBefore = cfgApiCallCount.get();

        String body = client.fetchCfgApi();

        assertThat( body ).isEqualTo( "FAKE-CFG-API-RESPONSE" );
        assertThat( cfgApiCallCount.get() ).isEqualTo( callsBefore + 1 );
    }

    @Test
    @DisplayName( "fetchPublicKey returns the body when the server replies 200" )
    void publicKeyHappy()
    {
        RESPONSES.put( "/jdev/sys/getPublicKey", "FAKE-PUBKEY" );
        STATUSES.put( "/jdev/sys/getPublicKey", 200 );
        int callsBefore = publicKeyCallCount.get();

        String body = client.fetchPublicKey();

        assertThat( body ).isEqualTo( "FAKE-PUBKEY" );
        assertThat( publicKeyCallCount.get() ).isEqualTo( callsBefore + 1 );
    }

    @Test
    @DisplayName( "non-200 status throws InvalidLoxoneResponseException" )
    void non200Throws()
    {
        RESPONSES.put( "/jdev/cfg/apiKey", "not found" );
        STATUSES.put( "/jdev/cfg/apiKey", 503 );

        assertThatThrownBy( () -> client.fetchCfgApi() )
                .isInstanceOf( InvalidLoxoneResponseException.class )
                .hasMessageContaining( "HTTP 503" );
    }

    @Test
    @DisplayName( "empty body on 200 throws InvalidLoxoneResponseException" )
    void emptyBodyThrows()
    {
        RESPONSES.put( "/jdev/cfg/apiKey", "" );
        STATUSES.put( "/jdev/cfg/apiKey", 200 );

        assertThatThrownBy( () -> client.fetchCfgApi() )
                .isInstanceOf( InvalidLoxoneResponseException.class )
                .hasMessageContaining( "body is empty" );
    }

    // ============================================================
    //  Keep-alive / connection reuse
    // ============================================================

    @Test
    @DisplayName( "N successive GETs reuse the same TCP connection (keep-alive)" )
    void reuseConnectionsAcrossCalls() throws Exception
    {
        // Capture the remote port of each request on the server side.
        // If keep-alive is in effect, the JDK HttpClient keeps the
        // socket open and all N requests come from the SAME port (same
        // TCP connection). If keep-alive were disabled (for example by
        // forcing Connection: close), each request would open a new
        // ephemeral socket from a different port.
        java.util.Set< Integer > observedPorts =
                java.util.Collections.synchronizedSet( new java.util.HashSet<>() );
        com.sun.net.httpserver.HttpContext probe = server.createContext(
                "/jdev/cfg/apiKey", exchange ->
                {
                    observedPorts.add( exchange.getRemoteAddress().getPort() );
                    byte[] body = "FAKE".getBytes( StandardCharsets.UTF_8 );
                    exchange.sendResponseHeaders( 200, body.length );
                    try ( OutputStream os = exchange.getResponseBody() ) { os.write( body ); }
                } );
        try
        {
            long start = System.nanoTime();
            for ( int i = 0; i < 10; i++ )
            {
                String body = client.fetchCfgApi();
                assertThat( body ).isEqualTo( "FAKE" );
            }
            long elapsedMs = ( System.nanoTime() - start ) / 1_000_000;

            // Asserts:
            // (1) The JDK HttpClient pools connections —
            //     observedPorts should contain ONLY ONE port (the same
            //     TCP connection reused 10 times).
            // (2) Total time << 5s on loopback.
            assertThat( observedPorts )
                    .as( "keep-alive must reuse the same TCP connection" )
                    .hasSize( 1 );
            assertThat( elapsedMs ).isLessThan( 5_000L );
        }
        finally
        {
            server.removeContext( probe );
        }
    }
}
