/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.session;

/**
 * Wraps every failure path of the WebSocket session orchestrator —
 * connect failures, handshake rejections, parse errors, crypto errors,
 * timeouts. The {@code message} is the operator-facing reason surfaced in
 * the dashboard / readiness / {@code /api/v1/state}.
 */
public class SessionException extends RuntimeException
{
    private static final long serialVersionUID = 1L;

    public SessionException( String message )
    {
        super( message );
    }

    public SessionException( String message, Throwable cause )
    {
        super( message, cause );
    }
}
