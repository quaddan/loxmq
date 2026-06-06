/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.admin;

import java.util.List;

/**
 * Rich detail object for a Miniserver user — returned by
 * {@code jdev/sps/getuser/{uuid}} (spec
 * {@code docs/loxone/1700_Usermanagement.pdf} V17, p. 5).
 *
 * <p>Loxone exposes ~20 fields per user (firstname, lastname, email,
 * phone, company, department, custom fields 1-5, etc.). We keep them
 * all in this record for transparency — the dashboard can display any
 * subset, the REST surface returns the full set.
 *
 * <p>Empty strings are preserved as-is (rather than mapped to null) so
 * that JSON serialization matches the Miniserver's verbatim payload —
 * useful for round-tripping data later if we ever expose write APIs.
 *
 * @param uuid           Loxone UUID
 * @param name           login name
 * @param desc           free-form description (operator-set)
 * @param userid         external user ID (badge number, SIRET, etc.)
 * @param firstname      given name
 * @param lastname       surname
 * @param email          email address
 * @param phone          phone number
 * @param uniqueUserId   externally-supplied unique ID (employee ID etc.)
 * @param company        company / org
 * @param department     department
 * @param personalno     personal number
 * @param title          job title
 * @param debitor        debitor reference (billing context)
 * @param customField1   operator-defined slot 1
 * @param customField2   operator-defined slot 2
 * @param customField3   operator-defined slot 3
 * @param customField4   operator-defined slot 4
 * @param customField5   operator-defined slot 5
 * @param lastedit       unix timestamp (seconds) of last edit
 * @param userState      per V17 spec p.14 : 0 = enabled no-limit, 1 = disabled,
 *                       2 = enabled until, 3 = enabled from, 4 = timespan
 * @param validUntil     Loxone epoch (seconds since 2009-01-01 UTC). Set when
 *                       userState ∈ 2 / 4. null otherwise.
 * @param validFrom      Loxone epoch (seconds since 2009-01-01 UTC). Set when
 *                       userState ∈ 3 / 4. null otherwise.
 * @param expirationAction action at expiration : 0 = Deactivate, 1 = Delete.
 *                       Set when userState ∈ 2 / 4. null otherwise.
 * @param isAdmin        true ↔ admin rights
 * @param changePassword true ↔ user must change password at next login
 * @param userGroups     UUIDs of the groups this user belongs to (may be empty)
 * @param nfcTags        NFC tag IDs assigned to this user (may be empty)
 */
public record UserDetail(
        String uuid,
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
        long lastedit,
        int userState,
        Long validUntil,
        Long validFrom,
        Integer expirationAction,
        boolean isAdmin,
        boolean changePassword,
        List< String > userGroups,
        List< String > nfcTags)
{
}
