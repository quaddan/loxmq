/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.transport.subscribe;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

/**
 * Runtime on/off switch for processing <strong>inbound</strong> MQTT commands
 * (the {@code …/command} and {@code …/api} topics handled by
 * {@link CommandSubscriber}). Toggled by the operator from the Commands page
 * ({@code GET/PUT /api/v1/commands/inbound}).
 *
 * <p>When disabled, {@link CommandSubscriber} drops every received command with
 * a {@code DROP-DISABLED} verdict instead of forwarding it to the Miniserver —
 * a kill-switch for a misbehaving downstream automation without tearing down the
 * broker connection. It does <em>not</em> affect the Commands page's own
 * outbound sends ({@code POST /api/v1/commands}).
 *
 * <p>In-memory + {@code volatile} (written on a REST thread, read on HiveMQ
 * worker threads). State is NOT persisted: it resets to <em>enabled</em> on
 * restart.
 */
@ApplicationScoped
public class InboundCommandGate
{
    private static final Logger LOG = Logger.getLogger( InboundCommandGate.class );

    private volatile boolean enabled = true;

    public boolean isEnabled()
    {
        return enabled;
    }

    public void setEnabled( boolean value )
    {
        boolean previous = this.enabled;
        this.enabled = value;
        if ( previous != value )
        {
            LOG.warnf( "Inbound command processing %s — received MQTT commands on …/command and …/api are now %s.",
                       value ? "ENABLED" : "DISABLED",
                       value ? "forwarded to the Miniserver" : "dropped (DROP-DISABLED)" );
        }
    }
}
