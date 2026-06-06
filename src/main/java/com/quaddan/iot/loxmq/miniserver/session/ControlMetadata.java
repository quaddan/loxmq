/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.session;

/**
 * Human-readable metadata about a Loxone Control — what {@code LoxApp3MetadataResolver}
 * resolves a state-UUID to.
 *
 * <p>Used by {@code LiveStateSseEnricher} to enrich the
 * {@code ValueStatesEvent} / {@code TextStatesEvent} payloads pushed to
 * the dashboard SSE stream: the operator sees "Salon / Température /
 * Sonde Salon = 21.5°C" instead of the raw state-UUID + value.
 *
 * <p>{@link #unknown()} is the sentinel returned by the resolver for
 * state-UUIDs not found in the current LoxAPP3 index — by decision
 * the live-states tab surfaces "UNKNOWN" + raw value rather
 * than silently dropping the event. Useful to debug stale cache /
 * structure-file regressions on a running miniserver.
 *
 * @param controlName e.g.: {@code "Sonde Salon"}; {@code "UNKNOWN"} on cache miss
 * @param roomName    e.g.: {@code "Salon"}      ; {@code "UNKNOWN"} on cache miss
 * @param catName     e.g.: {@code "Température"}; {@code "UNKNOWN"} on cache miss
 * @param format      e.g.: {@code "%.1f°C"}     ; {@code ""} if no format defined
 * @param unit        e.g.: {@code "°C"}         ; {@code ""} if format is empty or has no unit
 */
public record ControlMetadata(
        String controlName,
        String roomName,
        String catName,
        String format,
        String unit)
{
    private static final ControlMetadata UNKNOWN =
            new ControlMetadata( "UNKNOWN", "UNKNOWN", "UNKNOWN", "", "" );

    public static ControlMetadata unknown()
    {
        return UNKNOWN;
    }

    /** True iff this is the sentinel {@link #unknown()} singleton. Cheap
     *  identity check — used in tests + observability counters. */
    public boolean isUnknown()
    {
        return this == UNKNOWN;
    }
}
