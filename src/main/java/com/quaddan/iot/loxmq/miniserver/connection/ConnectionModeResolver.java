/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.connection;

import com.quaddan.iot.loxmq.config.LoxoneConfig;
import com.quaddan.iot.loxmq.miniserver.identity.HttpsStatus;
import com.quaddan.iot.loxmq.miniserver.identity.MiniserverIdentity;
import com.quaddan.iot.loxmq.miniserver.state.MiniserverState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;

/**
 * Pure function: decides whether the binding should talk to the Miniserver
 * in {@link ConnectionMode#PLAIN} or {@link ConnectionMode#SECURE}, given
 * the operator's preference and the miniserver's own TLS readiness.
 *
 * <h3>Decision matrix</h3>
 * <table>
 *   <caption>preference × identity → effective mode</caption>
 *   <tr><th>config.secure</th><th>bootstrap-prefer-secure</th><th>identity</th><th>httpsStatus</th><th>effective</th><th>downgrade?</th></tr>
 *   <tr><td>false</td><td>any</td>  <td>any</td>    <td>any</td>      <td>PLAIN</td>  <td>—</td></tr>
 *   <tr><td>true</td> <td>false</td><td>empty</td>  <td>—</td>        <td>PLAIN</td>  <td>conservative — bootstrap pending</td></tr>
 *   <tr><td>true</td> <td>true</td> <td>empty</td>  <td>—</td>        <td>SECURE</td> <td>trust operator pre-bootstrap</td></tr>
 *   <tr><td>true</td> <td>any</td>  <td>present</td><td>SUPPORTED</td><td>SECURE</td> <td>no</td></tr>
 *   <tr><td>true</td> <td>any</td>  <td>present</td><td>ABSENT</td>   <td>PLAIN</td>  <td>yes — Gen1 has no TLS</td></tr>
 *   <tr><td>true</td> <td>any</td>  <td>present</td><td>EXPIRED</td>  <td>PLAIN</td>  <td>yes — cert expired</td></tr>
 *   <tr><td>true</td> <td>any</td>  <td>present</td><td>UNKNOWN</td>  <td>PLAIN</td>  <td>yes — defensive</td></tr>
 * </table>
 *
 * <h3>Why no logging here</h3>
 * The resolver is a pure function called from the dashboard, the readiness
 * check, the {@code /api/v1/state} endpoint, and the handshake
 * orchestrator. Logging on every call would be noisy. Identity transitions
 * are logged by {@link MiniserverState#update}; the human-readable
 * "downgrade reason" is exposed via {@link #downgradeReason()} so the
 * dashboard and the orchestrator can surface it explicitly to the operator.
 */
@ApplicationScoped
public class ConnectionModeResolver
{
    @Inject
    LoxoneConfig config;

    @Inject
    MiniserverState state;

    /** Operator preference, straight from {@code loxone.miniserver.connection.secure}. */
    public boolean preferred()
    {
        return config.miniserver().connection().secure();
    }

    /** Effective mode after applying the decision matrix. Never null. */
    public ConnectionMode effective()
    {
        if ( !preferred() )
        {
            return ConnectionMode.PLAIN;
        }
        Optional< MiniserverIdentity > identity = state.identity();
        if ( identity.isEmpty() )
        {
            // Bootstrap call has not landed yet. Two behaviours depending on
            // the operator opt-in:
            //
            //   bootstrap-prefer-secure=false (default — conservative):
            //     return PLAIN. The first HTTP call goes out in plain HTTP,
            //     we discover httpsStatus from the response, and re-resolve
            //     to SECURE for subsequent calls. Right default when the
            //     Miniserver port serves both HTTP and HTTPS on the same
            //     listener (legacy "443 everywhere" Loxone Config).
            //
            //   bootstrap-prefer-secure=true (opt-in):
            //     return SECURE. The first HTTP call goes out as HTTPS. Right
            //     for setups where the configured port is TLS-only — Gen2
            //     miniservers with separate HTTP (:80) and HTTPS (:443)
            //     listeners, when the binding is pointed at :443 to force
            //     TLS end-to-end.
            return config.miniserver().connection().bootstrapPreferSecure()
                   ? ConnectionMode.SECURE
                   : ConnectionMode.PLAIN;
        }
        return ( identity.get().httpsStatus() == HttpsStatus.SUPPORTED )
               ? ConnectionMode.SECURE
               : ConnectionMode.PLAIN;
    }

    /**
     * Returns a non-empty operator-friendly reason when the effective mode is
     * {@link ConnectionMode#PLAIN} despite the operator having preferred
     * SECURE. Used by the dashboard and {@code /api/v1/state} to explain WHY
     * the binding fell back. Returns {@link Optional#empty()} when no
     * downgrade happened (preference honoured, or operator did not prefer
     * secure in the first place).
     */
    public Optional< String > downgradeReason()
    {
        if ( !preferred() || effective() == ConnectionMode.SECURE )
        {
            return Optional.empty();
        }
        Optional< MiniserverIdentity > identity = state.identity();
        if ( identity.isEmpty() )
        {
            // Only reachable here when bootstrap-prefer-secure=false (the
            // opt-in branch returns SECURE → not a downgrade).
            return Optional.of( "Bootstrap pending — first call goes out in HTTP "
                                + "(set loxone.miniserver.connection.bootstrap-prefer-secure=true "
                                + "to force HTTPS pre-bootstrap on TLS-only listeners). "
                                + "Re-evaluated once jdev/cfg/apiKey lands." );
        }
        HttpsStatus status = identity.get().httpsStatus();
        return switch ( status )
        {
            case ABSENT -> Optional.of( "Miniserver is Gen1 — no TLS support in hardware. Plain HTTP/WS is the only option." );
            case EXPIRED -> Optional.of( "Miniserver Gen2 has an EXPIRED certificate. Reinstall via Loxone Config (see README §TLS) — the cert path is /etc/letsencrypt/live/example.com/." );
            case UNKNOWN -> Optional.of( "Miniserver reported an unknown httpsStatus value. Defensive downgrade to plain — please report this with the raw jdev/cfg/apiKey response." );
            case SUPPORTED -> Optional.empty();                  // resolved to SECURE in effective(), unreachable here
        };
    }
}
