/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.admin;

/**
 * Reply payload from
 * {@link GroupMutationService#createGroup(EditGroupRequest)}.
 *
 * <p>Loxone {@code addeditgroup/{json}} without {@code uuid} in the JSON
 * creates a new group and returns {@code {"LL":{"value":{"uuid":"…",
 * "name":"…"}}}}. We expose just this subset on the REST side.
 *
 * @param uuid Loxone UUID allocated by the Miniserver
 * @param name display name actually registered
 */
public record CreatedGroup(String uuid, String name)
{
}
