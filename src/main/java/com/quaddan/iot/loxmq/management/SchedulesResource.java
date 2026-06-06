/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.management;

import com.quaddan.iot.loxmq.miniserver.admin.AdminCommandException;
import com.quaddan.iot.loxmq.miniserver.admin.AdminCommandTimeoutException;
import com.quaddan.iot.loxmq.miniserver.admin.CalendarMode;
import com.quaddan.iot.loxmq.miniserver.admin.ScheduleEntry;
import com.quaddan.iot.loxmq.miniserver.admin.ScheduleService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;

/**
 * REST surface for operating-mode schedule entries.
 * Sits at {@code /api/v1/schedules}.
 *
 * <p>Each handler delegates to {@link ScheduleService} which builds and
 * dispatches the corresponding {@code jdev/sps/calendar*} command via
 * the synchronous admin-command client. Exceptions from the client are
 * mapped to HTTP statuses :
 *
 * <ul>
 *   <li>{@link IllegalStateException} (session not RUNNING) → 503</li>
 *   <li>{@link IllegalArgumentException} (bad input) → 400</li>
 *   <li>{@link AdminCommandTimeoutException} (no reply in time) → 504</li>
 *   <li>{@link AdminCommandException} (everything else) → 502</li>
 * </ul>
 *
 * <p>Spec : {@code docs/loxone/OperatingModeSchedule.pdf} V14.4.
 */
@Path( "/api/v1/schedules" )
@ApplicationScoped
@Produces( MediaType.APPLICATION_JSON )
@Consumes( MediaType.APPLICATION_JSON )
@Tag( name = "Schedules",
      description = "CRUD for operating-mode calendar entries (Miniserver V14.4+)." )
public class SchedulesResource
{
    private static final Logger LOG = Logger.getLogger( SchedulesResource.class );

    @Inject
    ScheduleService schedules;

    @GET
    public Response list()
    {
        try
        {
            List< ScheduleEntry > entries = schedules.list();
            return Response.ok( entries ).build();
        }
        catch ( IllegalStateException e )
        {
            return error( Response.Status.SERVICE_UNAVAILABLE, "session-not-running", e.getMessage() );
        }
        catch ( AdminCommandTimeoutException e )
        {
            return error( Response.Status.GATEWAY_TIMEOUT, "miniserver-timeout", e.getMessage() );
        }
        catch ( AdminCommandException e )
        {
            return error( Response.Status.BAD_GATEWAY, "miniserver-error", e.getMessage() );
        }
    }

    @POST
    public Response create( ScheduleCreateRequest body )
    {
        if ( body == null )
        {
            return error( Response.Status.BAD_REQUEST, "missing-body",
                          "JSON body required: { name, operatingMode, calMode, calModeAttr }" );
        }
        try
        {
            CalendarMode mode = CalendarMode.fromCode( body.calMode() );
            schedules.create( body.name(), body.operatingMode(), mode, body.calModeAttr() );
            LOG.infof( "Schedule created: name=%s opMode=%d calMode=%s attr=%s",
                       body.name(), ( Integer ) body.operatingMode(), mode, body.calModeAttr() );
            return Response.status( Response.Status.CREATED )
                           .entity( Map.of( "status", "success" ) )
                           .build();
        }
        catch ( IllegalArgumentException e )
        {
            return error( Response.Status.BAD_REQUEST, "invalid-input", e.getMessage() );
        }
        catch ( IllegalStateException e )
        {
            return error( Response.Status.SERVICE_UNAVAILABLE, "session-not-running", e.getMessage() );
        }
        catch ( AdminCommandTimeoutException e )
        {
            return error( Response.Status.GATEWAY_TIMEOUT, "miniserver-timeout", e.getMessage() );
        }
        catch ( AdminCommandException e )
        {
            return error( Response.Status.BAD_GATEWAY, "miniserver-error", e.getMessage() );
        }
    }

