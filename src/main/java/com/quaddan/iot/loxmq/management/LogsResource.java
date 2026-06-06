/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.management;

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

    /** Files below this size are read whole + sliced ; above we use a
     *  reverse-scan via RandomAccessFile. 10 MB is generous enough for
     *  most cases on a steady-state binding. */
    private static final long SMALL_FILE_THRESHOLD = 10L * 1024 * 1024;

    /** Hard upper bound on the {@code tail} query param. Prevents an
     *  accidental {@code ?tail=999999999} from blocking the JVM heap
     *  on a multi-million-line file. */
    private static final int MAX_TAIL_LINES = 5_000;

    /** Resolved via the {@code quarkus.log.file.path} value
     *  ({@code ${LOG_DIR:logs}/application.log}). The parent directory
     *  of the main application log IS the log directory the binding
     *  writes to. All other handlers (errors, warns, commands, audit)
     *  share the same parent. */
    @ConfigProperty( name = "quarkus.log.file.path" )
    String applicationLogPath;

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

    @GET
    @Path( "/{name}" )
    @Produces( MediaType.TEXT_PLAIN )
    public Response tail( @PathParam( "name" ) String name,
                          @QueryParam( "tail" ) @DefaultValue( "500" ) int tail )
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
            String content = tailFile( target, tail );
            return Response.ok( content )
                           .type( MediaType.TEXT_PLAIN + ";charset=UTF-8" )
                           .header( "X-Log-File", name )
                           .header( "X-Log-Tail", String.valueOf( tail ) )
                           .build();
        }
        catch ( IOException e )
        {
            LOG.warnf( e, "Failed to read %s", target );
            return Response.serverError().entity( "read failed: " + e.getMessage() ).build();
        }
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
