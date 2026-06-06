/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.admin;

/**
 * Body for the password-update REST endpoints
 * ({@code POST /api/v1/users/{uuid}/auth/password},
 * {@code .../auth/visu-password},
 * {@code .../auth/access-code}).
 *
 * <p>One single field on purpose — Loxone V17 splits passwords vs.
 * access code into three distinct endpoints, but the body shape is
 * the same scalar value. Different REST paths surface the
 * intent ; one DTO keeps the wire format trivial.
 *
 * @param value the new plaintext value (password / visu-password / access code)
 */
public record UpdatePasswordRequest(String value)
{
}
