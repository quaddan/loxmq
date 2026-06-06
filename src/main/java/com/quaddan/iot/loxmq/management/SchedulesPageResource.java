/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.management;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quaddan.iot.loxmq.miniserver.session.LoxApp3MetadataResolver;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import java.util.Map;

/**
 * Server-rendered HTML page for managing operating-mode schedule entries.
 * Mounted at {@code /schedules}.
 *
 * <p>The page is a thin shell : table + add/edit modal driven entirely
 * by client-side JS that talks to {@code /api/v1/schedules}. We don't
 * pre-render the schedule list server-side because:
 * <ul>
 *   <li>The CRUD surface is mutation-heavy → a server render goes stale
 *       the instant the operator clicks "Delete".</li>
 *   <li>{@code calendargetentries} is a Miniserver round-trip → keeping
 *       the page render path free of Miniserver dependency means the
 *       page <em>itself</em> always loads even if the session is
 *       transiently DISCONNECTED ; the table just shows an error state.</li>
 * </ul>
 *
 * <p>Same minimal-shell philosophy as {@code LiveStatesResource}, so the
 * Qute template stays simple (no Topology pre-serialization, no
 * native-reflection surface to register).
 *
 * <p>The LoxAPP3 {@code operatingModes} map is pre-serialised and
 * passed to the template so the form's "Operating mode" picker renders
 * a {@code <select>} with human-friendly names (e.g.
 * {@code "Au bureau — 0"}) instead of a bare number input. Empty map
 * (LoxAPP3 not yet loaded) → template falls back to the number input
 * client-side ; the page itself still renders.
 */
@Path( "/schedules" )
@Produces( MediaType.TEXT_HTML )
@Tag( name = "Schedules page",
      description = "HTML view to manage operating-mode calendar entries." )
public class SchedulesPageResource
{
    private static final Logger LOG = Logger.getLogger( SchedulesPageResource.class );

    @Inject
    LoxApp3MetadataResolver resolver;
    @Inject
    ObjectMapper            jsonMapper;

    @CheckedTemplate
    static class Templates
    {
        public static native TemplateInstance schedules( String opModesJson );
    }

    @GET
    public TemplateInstance get()
    {
        Map< Integer, String > opModes = resolver.operatingModes();
        String                 json;
        try
        {
            json = jsonMapper.writeValueAsString( opModes );
        }
        catch ( JsonProcessingException e )
        {
            // Should never happen — Map<Integer,String> is trivially
            // serialisable. Defensive : log + render with empty map so
            // the page itself still works (client falls back to <input>).
            LOG.warnf( e, "Failed to serialise operating modes — falling back to empty map" );
            json = "{}";
        }
        return Templates.schedules( json );
    }
}
