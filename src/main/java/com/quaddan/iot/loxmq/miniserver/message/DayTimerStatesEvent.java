/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.message;

/**
 * CDI event for a decoded DayTimer-States batch (Loxone identifier 4). Fired
 * asynchronously by {@link BinaryStatesDecoder} once a complete event-table
 * has been parsed.
 */
public record DayTimerStatesEvent(DecodedMessages.DayTimerStates dayTimerStates) implements MiniserverStateEvent
{
}
