/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.admin;

import java.util.Map;

/**
 * One entry in the Miniserver's operating-mode schedule
 * (spec: {@code docs/loxone/OperatingModeSchedule.pdf}, V14.4, Nov 2023).
 *
 * <p>An entry activates a specific operating mode (e.g. "Vacances",
 * "Hors saison", "Confort") on dates matching a calendar pattern. The
 * Miniserver evaluates all entries and switches to the highest-priority
 * active one.
 *
 * <h3>Wire-shape asymmetry</h3>
 * The protocol is asymmetric between read and write:
 *
 * <ul>
 *   <li><b>Write</b> ({@code calendarcreateentry / calendarupdateentry})
 *       — the caller passes a single slash-delimited string
 *       ({@code "10/1/5/31"} for a yearly timespan, etc.). See
 *       {@link ScheduleService#create}/{@code update} which build the
 *       URL path from a {@code calModeAttr} parameter.</li>
 *   <li><b>Read</b> ({@code calendargetentries}) — the Miniserver
 *       returns each entry with the calMode-specific attributes
 *       <strong>expanded into named fields</strong> alongside
 *       {@code uuid / name / operatingMode / calMode}. Observed
 *       against a V17.0 Miniserver:
 *       <pre>
 *         {"uuid":"...", "name":"Période de chauffage", "operatingMode":10,
 *          "calMode":4, "startMonth":10, "startDay":1,
 *                       "endMonth":5,    "endDay":31}
 *
 *         {"uuid":"...", "name":"Lundi de Pâques", "operatingMode":0,
 *          "calMode":1, "easterOffset":1}
 *       </pre></li>
 * </ul>
 *
 * <p>This record therefore exposes the <strong>read</strong> shape:
 * the basic fields plus a {@code calModeAttrs} map of whatever
 * attributes the Miniserver included. The map keys are
 * {@code startYear / startMonth / startDay / endYear / endMonth /
 * endDay / easterOffset / weekDay / weekDayInMonth} (per spec V14.4
 * §"Calendar Mode Attributes"), but the actual present subset depends
 * on {@code calMode}. Defensive Map preserves forward-compat with
 * firmware that might add fields.
 *
 * @param uuid          stable identifier — required for update / delete.
 *                      Empty string for entries staged client-side before
 *                      a {@code calendarcreateentry}.
 * @param name          descriptive label shown in the dashboard
 * @param operatingMode integer ID referencing an operating mode declared
 *                      in the LoxAPP3 Structure File ({@code operatingModes}
 *                      block). The Miniserver returns IDs; the dashboard
 *                      resolves the human-readable name via the Structure
 *                      File at render time.
 * @param calMode       {@link CalendarMode#code()} 0-5 — when the entry
 *                      is active
 * @param calModeAttrs  map of calMode-specific attributes ({@code startMonth},
 *                      {@code startDay}, {@code endMonth}, {@code endDay},
 *                      {@code easterOffset}, {@code weekDay},
 *                      {@code weekDayInMonth}, {@code startYear},
 *                      {@code endYear}). Always non-null; empty when
 *                      the Miniserver didn't return any extra fields.
 */
public record ScheduleEntry(
        String uuid,
        String name,
        int operatingMode,
        int calMode,
        Map< String, Integer > calModeAttrs)
{
}
