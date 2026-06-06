/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.admin;

/**
 * Generic failure during an admin-command round-trip. Subclassed by
 * {@link AdminCommandTimeoutException} for the no-reply-in-time case;
 * direct throws cover encryption / transport / parse failures.
 *
 * <p>{@link RuntimeException} on purpose : REST handlers translate it to
 * a 5xx via standard Quarkus exception mapping — there's no caller-side
 * recovery the binding can do besides "tell the operator and retry".
 */
public class AdminCommandException extends RuntimeException
{
    private static final long serialVersionUID = 1L;

    public AdminCommandException( String message )
    {
        super( message );
    }

    public AdminCommandException( String message, Throwable cause )
    {
        super( message, cause );
    }
}
