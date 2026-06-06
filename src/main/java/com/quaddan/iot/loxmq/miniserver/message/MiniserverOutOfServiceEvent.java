/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.message;

/**
 * Fired by {@link BinaryStatesDecoder} when the Miniserver sends an
 * "out of service" message header (Loxone identifier 5). The Miniserver
 * sends this right before it closes the connection — typically because
 * it's rebooting, applying a firmware update, or being stopped manually.
 *
 * <p>The WebSocket will be closed shortly after (RFC 1000 — normal
 * closure), so the binding's reconnect scheduler picks it up under the
 * {@code NORMAL} policy.
 *
 * <p>The MQTT publisher subscribes to this event and
 * republishes a notification on {@code …/out_of_service} so downstream
 * automations (Home Assistant, etc.) can pause work that depends on the
 * Miniserver.
 *
 * @param timestamp wall-clock ms when the decoder observed the
 *                  out-of-service indicator
 */
public record MiniserverOutOfServiceEvent(long timestamp)
{
}
