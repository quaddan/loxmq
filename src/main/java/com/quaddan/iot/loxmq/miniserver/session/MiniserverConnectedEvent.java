/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.session;

import com.quaddan.iot.loxmq.miniserver.identity.MiniserverIdentity;

import java.time.Instant;
import java.util.Optional;

/**
 * Fired by {@code SessionOrchestrator} the moment the session transitions
 * to {@link SessionState#RUNNING} — handshake complete, status updates
 * enabled, binary state-events about to start flowing.
 *
 * <p>Observed by the publishers ({@code AppInfoPublisher},
 * {@code LoxApp3Publisher}) that broadcast retained metadata on the MQTT
 * broker so downstream consumers can pick up "binding online + here's the
 * topology" without polling. Single firing per RUNNING transition; on
 * reconnect the event fires again with a fresh timestamp.
 *
 * <p>{@code identity} is the {@link MiniserverIdentity} resolved during
 * bootstrap (serial, version, generation, httpsStatus). May be
 * {@link Optional#empty()} on a Miniserver that hasn't surfaced its
 * identity yet — defensive, the orchestrator only fires this event after
 * a successful handshake, which itself requires the identity to have
 * been resolved.
 *
 * @param timestamp wall-clock ms when RUNNING was reached (use this for
 *                  the {@code timestamp} field of the JSON payload
 *                  published downstream, not the Instant when the
 *                  observer runs).
 * @param identity  resolved miniserver identity at session-establish
 *                  time.
 */
public record MiniserverConnectedEvent(Instant timestamp,
                                       Optional< MiniserverIdentity > identity)
{
}
