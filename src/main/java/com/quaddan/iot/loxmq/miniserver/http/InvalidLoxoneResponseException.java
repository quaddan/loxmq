/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.http;

/**
 * Thrown when the miniserver's HTTP response doesn't match the documented
 * shape — non-200 {@code Code}, missing {@code LL} wrapper, blank
 * {@code value}, etc. Caught by the bootstrap orchestrator and surfaced as
 * a {@code BootstrapException} with the underlying message.
 */
public class InvalidLoxoneResponseException extends RuntimeException
{
    private static final long serialVersionUID = 1L;

    public InvalidLoxoneResponseException( String message )
    {
        super( message );
    }

    public InvalidLoxoneResponseException( String message, Throwable cause )
    {
        super( message, cause );
    }
}
