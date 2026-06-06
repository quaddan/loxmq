/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.connection;

import com.quaddan.iot.loxmq.config.LoxoneConfig;

import java.net.URI;

/**
 * Concrete address the binding will dial — scheme, host, port, optional path
 * — produced by {@link EndpointResolver} from the active {@link ConnectionMode}
 * and the {@link LoxoneConfig}
 * miniserver.connection branch.
 * <p>
 * Two endpoints are derived per resolution:
 * <ul>
 *   <li>{@link EndpointResolver#httpEndpoint()} — for the bootstrap REST calls
 *       (e.g. {@code jdev/cfg/apiKey}, {@code jdev/sys/getPublicKey}).</li>
 *   <li>{@link EndpointResolver#wsEndpoint()} — for the WebSocket session
 *       (path: {@code loxone.miniserver.connection.ws.path}, default
 *       {@code /ws/rfc6455}).</li>
 * </ul>
 *
 * Records keep the URI assembly in one place — operators see the resolved
 * address in the dashboard / {@code /api/v1/state} without the binding
 * having to template it in three different log lines.
 */
public record Endpoint(String scheme, String host, int port, String path)
{
    public URI toUri()
    {
        // Path is optional (may be empty for the bare HTTP endpoint, /ws/rfc6455
        // for the WebSocket one). URI builder handles the optional leading slash
        // gracefully.
        String normalisedPath = ( path == null || path.isEmpty() )
                                ? ""
                                : ( path.startsWith( "/" ) ? path : "/" + path );
        return URI.create( scheme + "://" + host + ":" + port + normalisedPath );
    }

    @Override
    public String toString()
    {
        return toUri().toString();
    }
}
