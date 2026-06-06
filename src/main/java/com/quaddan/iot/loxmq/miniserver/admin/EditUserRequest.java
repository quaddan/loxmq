/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.admin;

/**
 * Editable fields of a user via {@code addedituser/{json}}.
 *
 * <p>All fields are {@code nullable} — only non-{@code null} values are sent
 * in the JSON, which avoids overwriting other attributes on the Miniserver
 * side. The V17 spec §"Create or Edit existing user" (p. 7) states:
 *
 * <blockquote>All other json attributes are optional.</blockquote>
 *
 * <p>Intentionally excluded from the record:
 * <ul>
 *   <li>{@code uuid} — passed separately to
 *       {@link UserMutationService#editUser(String, EditUserRequest)} as a
 *       path-param.</li>
 *   <li>{@code userGroups} — handled by dedicated endpoints
 *       ({@code assignToGroup} / {@code removeFromGroup}). The spec says
 *       <em>"When during editing a user and no groups-array is set, the
 *       group-assignment will remain unchanged"</em>, so we never send
 *       the array via {@code editUser}.</li>
 *   <li>{@code nfcTags} — handled by NFC endpoints.</li>
 *   <li>{@code isAdmin} — CANNOT be changed via {@code addedituser}
 *       directly (the Miniserver ignores it; the admin role comes from
 *       the "All Access" / "Loxone Config" groups on the Loxone Config side).</li>
 * </ul>
 *
 * @param name             login name (validated by the Miniserver, invalid
 *                         characters replaced by {@code _})
 * @param desc             free-form description
 * @param userid           external ID (badge, SIRET)
 * @param firstname        first name
 * @param lastname         last name
 * @param email
 * @param phone
 * @param uniqueUserId     external unique ID
 * @param company
 * @param department
 * @param personalno
 * @param title
 * @param debitor
 * @param customField1     slot 1 (label via {@code getcustomuserfields})
 * @param customField2     slot 2
 * @param customField3     slot 3
 * @param customField4     slot 4
 * @param customField5     slot 5
 * @param userState        0 active, 1 disabled, 2 enabled-until, 3 enabled-from,
 *                         4 timespan. See spec V17 §"userstate" p. 14.
 * @param validUntil       seconds since 2009-01-01 UTC (required if userState=2 or 4)
 * @param validFrom        seconds since 2009-01-01 UTC (required if userState=3 or 4)
 * @param expirationAction 0 = deactivate, 1 = delete (required if userState=2 or 4)
 * @param changePassword   true = the user must change their password at the next login
 */
public record EditUserRequest(
        String name,
        String desc,
        String userid,
        String firstname,
        String lastname,
        String email,
        String phone,
        String uniqueUserId,
        String company,
        String department,
        String personalno,
        String title,
        String debitor,
        String customField1,
        String customField2,
        String customField3,
        String customField4,
        String customField5,
        Integer userState,
        Long validUntil,
        Long validFrom,
        Integer expirationAction,
        Boolean changePassword)
{
}
