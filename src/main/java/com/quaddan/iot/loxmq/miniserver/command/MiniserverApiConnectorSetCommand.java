/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.command;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * MQTT-side API-Connector SET command. Targets a Virtual Input Text + a
 * function block input via the legacy "API Connector" idiom.
 *
 * <p>Wire format:
 * <pre>{@code
 * {
 *   "virtual_input_text": "VTI-LumiereBureau",
 *   "function_block":     "Lico",
 *   "input":              "Lc1",
 *   "value":              "Pulse"
 * }
 * }</pre>
 *
 * <p>The binding builds the plain miniserver command as
 * {@code {cmd.prefix.root}{virtualInputText}/SET({functionBlock};{input};{value})},
 * encrypts it, and forwards it to the Miniserver WebSocket.
 *
 * @param virtualInputText Name of the Virtual Input Text (configured in
 *                         Loxone Config) attached to the function block.
 * @param functionBlock    Abbreviation of the target function block (e.g.
 *                         {@code Lico}).
 * @param input            Abbreviation of the target input (e.g. {@code Lc1}).
 * @param value            Value to set (string for text, decimal for
 *                         analogue, {@code Pulse} for a one-shot trigger).
 */
public record MiniserverApiConnectorSetCommand(
        @JsonProperty( "virtual_input_text" ) String virtualInputText,
        @JsonProperty( "function_block" ) String functionBlock,
        @JsonProperty( "input" ) String input,
        @JsonProperty( "value" ) String value)
{
}
