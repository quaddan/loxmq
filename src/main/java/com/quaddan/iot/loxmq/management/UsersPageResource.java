/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.management;

import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Server-rendered HTML page listing Miniserver users + groups.
 * Mounted at {@code /users}.
 *
 * <p>Same minimal-shell philosophy as {@code SchedulesPageResource} —
 * the template renders without contacting the Miniserver, and the table
 * is populated client-side via {@code GET /api/v1/users-snapshot}.
 * Decouples the page render path from the session state : the page
 * always loads, the table shows an error state if the session is down.
 */
@Path( "/users" )
@Produces( MediaType.TEXT_HTML )
@Tag( name = "Users page",
      description = "HTML view of the Miniserver user / group configuration." )
public class UsersPageResource
{
    @CheckedTemplate
    static class Templates
    {
        public static native TemplateInstance users();
    }

    @GET
    public TemplateInstance get()
    {
        return Templates.users();
    }
}
