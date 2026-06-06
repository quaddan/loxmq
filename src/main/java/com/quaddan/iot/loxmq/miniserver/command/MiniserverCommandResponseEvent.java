/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.command;

/**
 * Fired by {@code SessionOrchestrator} when a text frame arrives on the
 * WebSocket while the session is in {@code RUNNING} state — i.e. after the
 * handshake has finished, when the only expected text frames are
 * miniserver responses to encrypted commands the binding sent earlier
 * (and the occasional acks/keepalive responses, which are also text in
 * Loxone Miniserver V17).
 *
 * <p>Observed by {@code CommandResponsePublisher} which republishes the
 * raw response on the configured {@code command_response} topic.
 *
 * @param response Verbatim text frame from the miniserver — usually a
 *                 JSON envelope like {@code {"LL":{"control":"…","value":"…","Code":"200"}}}
 *                 but downstream consumers may parse however they want.
 */
public record MiniserverCommandResponseEvent(String response)
{
}
