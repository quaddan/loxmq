/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.session;

/**
 * Ordered states of the Miniserver WebSocket session.
 * <p>
 * The state machine runs all the way to {@link #RUNNING} — at that point the
 * LoxAPP3 structure file has been loaded (from cache or fresh download) and
 * the binding is receiving binary state-update frames from the miniserver,
 * which the binary-states decoder decodes.
 *
 * <h3>Order of declaration matches the order of transitions</h3>
 * <ol>
 *   <li>{@link #DISCONNECTED} — initial state, also re-entered after every clean close.</li>
 *   <li>{@link #CONNECTING} — JDK WebSocket open in progress (TCP handshake +
 *       WS upgrade).</li>
 *   <li>{@link #AWAITING_KEY_EXCHANGE_REPLY} — keyexchange command sent
 *       ({@code jdev/sys/keyexchange/{wrappedSessionKey}}); waiting for the
 *       miniserver to ack with a 200 Code.</li>
 *   <li>{@link #AWAITING_TOKEN_REPLY} — HTTP getkey2 done, encrypted getjwt
 *       sent; waiting for the JWT reply.</li>
 *   <li>{@link #AWAITING_STRUCTURE_VERSION_REPLY} — JWT acquired,
 *       {@code jdev/sps/LoxAPPversion3} probe sent; waiting for the version
 *       string used to check the on-disk cache.</li>
 *   <li>{@link #AWAITING_STRUCTURE_FILE} — cache miss; full
 *       {@code data/LoxAPP3.json} download in progress. Skipped on cache hit.</li>
 *   <li>{@link #AWAITING_STATUS_UPDATE_REPLY} — structure file loaded,
 *       {@code jdev/sps/enablebinstatusupdate} sent; waiting for the
 *       subscription ack.</li>
 *   <li>{@link #RUNNING} — handshake complete. The miniserver is pushing
 *       binary state-update frames, which the binary-states decoder decodes
 *       and republishes.</li>
 *   <li>{@link #FAILED} — any handshake step rejected, or any unexpected
 *       error. Last-error in {@code SessionTracker.lastError()}; the
 *       reconnect scheduler picks it up.</li>
 *   <li>{@link #CLOSED} — clean shutdown initiated by the operator
 *       (POST /api/v1/disconnect) or process shutdown.</li>
 * </ol>
 *
 * <h3>Why no {@code TOKEN_ACQUIRED} state</h3>
 * The post-token version probe fires immediately on the same WS reader
 * callback that parsed the token reply, so the session would only ever
 * spend microseconds in TOKEN_ACQUIRED before transitioning out. Folding
 * it away keeps the state machine focused on transitions an operator can
 * actually observe.
 */
public enum SessionState
{
    DISCONNECTED,
    CONNECTING,
    AWAITING_KEY_EXCHANGE_REPLY,
    AWAITING_TOKEN_REPLY,
    AWAITING_STRUCTURE_VERSION_REPLY,
    AWAITING_STRUCTURE_FILE,
    AWAITING_STATUS_UPDATE_REPLY,
    RUNNING,

    /**
     * A {@code jdev/sys/refreshjwt} command has been sent over
     * the WS and we're waiting for the new-token reply. Transient state —
     * loops back to {@link #RUNNING} on success or fails the handshake on
     * rejection (which the reconnect scheduler then picks up).
     * <p>
     * Binary state-event frames continue to arrive during this window;
     * they're handled exactly as if {@link #RUNNING} (the binary-states
     * decoder consumes them).
     */
    AWAITING_TOKEN_REFRESH_REPLY,

    FAILED,
    CLOSED
}
