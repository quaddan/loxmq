/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.command;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * MQTT-side command intent: which Loxone control to actuate and how.
 * Deserialised from the JSON payload received on
 * {@code loxone.transport.topics.subscribe.command.topic}.
 *
 * <p>Wire format:
 * <pre>{@code
 * {
 *   "uuid":    "1072755d-024f-4540-ffff112233445566/AI1",
 *   "command": "on"
 * }
 * }</pre>
 *
 * <p>The binding builds the plain miniserver command as
 * {@code {cmd.prefix.root}{uuid}/{command}}, encrypts it with the active
 * session key, and forwards it to the Miniserver WebSocket.
 *
 * @param uuid    UUID of the control to operate (Loxone format —
 *                32 hex digits + slashes for sub-inputs).
 * @param command Loxone command string (e.g. {@code on}, {@code off},
 *                {@code pulse}, or a numeric setpoint).
 */
public record MiniserverCommand(String uuid,
                                @JsonProperty( value = "command", required = true )
                                String command)
{
}
