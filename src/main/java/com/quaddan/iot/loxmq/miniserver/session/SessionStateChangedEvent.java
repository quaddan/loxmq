/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.session;

import java.time.Instant;

/**
 * Fired by {@link SessionTracker#transition} whenever the binding's
 * miniserver session moves from one state to another. Consumers
 * (dashboard SSE broadcaster, future audit log) observe this to push an
 * update to the operator without polling {@code /api/v1/state} on a
 * timer.
 *
 * <p>The {@code at} field is the wall-clock instant of the transition —
 * same value {@code SessionTracker.stateChangedAt()} reports — so a
 * downstream consumer can detect coalesced events (e.g. CONNECTING →
 * FAILED → CONNECTING in rapid succession) by their timestamps if
 * needed.
 *
 * @param previous   the state we just left (never null — initial state
 *                   is {@link SessionState#DISCONNECTED}).
 * @param current    the state we just entered.
 * @param at         transition wall-clock instant.
 * @param lastError  the operator-visible last-error message, when the
 *                   transition is to {@link SessionState#FAILED} or
 *                   {@link SessionState#DISCONNECTED} with a known
 *                   cause; null otherwise. Mirrors
 *                   {@link SessionTracker#lastError()} read at the
 *                   moment of firing.
 */
public record SessionStateChangedEvent(
        SessionState previous,
        SessionState current,
        Instant at,
        String lastError)
{
}
