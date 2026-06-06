/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.transport.connection;

/**
 * Transport family for the MQTT broker connection — independent of TLS.
 * <p>
 * Two families are supported by HiveMQ MQTT Client:
 * <ul>
 *   <li>{@link #TCP} — raw MQTT-over-TCP (standard port 1883 plain, 8883 with TLS).</li>
 *   <li>{@link #WS}  — MQTT-over-WebSocket (typical ports 8083 plain, 8084 with TLS,
 *       or 443 if reverse-proxied behind nginx/Traefik).</li>
 * </ul>
 *
 * Derived from {@code loxone.transport.connection.protocol} regardless of
 * whether the operator wrote the bare family name ({@code tcp}, {@code ws})
 * or the secure variant ({@code ssl}/{@code tls}/{@code mqtts} for TCP,
 * {@code wss} for WS). The TLS choice is the orthogonal
 * {@code loxone.transport.connection.secure} preference.
 */
public enum TransportFamily
{
    TCP,
    WS;

    /**
     * Maps any value accepted by the {@code transport.connection.protocol}
     * config field to its transport family. Throws
     * {@link IllegalArgumentException} for unknown inputs.
     */
    public static TransportFamily from( String protocol )
    {
        if ( protocol == null )
        {
            throw new IllegalArgumentException( "transport.connection.protocol is null" );
        }
        return switch ( protocol.trim().toLowerCase() )
        {
            case "tcp", "ssl", "tls", "mqtts" -> TCP;
            case "ws", "wss" -> WS;
            default -> throw new IllegalArgumentException(
                    "Unknown transport.connection.protocol value: '" + protocol + "'" );
        };
    }
}
