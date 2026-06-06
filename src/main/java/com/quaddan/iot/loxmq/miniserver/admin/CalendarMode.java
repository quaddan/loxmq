/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.admin;

/**
 * Calendar match modes for an operating-mode schedule entry. Codes 0-5
 * per Loxone V14.4 spec (docs/loxone/OperatingModeSchedule.pdf §"Calendar
 * Mode & Calendar Mode Attributes").
 *
 * <p>The integer {@link #code()} is what the Miniserver expects in the
 * {@code calMode} field of {@code calendarcreateentry} /
 * {@code calendarupdateentry} commands.
 */
public enum CalendarMode
{
    /** Repeats every year on {@code <month>/<day>}. */
    YEARLY_DATE( 0 ),

    /** Repeats every year, offset N days from easter sunday
     *  (offset attribute can be negative). */
    EASTER( 1 ),

    /** One-shot date {@code <year>/<month>/<day>}. */
    SPECIFIC_DATE( 2 ),

    /** One-shot date range {@code <sy>/<sm>/<sd>/<ey>/<em>/<ed>}. */
    SPECIFIC_TIMESPAN( 3 ),

    /** Repeats every year between {@code <sm>/<ey>/<em>} (start day +
     *  end day evaluated yearly). */
    YEARLY_TIMESPAN( 4 ),

    /** Repeats on a weekday in a month, e.g. "first Monday in January".
     *  Attribute {@code <month>/<weekday>/<weekDayInMonth>} where
     *  weekday=0..6 (Mon=0), weekDayInMonth=0..5 (0=every, 1=first,
     *  5=last). Month=13 means every month. */
    WEEKDAY( 5 );

    private final int code;

    CalendarMode( int code )
    {
        this.code = code;
    }

    public int code()
    {
        return code;
    }

    /** Lookup by wire code. Throws {@link IllegalArgumentException} for
     *  unknown codes — defensive : a Miniserver returning a code &gt; 5
     *  would be a protocol violation we'd want to surface, not silently
     *  drop into some default. */
    public static CalendarMode fromCode( int code )
    {
        for ( CalendarMode m : values() )
        {
            if ( m.code == code )
            { return m; }
        }
        throw new IllegalArgumentException( "Unknown CalendarMode code: " + code );
    }
}
