/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.message;

import com.quaddan.iot.loxmq.miniserver.session.KeepAliveScheduler;

import java.time.Instant;

/**
 * Fired by {@code BinaryStatesDecoder} when an inbound binary frame
 * carries the {@code KEEP_ALIVE_RESPONSE} header identifier (Loxone
 * protocol type 6 — V17.0 §"Message Header" p.18).
 *
 * <p>The binding sends a {@code keepalive} text command periodically
 * from {@link KeepAliveScheduler}
 * once the session reaches {@code RUNNING}; the miniserver replies
 * with a header-only binary frame (no payload, the response is the
 * type-6 identifier itself). The scheduler observes this event to
 * compute the request → response round-trip time, which doubles as
 * a link-quality indicator (V17.0 §"Detecting issues").
 *
 * <h3>Correlation</h3>
 * The Loxone protocol does not carry correlation IDs. The pairing
 * rule is positional: the keepalive scheduler assumes the next
 * KEEP_ALIVE_RESPONSE arriving after a {@code sendText("keepalive")}
 * IS the response to that send. The single-threaded WebSocket reader
 * + the spec's frame-ordering guarantee make this safe in practice.
 *
 * @param arrivedAt wall-clock instant the response landed in the
 *                  binding's WS reader thread. Used by the scheduler
 *                  to compute {@code arrivedAt - lastSentAt = RTT}.
 */
public record MiniserverKeepAliveResponseEvent(Instant arrivedAt)
{
}
