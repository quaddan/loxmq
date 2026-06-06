/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.identity;

import java.util.Comparator;

/**
 * Loxone Miniserver firmware version, parsed from the {@code version} string
 * returned by {@code jdev/cfg/apiKey}.
 * <p>
 * Examples observed in the wild: {@code "12.2.11.5"}, {@code "15.6.05.11"},
 * {@code "17.0.1.0"}. The format is always four dot-separated integers:
 * {@code major.minor.patch.build}. Leading zeros are allowed
 * (Loxone occasionally ships {@code 15.6.05.11} with a zero-padded patch);
 * {@link Integer#parseInt(String)} handles them.
 *
 * <h3>Why a record</h3>
 * Immutable, value-based equality, trivially serialisable to JSON (Jackson
 * supports records out of the box). The companion {@link #parse(String)}
 * keeps the public surface focused.
 *
 * <h3>What this is NOT</h3>
 * The {@code version} field does NOT tell us Gen1 vs Gen2 hardware. Both
 * generations ship the same firmware version line — the only reliable way to
 * tell Gen1 apart is the absence of {@code httpsStatus} in the
 * {@code jdev/cfg/apiKey} response. See {@link HttpsStatus} and
 * {@link MiniserverGeneration}.
 */
public record MiniserverVersion(int major, int minor, int patch, int build)
        implements Comparable< MiniserverVersion >
{
    private static final Comparator< MiniserverVersion > NATURAL =
            Comparator.comparingInt( MiniserverVersion::major )
                      .thenComparingInt( MiniserverVersion::minor )
                      .thenComparingInt( MiniserverVersion::patch )
                      .thenComparingInt( MiniserverVersion::build );

    /**
     * Parses a four-part dotted version string. Throws
     * {@link IllegalArgumentException} for any input that does not match
     * exactly four non-negative integer parts.
     */
    public static MiniserverVersion parse( String raw )
    {
        if ( raw == null || raw.isBlank() )
        {
            throw new IllegalArgumentException( "version string is null or blank" );
        }
        String[] parts = raw.trim().split( "\\." );
        if ( parts.length != 4 )
        {
            throw new IllegalArgumentException(
                    "version string '" + raw + "' must have exactly four dot-separated parts" );
        }
        try
        {
            int major = Integer.parseInt( parts[ 0 ] );
            int minor = Integer.parseInt( parts[ 1 ] );
            int patch = Integer.parseInt( parts[ 2 ] );
            int build = Integer.parseInt( parts[ 3 ] );
            if ( major < 0 || minor < 0 || patch < 0 || build < 0 )
            {
                throw new IllegalArgumentException(
                        "version parts must be non-negative, got '" + raw + "'" );
            }
            return new MiniserverVersion( major, minor, patch, build );
        }
        catch ( NumberFormatException nfe )
        {
            throw new IllegalArgumentException(
                    "version string '" + raw + "' contains a non-integer part", nfe );
        }
    }

    @Override
    public int compareTo( MiniserverVersion other )
    {
        return NATURAL.compare( this, other );
    }

    /** {@code 15.6.05.11} → {@code "15.6.5.11"} (no zero-padding on output). */
    @Override
    public String toString()
    {
        return major + "." + minor + "." + patch + "." + build;
    }
}
