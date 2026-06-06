/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.message;

/**
 * CDI event for a decoded Weather-States batch (Loxone identifier 7). Fired
 * asynchronously by {@link BinaryStatesDecoder} once a complete event-table
 * has been parsed. Only emitted when the operator has subscribed to the
 * Loxone Weather Service (config {@code loxone.miniserver.subscription.weather=true}
 * and Weather added to {@code loxone.miniserver.states-to-decode}).
 */
public record WeatherStatesEvent(DecodedMessages.WeatherStates weatherStates) implements MiniserverStateEvent
{
}
