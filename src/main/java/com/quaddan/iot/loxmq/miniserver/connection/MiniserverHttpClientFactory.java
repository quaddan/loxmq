/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.connection;

import com.quaddan.iot.loxmq.config.LoxoneConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.Socket;
import java.net.http.HttpClient;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Arrays;

/**
 * Builds the {@link HttpClient} instances used to dial the Miniserver — the
 * one used by {@code MiniserverHttpClient} for REST-style bootstrap calls,
 * and the one used by {@code JdkMiniserverWebSocket} for the WS upgrade.
 *
 * <p>Centralised here because both call-sites need the same TLS
 * posture when
 * {@code loxone.miniserver.connection.tls-skip-hostname-verification=true} —
 * a single source of truth for the {@link SSLContext} configuration, so a
 * future tweak (cipher restrictions, protocol pinning, custom truststore)
 * lands in one place.
 *
 * <h3>Why custom {@link X509ExtendedTrustManager} (not just {@code SSLParameters})</h3>
 * Initial attempt set
 * {@code SSLParameters.setEndpointIdentificationAlgorithm(null)} and handed
 * the params to {@link HttpClient.Builder#sslParameters}. That <b>does not
 * work</b>: the JDK {@code java.net.http.HttpClient} internally forces
 * {@code endpointIdentificationAlgorithm = "HTTPS"} on every TLS handshake,
 * overwriting whatever the operator configured. See
 * {@code jdk.internal.net.http.AbstractAsyncSSLConnection#createSSLParameters}
 * in the JDK sources — the line is hardcoded and not overridable.
 *
 * <p>The workaround is to provide a custom {@link X509ExtendedTrustManager}
 * that:
 * <ol>
 *   <li>Keeps the JVM default truststore chain validation (Let's Encrypt
 *       roots etc. — so a self-signed cert outside the truststore is still
 *       rejected).</li>
 *   <li>Strips the hostname check from the {@code SSLEngine}/{@code Socket}
 *       overloads by delegating to the 2-arg
 *       {@code checkServerTrusted(chain, authType)} variant — which by
 *       definition does NOT do hostname matching (that's the contract of
 *       the base {@link X509TrustManager} interface).</li>
 * </ol>
 *
 * <p>The net effect is the same posture as curl's {@code -k} but narrower —
 * the chain still has to trust to a known root, only the hostname-vs-SAN
 * check is skipped.
 */
@ApplicationScoped
public class MiniserverHttpClientFactory
{
    private static final Logger LOG = Logger.getLogger( MiniserverHttpClientFactory.class );

    @Inject
    LoxoneConfig config;

    /**
     * Build an {@link HttpClient} with the configured connect timeout AND
     * the right TLS posture. Each call returns a fresh instance — callers
     * keep ONE long-lived client per use case (HTTP REST, WS upgrade) and
     * reuse it across requests.
     */
    public HttpClient newHttpClient( Duration connectTimeout, HttpClient.Version version )
    {
        HttpClient.Builder b = HttpClient.newBuilder()
                                         .version( version )
                                         .connectTimeout( connectTimeout );
        if ( config.miniserver().connection().tlsSkipHostnameVerification() )
        {
            b.sslContext( buildHostnameSkippingContext() );
            // WARN, not DEBUG: explicit security downgrade — surface it loudly
            // at every boot so it doesn't get forgotten.
            LOG.warnf( "TLS hostname verification DISABLED for the Miniserver leg "
                       + "(loxone.miniserver.connection.tls-skip-hostname-verification=true). "
                       + "Chain still validated against the JVM truststore. Recommend uploading "
                       + "a cert matching the configured host to the Miniserver via Loxone Config." );
        }
        return b.build();
    }

    /** Convenience overload using {@code HTTP_1_1} — what the Miniserver speaks. */
    public HttpClient newHttpClient( Duration connectTimeout )
    {
        return newHttpClient( connectTimeout, HttpClient.Version.HTTP_1_1 );
    }

    // ------------------------------------------------------------------------
    //  Internals
    // ------------------------------------------------------------------------

    /**
     * Build an {@link SSLContext} that validates the cert chain against the
     * JVM default truststore but skips hostname verification.
     */
    private SSLContext buildHostnameSkippingContext()
    {
        try
        {
            // Locate the JVM default X509ExtendedTrustManager — the one that
            // would normally check both the chain AND the hostname.
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm() );
            tmf.init( ( KeyStore ) null );   // null → JVM cacerts default
            X509ExtendedTrustManager defaultTm = Arrays.stream( tmf.getTrustManagers() )
                                                       .filter( X509ExtendedTrustManager.class::isInstance )
                                                       .map( X509ExtendedTrustManager.class::cast )
                                                       .findFirst()
                                                       .orElseThrow( () -> new IllegalStateException(
                                                               "Default TrustManagerFactory did not provide an X509ExtendedTrustManager" ) );

            // Wrap it so the SSLEngine/Socket overloads (which the JDK
            // HttpClient invokes) fall through to the 2-arg variant — which
            // by the X509TrustManager contract does chain validation only,
            // no hostname check.
            X509ExtendedTrustManager wrapper = new X509ExtendedTrustManager()
            {
                @Override
                public X509Certificate[] getAcceptedIssuers() { return defaultTm.getAcceptedIssuers(); }

                @Override
                public void checkClientTrusted( X509Certificate[] chain, String authType ) throws CertificateException
                {
                    defaultTm.checkClientTrusted( chain, authType );
                }

                @Override
                public void checkServerTrusted( X509Certificate[] chain, String authType ) throws CertificateException
                {
                    defaultTm.checkServerTrusted( chain, authType );
                }

                @Override
                public void checkClientTrusted( X509Certificate[] chain, String authType, Socket socket ) throws CertificateException
                {
                    defaultTm.checkClientTrusted( chain, authType );   // hostname skip
                }

                @Override
                public void checkServerTrusted( X509Certificate[] chain, String authType, Socket socket ) throws CertificateException
                {
                    defaultTm.checkServerTrusted( chain, authType );   // hostname skip
                }

                @Override
                public void checkClientTrusted( X509Certificate[] chain, String authType, SSLEngine engine ) throws CertificateException
                {
                    defaultTm.checkClientTrusted( chain, authType );   // hostname skip
                }

                @Override
                public void checkServerTrusted( X509Certificate[] chain, String authType, SSLEngine engine ) throws CertificateException
                {
                    defaultTm.checkServerTrusted( chain, authType );   // hostname skip
                }
            };

            SSLContext ctx = SSLContext.getInstance( "TLS" );
            ctx.init( null, new TrustManager[]{ wrapper }, null );
            return ctx;
        }
        catch ( GeneralSecurityException e )
        {
            throw new IllegalStateException(
                    "Failed to build SSLContext for tls-skip-hostname-verification", e );
        }
    }
}
