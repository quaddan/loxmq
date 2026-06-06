/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.command;

import com.quaddan.iot.loxmq.transport.subscribe.CommandSubscriber;

/**
 * Fired by {@link CommandSubscriber}
 * when a JSON payload arrives on the configured {@code command} topic.
 * Observed by the {@code SessionOrchestrator} which builds the plain
 * Loxone command, encrypts it, and sends it on the active WebSocket.
 *
 * <p>Sync ({@code fire()}) — the chain is short enough that the MQTT
 * worker thread can carry it through.
 */
public record MiniserverCommandEvent(MiniserverCommand command)
{
}
