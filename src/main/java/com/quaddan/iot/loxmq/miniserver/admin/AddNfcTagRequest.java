/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.admin;

/**
 * Body for {@code POST /api/v1/users/{uuid}/nfc}.
 *
 * <p>{@code tagId} is the NFC UID (hex, optionally with {@code :}
 * separators) ; {@code name} is the operator-facing label shown next
 * to the tag in the Loxone Config / app UI ("front-door badge", etc.)
 * — optional, may be null/blank, in which case the Miniserver uses
 * the tagId as the display value.
 *
 * @param tagId NFC tag UID, required
 * @param name  friendly label, optional (≤ 100 chars)
 */
public record AddNfcTagRequest(String tagId, String name)
{
}
