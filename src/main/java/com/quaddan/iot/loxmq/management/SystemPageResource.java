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
 * Server-rendered HTML page to run Miniserver SYSTEM / CAN-bus diagnostic
 * commands ({@code jdev/sys/*}, {@code jdev/bus/*}). Mounted at {@code /system}.
 *
 * <p>Thin shell — like {@link LogsPageResource} / {@link UsersPageResource}: the
 * page is HTML + vanilla JS that loads the curated command catalogue from
 * {@code /js/system-commands.json}, lets the operator pick a command, and POSTs
 * it to {@link ManagementResource#systemCommand} ({@code /api/v1/system-command}),
 * which returns the raw {@code LL} JSON reply. LAN-only, unauthenticated by
 * design (no bearer auth). Destructive commands are gated behind a confirm in
 * the JS. User and token-management commands are intentionally NOT listed here
 * (they have their own surfaces).
 */
@Path( "/system" )
@Produces( MediaType.TEXT_HTML )
@Tag( name = "System page",
      description = "HTML view to run Miniserver system / CAN-bus diagnostic commands." )
public class SystemPageResource
{
    @CheckedTemplate
    static class Templates
    {
        public static native TemplateInstance system();
    }

    /**
     * {@code GET /system} — renders the System page HTML shell. No template
     * parameters; the command catalogue is fetched client-side from
     * {@code /js/system-commands.json}.
     */
    @GET
    public TemplateInstance get()
    {
        return Templates.system();
    }
}
