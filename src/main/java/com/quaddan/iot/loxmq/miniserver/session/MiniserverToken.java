/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.session;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Loxone JWT token + sidecar metadata returned by {@code jdev/sys/getjwt/...}.
 * <p>
 * Loxone's {@code validUntil} is encoded as <b>seconds since 2009-01-01 UTC</b>
 * — not the Unix epoch. {@link #expiresAt()} converts to a regular
 * {@link Instant} for downstream use (logging, refresh-timer scheduling,
 * dashboard rendering).
 *
 * <h3>Field meanings (per V17.0 §Authentication via tokens)</h3>
 * <ul>
 *   <li>{@code token}        — the JWT itself, used in subsequent
 *       {@code authwithtoken/{hash}/{user}} reconnects.</li>
 *   <li>{@code key}          — HMAC key the binding must use to sign the token
 *       on each reconnect (combined with the user salt from {@code getkey2}).</li>
 *   <li>{@code validUntil}   — token expiry, in "seconds since 2009-01-01 UTC".</li>
 *   <li>{@code tokenRights}  — bitfield mirror of the requested permission
 *       (typically {@code 4} = web/visualisation).</li>
 *   <li>{@code unsecurePass} — true if the user's password is considered weak
 *       by the miniserver; not actionable from the binding's side, just
 *       echoed to the dashboard for operator awareness.</li>
 * </ul>
 */
public record MiniserverToken(String token,
                              String key,
                              long validUntil,
                              int tokenRights,
                              boolean unsecurePass)
{
    /** {@code 2009-01-01T00:00:00Z} — Loxone's custom epoch base. */
    private static final Instant LOXONE_EPOCH = LocalDateTime.of( 2009, 1, 1, 0, 0, 0 )
                                                             .atZone( ZoneId.of( "UTC" ) )
                                                             .toInstant();

    /** Convert {@code validUntil} (Loxone seconds since 2009-01-01) to a regular {@link Instant}. */
    public Instant expiresAt()
    {
        return LOXONE_EPOCH.plusSeconds( validUntil );
    }

    /** True if the token's {@link #expiresAt()} is already in the past. */
    public boolean expired()
    {
        return Instant.now().isAfter( expiresAt() );
    }
}
