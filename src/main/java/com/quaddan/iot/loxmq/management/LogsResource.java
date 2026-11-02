/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.management;

import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * REST surface for the {@code /logs} HTML viewer page.
 *
 * <h3>Endpoints</h3>
 * <ul>
 *   <li>{@code GET /api/v1/logs} — JSON list of available log files with
 *       size and lastModified, sorted by mtime desc.</li>
 *   <li>{@code GET /api/v1/logs/{name}?tail=N} — text/plain content of
 *       a single log file. Default {@code tail=500} lines, max {@code 5000}
 *       to keep the browser-side responsive on a busy binding.</li>
 * </ul>
 *
 * <h3>Security — path traversal + filename whitelist</h3>
 * The viewer is meant for **operator self-service** so it can ship behind
 * the same TLS layer as the rest of the management UI, but it still must
 * not let a malformed {@code {name}} read arbitrary files on the LXC.
 * Two guards apply :
 * <ol>
 *   <li>{@code filename} is checked against {@link #ALLOWED_FILE_PATTERN}
 *       which only accepts {@code [a-z]+\.log(\.\d{4}-\d{2}-\d{2}|\.\d+)?} —
 *       handles {@code application.log}, {@code error.log},
 *       {@code application.log.1} (size-rotated backup) and the legacy
 *       {@code commands.log.2026-05-28} (date) form. Refuses anything with
 *       slashes, dots, parent-dir refs, or unexpected extensions.</li>
 *   <li>The resolved target path is checked via
 *       {@link java.nio.file.Path#startsWith(java.nio.file.Path)} against
 *       the canonical {@link #logDirectory()}. A would-be
 *       {@code "../../etc/passwd"} that somehow slipped past the pattern
 *       would still be rejected here.</li>
 * </ol>
 *
 * <h3>Tail strategy</h3>
 * For files under {@link #SMALL_FILE_THRESHOLD} bytes we read the whole
 * file then slice the last N lines — simple, fast, no edge cases. For
 * larger files we open the file via {@link RandomAccessFile} and seek
 * backward in 64 KB chunks until we've gathered {@code tail} newlines,
 * then return only the matching trailing window. Caps memory at the
 * tail size (no risk of OOM-ing on a 200 MB rotated log).
 */
@Path( "/api/v1/logs" )
@Produces( MediaType.APPLICATION_JSON )
@Tag( name = "Logs",
      description = "Operator log viewer — list files + tail content." )
public class LogsResource
{
    private static final Logger LOG = Logger.getLogger( LogsResource.class );

    /** Accepts {@code application.log}, {@code error.log},
     *  {@code application.log.1} (size-rotated backup) and the legacy
     *  {@code commands.log.2026-05-28} (date-rotated) form, but rejects
     *  anything with slashes, parent-dir refs, or unexpected extensions. */
    private static final Pattern ALLOWED_FILE_PATTERN =
            Pattern.compile( "[a-z]+\\.log(\\.\\d{4}-\\d{2}-\\d{2}|\\.\\d+)?" );

    /** A ROTATED (archived) log file: a base {@code name.log} followed by a
     *  <strong>required</strong> backup suffix ({@code .1}, {@code .2}, … or a
     *  {@code .2026-05-28} date). The active {@code name.log} (no suffix) does
     *  NOT match — so {@link #deleteOld()} can never remove a live log. */
    private static final Pattern ROTATED_FILE_PATTERN =
            Pattern.compile( "[a-z]+\\.log(\\.\\d{4}-\\d{2}-\\d{2}|\\.\\d+)" );

    /** Files below this size are read whole + sliced ; above we use a
     *  reverse-scan via RandomAccessFile. 10 MB is generous enough for
     *  most cases on a steady-state binding. */
    private static final long SMALL_FILE_THRESHOLD = 10L * 1024 * 1024;

    /** Hard upper bound on the {@code tail} query param. Prevents an
     *  accidental {@code ?tail=999999999} from blocking the JVM heap
     *  on a multi-million-line file. */
    private static final int MAX_TAIL_LINES = 5_000;

    /** Incremental-mode resync threshold. If a client's {@code since} offset is
     *  more than this far behind EOF (e.g. the tab was backgrounded while the
     *  log churned), skip replaying the backlog byte-for-byte and return a fresh
     *  tail instead (flagged {@code X-Log-Reset: true}). */
    private static final long RESYNC_LIMIT = 2L * 1024 * 1024;

    /** Resolved via the {@code quarkus.log.file.path} value
     *  ({@code ${LOG_DIR:logs}/application.log}). The parent directory
     *  of the main application log IS the log directory the binding
     *  writes to. All other handlers (errors, warns, commands, audit)
     *  share the same parent. */
    @ConfigProperty( name = "quarkus.log.file.path" )
    String applicationLogPath;

    /**
     * {@code GET /api/v1/logs} — list the whitelisted log files in the
     * log directory as JSON {@link LogFileEntry} records (name, size,
     * lastModified), sorted by mtime descending (active file first).
     * Returns an empty array if the log directory does not exist yet or
     * cannot be listed.
     *
     * @return JSON array of {@link LogFileEntry}
     */
    @GET
    public List< LogFileEntry > list()
    {
        java.nio.file.Path dir = logDirectory();
        if ( !Files.isDirectory( dir ) )
        {
            LOG.debugf( "Log directory %s does not exist yet (no logs produced) — returning empty list", dir );
            return List.of();
        }
        try ( Stream< java.nio.file.Path > files = Files.list( dir ) )
        {
            List< LogFileEntry > entries = new ArrayList<>();
            files.filter( Files::isRegularFile )
                 .filter( p -> ALLOWED_FILE_PATTERN.matcher( p.getFileName().toString() ).matches() )
                 .forEach( p ->
                           {
                               try
                               {
                                   entries.add( new LogFileEntry(
                                           p.getFileName().toString(),
                                           Files.size( p ),
                                           Files.getLastModifiedTime( p ).toMillis() ) );
                               }
                               catch ( IOException e )
                               {
                                   LOG.warnf( e, "Could not stat log file %s — skipped", p );
                               }
                           } );
            // mtime desc — most recently written first so the operator
            // sees the active file at the top.
            entries.sort( Comparator.comparingLong( LogFileEntry::lastModified ).reversed() );
            return entries;
        }
        catch ( IOException e )
        {
            LOG.warnf( e, "Could not list log directory %s — returning empty list", dir );
            return List.of();
        }
    }

    /**
     * {@code GET /api/v1/logs/{name}} — return {@code text/plain} content
     * of a single log file. Two modes:
     * <ul>
     *   <li>Full-tail ({@code since} absent / &lt; 0): last {@code tail}
     *       lines (default 500, clamped to [1, {@link #MAX_TAIL_LINES}]).</li>
     *   <li>Incremental ({@code since} &ge; 0): only the bytes appended past
     *       that byte offset, for zero-idle delta polling. If the file
     *       rotated/truncated or the client fell beyond {@link #RESYNC_LIMIT}
     *       behind, resync to a fresh tail flagged {@code X-Log-Reset: true}.</li>
     * </ul>
     * Responses carry {@code X-Log-File}, {@code X-Log-Size} (current EOF
     * offset — the next {@code since} cursor), plus {@code X-Log-Tail}
     * (full-tail mode) or {@code X-Log-Reset} (incremental mode).
     *
     * @param name  path param — filename, must match {@link #ALLOWED_FILE_PATTERN}
     * @param tail  query param — number of trailing lines (default 500)
     * @param since query param — byte offset for incremental mode (default -1 = full tail)
     * @return 200 with the content; 400 whitelist/path-escape rejection;
     *         404 file not found; 500 read failure.
     */
    @GET
    @Path( "/{name}" )
    @Produces( MediaType.TEXT_PLAIN )
    public Response tail( @PathParam( "name" ) String name,
                          @QueryParam( "tail" ) @DefaultValue( "500" ) int tail,
                          @QueryParam( "since" ) @DefaultValue( "-1" ) long since )
    {
        if ( !ALLOWED_FILE_PATTERN.matcher( name ).matches() )
        {
            return Response.status( Response.Status.BAD_REQUEST )
                           .type( MediaType.TEXT_PLAIN )
                           .entity( "filename rejected by whitelist" )
                           .build();
        }
        if ( tail < 1 )
        { tail = 1; }
        if ( tail > MAX_TAIL_LINES )
        { tail = MAX_TAIL_LINES; }

        java.nio.file.Path dir    = logDirectory();
        java.nio.file.Path target = dir.resolve( name ).normalize();
        // Defence in depth — even if the regex above missed something,
        // refuse paths that escape the log directory.
        if ( !target.startsWith( dir ) )
        {
            return Response.status( Response.Status.BAD_REQUEST )
                           .type( MediaType.TEXT_PLAIN )
                           .entity( "path escapes log directory" )
                           .build();
        }
        if ( !Files.isRegularFile( target ) )
        {
            return Response.status( Response.Status.NOT_FOUND )
                           .type( MediaType.TEXT_PLAIN )
                           .entity( "log file not found (may not be created yet — empty file = no events of that level so far)" )
                           .build();
        }
        try
        {
            long size = Files.size( target );

            // Incremental mode (?since=<byte offset>) — return ONLY the bytes
            // appended since the client's last read, so near-real-time polling
            // transfers deltas instead of re-reading the whole tail each tick.
            // The server does nothing unless the client polls → zero cost when
            // the page is closed.
            if ( since >= 0 )
            {
                boolean reset = since > size || ( size - since ) > RESYNC_LIMIT;
                // reset = file rotated/truncated, OR the client fell too far
                // behind (backgrounded tab) → resync to a fresh tail rather than
                // replay the whole backlog. Otherwise just the new bytes.
                String body = reset
                        ? tailFile( target, tail )
                        : new String( readRange( target, since, size ), StandardCharsets.UTF_8 );
                return Response.ok( body )
                               .type( MediaType.TEXT_PLAIN + ";charset=UTF-8" )
                               .header( "X-Log-File", name )
                               .header( "X-Log-Size", String.valueOf( size ) )
                               .header( "X-Log-Reset", String.valueOf( reset ) )
                               .build();
            }

            // Full-tail mode (initial load / manual refresh). X-Log-Size carries
            // the current EOF offset so the client can start incremental polling.
            String content = tailFile( target, tail );
            return Response.ok( content )
                           .type( MediaType.TEXT_PLAIN + ";charset=UTF-8" )
                           .header( "X-Log-File", name )
                           .header( "X-Log-Tail", String.valueOf( tail ) )
                           .header( "X-Log-Size", String.valueOf( size ) )
                           .build();
        }
        catch ( IOException e )
        {
            LOG.warnf( e, "Failed to read %s", target );
            return Response.serverError().entity( "read failed: " + e.getMessage() ).build();
        }
    }

    /** Convenience full-tail overload ({@code since = -1}) — package-private,
     *  not a JAX-RS endpoint; used by tests and internal call sites. */
    Response tail( String name, int tail )
    {
        return tail( name, tail, -1L );
    }

    /** Read bytes {@code [from, to)} of a file. {@code to - from} is bounded by
     *  {@link #RESYNC_LIMIT} at the single call site, so the int cast is safe. */
    private static byte[] readRange( java.nio.file.Path file, long from, long to ) throws IOException
    {
        int len = ( int ) Math.max( 0L, to - from );
        byte[] buf = new byte[ len ];
        if ( len == 0 )
        { return buf; }
        try ( RandomAccessFile raf = new RandomAccessFile( file.toFile(), "r" ) )
        {
            raf.seek( from );
            raf.readFully( buf );
        }
        return buf;
    }

    /**
     * Delete every ROTATED (archived) log file — {@code application.log.1},
     * {@code commands.log.2026-05-28}, … — from the log directory. The active
     * logs ({@code application.log}, {@code error.log}, …) are kept: the
     * {@link #ROTATED_FILE_PATTERN} only matches a {@code .log} name WITH a
     * backup suffix. Returns the count + bytes freed.
     */
    @DELETE
    @Path( "/old" )
    public Response deleteOld()
    {
        java.nio.file.Path dir = logDirectory();
        if ( !Files.isDirectory( dir ) )
        {
            return Response.ok( Map.of( "deleted", 0, "freedBytes", 0L ) ).build();
        }
        int  deleted = 0;
        long freed   = 0L;
        try ( Stream< java.nio.file.Path > files = Files.list( dir ) )
        {
            List< java.nio.file.Path > rotated = files
                    .filter( Files::isRegularFile )
                    .filter( p -> ROTATED_FILE_PATTERN.matcher( p.getFileName().toString() ).matches() )
                    .toList();
            for ( java.nio.file.Path p : rotated )
            {
                // Defence in depth — never step outside the log directory.
                if ( !p.normalize().startsWith( dir ) )
                { continue; }
                try
                {
                    long sz = Files.size( p );
                    Files.delete( p );
                    deleted++;
                    freed += sz;
                    LOG.infof( "Deleted rotated log %s (%d bytes)", p.getFileName(), ( Long ) sz );
                }
                catch ( IOException e )
                {
                    LOG.warnf( e, "Could not delete rotated log %s — skipped", p );
                }
            }
        }
        catch ( IOException e )
        {
            LOG.warnf( e, "Could not list log directory %s for cleanup", dir );
            return Response.serverError()
                           .entity( Map.of( "status", "error", "reason", String.valueOf( e.getMessage() ) ) )
                           .build();
        }
        LOG.infof( "Deleted %d rotated log file(s), freed %d bytes", ( Integer ) deleted, ( Long ) freed );
        return Response.ok( Map.of( "deleted", deleted, "freedBytes", freed ) ).build();
    }

    /**
     * Resolve the log directory from the configured main-log path. All
     * five handlers (application, errors, warns, commands, audit) share
     * the same {@code ${LOG_DIR:logs}/} parent ; deriving from
     * application.log is the single source of truth.
     */
    java.nio.file.Path logDirectory()
    {
        java.nio.file.Path p = Paths.get( applicationLogPath ).toAbsolutePath().normalize();
        return p.getParent() != null ? p.getParent() : Paths.get( "." ).toAbsolutePath();
    }

    /** Strategy switch — small file: read-all + slice. Large file:
     *  reverse-scan to skip the head. */
    private String tailFile( java.nio.file.Path file, int n ) throws IOException
    {
        long size = Files.size( file );
        if ( size <= SMALL_FILE_THRESHOLD )
        {
            List< String > all  = Files.readAllLines( file, StandardCharsets.UTF_8 );
            int            from = Math.max( 0, all.size() - n );
            return String.join( "\n", all.subList( from, all.size() ) );
        }
        return reverseScanTail( file, n );
    }

    /**
     * Read the file from the end, 64 KB at a time, accumulating bytes
     * until we've collected at least {@code n} newlines (plus a margin
     * for the partial line we landed in). Then decode + split + return
     * the last {@code n} lines.
     */
    private String reverseScanTail( java.nio.file.Path file, int n ) throws IOException
    {
        final int chunk = 64 * 1024;
        try ( RandomAccessFile raf = new RandomAccessFile( file.toFile(), "r" ) )
        {
            long   pos      = raf.length();
            int    newlines = 0;
            byte[] buf      = new byte[ chunk ];
            // Read chunks backwards until we have enough newlines or hit BOF.
            java.io.ByteArrayOutputStream acc = new java.io.ByteArrayOutputStream();
            while ( pos > 0 && newlines <= n )
            {
                int read = ( int ) Math.min( chunk, pos );
                pos -= read;
                raf.seek( pos );
                raf.readFully( buf, 0, read );
                // Count newlines in this chunk.
                for ( int i = 0; i < read; i++ )
                {
                    if ( buf[ i ] == '\n' )
                    { newlines++; }
                }
                // Prepend this chunk to the accumulator.
                java.io.ByteArrayOutputStream next = new java.io.ByteArrayOutputStream( acc.size() + read );
                next.write( buf, 0, read );
                acc.writeTo( next );
                acc = next;
            }
            String   text  = acc.toString( StandardCharsets.UTF_8 );
            String[] lines = text.split( "\n", -1 );
            int      from  = Math.max( 0, lines.length - n );
            // Drop the last empty element if the file ended with \n.
            int to = lines.length;
            if ( to > 0 && lines[ to - 1 ].isEmpty() )
            { to--; }
            if ( from >= to )
            { return ""; }
            return String.join( "\n", List.of( lines ).subList( from, to ) );
        }
    }

    /** JSON record for the file-list endpoint. */
    public record LogFileEntry(String name, long size, long lastModified)
    {
    }
}
