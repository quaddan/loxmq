/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.message;

import java.io.Serial;

/**
 * Checked exception thrown by binary-message decoding helpers when a frame's
 * structure is invalid (truncated UUID, length mismatch, out-of-range read…).
 * <p>
 * Package-private because the binary protocol details live entirely inside
 * {@code miniserver.message}; outside the package, decoding failures surface
 * as {@link DecodingFailureEvent} so subscribers don't depend on the exception
 * type.
 */
final class BinaryStatesDecodingException extends Exception
{
    @Serial
    private static final long serialVersionUID = -4899618026315259195L;

    BinaryStatesDecodingException( String message )
    {
        super( message );
    }
}