    @PUT
    @Path( "/{uuid}" )
    public Response update( @PathParam( "uuid" ) String uuid, ScheduleCreateRequest body )
    {
        if ( body == null )
        {
            return error( Response.Status.BAD_REQUEST, "missing-body",
                          "JSON body required: { name, operatingMode, calMode, calModeAttr }" );
        }
        try
        {
            CalendarMode mode = CalendarMode.fromCode( body.calMode() );
            schedules.update( uuid, body.name(), body.operatingMode(), mode, body.calModeAttr() );
            LOG.infof( "Schedule updated: uuid=%s name=%s opMode=%d calMode=%s attr=%s",
                       uuid, body.name(), ( Integer ) body.operatingMode(), mode, body.calModeAttr() );
            return Response.ok( Map.of( "status", "success" ) ).build();
        }
        catch ( IllegalArgumentException e )
        {
            return error( Response.Status.BAD_REQUEST, "invalid-input", e.getMessage() );
        }
        catch ( IllegalStateException e )
        {
            return error( Response.Status.SERVICE_UNAVAILABLE, "session-not-running", e.getMessage() );
        }
        catch ( AdminCommandTimeoutException e )
        {
            return error( Response.Status.GATEWAY_TIMEOUT, "miniserver-timeout", e.getMessage() );
        }
        catch ( AdminCommandException e )
        {
            return error( Response.Status.BAD_GATEWAY, "miniserver-error", e.getMessage() );
        }
    }

    @DELETE
    @Path( "/{uuid}" )
    public Response delete( @PathParam( "uuid" ) String uuid )
    {
        try
        {
            schedules.delete( uuid );
            LOG.infof( "Schedule deleted: uuid=%s", uuid );
            return Response.ok( Map.of( "status", "success" ) ).build();
        }
        catch ( IllegalArgumentException e )
        {
            return error( Response.Status.BAD_REQUEST, "invalid-input", e.getMessage() );
        }
        catch ( IllegalStateException e )
        {
            return error( Response.Status.SERVICE_UNAVAILABLE, "session-not-running", e.getMessage() );
        }
        catch ( AdminCommandTimeoutException e )
        {
            return error( Response.Status.GATEWAY_TIMEOUT, "miniserver-timeout", e.getMessage() );
        }
        catch ( AdminCommandException e )
        {
            return error( Response.Status.BAD_GATEWAY, "miniserver-error", e.getMessage() );
        }
    }

    @GET
    @Path( "/heat-period" )
    public Response heatPeriod()
    {
        try
        {
            return Response.ok( Map.of( "period", schedules.getHeatPeriod() ) ).build();
        }
        catch ( IllegalStateException e )
        {
            return error( Response.Status.SERVICE_UNAVAILABLE, "session-not-running", e.getMessage() );
        }
        catch ( AdminCommandException e )
        {
            return error( Response.Status.BAD_GATEWAY, "miniserver-error", e.getMessage() );
        }
    }

    @GET
    @Path( "/cool-period" )
    public Response coolPeriod()
    {
        try
        {
            return Response.ok( Map.of( "period", schedules.getCoolPeriod() ) ).build();
        }
        catch ( IllegalStateException e )
        {
            return error( Response.Status.SERVICE_UNAVAILABLE, "session-not-running", e.getMessage() );
        }
        catch ( AdminCommandException e )
        {
            return error( Response.Status.BAD_GATEWAY, "miniserver-error", e.getMessage() );
        }
    }

    // ============================================================
    //  Helpers
    // ============================================================

    private static Response error( Response.Status status, String code, String message )
    {
        return Response.status( status )
                       .entity( Map.of( "status", "error",
                                        "code", code,
                                        "message", message == null ? "" : message ) )
                       .build();
    }

    /** Request body for create + update. Wrapped in a separate record so
     *  the same payload schema applies to both, and JAX-RS/Jackson
     *  doesn't have to deal with @QueryParam combinatorics. */
    public record ScheduleCreateRequest(
            String name,
            int operatingMode,
            int calMode,
            String calModeAttr)
    {
    }
}
