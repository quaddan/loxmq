/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.util.logging;

import org.jboss.logmanager.ExtFormatter;
import org.jboss.logmanager.ExtLogRecord;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.logging.Level;

/**
 * Console formatter for the application's console handler. Two design
 * goals:
 *
 * <ol>
 *   <li><b>Readable, colour-rich console output</b> — an xterm-256
 *       palette per field (timestamp / thread / logger / method / line /
 *       level / message), per-level separator icons (🟣🔴🟡🟢🔵🟤) and
 *       per-level message wrappers (💣🔥⚠️✅).</li>
 *   <li><b>Adaptive verbosity</b> — INFO and above show the stripped-down
 *       set (timestamp · thread · logger · level · message). DEBUG
 *       and TRACE add {@code method · line} for diagnostic precision
 *       at the cost of {@code getSourceMethodName()} / {@code
 *       getSourceLineNumber()} forcing a fillStackTrace — acceptable
 *       since DEBUG/TRACE is off in prod by default.</li>
 * </ol>
 *
 * <h3>Why a custom {@code ExtFormatter}, not a Quarkus pattern</h3>
 * Quarkus's {@code quarkus.log.console.format=…} string supports
 * tokens like {@code %K{level}} for ANSI colouring, but it cannot
 * express:
 * <ul>
 *   <li>An xterm-256 colour per field (only basic 16-colour level
 *       colouring).</li>
 *   <li>A per-level separator emoji that varies across fields.</li>
 *   <li>A message wrapper that opens at the start of the message
 *       and closes at the end.</li>
 *   <li>Conditional field inclusion based on level
 *       (method+line only for DEBUG/TRACE).</li>
 * </ul>
 * A Java formatter is the only way to get this level of visual
 * control. The class is small and self-contained — no Quarkus or CDI
 * dependency, can be unit-tested in isolation.
 *
 * <h3>Wiring</h3>
 * Installed via a CDI bean producer
 * ({@code LoggingProducers#loxmqConsoleHandler}) that wraps it
 * in a {@code org.jboss.logmanager.handlers.ConsoleHandler}. Quarkus
 * auto-discovers CDI beans of type {@code java.util.logging.Handler}
 * and adds them to the root logger. The default Quarkus console
 * handler is disabled via {@code quarkus.log.console.enable=false}
 * so we don't double-print.
 *
 * <h3>Palette reference</h3>
 * xterm-256 colour codes — see
 * <a href="https://ss64.com/bash/syntax-colors.html">ss64.com syntax
 * colours</a>. Replace the integer just before the {@code m} with
 * the desired xterm index.
 */
public final class LoxmqConsoleFormatter extends ExtFormatter
{
    /** ISO-style timestamp pattern.
     *  Thread-safe (java.time, not {@code SimpleDateFormat}). */
    private static final DateTimeFormatter TS_FORMAT =
            DateTimeFormatter.ofPattern( "yyyy-MM-dd HH:mm:ss.SSS" );

    /** Per-level styling — the xterm-256 colour code, separator and
     *  wrapper glyphs for each log level. */
    private enum LevelStyle
    {
        // (code, separator, wrapper) — drop CONFIG / FINER / FINEST
        // / SEVERE / WARNING / FATAL since java.util.logging in
        // Quarkus only emits TRACE/DEBUG/INFO/WARN/ERROR; FATAL gets
        // mapped at runtime.
        ERROR( "[38;5;203m", "🔴", "🔥" ),  // IndianRed1
        WARN( "[38;5;214m", "🟡", "⚠️" ),  // Gold-ish for WARN
        INFO( "[38;5;10m", "🟢", "✅" ),  // Lime
        DEBUG( "[38;5;39m", "🔵", "" ),   // DeepSkyBlue1
        TRACE( "[38;5;95m", "🟤", "" );   // LightPink4

        final String code;       // ANSI sequence to colour the level + message
        final String separator;  // emoji between fields
        final String wrapper;    // emoji bracketing the message (empty for DEBUG/TRACE)

        LevelStyle( String code, String separator, String wrapper )
        {
            this.code      = code;
            this.separator = separator;
            this.wrapper   = wrapper;
        }

        /** Map a JUL {@link Level} to a style. Unknown / custom levels
         *  fall back to INFO so we never throw from the formatter. */
        static LevelStyle of( Level level )
        {
            int n = level.intValue();
            if ( n >= Level.SEVERE.intValue() )
            { return ERROR; }
            if ( n >= Level.WARNING.intValue() )
            { return WARN; }
            if ( n >= Level.INFO.intValue() )
            { return INFO; }
            if ( n >= Level.FINE.intValue() )
            { return DEBUG; }
            return TRACE;
        }
    }

    /** Per-field xterm-256 colour codes. Kept as constants (not an
     *  enum) since there's no iteration use case. */
    private static final String C_TIMESTAMP = "[38;5;100m";  // Olive
    private static final String C_THREAD    = "[38;5;180m";  // Khaki
    private static final String C_LOGGER    = "[38;5;98m";   // MediumPurple3
    private static final String C_METHOD    = "[38;5;153m";  // LightSkyBlue1
    private static final String C_LINE      = "[38;5;6m";    // Teal
    private static final String C_RESET     = "[0m";

