/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.admin;

/**
 * Reply payload from {@link UserMutationService#createUser(EditUserRequest)}.
 *
 * <p>Loxone {@code addedituser/{json}} without {@code uuid} in the JSON
 * creates a new user and returns {@code {"LL":{"value":{"uuid":"…",
 * "name":"…"}}}}. We expose just this subset on the REST side — the
 * client can then issue a GET on {@code /api/v1/users/{uuid}} to fetch
 * the full detail.
 *
 * <p>Spec: {@code docs/loxone/1700_Usermanagement.pdf} V17 §"Create or
 * Edit existing user" p. 7.
 *
 * @param uuid Loxone UUID allocated by the Miniserver
 * @param name login name actually registered (may differ from the requested
 *             name if invalid characters were replaced by {@code _})
 */
public record CreatedUser(String uuid, String name)
{
}
