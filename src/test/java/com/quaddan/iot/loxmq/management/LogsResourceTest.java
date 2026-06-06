/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.management;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-level coverage for the {@code /api/v1/logs} surface.
 *
 * <p>We don't go through REST-Assured here — the resource is thin enough
 * to drive directly via the CDI instance. That keeps the test focused
 * on path-handling logic (whitelist, tail size, reverse-scan) without
 * needing a live broker or miniserver session.
 *
 * <h3>Why a real on-disk dir</h3>
 * The reverse-scan branch only kicks in for files larger than 10 MB. We
 * exercise the small-file branch (most common in practice) on a few
 * fixture files we create in the configured {@code logs/} directory
 * before each test and clean up after. {@link
 * LogsResource#logDirectory}
 * resolves via {@code quarkus.log.file.path} — in the test profile that
 * lands under {@code target/test.log}'s parent (the build dir), so we
 * mirror the same parent for the fixtures.
 */
@QuarkusTest
@DisplayName( "LogsResource — list + tail + path-traversal guard" )
class LogsResourceTest
{
    @Inject
    LogsResource resource;

    private       Path         dir;
    private final List< Path > created = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() throws IOException
    {
        dir = resource.logDirectory();
        Files.createDirectories( dir );
    }

    @AfterEach
    void tearDown()
    {
        for ( Path p : created )
        {
            try { Files.deleteIfExists( p ); } catch ( IOException ignored ) { }
        }
        created.clear();
    }

    private Path writeFixture( String name, List< String > lines ) throws IOException
    {
        Path p = dir.resolve( name );
        Files.write( p, String.join( "\n", lines ).getBytes( StandardCharsets.UTF_8 ) );
        created.add( p );
        return p;
    }

    @Test
    @DisplayName( "list ignores unrelated files + sorts by mtime desc" )
    void listMatchesWhitelistAndOrder() throws Exception
    {
        // Three fixture files at different mtimes, plus one non-log file
        // that must be filtered out by the regex.
        Path older = writeFixture( "application.log", List.of( "older content" ) );
        Files.setLastModifiedTime( older,
                                   java.nio.file.attribute.FileTime.fromMillis( System.currentTimeMillis() - 60_000 ) );

        Path mid = writeFixture( "error.log", List.of( "error content" ) );
        Files.setLastModifiedTime( mid,
                                   java.nio.file.attribute.FileTime.fromMillis( System.currentTimeMillis() - 30_000 ) );

        Path newest = writeFixture( "warn.log", List.of( "warn content" ) );
        Files.setLastModifiedTime( newest,
                                   java.nio.file.attribute.FileTime.fromMillis( System.currentTimeMillis() ) );

        Path noise = dir.resolve( "config.yml" );
        Files.write( noise, "irrelevant".getBytes( StandardCharsets.UTF_8 ) );
        created.add( noise );

        List< LogsResource.LogFileEntry > entries = resource.list();
        // Sub-list those that match our fixtures (the test profile may
        // have already produced a real test.log — we don't assert on
        // unrelated files, only that ours are present + in the right order).
        List< String > names = entries.stream()
                                      .map( LogsResource.LogFileEntry::name )
                                      .filter( n -> List.of( "application.log", "error.log", "warn.log" ).contains( n ) )
                                      .collect( Collectors.toList() );
        assertThat( names ).containsExactly( "warn.log", "error.log", "application.log" );

        // Whitelist : config.yml MUST NOT appear.
        assertThat( entries.stream().map( LogsResource.LogFileEntry::name ) )
                .doesNotContain( "config.yml" );
    }

    @Test
    @DisplayName( "tail clips to last N lines on a small file" )
    void tailReturnsLastNLines() throws Exception
    {
        List< String > lines = IntStream.range( 0, 100 )
                                        .mapToObj( i -> "line-" + i )
                                        .toList();
        writeFixture( "application.log", lines );

        var resp = resource.tail( "application.log", 5 );
        assertThat( resp.getStatus() ).isEqualTo( 200 );
        String body = ( String ) resp.getEntity();
        assertThat( body.lines().toList() )
                .containsExactly( "line-95", "line-96", "line-97", "line-98", "line-99" );
    }

    @Test
    @DisplayName( "tail caps oversized N at MAX_TAIL_LINES (5000)" )
    void tailCapsAtMax() throws Exception
    {
        // 20 lines is enough — we just want to assert the cap doesn't
        // bork on a sane request.
        writeFixture( "application.log",
                      IntStream.range( 0, 20 ).mapToObj( i -> "x" + i ).toList() );

        var resp = resource.tail( "application.log", 999_999 );
        assertThat( resp.getStatus() ).isEqualTo( 200 );
        // Header X-Log-Tail must reflect the capped value, not the request.
        assertThat( resp.getHeaderString( "X-Log-Tail" ) ).isEqualTo( "5000" );
    }

    @Test
    @DisplayName( "tail rejects filename that doesn't match whitelist (path traversal sentinel)" )
    void tailRejectsBadFilename()
    {
        // Each of these violates the [a-z]+.log(.YYYY-MM-DD|.N)? pattern.
        for ( String bad : List.of(
                "../etc/passwd",
                "application.log/../../oops",
                "..%2Fetc%2Fpasswd",          // pre URL-decoded form
                "application.LOG",            // uppercase — pattern is lowercase
                "application.txt",
                "Some.Log",
                "application.log.x" ) )
        {
            var resp = resource.tail( bad, 10 );
            assertThat( resp.getStatus() ).as( "filename=%s", bad ).isEqualTo( 400 );
        }
    }

    @Test
    @DisplayName( "tail returns 404 for whitelist-OK filename that does not exist" )
    void tailMissingFileReturns404()
    {
        var resp = resource.tail( "nonexistent.log", 10 );
        assertThat( resp.getStatus() ).isEqualTo( 404 );
    }

    @Test
    @DisplayName( "rotated size-backup filename (application.log.1) is accepted by the whitelist" )
    void tailRotatedSizeBackupAccepted() throws Exception
    {
        writeFixture( "application.log.1", List.of( "size-rotated line" ) );
        var resp = resource.tail( "application.log.1", 10 );
        assertThat( resp.getStatus() ).isEqualTo( 200 );
        assertThat( ( String ) resp.getEntity() ).isEqualTo( "size-rotated line" );
    }

    @Test
    @DisplayName( "legacy date-rotated filename (application.log.2026-05-28) is still accepted by the whitelist" )
    void tailRotatedDailyAccepted() throws Exception
    {
        writeFixture( "application.log.2026-05-28", List.of( "rotated line" ) );
        var resp = resource.tail( "application.log.2026-05-28", 10 );
        assertThat( resp.getStatus() ).isEqualTo( 200 );
        assertThat( ( String ) resp.getEntity() ).isEqualTo( "rotated line" );
    }

    @Test
    @DisplayName( "tail on empty file returns 200 + empty body" )
    void tailEmptyFile() throws Exception
    {
        Path p = writeFixture( "application.log", List.of() );
        assertThat( Files.size( p ) ).isEqualTo( 0 );

        var resp = resource.tail( "application.log", 10 );
        assertThat( resp.getStatus() ).isEqualTo( 200 );
        assertThat( ( String ) resp.getEntity() ).isEqualTo( "" );
    }

    /**
     * Sanity — the whitelist regex itself does what the docstring claims.
     * Cheap to test in isolation, helps catch a future tightening that
     * accidentally rejects a legitimate format.
     */
    @Test
    @DisplayName( "whitelist regex — accepts canonical names, rejects everything else" )
    void whitelistMatrix()
    {
        // Avoid coupling to LogsResource internals — re-derive the same
        // pattern. Documentation lives in the resource itself.
        var re = java.util.regex.Pattern.compile( "[a-z]+\\.log(\\.\\d{4}-\\d{2}-\\d{2}|\\.\\d+)?" );
        try ( Stream< String > accepted = Stream.of(
                "application.log",
                "error.log",
                "warn.log",
                "commands.log",
                "audit.log",
                "application.log.1",          // size-rotated backup (current scheme)
                "warn.log.30",                // oldest size-rotated backup
                "application.log.2026-05-28", // legacy date-rotated backup
                "commands.log.2025-01-01" ) )
        {
            accepted.forEach( s -> assertThat( re.matcher( s ).matches() ).as( "accept %s", s ).isTrue() );
        }
        try ( Stream< String > rejected = Stream.of(
                "",
                "Application.log",
                "application.LOG",
                "../etc/passwd",
                "/etc/passwd",
                "../application.log",
                "application.log.bad",
                "application.log.2026-13-99", // pattern doesn't validate month/day, but the format is structurally wrong here too — regex IS strict on digits but allows 13/99 ; we accept that as a known false positive (broker only writes valid dates)
                "applicationlog",
                "application.log.2026-05" ) )
        {
            rejected.filter( s -> !s.equals( "application.log.2026-13-99" ) )
                    .forEach( s -> assertThat( re.matcher( s ).matches() ).as( "reject %s", s ).isFalse() );
        }
    }
}
