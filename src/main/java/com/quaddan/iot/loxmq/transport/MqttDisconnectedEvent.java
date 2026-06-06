/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.transport;

import java.time.Instant;

/**
 * Fired by {@code HiveMqClient#onDisconnected} whenever the HiveMQ
 * client transitions out of {@code CONNECTED}. Counterpart to
 * {@link MqttConnectedEvent}; consumers can react to broker drops
 * symmetrically with reconnects.
 *
 * @param at       wall-clock instant of the disconnect.
 * @param source   "USER" / "CLIENT" / "SERVER" — the HiveMQ
 *                 {@code MqttClientDisconnectedContext.getSource()}
 *                 value. Useful to distinguish a graceful operator
 *                 disconnect (USER) from a broker eviction (SERVER).
 * @param reason   short human-readable reason — typically the cause's
 *                 message, or {@code ""} if no cause was attached. Kept
 *                 brief so the SSE payload stays small.
 */
public record MqttDisconnectedEvent(
        Instant at,
        String source,
        String reason)
{
}
