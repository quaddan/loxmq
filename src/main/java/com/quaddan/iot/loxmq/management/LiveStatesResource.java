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

/**
 * "Live states" page — dedicated full-screen view of Value + Text events
 * decoded in real-time, enriched via {@code LoxAPP3.json} to display
 * room / category / name / formatted value + unit.
 *
 * <h3>Why a separate page (vs dashboard tab-panel)</h3>
 * Operator decision: the live table can accumulate many rows (no FIFO
 * cap — the operator clears manually whenever they want), so sharing
 * the page with the dashboard's frozen state panels hurts readability.
 * Dedicated page = full screen for the table, and the header navigation
 * links the two pages.
 *
 * <h3>Pre-populating the filter dropdowns</h3>
 * The template receives a serialised JSON of the {@link LoxApp3MetadataResolver.Topology}
 * (sorted rooms, sorted categories, deduplicated controls) injected inline
 * into a {@code <script>} so the filter dropdowns are populated on first
 * render — without an XHR round-trip. The cascading filter
 * (room → visible categories → visible names) is computed client-side from
 * that snapshot.
 *
 * <h3>Security: JSON injection in &lt;script&gt;</h3>
 * We escape {@code </} → {@code <\/} sequences in the JSON to prevent
 * a malicious room name from the LoxAPP3 from breaking the browser's
 * script context. Standard precaution, free in CPU.
 *
 * <h3>EventSource browser-side</h3>
 * The page opens its own {@code EventSource('/api/v1/state/stream')}
 * (separate from the dashboard {@code /} which doesn't listen to state
 * events). No coupling between the two pages — the operator can have
 * both browser tabs open in parallel.
 */
@Path( "/states" )
@Produces( MediaType.TEXT_HTML )
@Tag( name = "Live states",
      description = "Full-screen view of Value + Text state updates in real time, "
                    + "enriched via LoxAPP3 (room / category / name / formatted value)." )
public class LiveStatesResource
{
    private static final Logger LOG = Logger.getLogger( LiveStatesResource.class );

    @CheckedTemplate
    static class Templates
    {
        public static native TemplateInstance states( String topologyJson );
    }

    @Inject
    LoxApp3MetadataResolver resolver;
    @Inject
    ObjectMapper            jsonMapper;

    @GET
    public TemplateInstance get()
    {
        LoxApp3MetadataResolver.Topology topology = resolver.topology();
        String                           json;
        try
        {
            json = jsonMapper.writeValueAsString( topology );
        }
        catch ( JsonProcessingException e )
        {
            LOG.warnf( e, "Failed to serialise topology — falling back to empty topology JSON" );
            json = "{\"rooms\":[],\"categories\":[],\"controls\":[]}";
        }
        // Security: escape `</` to prevent a name from accidentally
        // containing `</script>` and breaking the browser context. Not
        // a real attack vector here (the LoxAPP3 comes from the
        // Miniserver we control), but the measure is free.
        json = json.replace( "</", "<\\/" );
        return Templates.states( json );
    }
}
