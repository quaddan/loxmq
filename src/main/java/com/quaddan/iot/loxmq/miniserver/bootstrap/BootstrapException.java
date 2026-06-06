/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.bootstrap;

/**
 * Wraps every failure path of the bootstrap orchestrator into a single
 * unchecked type. The {@code message} is operator-readable (referenced in
 * the dashboard and the readiness payload); the {@code cause} carries the
 * underlying I/O / parse / crypto exception for debugging.
 */
public class BootstrapException extends RuntimeException
{
    private static final long serialVersionUID = 1L;

    public BootstrapException( String message )
    {
        super( message );
    }

    public BootstrapException( String message, Throwable cause )
    {
        super( message, cause );
    }
}
