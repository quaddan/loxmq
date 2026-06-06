/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.admin;

/**
 * Editable fields of a group via {@code addeditgroup/{json}}.
 *
 * <p>Mirror semantics of {@link EditUserRequest} — all fields are
 * {@code nullable} and only non-{@code null} values are sent in the JSON,
 * which avoids overwriting other attributes on the Miniserver side.
 *
 * <p>Intentionally excluded from the record:
 * <ul>
 *   <li>{@code uuid} — passed separately to
 *       {@link GroupMutationService#editGroup(String, EditGroupRequest)}
 *       as a path-param.</li>
 *   <li>{@code type} / role bitmask / visualization permissions — these
 *       rich fields of the Loxone model are defined via Loxone Config
 *       for now. They may be exposed here later if usage warrants.</li>
 *   <li>membership (users in this group) — handled by the dedicated
 *       {@code assignusertogroup} / {@code removeuserfromgroup} endpoints.</li>
 * </ul>
 *
 * <p>Spec: {@code docs/loxone/1700_Usermanagement.pdf} V17 §"Add or
 * edit existing group".
 *
 * @param name        display name (required on create, optional on edit)
 * @param description free-form description
 */
public record EditGroupRequest(
        String name,
        String description)
{
}
