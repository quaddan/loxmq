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
 * Server-rendered HTML log viewer. Mounted at {@code /logs}.
 *
 * <p>Thin shell — like {@link SchedulesPageResource} and
 * {@link UsersPageResource}, the page is HTML + vanilla JS that talks to
 * the REST surface ({@link LogsResource} at {@code /api/v1/logs}). No
 * server-side preload because the file content can change between the
 * GET on the page HTML and the operator clicking a file — fetching at
 * click time guarantees freshness.
 */
@Path( "/logs" )
@Produces( MediaType.TEXT_HTML )
@Tag( name = "Logs page",
      description = "HTML view to inspect binding log files." )
public class LogsPageResource
{
    @CheckedTemplate
    static class Templates
    {
        public static native TemplateInstance logs();
    }

    @GET
    public TemplateInstance get()
    {
        return Templates.logs();
    }
}
