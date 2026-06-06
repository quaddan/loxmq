/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.transport.connection;

import com.quaddan.iot.loxmq.config.LoxoneConfig;
import com.quaddan.iot.loxmq.miniserver.connection.ConnectionMode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URI;

/**
 * Pure function: derives the effective MQTT broker connection parameters
 * from {@code (loxone.transport.connection.protocol, loxone.transport.connection.secure)}.
 *
 * <h3>Why a separate resolver (mirroring {@code ConnectionModeResolver} for the miniserver)</h3>
 * Same design as the miniserver resolver: the operator writes a
 * <i>preference</i> ({@code secure=true|false}) and the binding computes the
 * actual on-the-wire scheme. Symmetry between the two sides keeps the mental
 * model simple — there's exactly one place per leg where the secure flag is
 * applied.
 *
 * <h3>No auto-downgrade</h3>
 * Unlike the miniserver case (which downgrades to PLAIN when the Gen2 cert
 * is absent or the hardware is Gen1), the broker doesn't expose its TLS
 * readiness. If the operator picks SECURE and the broker doesn't support
 * TLS, the connect will fail loudly at the TLS handshake — that's correct
 * (don't silently degrade broker security).
 *
 * <h3>Reused {@link ConnectionMode} enum</h3>
 * The semantics (PLAIN vs SECURE) are identical to the miniserver side, so
 * we reuse the same enum. The {@code httpScheme} / {@code wsScheme} helpers
 * on {@link ConnectionMode} aren't used here (transport works with
 * tcp/ssl/ws/wss, not http/https); we have {@link #effectiveProtocol()}
 * that returns the MQTT-specific scheme.
 */
@ApplicationScoped
public class TransportConnectionResolver
{
    @Inject
    LoxoneConfig config;

    /** Operator preference, straight from {@code loxone.transport.connection.secure}. */
    public boolean preferred()
    {
        return config.transport().connection().secure();
    }

    /** TCP-raw or WebSocket-tunneled, from the {@code protocol} config field. */
    public TransportFamily family()
    {
        return TransportFamily.from( config.transport().connection().protocol() );
    }

    /** {@link ConnectionMode#SECURE} if {@code secure=true}, else {@link ConnectionMode#PLAIN}. */
    public ConnectionMode mode()
    {
        return preferred() ? ConnectionMode.SECURE : ConnectionMode.PLAIN;
    }

    /**
     * The MQTT scheme actually used on the wire — what HiveMQ MQTT Client's
     * builder needs to know to pick the right transport + SSL config:
     *
     * <ul>
     *   <li>{@code (TCP, SECURE)} → {@code "ssl"}</li>
     *   <li>{@code (TCP, PLAIN)}  → {@code "tcp"}</li>
     *   <li>{@code (WS,  SECURE)} → {@code "wss"}</li>
     *   <li>{@code (WS,  PLAIN)}  → {@code "ws"}</li>
     * </ul>
     */
    public String effectiveProtocol()
    {
        TransportFamily fam  = family();
        ConnectionMode  mode = mode();
        return switch ( fam )
        {
            case TCP -> ( mode == ConnectionMode.SECURE ) ? "ssl" : "tcp";
            case WS -> ( mode == ConnectionMode.SECURE ) ? "wss" : "ws";
        };
    }

    /**
     * Convenience: the full broker URI in the resolved scheme. Includes the
     * configured path for WS variants (e.g. {@code /mqtt}); omits it for
     * raw TCP (the path is not meaningful there). HiveMQ's builder doesn't
     * take a URI directly but this is what the dashboard / state endpoint
     * render to give the operator a human-readable address.
     */
    public URI effectiveUri()
    {
        var    conn   = config.transport().connection();
        String scheme = effectiveProtocol();
        String path = ( family() == TransportFamily.WS )
                      ? conn.path().map( p -> p.startsWith( "/" ) ? p : "/" + p ).orElse( "" )
                      : "";
        return URI.create( scheme + "://" + conn.host() + ":" + conn.port() + path );
    }
}
