/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.management;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.Map;

/**
 * Read-only snapshot of the last known value of every decoded state-UUID.
 *
 * <pre>
 *   GET /api/v1/state/current
 *   → { "&lt;state-UUID&gt;": { "formatted": "21.5°C", "unit": "°C", "ts": "…" }, … }
 * </pre>
 *
 * Backed by {@link LiveStateSnapshot}. Consumed by the {@code /commands} page to
 * show the current state of the selected control (kept live afterwards from the
 * SSE stream). The return type is a typed map, so Quarkus auto-registers
 * {@link LiveStateSnapshot.StateValue} for reflection — no manual
 * {@code NativeReflectionConfig} entry needed (unlike the manually-serialised
 * topology).
 *
 * <p>Class {@code @Path} is {@code /api/v1} (not {@code /api/v1/state}) with the
 * full sub-path on the method — same pattern as {@code StateStreamResource}: a
 * class-level {@code @Path("/api/v1/state")} would shadow
 * {@code ManagementResource}'s {@code GET /api/v1/state} and 404 it.
 */
@Path( "/api/v1" )
@Produces( MediaType.APPLICATION_JSON )
@Tag( name = "State snapshot",
      description = "Last known value per state-UUID — read-model for the Commands page." )
public class StateSnapshotResource
{
    @Inject
    LiveStateSnapshot snapshot;

    /**
     * {@code GET /api/v1/state/current} — returns the in-memory snapshot as a
     * JSON map keyed by state-UUID, each value a {@link LiveStateSnapshot.StateValue}
     * (the last known decoded value). Media type {@code application/json}.
     * LAN-only, unauthenticated.
     */
    @GET
    @Path( "/state/current" )
    @Operation( summary = "Snapshot of the last known value of every decoded state-UUID" )
    public Map< String, LiveStateSnapshot.StateValue > current()
    {
        return snapshot.snapshot();
    }
}
