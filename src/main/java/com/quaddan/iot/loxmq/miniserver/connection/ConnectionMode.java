/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.connection;

/**
 * Effective transport flavour for talking to the Miniserver — derived from
 * the operator's <i>preference</i> (config property
 * {@code loxone.miniserver.connection.secure}) AND the miniserver's own
 * TLS-readiness (Gen1/Gen2 + cert state).
 *
 * <h3>Resolution rule</h3>
 * <ul>
 *   <li>preference {@code secure=false} ⇒ {@link #PLAIN}, always.</li>
 *   <li>preference {@code secure=true} + Gen2 with valid cert ⇒ {@link #SECURE}.</li>
 *   <li>preference {@code secure=true} on Gen1 hardware ⇒ {@link #PLAIN}
 *       (Gen1 cannot do TLS — no cert slot, no firmware path).</li>
 *   <li>preference {@code secure=true} on Gen2 with no / expired cert ⇒
 *       {@link #PLAIN} (TLS handshake would fail; we don't silently break,
 *       we explicitly downgrade and the resolver logs a clear WARN once).</li>
 *   <li>preference {@code secure=true} but identity not yet known ⇒
 *       {@link #PLAIN} (defensive: the bootstrap call itself has to be plain
 *       HTTP because we don't yet know what the miniserver supports).</li>
 * </ul>
 *
 * Implemented by {@link ConnectionModeResolver}.
 */
public enum ConnectionMode
{
    /** {@code http://} for REST, {@code ws://} for WebSocket. */
    PLAIN,

    /** {@code https://} for REST, {@code wss://} for WebSocket. */
    SECURE;

    public String httpScheme()
    {
        return ( this == SECURE ) ? "https" : "http";
    }

    public String wsScheme()
    {
        return ( this == SECURE ) ? "wss" : "ws";
    }
}