    /** Threshold below which we add the method+line block. Levels
     *  strictly less than INFO get the verbose layout. */
    private static final int INFO_LEVEL_VALUE = Level.INFO.intValue();

    @Override
    public String format( ExtLogRecord record )
    {
        LevelStyle s       = LevelStyle.of( record.getLevel() );
        String     sep     = s.separator;
        boolean    verbose = record.getLevel().intValue() < INFO_LEVEL_VALUE;

        StringBuilder buf = new StringBuilder( 256 );

        // ── Layout note: NO spaces between separator emojis and surrounding
        // fields. The xterm-256 codes already provide visual breaks; spaces
        // only add bloat. Wrappers (✅/⚠️/🔥) also sit flush against the
        // message — no trailing/leading space.

        // ┌── separator timestamp separator
        buf.append( sep )
           .append( C_TIMESTAMP )
           .append( TS_FORMAT.format(
                   Instant.ofEpochMilli( record.getMillis() ).atZone( ZoneId.systemDefault() ) ) )
           .append( C_RESET )
           .append( sep );

        // ── thread
        buf.append( C_THREAD ).append( record.getThreadName() ).append( C_RESET )
           .append( sep );

        // ── logger — abbreviated "c.q.i.l.m.s.SessionOrchestrator" form,
        //    same convention Logback's "%logger{0}" or Quarkus's compact
        //    pattern uses. Keeps the class name in full; replaces every
        //    package segment with its first character.
        buf.append( C_LOGGER ).append( abbreviateLogger( record.getLoggerName() ) ).append( C_RESET )
           .append( sep );

        // ── method + line (DEBUG / TRACE only)
        if ( verbose )
        {
            buf.append( C_METHOD ).append( safe( record.getSourceMethodName(), "?" ) ).append( C_RESET )
               .append( sep )
               .append( C_LINE ).append( safeLine( record.getSourceLineNumber() ) ).append( C_RESET )
               .append( sep );
        }

        // ── level
        buf.append( s.code ).append( record.getLevel().getName() ).append( C_RESET )
           .append( sep );

        // ── message, wrapped if the level has a wrapper (INFO/WARN/ERROR)
        if ( !s.wrapper.isEmpty() )
        { buf.append( s.wrapper ); }
        buf.append( s.code ).append( formatMessage( record ) ).append( C_RESET );
        if ( !s.wrapper.isEmpty() )
        { buf.append( s.wrapper ); }

        buf.append( System.lineSeparator() );

        // ── stack trace, if any. Same colour as the level so it
        //    visually attaches to the message above. Without this,
        //    Netty / HiveMQ warnings that pass a Throwable are
        //    silently flattened.
        Throwable thrown = record.getThrown();
        if ( thrown != null )
        {
            StringWriter sw = new StringWriter();
            thrown.printStackTrace( new PrintWriter( sw ) );
            buf.append( s.code ).append( sw ).append( C_RESET );
        }

        return buf.toString();
    }

    private static String safe( String value, String fallback )
    {
        return ( value == null || value.isEmpty() ) ? fallback : value;
    }

    /** {@code ExtLogRecord.getSourceLineNumber()} returns a negative
     *  sentinel when the source is unknown. Render "?" rather than a
     *  scary "-1". */
    private static String safeLine( int line )
    {
        return line < 0 ? "?" : Integer.toString( line );
    }

    /** Abbreviate a fully-qualified logger name into the compact form
     *  used by Logback's {@code %logger{0}} / Quarkus's compact
     *  pattern: every package segment is reduced to its first
     *  character; the class name (after the last dot) is kept in
     *  full.
     *
     *  <p>Examples:
     *  <pre>
     *    com.quaddan.iot.loxmq.miniserver.session.SessionOrchestrator
     *      → c.q.i.l.m.s.SessionOrchestrator
     *    org.jboss.logmanager.handlers.ConsoleHandler
     *      → o.j.l.h.ConsoleHandler
     *    NoPackageClass
     *      → NoPackageClass
     *  </pre>
     *
     *  <p>Null / empty returns {@code "?"} so the formatter never
     *  throws and the operator still sees a placeholder. */
    static String abbreviateLogger( String fqn )
    {
        if ( fqn == null || fqn.isEmpty() )
        { return "?"; }
        int lastDot = fqn.lastIndexOf( '.' );
        if ( lastDot < 0 )
        {
            return fqn;          // no package — class only
        }

        StringBuilder sb           = new StringBuilder( fqn.length() );
        int           segmentStart = 0;
        for ( int i = 0; i < lastDot; i++ )
        {
            if ( fqn.charAt( i ) == '.' )
            {
                sb.append( fqn.charAt( segmentStart ) ).append( '.' );
                segmentStart = i + 1;
            }
        }
        // Last package segment (between segmentStart and lastDot).
        sb.append( fqn.charAt( segmentStart ) ).append( '.' );
        // Full class name (after lastDot).
        sb.append( fqn, lastDot + 1, fqn.length() );
        return sb.toString();
    }
}
