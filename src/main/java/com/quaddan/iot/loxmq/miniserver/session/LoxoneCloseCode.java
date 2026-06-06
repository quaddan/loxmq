/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.session;

/**
 * WebSocket close codes documented by Loxone in
 * <i>Communicating with the Miniserver V17.0</i> (March 2026), sections
 * <i>Websocket Close Codes</i> and <i>What can go wrong</i>.
 * <p>
 * The standard RFC 6455 codes (1000-1015) are not enumerated here; they map
 * to {@link #STANDARD_OR_UNKNOWN}. Only the Loxone-specific {@code 4xxx}
 * range needs explicit handling — each carries a distinct recommended
 * client response.
 *
 * <h3>Close-code representation</h3>
 * This binding uses the JDK's {@link java.net.http.WebSocket}, which exposes
 * the close code as a bare {@code int} — {@link #from(int)} adapts it to this
 * enum.
 *
 * <h3>{@link ReconnectPolicy}</h3>
 * Each enum value carries the policy the reconnect scheduler should apply.
 * The values map roughly to "standard backoff", "wait a long time", or
 * "give up entirely" — see the constants' javadoc for the per-code
 * rationale.
 */
public enum LoxoneCloseCode
{
    /**
     * 4003 — Client blocked due to too many failed login attempts.
     * Per spec: <i>"If you are blocked due to too many failed login attempts,
     * the Miniserver will block you for a certain time. The WebSocket
     * closes right after it opens with a Close Code of 4003."</i>
     * Reconnecting immediately would only extend the block — long pause required.
     */
    LOGIN_BLOCKED(
            4003,
            "Login attempts blocked: too many failed logins. Miniserver has temporarily blacklisted this client. "
            + "Reconnecting too soon will extend the block.",
            ReconnectPolicy.LONG_PAUSE ),

    /**
     * 4004 — Some user (not necessarily the current one) was changed by an
     * administrator. The miniserver invalidates all sessions to force a
     * clean state. Safe to reconnect — the new handshake obtains a fresh token.
     */
    SOME_USER_CHANGED(
            4004,
            "Some user has been changed on the miniserver. Reconnecting with a fresh handshake.",
            ReconnectPolicy.NORMAL ),

    /**
     * 4005 — The user this binding authenticates as was changed (password,
     * permissions, etc.). Same recovery as 4004 — re-handshake with the
     * (presumably still valid) credentials.
     */
    CURRENT_USER_CHANGED(
            4005,
            "The user currently connected has been changed (by themself or by another user). "
            + "Reconnecting with a fresh handshake.",
            ReconnectPolicy.NORMAL ),

    /**
     * 4006 — The user this binding authenticates as has been disabled. Any
     * subsequent connection attempt is rejected with 401. Reconnecting is
     * pointless until an operator re-enables the user via Loxone Config.
     */
    CURRENT_USER_DISABLED(
            4006,
            "The user trying to establish a connection has been DISABLED on the miniserver. "
            + "Reconnection attempts will keep failing with 401 until the user is re-enabled in Loxone Config. "
            + "STAYING OFFLINE.",
            ReconnectPolicy.DO_NOT_RECONNECT ),

    /**
     * 4007 — Miniserver is performing a firmware update. It will be
     * unavailable for a few minutes. Reconnecting too aggressively wastes
     * attempts.
     */
    MINISERVER_UPDATING(
            4007,
            "Miniserver is currently performing an update and will be unavailable for a few minutes. "
            + "Pausing reconnection.",
            ReconnectPolicy.LONG_PAUSE ),

    /**
     * 4008 — Miniserver has no event slots left for this client. Per spec
     * the miniserver can serve at most 31 concurrent clients with live
     * status updates. Retry with a longer delay; may succeed once another
     * client disconnects.
     */
    NO_EVENT_SLOTS(
            4008,
            "Miniserver has no event slots available for this connection (max 31 concurrent clients). "
            + "Retrying with a longer delay.",
            ReconnectPolicy.LONG_PAUSE ),

    /**
     * RFC 6455 standard close codes (1000 normal, 1001 going away, 1006
     * abnormal, etc.) and anything we don't recognise. Standard backoff applies.
     */
    STANDARD_OR_UNKNOWN(
            -1,
            "Connection closed.",
            ReconnectPolicy.NORMAL );

    private final int             code;
    private final String          message;
    private final ReconnectPolicy policy;

    LoxoneCloseCode( int code, String message, ReconnectPolicy policy )
    {
        this.code    = code;
        this.message = message;
        this.policy  = policy;
    }

    public int code() { return code; }

    public String message() { return message; }

    public ReconnectPolicy policy() { return policy; }

    /**
     * Resolves a raw integer close code (as delivered by
     * {@link java.net.http.WebSocket.Listener#onClose}) to its Loxone-specific
     * meaning, or {@link #STANDARD_OR_UNKNOWN} for any code we don't model.
     * Always returns a non-null value.
     */
    public static LoxoneCloseCode from( int statusCode )
    {
        for ( LoxoneCloseCode lcc : values() )
        {
            if ( lcc.code == statusCode )
            {
                return lcc;
            }
        }
        return STANDARD_OR_UNKNOWN;
    }

    /**
     * What the reconnect scheduler should do in response to a given close
     * code.
     */
    public enum ReconnectPolicy
    {
        /**
         * Use the exponential-backoff ladder
         * ({@code loxone.miniserver.reconnect.*} config). Each consecutive
         * failure increases the wait via the multiplier, with a jitter
         * spread and a hard cap at {@code maxDelay}.
         */
        NORMAL,

        /**
         * Force the next-attempt delay to {@code maxDelay} regardless of
         * the current backoff position. Used when the miniserver explicitly
         * indicates a transient but non-trivial unavailability (firmware
         * update, slot exhaustion, login block).
         */
        LONG_PAUSE,

        /**
         * Do not schedule any further reconnection. Used when the miniserver
         * has indicated a condition the binding cannot recover from without
         * operator intervention (user disabled).
         */
        DO_NOT_RECONNECT
    }
}
