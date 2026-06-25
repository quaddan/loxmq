/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.state;

import com.quaddan.iot.loxmq.miniserver.identity.MiniserverGeneration;
import com.quaddan.iot.loxmq.miniserver.identity.MiniserverIdentity;
import com.quaddan.iot.loxmq.miniserver.identity.MiniserverVersion;
import com.quaddan.iot.loxmq.miniserver.session.MiniserverConnectedEvent;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks, once per session, whether the Miniserver's installed firmware is the
 * latest Release published by Loxone — surfaced as a badge in the dashboard's
 * Miniserver Identity panel.
 *
 * <h3>What it does</h3>
 * On each {@link MiniserverConnectedEvent} (session reached RUNNING) it fetches
 * Loxone's public {@code updatecheck.xml}, picks the {@code <update>} block
 * matching the connected Miniserver's generation, reads its
 * {@code <LatestRelease Version="…">} — a packed 8-digit integer read in pairs
 * ({@code 17000331} → {@code 17.0.3.31}) — and compares it with the installed
 * {@link MiniserverVersion}. The result is stashed for {@link #status()}.
 *
 * <h3>Generation → update channel</h3>
 * <ul>
 *   <li>{@link MiniserverGeneration#GEN1} → {@code type="ms"}  (Name=LoxLIVE)</li>
 *   <li>{@link MiniserverGeneration#GEN2} → {@code type="ms2"} (Name=Miniserver)</li>
 * </ul>
 * The Miniserver Compact channel ({@code type="msc"}) is deliberately NOT
 * handled: the binding only distinguishes GEN1/GEN2 (derived from
 * {@code httpsStatus}), so a Compact is seen as GEN2 and compared against the
 * Gen2 line. Wire up Compact detection before relying on this for Compact units.
 *
 * <h3>External call — the one outbound dependency</h3>
 * Unlike the otherwise LAN-only binding, this reaches the public internet
 * ({@code https://update.loxone.com}). Strictly best-effort and isolated: any
 * failure (offline, timeout, unparseable) is swallowed and the dashboard simply
 * shows no firmware badge until a later check succeeds.
 *
 * <h3>Holder + observer pattern</h3>
 * Mirrors {@link SdCardHealthService}: {@code @ApplicationScoped @Startup}, an
 * {@code @ObservesAsync} hook on {@link MiniserverConnectedEvent}, the value in
 * an {@link AtomicReference}, and a read-only {@link Optional} accessor.
 */
@ApplicationScoped
@Startup
public class FirmwareUpdateService
{
    private static final Logger LOG = Logger.getLogger( FirmwareUpdateService.class );

    private static final String UPDATE_CHECK_URL = "https://update.loxone.com/updatecheck.xml";

    /** {@code <LatestRelease … Version="<digits>" …>} within a chosen channel block. */
    private static final Pattern LATEST_RELEASE =
            Pattern.compile( "<LatestRelease\\b[^>]*\\bVersion=\"(\\d+)\"" );

    @Inject
    MiniserverState miniserverState;

    private final HttpClient http = HttpClient.newBuilder()
                                              .connectTimeout( Duration.ofSeconds( 8 ) )
                                              .build();

    /** Latest-firmware comparison result. {@code null} until the first check. */
    private final AtomicReference< Status > statusRef = new AtomicReference<>();

    /** Outcome of a firmware-version check. */
    public record Status( MiniserverVersion latest, boolean upToDate, Instant checkedAt ) {}

    /** Session reached RUNNING → compare against the latest published firmware once. */
    void onMiniserverConnected( @ObservesAsync MiniserverConnectedEvent event )
    {
        try
        {
            Optional< MiniserverIdentity > idOpt = miniserverState.identity();
            if ( idOpt.isEmpty() )
            {
                return;   // no identity yet — nothing to compare against
            }
            MiniserverIdentity id        = idOpt.get();
            MiniserverVersion  installed = id.version();
            String             channel   = channelFor( id.generation() );

            Optional< MiniserverVersion > latestOpt = parseLatest( fetch(), channel );
            if ( latestOpt.isEmpty() )
            {
                LOG.warnf( "Could not read LatestRelease for channel '%s' from updatecheck.xml", channel );
                return;
            }
            MiniserverVersion latest   = latestOpt.get();
            boolean           upToDate = installed.compareTo( latest ) >= 0;
            statusRef.set( new Status( latest, upToDate, Instant.now() ) );
            LOG.infof( "Firmware check (%s): installed=%s latest=%s → %s",
                       channel, installed, latest, upToDate ? "up to date" : "UPDATE AVAILABLE" );
        }
        catch ( Exception e )
        {
            // Best-effort, must NEVER escape to the async-observer exception
            // handler. Keep the last-known status; the next connect retries.
            LOG.warnf( "Firmware update check failed — %s",
                       e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName() );
        }
    }

    /** Most recent firmware-version comparison, if one has completed.
     *  {@link Optional#empty()} before the first successful check (or if every
     *  attempt so far failed) — the dashboard then shows no firmware badge. */
    public Optional< Status > status()
    {
        return Optional.ofNullable( statusRef.get() );
    }

    // ============================================================
    //  Internals
    // ============================================================

    /** Map the detected hardware generation to Loxone's update-channel {@code type}. */
    private static String channelFor( MiniserverGeneration generation )
    {
        return generation == MiniserverGeneration.GEN1 ? "ms" : "ms2";
    }

    private String fetch() throws Exception
    {
        HttpRequest request = HttpRequest.newBuilder( URI.create( UPDATE_CHECK_URL ) )
                                         .timeout( Duration.ofSeconds( 10 ) )
                                         .header( "Accept", "application/xml" )
                                         .GET()
                                         .build();
        HttpResponse< String > response = http.send( request, HttpResponse.BodyHandlers.ofString() );
        if ( response.statusCode() != 200 )
        {
            throw new IllegalStateException( "updatecheck.xml HTTP " + response.statusCode() );
        }
        return response.body();
    }

    /** Find the {@code LatestRelease} version for {@code channel} and decode the
     *  packed 8-digit integer (read in pairs) into a {@link MiniserverVersion}:
     *  {@code 17000331} → {@code 17.0.3.31}. Integer math handles a major of any
     *  length (so {@code 9000331} → {@code 9.0.3.31} too). */
    private static Optional< MiniserverVersion > parseLatest( String xml, String channel )
    {
        Pattern block = Pattern.compile(
                "<update\\b[^>]*\\btype=\"" + Pattern.quote( channel ) + "\"[^>]*>(.*?)</update>",
                Pattern.DOTALL );
        Matcher mb = block.matcher( xml );
        if ( !mb.find() )
        {
            return Optional.empty();
        }
        Matcher mv = LATEST_RELEASE.matcher( mb.group( 1 ) );
        if ( !mv.find() )
        {
            return Optional.empty();
        }
        long packed = Long.parseLong( mv.group( 1 ) );
        int  build  = ( int ) ( packed % 100 );
        int  patch  = ( int ) ( ( packed / 100 ) % 100 );
        int  minor  = ( int ) ( ( packed / 10_000 ) % 100 );
        int  major  = ( int ) ( packed / 1_000_000 );
        return Optional.of( new MiniserverVersion( major, minor, patch, build ) );
    }
}
