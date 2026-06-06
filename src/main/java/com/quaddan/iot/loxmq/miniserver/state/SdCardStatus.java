/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.state;

import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Result of the on-device SD-card self-test ({@code jdev/sys/sdtest}).
 *
 * <p>The Miniserver replies with a single-line performance + health report
 * in {@code LL.value}, e.g.
 * <pre>
 *   SD Performance: Read: 7182kB/s, Write: 6808kB/s, No error (0 0),
 *   Usage: 0.00%, Used: 1%, …, PowerOnCycles: 187
 * </pre>
 *
 * <p>The health verdict is intentionally simple: the report contains the
 * literal marker {@code "No error"} on a healthy card. Its absence means the
 * device flagged a fault, so {@link #healthy()} is {@code false}. The report is
 * kept in {@link #detail()} so the operator still sees the read/write
 * throughput and usage figures on the dashboard — minus the internal
 * wear-levelling counters dropped by {@link #stripHiddenFields}.
 *
 * @param healthy   {@code true} when the report carries the {@code "No error"}
 *                  marker.
 * @param detail    the trimmed {@code LL.value} report with the internal
 *                  wear-levelling counters removed (see {@link #stripHiddenFields}).
 * @param checkedAt wall-clock instant the reply was parsed.
 */
public record SdCardStatus(boolean healthy, String detail, Instant checkedAt)
{
    /** Lower-cased marker present in a healthy-card report. */
    private static final String HEALTHY_MARKER = "no error";

    /**
     * Internal SD wear-levelling counters the Miniserver reports but which are
     * noise for an at-a-glance operator view: per-area usage and spare-block
     * figures. Dropped from {@link #detail()} so the dashboard keeps only the
     * actionable fields (throughput, the error marker, overall usage, ECC,
     * power-on cycles). Matched against the segment key (text before the colon),
     * so {@code "Used: 1%"} is preserved.
     */
    private static final Set< String > HIDDEN_FIELDS = Set.of(
            "UsedForSlcArea",
            "UsedForMlcArea",
            "UsedSlcSpareBlock",
            "UsedMlcSpareBlock" );

    /**
     * Parse the raw {@code LL.value} report into a status.
     *
     * @param value     the {@code LL.value} string (may be {@code null}).
     * @param checkedAt instant the reply was received.
     * @return a populated {@link SdCardStatus}; {@code healthy} is {@code true}
     *         only when {@code value} contains the {@code "No error"} marker.
     *         {@link #detail()} carries the report minus {@link #HIDDEN_FIELDS}.
     */
    public static SdCardStatus parse( String value, Instant checkedAt )
    {
        String  raw     = value == null ? "" : value.trim();
        boolean healthy = raw.toLowerCase( Locale.ROOT ).contains( HEALTHY_MARKER );
        return new SdCardStatus( healthy, stripHiddenFields( raw ), checkedAt );
    }

    /**
     * Drop the {@link #HIDDEN_FIELDS} segments from a comma-separated report,
     * preserving the original {@code "key: value, …"} layout for the rest. The
     * health verdict is computed from the full report before this runs, so
     * stripping never affects {@link #healthy()}.
     */
    private static String stripHiddenFields( String report )
    {
        if ( report.isEmpty() )
        { return report; }
        return Arrays.stream( report.split( "," ) )
                     .map( String::trim )
                     .filter( segment -> !HIDDEN_FIELDS.contains( keyOf( segment ) ) )
                     .collect( Collectors.joining( ", " ) );
    }

    /** The field key of a {@code "key: value"} segment — the text before the
     *  first colon, trimmed — or the whole segment when it carries no colon. */
    private static String keyOf( String segment )
    {
        int colon = segment.indexOf( ':' );
        return colon < 0 ? segment : segment.substring( 0, colon ).trim();
    }
}
