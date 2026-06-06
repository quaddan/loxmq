/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.testresources;

import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Map;

/**
 * Spins up a minimal HTTP-only fake Loxone miniserver for integration
 * tests, and rewires the binding to talk to it instead of a real LAN
 * miniserver.
 *
 * <h3>Coverage</h3>
 * The two endpoints of the binding's bootstrap pass
 * ({@code com.quaddan.iot.loxmq.miniserver.bootstrap.BootstrapOrchestrator}):
 * <ul>
 *   <li>{@code GET /jdev/cfg/apiKey} — Gen2 + valid cert identity
 *       (snr=50:4F:94:AA:BB:CC, version=17.0.3.31, httpsStatus=1).</li>
 *   <li>{@code GET /jdev/sys/getPublicKey} — a fresh 2048-bit RSA public
 *       key generated at {@link #start()}, wrapped in BEGIN/END
 *       CERTIFICATE markers so the binding's {@code LoxoneCryptoService}
 *       parses it.</li>
 * </ul>
 *
 * <p>Anything past bootstrap (websocket, key exchange, tokens, structure
 * file) is NOT covered here. The session-orchestrator-side flow is
 * exercised in-JVM by {@code SessionOrchestratorTest} (uses a CDI
 * {@code QuarkusMock} of the WS client — only works under
 * {@code @QuarkusTest}, can't reach a {@code @QuarkusIntegrationTest}
 * subprocess). A follow-up can stand up a real WS endpoint here once
 * the cost/benefit is worth it.
 *
 * <h3>What this overrides</h3>
 * <pre>
 *   loxone.miniserver.connection.host     → 127.0.0.1
 *   loxone.miniserver.connection.port     → &lt;random OS-assigned&gt;
 *   loxone.miniserver.connection.secure   → false
 * </pre>
 *
 * <p>The {@code secure=false} override is deliberate — the embedded
 * HttpServer is HTTP-only. With Gen2 + httpsStatus=1 in the apiKey
 * response, the binding's {@code ConnectionModeResolver} would
 * otherwise flip to SECURE and try {@code https://127.0.0.1:&lt;port&gt;},
 * which the JDK HttpServer can't serve. The SECURE branch is unit-
 * tested separately under {@code ConnectionModeResolverTest}.
 *
 * <h3>Why a separate resource from {@link MosquittoTestResource}</h3>
 * The two resources have unrelated lifetimes and config surfaces. ITs
 * that need only the broker (e.g. {@code LiveBrokerIT}) shouldn't pay
 * the cost of bootstrapping a fake HTTP server they won't hit, and
 * vice-versa.
 */
public final class FakeMiniserverHttpResource implements QuarkusTestResourceLifecycleManager
{
    /** Canned Gen2 apiKey response — matches the format the real
     *  miniserver returns (verified live against firmware 17.0.3.31).
     *  Single-quoted inner JSON is intentional; the binding's
     *  {@code LoxoneJsonParser.parseInner} handles the SQ→DQ swap. */
    private static final String API_KEY_RESPONSE = """
                                                   {"LL":{"control":"dev/cfg/apiKey","value":\
                                                   "{'snr':'50:4F:94:AA:BB:CC','version':'17.0.3.31','key':'DEADBEEF',\
                                                   'isInTrust':false,'local':true,'address':'127.0.0.1','httpsStatus':1}",\
                                                   "Code":"200"}}""";

    private HttpServer server;

    /** Bound address — populated in {@link #start()}, exposed via
     *  {@link #host()} / {@link #port()} for ITs that need to reach the
     *  fake miniserver from the test JVM (e.g. to assert side effects).
     *  Same cross-JVM-boundary pattern as {@link MosquittoTestResource}. */
    private static volatile String  miniserverHost;
    private static volatile Integer miniserverPort;

    public static String host() { return miniserverHost; }

    public static int port() { return miniserverPort; }

    @Override
    public Map< String, String > start()
    {
        try
        {
            // Bind to port 0 → OS assigns a random free port. Better than
            // a hardcoded port: parallel CI runs + leftover sockets from
            // an earlier crashed run won't collide.
            server = HttpServer.create( new InetSocketAddress( "127.0.0.1", 0 ), 0 );

            // Wrap a fresh 2048-bit RSA public key in CERTIFICATE markers.
            // The binding's LoxoneCryptoService.loadPublicKey accepts both
            // SPKI (X509EncodedKeySpec) and PEM CERTIFICATE wrappers — we
            // use the wrapped form to match the on-wire shape.
            String publicKeyResponse = buildGetPublicKeyResponse();

            server.createContext( "/jdev/cfg/apiKey",
                                  exchange -> respond( exchange, API_KEY_RESPONSE ) );
            server.createContext( "/jdev/sys/getPublicKey",
                                  exchange -> respond( exchange, publicKeyResponse ) );
            // Catch-all returning 404 so an unexpected probe surfaces
            // clearly rather than hanging the binding.
            server.createContext( "/",
                                  exchange ->
                                  {
                                      exchange.sendResponseHeaders( 404, -1 );
                                      exchange.close();
                                  } );

            server.start();
            miniserverHost = "127.0.0.1";
            miniserverPort = server.getAddress().getPort();
            return Map.of(
                    "loxone.miniserver.connection.host", miniserverHost,
                    "loxone.miniserver.connection.port", String.valueOf( miniserverPort ),
                    "loxone.miniserver.connection.secure", "false"
                         );
        }
        catch ( Exception e )
        {
            throw new RuntimeException( "Failed to start FakeMiniserverHttpResource", e );
        }
    }

    @Override
    public void stop()
    {
        if ( server != null )
        {
            server.stop( 0 );
            server = null;
        }
        miniserverHost = null;
        miniserverPort = null;
    }

    private static void respond( com.sun.net.httpserver.HttpExchange exchange, String body )
    {
        try ( OutputStream os = exchange.getResponseBody() )
        {
            byte[] payload = body.getBytes( StandardCharsets.UTF_8 );
            exchange.sendResponseHeaders( 200, payload.length );
            os.write( payload );
        }
        catch ( Exception e )
        {
            throw new RuntimeException( e );
        }
    }

    /** Generate a fresh 2048-bit RSA key and wrap its X509 SPKI form in
     *  PEM CERTIFICATE markers (the same envelope shape the real
     *  miniserver returns). 2048 bits is the smallest the binding's
     *  RSA-OAEP unwrap path tolerates without warning. */
    private static String buildGetPublicKeyResponse() throws Exception
    {
        KeyPairGenerator gen = KeyPairGenerator.getInstance( "RSA" );
        gen.initialize( 2048 );
        KeyPair pair   = gen.generateKeyPair();
        String  base64 = Base64.getEncoder().encodeToString( pair.getPublic().getEncoded() );
        return """
               {"LL":{"control":"dev/sys/getPublicKey",\
               "value":"-----BEGIN CERTIFICATE-----%s-----END CERTIFICATE-----",\
               "Code":"200"}}""".formatted( base64 );
    }
}
