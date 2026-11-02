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
 * "Commands" page — send a command to a known Loxone control, reusing the
 * same room → category → control cascade as {@code /states}, plus a per-type
 * command selector, a UUID filter, and a free-form command fallback.
 *
 * <h3>Topology + type</h3>
 * Receives the serialised {@link LoxApp3MetadataResolver.Topology} inline in a
 * {@code <script>} (same pattern + {@code </} escaping as
 * {@link LiveStatesResource}). Each control now carries its Loxone
 * {@code type} and command-target {@code uuid}, so the page can:
 * <ul>
 *   <li>populate the cascade dropdowns client-side,</li>
 *   <li>look up the selected control's valid commands in the static
 *       {@code /js/control-commands.json} registry by type, and</li>
 *   <li>filter the control list by UUID substring.</li>
 * </ul>
 *
 * <h3>Sending</h3>
 * The Send button POSTs {@code {uuid, command}} to
 * {@code POST /api/v1/commands}, which forwards it synchronously to the
 * Miniserver. No confirmation dialog — a click actuates the device directly.
 */
@Path( "/commands" )
@Produces( MediaType.TEXT_HTML )
@Tag( name = "Commands",
      description = "Send a command to a Loxone control — same device cascade as /states, "
                    + "with a per-type command selector + UUID filter." )
public class CommandsPageResource
{
    private static final Logger LOG = Logger.getLogger( CommandsPageResource.class );

    @CheckedTemplate
    static class Templates
    {
        public static native TemplateInstance commands( String topologyJson, String controlStatesJson );
    }

    @Inject
    LoxApp3MetadataResolver resolver;
    @Inject
    ObjectMapper            jsonMapper;

    /**
     * {@code GET /commands} — renders the Commands page HTML. Serialises the
     * {@link LoxApp3MetadataResolver.CommandSurface} topology and control
     * states to JSON (empty fallbacks on failure, {@code </} escaped) and
     * passes both inline to the Qute template.
     */
    @GET
    public TemplateInstance get()
    {
        LoxApp3MetadataResolver.CommandSurface surface = resolver.commandSurface();
        String                                 topoJson;
        String                                 statesJson;
        try
        {
            topoJson   = jsonMapper.writeValueAsString( surface.topology() );
            statesJson = jsonMapper.writeValueAsString( surface.controlStates() );
        }
        catch ( JsonProcessingException e )
        {
            LOG.warnf( e, "Failed to serialise command surface — falling back to empty topology + control states" );
            topoJson   = "{\"rooms\":[],\"categories\":[],\"controls\":[]}";
            statesJson = "{}";
        }
        // Escape `</` so a control name / UUID can't break out of the <script> context.
        topoJson   = topoJson.replace( "</", "<\\/" );
        statesJson = statesJson.replace( "</", "<\\/" );
        return Templates.commands( topoJson, statesJson );
    }
}
