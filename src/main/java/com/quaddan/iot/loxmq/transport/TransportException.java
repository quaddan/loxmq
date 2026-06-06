/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.transport;

import java.io.Serial;

/**
 * Unchecked exception for unrecoverable transport-layer errors — broker
 * connect timeout, TLS handshake failure, authentication rejection.
 * The MQTT publisher surfaces these to the dashboard /
 * management API instead of relying on log scraping.
 */
public final class TransportException extends RuntimeException
{
    @Serial
    private static final long serialVersionUID = 1L;

    public TransportException( String message )
    {
        super( message );
    }

    public TransportException( String message, Throwable cause )
    {
        super( message, cause );
    }
}
