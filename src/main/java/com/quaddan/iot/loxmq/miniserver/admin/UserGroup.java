/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.admin;

/**
 * Configurable user group as returned by {@code jdev/sps/getgrouplist}
 * (spec {@code docs/loxone/1700_Usermanagement.pdf} V17, p. 6).
 *
 * <p>Loxone returns at least {@code name} + {@code uuid} per group ; the
 * full list of fields varies by firmware. We keep a defensive minimal
 * shape and let the dashboard display whatever is available — extra
 * fields end up in {@code descriptionOrEmpty} when they exist.
 *
 * @param uuid                 stable identifier
 * @param name                 group display name
 * @param descriptionOrEmpty   empty string when not provided by the Miniserver
 */
public record UserGroup(
        String uuid,
        String name,
        String descriptionOrEmpty)
{
}
