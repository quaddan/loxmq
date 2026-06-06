/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.admin;

/**
 * The Miniserver didn't reply to an admin command within the caller-
 * supplied timeout. Separate type from {@link AdminCommandException} so
 * REST handlers can map it to a more specific HTTP status (504 Gateway
 * Timeout) and distinguish it from generic failures.
 */
public class AdminCommandTimeoutException extends AdminCommandException
{
    private static final long serialVersionUID = 1L;

    public AdminCommandTimeoutException( String message, Throwable cause )
    {
        super( message, cause );
    }
}
