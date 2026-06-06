/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.http;

import com.quaddan.iot.loxmq.miniserver.connection.MiniserverHttpClientFactory;
import com.quaddan.iot.loxmq.config.LoxoneConfig;
import com.quaddan.iot.loxmq.miniserver.connection.Endpoint;
import com.quaddan.iot.loxmq.miniserver.connection.EndpointResolver;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Thin synchronous HTTP client for the two bootstrap calls:
 * {@code jdev/cfg/apiKey} and {@code jdev/sys/getPublicKey}, plus the auth /
 * admin commands ({@code getkey}, {@code getkey2}, {@code jdev/sps/*}).
 *
 * <h3>Why {@link java.net.http.HttpClient} (not Quarkus REST Client or Vert.x WebClient)</h3>
 * <ul>
 *   <li><b>No typed JAX-RS interface needed.</b> We hit two arbitrary URLs and
 *       read the body as a string — the REST Client's generated proxies add
 *       weight for no benefit.</li>
 *   <li><b>Sync semantics fit the bootstrap.</b> Two sequential calls, no
 *       interleaving with reactive streams — the JDK client's blocking
 *       {@code send()} is the right primitive.</li>
 *   <li><b>Zero extra deps.</b> Java HttpClient ships in {@code java.net.http}
 *       since JDK 11; works in native image without additional reflection
 *       hints (the SSL setup is wired automatically by Quarkus's TLS Registry).</li>
 * </ul>
 *
 * <h3>Timeouts</h3>
 * Read from {@code loxone.miniserver.connection.http.{connect,request}-timeout}.
 * Connect timeout applies to the TCP handshake, request timeout to the full
 * round-trip. Both default to 3 s in the base config.
 *
 * <h3>Connection pooling / keep-alive</h3>
 * <ul>
 *   <li>A SINGLE {@code HttpClient} instance built in {@link #init()} and
 *       reused for ALL calls (bootstrap + auth + admin) — the JDK pools
 *       TCP connections by origin (host+port+scheme) automatically, so
 *       a burst of N calls to the same Miniserver uses the same TLS
 *       connection.</li>
 *   <li>HTTP/1.1 pinned (Miniserver doesn't speak HTTP/2). HTTP/1.1
 *       default = keep-alive, so the Miniserver keeps the connection
 *       open between requests. The JDK manages the {@code Connection}
 *       header internally (restricted header — can't be overridden by
 *       the application).</li>
 *   <li>JDK-side idle timeout: {@code jdk.httpclient.keepalive.timeout}
 *       system property (default 1200s, well above any operator burst).</li>
 *   <li>Reuse test: {@code MiniserverHttpClientTest
 *       #reuseConnectionsAcrossCalls} observes the remote port on the
 *       embedded server side for each exchange — identical port across
 *       N calls = same TCP connection = keep-alive operational.</li>
 * </ul>
 *
 * <p>Direct benefit: eliminates the TLS handshake cost (~50-200ms per
 * connection) on repeated calls to the same Miniserver — bursts of 40+
 * admin calls per navigation burst would otherwise have the handshake
 * dominating the total RTT.
 */
@ApplicationScoped
public class MiniserverHttpClient
{
    private static final Logger LOG = Logger.getLogger( MiniserverHttpClient.class );

    @Inject
    LoxoneConfig config;

    @Inject
    EndpointResolver endpointResolver;

    @Inject
    MiniserverHttpClientFactory clientFactory;

    private HttpClient httpClient;
    private Duration   requestTimeout;

    @PostConstruct
    void init()
    {
        Duration connectTimeout = config.miniserver().connection().http().connectTimeout();
        requestTimeout = config.miniserver().connection().http().requestTimeout();
        // Delegate to MiniserverHttpClientFactory so the TLS posture
        // (skip-hostname-verification opt-in) stays in one place shared
        // with JdkMiniserverWebSocket. Miniserver does not speak HTTP/2
        // → HTTP_1_1 pinned.
        httpClient = clientFactory.newHttpClient( connectTimeout, HttpClient.Version.HTTP_1_1 );
    }

    /** {@code GET <endpoint>/jdev/cfg/apiKey}. Returns the raw response body. */
    public String fetchCfgApi()
    {
        return get( buildUrl( config.miniserver().cmd().getCfgApi() ) );
    }

    /** {@code GET <endpoint>/jdev/sys/getPublicKey}. Returns the raw response body. */
    public String fetchPublicKey()
    {
        return get( buildUrl( config.miniserver().cmd().getPublicKey() ) );
    }

    /**
     * {@code GET <endpoint>/jdev/sys/getkey2/{user}}. Returns the raw response
     * body. The handshake calls this AFTER the WS keyexchange has succeeded
     * but BEFORE the encrypted {@code getjwt} command — one of those bits
     * where Loxone tolerates plain HTTP during the handshake even though the
     * WS could carry it encrypted.
     */
    public String fetchKeyAndSalt( String base64EncodedUser )
    {
        String stem = config.miniserver().cmd().getKeyAndSalt();    // "jdev/sys/getkey2"
        // The stem in config has NO trailing slash; the user is appended directly.
        // (Loxone's spec writes it as "jdev/sys/getkey2/{user}".)
        return get( buildUrl( stem ) + "/" + base64EncodedUser );
    }

    /**
     * {@code GET <endpoint>/jdev/sys/getkey/{user}} — fetches the HMAC hash
     * key used to sign the {@code refreshjwt} and {@code killtoken} commands.
     * <p>
     * Different endpoint from {@link #fetchKeyAndSalt} (which is the
     * {@code getkey2} variant for the initial token request) — the response
     * is simpler: a single hex-string {@code LL.value} carrying just the
     * HMAC key. No salt, no hashAlg.
     * <p>
     * Reference: V17.0 §"Refresh JWT token" + §"Kill token".
     */
    public String fetchHashKey( String decodedUser )
    {
        String stem = config.miniserver().cmd().getKey();    // "jdev/sys/getkey"
        return get( buildUrl( stem ) + "/" + decodedUser );
    }

    /**
     * Send an authenticated admin command via HTTPS.
     *
     * <p>Format per V17.0 §"Authenticating using tokens, HTTP-Requests":
     * <pre>
     *   GET https://{miniserver}/{commandPath}?autht={hashHex}&user={user}
     * </pre>
     *
     * <p>The {@code autht} hash is computed by the caller using
     * {@code LoxoneCryptoService.hashToken(tokenKey, token)} — see the
     * {@code MiniserverAdminCommandClient} for the call site.
     *
     * <h3>Why a separate method (not a generic {@code get})</h3>
     * The existing {@link #get} helper logs + maps errors for the bootstrap
     * surface (apiKey / publicKey / getkey2). Admin commands have richer
     * error semantics — the Miniserver returns {@code Code} in the JSON
     * envelope, and a non-200 HTTP status carries an HTML body rather than
     * the JSON envelope. We expose the raw body + status so the admin
     * client can produce {@code AdminCommandException} with the same
     * decorated message as the WS path used to.
     *
     * @param commandPath the {@code jdev/sps/...} path (no leading slash,
     *                    URL-safe — caller URL-encodes path segments)
     * @param userDecoded plaintext username (as entered in Loxone Config,
     *                    not base64)
     * @param authtHex    hex-encoded HMAC of the JWT, computed via
     *                    {@code LoxoneCryptoService.hashToken}
     * @return the response body (typically a JSON envelope with
     *         {@code LL.{control,value,Code}})
     * @throws InvalidLoxoneResponseException on transport / HTTP &gt;= 400
     */
    public String sendAuthenticatedAdminGet( String commandPath, String userDecoded, String authtHex )
    {
        // URL-encode user — Loxone usernames are typically ASCII but
        // tolerate accents/diacritics. URLEncoder uses + for space; the
        // Miniserver decodes %20 / + identically.
        String userEnc = java.net.URLEncoder.encode( userDecoded, java.nio.charset.StandardCharsets.UTF_8 );
        String url     = buildUrl( commandPath ) + "?autht=" + authtHex + "&user=" + userEnc;
        return get( url );
    }

    /**
     * {@code GET <endpoint>/jdev/sys/sdtest} with HTTP Basic auth — triggers
     * the Miniserver's on-device SD-card self-test and returns the raw JSON
     * body ({@code LL.{control,value,Code}}, where {@code value} is the
     * one-line performance + health report).
     *
     * <p>Unlike the admin commands ({@link #sendAuthenticatedAdminGet}) this
     * {@code jdev/sys/*} diagnostic is authenticated with a standard HTTP
     * Basic header (RFC 7617) rather than the {@code autht} token hash — the
     * Miniserver verifies the Basic credential directly for system commands.
     * Reference: "Communicating with the Miniserver" §HTTP requests.
     *
     * <p>Deliberately uses {@code MiniserverHttpClient} (materialised during
     * the handshake, well before {@code RUNNING}) so the SD-card observer
     * never triggers a first-time lazy bean materialisation from the async
     * observer thread.
     *
     * @param basicAuthHeader the full {@code Authorization} value, i.e.
     *                        {@code "Basic " + base64(user + ":" + password)}
     *                        — built by the caller from the decoded
     *                        credentials, keeping this client credential-agnostic.
     * @return the raw response body (JSON envelope)
     * @throws InvalidLoxoneResponseException on transport / HTTP &ge; 400
     */
    public String fetchSdTest( String basicAuthHeader )
    {
        // Fixed Loxone system command — no config key (it never varies).
        return get( buildUrl( "jdev/sys/sdtest" ), basicAuthHeader );
    }

    // ==========================================================================
    //  internals
    // ==========================================================================

    /**
     * Builds the full URL from the resolved {@link Endpoint} + the protocol
     * stem. The resolver returns the EFFECTIVE scheme (http vs https) based
     * on the operator preference + miniserver identity — for the bootstrap
     * itself the identity is still empty so this returns http, but the same
     * client survives the post-bootstrap re-resolution.
     */
    private String buildUrl( String stem )
    {
        Endpoint base = endpointResolver.httpEndpoint();
        // Stem already starts with "jdev/..." (no leading slash) per the config.
        return base.toUri() + "/" + stem;
    }

    private String get( String url )
    {
        return get( url, null );
    }

    /**
     * GET with an optional {@code Authorization} header — used by
     * {@link #fetchSdTest} to carry the HTTP Basic credential. The header
     * value is NEVER logged; only the URL (which carries no credentials) is
     * traced.
     */
    private String get( String url, String authorizationHeader )
    {
        LOG.tracef( "HTTP GET → %s", url );

        // Connection header NOT included explicitly: the JDK
        // HttpClient marks it as "restricted" (see
        // java.net.http.HttpRequest.Builder#header — forbidden).
        // HTTP/1.1 (which we pinned) implies keep-alive by default;
        // the JDK handles the header automatically and reuses TCP
        // connections in the pool of the shared HttpClient instance
        // (see {@link #init}).
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                                                 .uri( URI.create( url ) )
                                                 .timeout( requestTimeout )
                                                 .GET()
                                                 .header( "Accept", "application/json, text/plain" );
        if ( authorizationHeader != null && !authorizationHeader.isBlank() )
        {
            builder.header( "Authorization", authorizationHeader );
        }
        HttpRequest request = builder.build();
        try
        {
            HttpResponse< String > response = httpClient.send( request, HttpResponse.BodyHandlers.ofString() );
            LOG.tracef( "HTTP %d ← %s (body=%d chars)",
                        ( Integer ) response.statusCode(),
                        ( Object ) url,
                        ( Integer ) ( response.body() == null ? 0 : response.body().length() ) );

            if ( response.statusCode() == 200 )
            {
                String body = response.body();
                if ( body == null || body.isBlank() )
                {
                    throw new InvalidLoxoneResponseException(
                            "HTTP 200 from " + url + " but body is empty" );
                }
                return body;
            }
            throw new InvalidLoxoneResponseException(
                    "HTTP " + response.statusCode() + " from " + url );
        }
        catch ( InterruptedException e )
        {
            Thread.currentThread().interrupt();
            throw new InvalidLoxoneResponseException( "Interrupted while calling " + url, e );
        }
        catch ( IOException e )
        {
            throw new InvalidLoxoneResponseException( "I/O failure calling " + url + ": " + e.getMessage(), e );
        }
    }
}
