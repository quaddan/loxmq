/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.message;

/**
 * CDI event for a decoded Value-States batch (Loxone identifier 2). Fired
 * asynchronously by {@link BinaryStatesDecoder} once a complete event-table
 * has been parsed. The MQTT publisher subscribes via
 * {@code @ObservesAsync ValueStatesEvent}.
 */
public record ValueStatesEvent(DecodedMessages.ValueStates valueStates) implements MiniserverStateEvent
{
}
