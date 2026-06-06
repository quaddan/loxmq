/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.message;

/**
 * Fired synchronously by {@link BinaryStatesDecoder} whenever a binary frame
 * can't be decoded (truncated UUID, size not a multiple of 24 for value-states,
 * etc.). Subscribers can use this to surface the failure to a dashboard
 * counter or to fail-fast in tests.
 *
 * <p>The session itself stays up — a malformed event-table is a per-frame
 * failure, not a connection-level problem, so the decoder logs the issue and
 * keeps consuming. This event exists so test code can assert on the failure
 * path without grepping the log buffer.
 *
 * <p>{@code reason} is the human-readable diagnostic; {@code cause} is the
 * decoder-internal exception (may be {@code null} for synthetic failures
 * like "size not divisible by 24").
 */
public record DecodingFailureEvent(String reason, Throwable cause)
{
}
