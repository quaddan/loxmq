/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.crypto;

/**
 * Wraps every checked exception that can come out of the JCE pipeline
 * ({@code NoSuchAlgorithmException}, {@code InvalidKeyException},
 * {@code BadPaddingException}, …) into a single unchecked type the
 * binding code can handle uniformly.
 * <p>
 * A single unchecked type, used with Fault Tolerance retry rules.
 */
public class LoxoneCryptoException extends RuntimeException
{
    private static final long serialVersionUID = 1L;

    public LoxoneCryptoException( String message )
    {
        super( message );
    }

    public LoxoneCryptoException( String message, Throwable cause )
    {
        super( message, cause );
    }

    public LoxoneCryptoException( Throwable cause )
    {
        super( cause );
    }
}
