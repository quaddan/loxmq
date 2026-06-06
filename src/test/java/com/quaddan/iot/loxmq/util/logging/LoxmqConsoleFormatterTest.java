/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.util.logging;

import org.jboss.logmanager.ExtLogRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.logging.Level;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link LoxmqConsoleFormatter}. Pure assertions on
 * the formatted string — no Quarkus, no CDI, no log handler wiring.
 * <p>
 * Coverage:
 * <ul>
 *   <li>The per-level emoji separator + message wrapper land in the
 *       output.</li>
 *   <li>xterm-256 ANSI codes are emitted around the appropriate
 *       fields.</li>
 *   <li>INFO+ levels omit the method+line block.</li>
 *   <li>DEBUG / TRACE include the method+line block.</li>
 *   <li>A null source method renders as "?" (no NPE).</li>
 *   <li>A negative source line renders as "?" (no scary "-1").</li>
 *   <li>A Throwable in the record gets its stack trace appended.</li>
 * </ul>
 */
@DisplayName( "LoxmqConsoleFormatter — coloured, per-level console output" )
class LoxmqConsoleFormatterTest
{
    private static final String                ESC       = "";
    private final        LoxmqConsoleFormatter formatter = new LoxmqConsoleFormatter();

    @Test
    @DisplayName( "INFO record — 🟢 separator, ✅ wrapper, no method/line block" )
    void infoStripped()
    {
        ExtLogRecord r = record( Level.INFO, "Boot complete" );

        String out = formatter.format( r );

        assertThat( out )
                .contains( "🟢" )            // INFO separator
                .contains( "✅" )            // INFO wrapper (twice — before+after message)
                .contains( ESC + "[38;5;10m" )   // Lime — INFO ANSI code
                .contains( ESC + "[38;5;100m" )  // Olive — timestamp colour
                .contains( ESC + "[38;5;180m" )  // Khaki — thread colour
                .contains( ESC + "[38;5;98m" )   // Purple — logger colour
                .contains( "Boot complete" )
                .endsWith( System.lineSeparator() );
        // Method colour (C_METHOD = LightSkyBlue1) MUST NOT appear at INFO.
        assertThat( out ).doesNotContain( ESC + "[38;5;153m" );
        // Wrapper appears twice (before and after the message).
        assertThat( out.split( "✅", -1 ).length - 1 ).isEqualTo( 2 );
    }

    @Test
    @DisplayName( "WARN record — 🟡 separator, ⚠️ wrapper, no method/line block" )
    void warnStripped()
    {
        ExtLogRecord r = record( Level.WARNING, "Reconnecting in 5s" );

        String out = formatter.format( r );

        assertThat( out )
                .contains( "🟡" )
                .contains( "⚠️" )
                .contains( ESC + "[38;5;214m" )  // Gold-ish WARN
                .contains( "Reconnecting in 5s" );
    }

    @Test
    @DisplayName( "ERROR record — 🔴 separator, 🔥 wrapper" )
    void errorStripped()
    {
        ExtLogRecord r = record( Level.SEVERE, "Boom" );

        String out = formatter.format( r );

        assertThat( out )
                .contains( "🔴" )
                .contains( "🔥" )
                .contains( ESC + "[38;5;203m" )  // IndianRed1
                .contains( "Boom" );
    }

    @Test
    @DisplayName( "DEBUG record — 🔵 separator, NO wrapper, method+line block PRESENT" )
    void debugVerbose()
    {
        ExtLogRecord r = record( Level.FINE, "Payload preview: foo" );
        r.setSourceMethodName( "publish" );
        r.setSourceLineNumber( 142 );

        String out = formatter.format( r );

        assertThat( out )
                .contains( "🔵" )
                .contains( ESC + "[38;5;39m" )   // DeepSkyBlue1
                .contains( ESC + "[38;5;153m" )  // Method colour — present at DEBUG
                .contains( ESC + "[38;5;6m" )    // Line colour — present at DEBUG
                .contains( "publish" )
                .contains( "142" )
                .contains( "Payload preview: foo" );
        // DEBUG has no wrapper — no ✅/⚠️/🔥.
        assertThat( out ).doesNotContain( "✅" ).doesNotContain( "⚠️" ).doesNotContain( "🔥" );
    }

    @Test
    @DisplayName( "TRACE record — 🟤 separator, method+line block PRESENT" )
    void traceVerbose()
    {
        ExtLogRecord r = record( Level.FINEST, "deep dive" );
        r.setSourceMethodName( "doDecode" );
        r.setSourceLineNumber( 88 );

        String out = formatter.format( r );

        assertThat( out )
                .contains( "🟤" )
                .contains( ESC + "[38;5;95m" )   // LightPink4
                .contains( ESC + "[38;5;153m" )  // method colour still present at TRACE
                .contains( "doDecode" )
                .contains( "88" )
                .contains( "deep dive" );
    }

    @Test
    @DisplayName( "Source method null → \"?\" (no NPE)" )
    void nullSourceMethod()
    {
        ExtLogRecord r = record( Level.FINE, "from somewhere" );
        // Don't set source method/line — defaults to null/negative.

        String out = formatter.format( r );

        assertThat( out ).contains( "?" );  // both method and line render as "?"
    }

    @Test
    @DisplayName( "Throwable on record → stack trace appended in the level colour" )
    void throwableAppended()
    {
        ExtLogRecord r = record( Level.SEVERE, "Something failed" );
        r.setThrown( new RuntimeException( "intentional", new IllegalStateException( "root cause" ) ) );

        String out = formatter.format( r );

        assertThat( out )
                .contains( "Something failed" )
                .contains( "java.lang.RuntimeException: intentional" )
                .contains( "Caused by: java.lang.IllegalStateException: root cause" )
                // Stack trace must be wrapped in the ERROR colour code.
                .containsPattern( "\\[38;5;203m[\\s\\S]*RuntimeException[\\s\\S]*\\[0m" );
    }

    @Test
    @DisplayName( "Logger name → c.q.i.l.m.ClassName abbreviation" )
    void loggerAbbreviation()
    {
        // Typical binding FQN — every package segment reduced to its first char,
        // class name kept in full.
        assertThat( LoxmqConsoleFormatter.abbreviateLogger(
                "com.quaddan.iot.loxmq.miniserver.session.SessionOrchestrator" ) )
                .isEqualTo( "c.q.i.l.m.s.SessionOrchestrator" );

        // Short package.
        assertThat( LoxmqConsoleFormatter.abbreviateLogger( "org.jboss.logmanager.handlers.ConsoleHandler" ) )
                .isEqualTo( "o.j.l.h.ConsoleHandler" );

        // No package → returned as-is.
        assertThat( LoxmqConsoleFormatter.abbreviateLogger( "NoPackageClass" ) )
                .isEqualTo( "NoPackageClass" );

        // Null / empty → "?" (no NPE, no scary blank).
        assertThat( LoxmqConsoleFormatter.abbreviateLogger( null ) ).isEqualTo( "?" );
        assertThat( LoxmqConsoleFormatter.abbreviateLogger( "" ) ).isEqualTo( "?" );
    }

    // -------------------------------------------------------------------------
    //  Helpers
    // -------------------------------------------------------------------------

    /** Build a minimal {@link ExtLogRecord} with a known logger name +
     *  thread name. Tests override source method/line individually. */
    private static ExtLogRecord record( Level level, String message )
    {
        ExtLogRecord r = new ExtLogRecord( level, message, "fqcn" );
        r.setLoggerName( "c.y.iot.test.SomeBean" );
        r.setThreadName( "test-thread-1" );
        return r;
    }
}
