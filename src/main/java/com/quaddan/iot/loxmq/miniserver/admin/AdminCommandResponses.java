/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.admin;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Static helpers shared by the admin services to interpret the Miniserver's
 * common reply envelope :
 *
 * <pre>
 *   { "LL": { "control": "...", "value": ..., "Code": "200" | "4xx" | "5xx" } }
 * </pre>
 *
 * <p>The {@code requireOk} + {@code decorateCodeError} surface lives
 * here rather than being duplicated across {@link ScheduleService} and
 * {@link UserMutationService} (which both write) and the read paths of
 * {@link ScheduleService#list} / {@link UserService#listUsers} / etc.
 *
 * <h3>Why read paths also need a Code check</h3>
 * Without it, read methods skip the Code field entirely and go straight
 * to parsing {@code value}. If the Miniserver returns
 * {@code "Code":"403", "value":""} (e.g. user lacks the
 * {@code User-Mgmt} permission bit), the empty {@code value} parses as
 * a non-array → silent fallback to empty list → REST returns 200 with
 * {@code []} → UI shows "✓ 0 users, 0 groups" → operator has no idea
 * the Miniserver was actually refusing.
 *
 * <p>Read methods funnel through {@link #requireOk} first ; a non-200
 * Code propagates as an {@link AdminCommandException} with the
 * decorated hint, the REST layer maps it to HTTP 502, and the dashboard
 * surface bar shows the actionable message.
 */
public final class AdminCommandResponses
{
    private AdminCommandResponses() { }

    /**
     * Throws an {@link AdminCommandException} if the reply's {@code Code}
     * field isn't {@code "200"}. Uses {@link #decorateCodeError} to enrich
     * common rejection codes (401/403/400/404) with a hint that points the
     * operator at the actual fix (Loxone Config permission change,
     * malformed input, etc.).
     *
     * @param ll    the {@code LL} sub-tree of the admin reply
     * @param label short label of the command (e.g. {@code "getuserlist2"})
     *              — used in the exception message
     */
    public static void requireOk( JsonNode ll, String label )
    {
        String code = ll.path( "Code" ).asText( "" );
        if ( !"200".equals( code ) )
        {
            throw new AdminCommandException(
                    decorateCodeError( code, label, ll.path( "value" ).asText( "" ) ) );
        }
    }

    /**
     * Format the Miniserver-rejection message with an actionable hint for
     * common error codes. Centralised so all admin services produce a
     * consistent message format.
     *
     * <p>Hints :
     * <ul>
     *   <li>{@code 401 / 403} — permission missing : point at Loxone
     *       Config + spec §"Required Rights".</li>
     *   <li>{@code 400} — context-aware : schedule commands
     *       (addschedule / updateschedule / addsubsched) get the
     *       calMode↔calModeAttr hint ; user-group endpoints like
     *       {@code assignusertogroup} with the idempotent "already
     *       assigned" payload get a friendlier "no-op" hint ; everything
     *       else gets a generic "malformed input" without misleading
     *       calMode reference.</li>
     *   <li>{@code 404} — entity gone : suggest concurrent deletion via
     *       Loxone Config.</li>
     * </ul>
     */
    public static String decorateCodeError( String code, String cmd, String value )
    {
        String hint = switch ( code )
        {
            case "401", "403" -> " — the Miniserver user authenticated by the "
                                 + "binding lacks the required permission. Verify "
                                 + "the user has admin rights in Loxone Config "
                                 + "(spec §\"Required Rights\", p. 13 of Usermanagement.pdf).";
            case "400" -> hint400( cmd, value );
            case "404" -> " — entity not found (UUID may have been removed "
                          + "concurrently in Loxone Config).";
            default -> "";
        };
        return "Miniserver rejected " + cmd + " : Code=" + code
               + ( value.isEmpty() ? "" : " value=" + value ) + hint;
    }

    /**
     * Context-aware 400 hint. A blanket "Check calMode" assumption would
     * be misleading because users / groups / NFC endpoints share this
     * code path with schedules ; only schedule commands deserve the
     * calMode↔calModeAttr hint.
     *
     * <p>Decision tree :
     * <ol>
     *   <li>If {@code value} contains "already assigned" (idempotent
     *       no-op for {@code assignusertogroup}), return a friendly
     *       no-op hint rather than an error tone.</li>
     *   <li>If {@code cmd} starts with a known schedule verb
     *       ({@code addschedule}, {@code updateschedule},
     *       {@code addsubsched}), keep the calMode hint.</li>
     *   <li>Otherwise, generic "malformed input" without misleading
     *       calMode reference.</li>
     * </ol>
     */
    private static String hint400( String cmd, String value )
    {
        if ( value != null && value.toLowerCase().contains( "already assigned" ) )
        {
            return " — already assigned (idempotent no-op). No change needed.";
        }
        if ( cmd != null
             && ( cmd.startsWith( "addschedule" )
                  || cmd.startsWith( "updateschedule" )
                  || cmd.startsWith( "addsubsched" ) ) )
        {
            return " — malformed input. Check calMode↔calModeAttr "
                   + "consistency (e.g. WEEKDAY needs 3 attrs, "
                   + "SPECIFIC_TIMESPAN needs 6).";
        }
        return " — malformed input (check the request payload against the spec).";
    }
}
