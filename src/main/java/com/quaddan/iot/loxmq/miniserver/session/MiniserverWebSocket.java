/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.session;

import java.net.URI;

/**
 * Abstraction over the WebSocket transport between the binding and the
 * Miniserver. One method per outbound operation, one {@link Listener} per
 * inbound channel — keeps the {@code SessionOrchestrator} testable with a
 * fake implementation (no embedded WS server needed for unit tests).
 *
 * <h3>Why an interface</h3>
 * The production impl is {@link JdkMiniserverWebSocket} built on top of
 * {@link java.net.http.WebSocket}. Tests substitute a fake that records
 * outbound frames and lets the test code drive inbound deliveries on demand.
 * The {@code SessionOrchestrator} only ever sees this interface.
 *
 * <h3>Threading</h3>
 * {@link #sendText(String)} is safe to call from any thread.
 * {@link Listener#onText} is invoked on the WebSocket's internal reader
 * thread (single-threaded per spec). Multi-fragment messages are joined
 * before being delivered as one {@code onText} call — Loxone text frames
 * are small enough that this is fine.
 */
public interface MiniserverWebSocket
{
    /** Opens a WebSocket connection. Returns once the WS handshake has completed (or thrown). */
    void connect( URI uri, Listener listener );

    /** Sends a text frame. Errors propagate as {@link SessionException}. */
    void sendText( String text );

    /** Closes the WebSocket cleanly with the given reason phrase. Idempotent. */
    void close( String reason );

    /** True if a session is currently open. */
    boolean isOpen();

    /** Callback contract for inbound WebSocket events. */
    interface Listener
    {
        void onOpen();

        void onText( String message );

        void onBinary( byte[] data );

        void onClose( int statusCode, String reason );

        void onError( Throwable error );
    }
}
