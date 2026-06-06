/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.admin;

/**
 * Summary view of a Miniserver user — as returned by
 * {@code jdev/sps/getuserlist2} (spec
 * {@code docs/loxone/1700_Usermanagement.pdf} V17, p. 4).
 *
 * <p>Just the high-signal fields for the dashboard table : name, UUID,
 * admin flag, state. The rich detail object lives in {@link UserDetail}
 * and is fetched lazily when the operator opens a single user.
 *
 * @param uuid              stable identifier
 * @param name              login name
 * @param isAdmin           true ↔ user has admin rights
 * @param userState         lifecycle state — meaningful values per Loxone :
 *                          0 = active, 1 = inactive / disabled,
 *                          2 = blocked. Higher values are reserved by
 *                          Loxone and treated as "unknown" downstream.
 * @param expirationAction  optional code (0 = deactivate, 1 = delete)
 *                          set on time-limited users. Null when absent
 *                          in the wire response (most users).
 */
public record User(
        String uuid,
        String name,
        boolean isAdmin,
        int userState,
        Integer expirationAction)
{
}
