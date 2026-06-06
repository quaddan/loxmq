/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.connection;

import com.quaddan.iot.loxmq.config.LoxoneConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Assembles the actual {@link Endpoint} pairs (HTTP and WebSocket) the
 * binding will dial, based on the active {@link ConnectionMode} and the
 * miniserver branch of {@link LoxoneConfig}.
 *
 * <p>Two endpoints per resolution:
 * <ul>
 *   <li>{@link #httpEndpoint()} — used by the bootstrap calls
 *       ({@code jdev/cfg/apiKey}, {@code jdev/sys/getPublicKey}, the
 *       management dashboard's <i>view</i> of which scheme is in use).</li>
 *   <li>{@link #wsEndpoint()} — used by the WebSocket session that carries
 *       all the encrypted commands + state updates once the handshake is
 *       complete.</li>
 * </ul>
 *
 * <p>The host and port come straight from the config — the Miniserver
 * listens on the SAME port for plain HTTP and TLS HTTPS (the legacy "443
 * everywhere" convention from Loxone Config). Only the URI scheme changes.
 */
@ApplicationScoped
public class EndpointResolver
{
    @Inject
    LoxoneConfig config;

    @Inject
    ConnectionModeResolver modeResolver;

    /**
     * HTTP/HTTPS endpoint for REST-style bootstrap calls. Path is intentionally
     * empty — each caller appends its own command stem
     * ({@code jdev/cfg/apiKey}, etc.).
     */
    public Endpoint httpEndpoint()
    {
        var conn = config.miniserver().connection();
        return new Endpoint( modeResolver.effective().httpScheme(),
                             conn.host(),
                             conn.port(),
                             "" );
    }

    /**
     * WS/WSS endpoint for the persistent session. Path is
     * {@code loxone.miniserver.connection.ws.path}
     * (default {@code /ws/rfc6455}).
     */
    public Endpoint wsEndpoint()
    {
        var conn = config.miniserver().connection();
        return new Endpoint( modeResolver.effective().wsScheme(),
                             conn.host(),
                             conn.port(),
                             conn.ws().path() );
    }
}
