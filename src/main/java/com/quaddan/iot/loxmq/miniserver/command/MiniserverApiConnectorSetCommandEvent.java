/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.command;

/**
 * Fired when a JSON payload arrives on the configured {@code api} topic
 * (API Connector SET). Same outbound path as {@link MiniserverCommandEvent}.
 */
public record MiniserverApiConnectorSetCommandEvent(MiniserverApiConnectorSetCommand apiConnector)
{
}
