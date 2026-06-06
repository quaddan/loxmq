/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.util.logging;

import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Singleton;
import org.jboss.logging.Logger;

import java.io.File;
import java.lang.reflect.Field;
import java.util.function.Predicate;
import java.util.logging.Filter;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;

/**
 * Post-boot surgery on the root logger's handler tree. Performs two fixes
 * that Quarkus's declarative config cannot express for us, both by walking
 * the handler tree at {@code @PostConstruct} time ({@link #walkRootHandlers}):
 *
 * <ol>
 *   <li><b>Console formatter</b> — installs {@link LoxmqConsoleFormatter}
 *       on Quarkus's existing console handler so the operator sees coloured
 *       output with per-level emoji separators / wrappers.</li>
 *   <li><b>Strict-WARN filter on {@code warn.log}</b> — attaches a
 *       {@link java.util.logging.Filter} that accepts <em>exactly</em>
 *       {@code WARN}, so {@code warn.log} carries recovered transients only
 *       and never duplicates the {@code ERROR} lines that already live in
 *       {@code error.log}. See {@link #installStrictWarnFilter} for why the
 *       declarative {@code filter: levels(WARN)} knob does NOT work.</li>
 * </ol>
 *
 * <h3>Why this is not a {@code @Produces Handler} bean</h3>
 * Quarkus 3.36's "install CDI Handler beans on the root logger"
 * mechanism is build-time only — extensions can produce a
 * {@code LogHandlerBuildItem}, but applications cannot. Producing
 * a {@code Handler} from an app-level CDI bean is silently ignored
 * by Quarkus's logging recorder; combined with
 * {@code quarkus.log.console.enable=false}, that gave "nothing
 * prints" on the first attempt.
 *
 * <h3>Why we swap the FORMATTER rather than the HANDLER</h3>
 * Removing Quarkus's default {@code ConsoleHandler} and adding our
 * own brittle (constructor differences across JBoss LogManager
 * versions, target-stream wiring quirks, double-print if removal
 * silently fails). Swapping just the {@code Formatter} on the
 * existing handler is one method call, deterministic, no risk of
 * losing stdout, no risk of double-print.
 *
 * <h3>Why we search RECURSIVELY + by CLASS NAME</h3>
 * Quarkus's {@code LoggingSetupRecorder} does NOT attach the
 * {@code ConsoleHandler} directly on the root logger. Instead:
 * <pre>
 *   root logger
 *     └── DelayedHandler  (extends ExtHandler)
 *           ├── ConsoleHandler   ← console-formatter target
 *           └── FileHandler      (application.log / error.log / warn.log / …)
 * </pre>
 * A flat loop over {@code root.getHandlers()} only sees the
 * {@code DelayedHandler}. We must recurse into every
 * {@code ExtHandler}'s children to reach the actual
 * {@code ConsoleHandler} / {@code FileHandler}.
 *
 * <h3>Why class-name matching instead of {@code instanceof}</h3>
 * In Quarkus dev-mode ({@code mvn quarkus:dev}), application classes
 * and JBoss LogManager classes may be loaded by <em>different</em>
 * classloaders. When that happens, {@code handler instanceof
 * ConsoleHandler} returns {@code false} even if the handler really
 * is a {@code ConsoleHandler} — the two {@code Class} objects are
 * not the same reference. Matching by {@code Class.getName()} (and,
 * for the file handlers, reflecting {@code getFile()}) is
 * classloader-safe.
 *
 * <h3>Why we recurse into reflected fields (Quarkus dev-mode wrappers)</h3>
 * In dev-mode, {@code LoggingSetupRecorder} wraps the real
 * {@code ConsoleHandler} inside an anonymous {@code ExtHandler}
 * subclass whose {@code doPublish()} calls {@code delegate.publish()}
 * directly — bypassing {@code getFormatter()} entirely. Calling
 * {@code setFormatter()} on the wrapper is silently ignored. The
 * wrapper captures the real handler as a {@code final} local, which
 * javac stores as a synthetic field named {@code val$delegate}.
 * We use reflection to walk every {@code Handler}-typed field and
 * recurse — eventually reaching the real {@code ConsoleHandler}
 * where {@code setFormatter()} actually takes effect.
 *
 * <h3>Order of events</h3>
 * <ol>
 *   <li>JVM boots. Quarkus's {@code LoggingSetupRecorder} installs a
 *       {@code DelayedHandler} on the root logger; once configured,
 *       it wraps a {@code ConsoleHandler} with its default pattern
 *       formatter. The first few log lines (Quarkus banner,
 *       feature list) use that format.</li>
 *   <li>This bean reaches {@code @PostConstruct} (~150ms in,
 *       triggered by {@code @Startup}). We walk the root logger's
 *       handler tree recursively, install our console formatter and
 *       attach the strict-WARN filter on {@code warn.log}.</li>
 *   <li>From there on, every console line uses the custom format and
 *       {@code warn.log} captures exactly {@code WARN}.</li>
 * </ol>
 *
 * <p>The few early lines before the swap stay in Quarkus's default
 * format — acceptable trade-off vs. writing a Quarkus extension to
 * intercept handler creation at build time.
 *
 * <h3>Safety</h3>
 * Each install step is wrapped in its own {@code try / catch (Throwable)}.
 * Any failure prints to {@code System.err} directly and lets Quarkus keep
 * its defaults as a fallback — this bean never breaks output.
 */
@Singleton
@Startup
public class LoggingProducers
{
    private static final Logger LOG = Logger.getLogger( LoggingProducers.class );

    // ── Class-name / file-name constants for classloader-safe matching ──
    // In Quarkus dev-mode, application classes and JBoss LogManager
    // classes live in DIFFERENT classloaders. `instanceof` fails even
    // when the class is correct. All matching is therefore by name.

    /** Standard JBoss LogManager console handler — the console-formatter
     *  target. In dev-mode it is hidden inside a Quarkus wrapper; we reach
     *  it via reflection on a synthetic {@code val$delegate} field. */
    private static final String JBOSS_CONSOLE_HANDLER =
            "org.jboss.logmanager.handlers.ConsoleHandler";

    /** Basename of the dedicated WARN log — matches
     *  {@code quarkus.log.handler.file.warns.path} in application.yml. */
    private static final String WARN_LOG_FILENAME = "warn.log";

    /** Strict WARN-only gate for {@code warn.log}: accepts a record only
     *  when its level value equals {@code WARN} (== {@link Level#WARNING},
     *  intValue 900). Rejects {@code ERROR}/{@code FATAL} (1000/1100 —
     *  already captured in {@code error.log}) and everything below WARN.
     *  This is the programmatic equivalent of the JBoss LogManager
     *  {@code levels(WARN)} filter expression (see
     *  {@link #installStrictWarnFilter} for why the declarative form
     *  cannot be used).
     *
     *  <p>Note JBoss LogManager's {@code WARN} and the JDK's {@code WARNING}
     *  share the same {@code intValue()} (900), so comparing the numeric
     *  level value accepts both regardless of which logging facade emitted
     *  the record. Package-private so {@code LoggingProducersTest} can
     *  assert the contract directly. */
    static final Filter WARN_ONLY =
            record -> record.getLevel().intValue() == Level.WARNING.intValue();

    @PostConstruct
    void configureRootLoggerHandlers()
    {
        installCustomConsoleFormatter();
        installStrictWarnFilter();
    }

    /** Swaps {@link LoxmqConsoleFormatter} onto every JBoss
     *  {@code ConsoleHandler} reachable from the root logger. */
    private void installCustomConsoleFormatter()
    {
        try
        {
            Formatter ours = new LoxmqConsoleFormatter();

            Predicate< Handler > swapFormatter = h -> {
                if ( JBOSS_CONSOLE_HANDLER.equals( h.getClass().getName() ) )
                {
                    h.setFormatter( ours );
                    return true;   // consumed — a ConsoleHandler has no children worth descending
                }
                return false;
            };

            int updated = walkRootHandlers( swapFormatter );

            if ( updated == 0 )
            {
                LOG.warnf( "No ConsoleHandler found on the root logger — LoxmqConsoleFormatter not installed. "
                           + "Custom console formatting will not be active." );
            }
            else
            {
                LOG.infof( "Installed LoxmqConsoleFormatter on %d console handler%s (colours + per-level icons active)",
                           ( Integer ) updated, updated == 1 ? "" : "s" );
            }
        }
        catch ( Throwable t )
        {
            // Fall back loudly to System.err — the logging system may
            // itself be the problem, so don't rely on it for the error
            // message. Quarkus's default formatter stays active.
            System.err.println( "[LoggingProducers] FAILED to install LoxmqConsoleFormatter — keeping Quarkus default format. "
                                + "Error: " + t );
            t.printStackTrace( System.err );
        }
    }

    /**
     * Attaches {@link #WARN_ONLY} to the {@code warn.log} file handler so it
     * captures strictly {@code WARN} (no {@code ERROR}/{@code FATAL}
     * duplication with {@code error.log}).
     *
     * <h3>Why not the declarative {@code filter: levels(WARN)}</h3>
     * Quarkus's {@code quarkus.log.handler.file."warns".filter} property
     * expects the <em>name</em> of a filter declared under
     * {@code quarkus.log.filter.<name>} — it does NOT evaluate JBoss
     * LogManager filter <em>expressions</em>. {@code levels(WARN)} is such
     * an expression, so Quarkus failed at boot with
     * {@code "Unable to find named filter 'levels(WARN)'"} and dropped the
     * filter silently (warn.log then captured {@code WARN}+{@code ERROR}+
     * {@code FATAL}). Quarkus's only built-in named-filter type is
     * {@code if-text-matches} (a message regex), which cannot match on
     * level — so we attach the level filter programmatically here.
     *
     * <p>The handler is identified by its output file basename
     * ({@value #WARN_LOG_FILENAME}) via the JBoss
     * {@code FileHandler.getFile()} accessor, reached by reflection so we
     * keep no compile-time dependency on the JBoss LogManager handler
     * classes (and stay classloader-safe in dev-mode — same rationale as
     * the console search above). The handler's own {@code level: WARN}
     * threshold short-circuits the filter for {@code INFO}/{@code DEBUG}
     * (fast path); the filter only has to reject {@code ERROR}/{@code FATAL}.
     */
    private void installStrictWarnFilter()
    {
        try
        {
            Predicate< Handler > attachWarnFilter = h -> {
                if ( isFileHandlerFor( h, WARN_LOG_FILENAME ) )
                {
                    h.setFilter( WARN_ONLY );
                    return true;   // consumed — the warn.log handler is a leaf
                }
                return false;
            };

            int updated = walkRootHandlers( attachWarnFilter );

            if ( updated == 0 )
            {
                LOG.warnf( "No '%s' file handler found on the root logger — strict-WARN filter not installed. "
                           + "warn.log may also capture ERROR/FATAL.", WARN_LOG_FILENAME );
            }
            else
            {
                LOG.infof( "Installed strict-WARN filter on %s (ERROR/FATAL excluded — they live in error.log)",
                           WARN_LOG_FILENAME );
            }
        }
        catch ( Throwable t )
        {
            System.err.println( "[LoggingProducers] FAILED to install strict-WARN filter on warn.log — "
                                + "it may also capture ERROR/FATAL. Error: " + t );
            t.printStackTrace( System.err );
        }
    }

    /** True iff {@code handler} is a JBoss {@code FileHandler} (or subclass,
     *  e.g. {@code SizeRotatingFileHandler}) whose current output file has
     *  the given basename. Uses reflection on {@code getFile()} so we keep
     *  no compile-time dependency on the JBoss LogManager handler classes
     *  and stay classloader-safe in dev-mode (same reasoning as the console
     *  class-name match). Handlers without a {@code getFile()} method
     *  (the {@code DelayedHandler}, the {@code ConsoleHandler}, …) simply
     *  return {@code false} and the caller keeps descending. */
    private static boolean isFileHandlerFor( Handler handler, String fileName )
    {
        try
        {
            Object file = handler.getClass().getMethod( "getFile" ).invoke( handler );
            return file instanceof File f && fileName.equals( f.getName() );
        }
        catch ( ReflectiveOperationException notAFileHandler )
        {
            // No getFile() → not a FileHandler. Fine — keep descending.
            return false;
        }
    }

    // ── Generic root-handler-tree walk ──────────────────────────────

    /** Walk the root logger's handler tree and apply {@code visitor} to
     *  every handler reached. When {@code visitor} returns {@code true}
     *  the handler counts as "consumed" and that branch is not descended
     *  further. Returns the number of handlers consumed. Package-private
     *  so a test can drive it directly.
     *
     *  <p>The traversal handles three nesting patterns:
     *  <ol>
     *    <li>Direct attachment (test/prod) — {@code root → handler}.</li>
     *    <li>Container handler — {@code root → DelayedHandler → handler}.
     *        Walked via {@code getHandlers()} reflection.</li>
     *    <li>Quarkus dev-mode wrapper — {@code root → ExtHandler anon$N →
     *        val$delegate → handler}. Walked via field reflection on any
     *        {@code Handler}-typed field (including synthetic captures of
     *        {@code final} locals).</li>
     *  </ol> */
    static int walkRootHandlers( Predicate< Handler > visitor )
    {
        java.util.logging.Logger root    = java.util.logging.Logger.getLogger( "" );
        int                      consumed = 0;
        java.util.Set< Handler > visited  = java.util.Collections.newSetFromMap( new java.util.IdentityHashMap<>() );
        for ( Handler h : root.getHandlers() )
        {
            consumed += walk( h, visitor, visited );
        }
        return consumed;
    }

    /** Depth-first recursion behind {@link #walkRootHandlers}.
     *  <ol>
     *    <li>Offer the handler to {@code visitor}; a {@code true} consumes
     *        the branch (stop).</li>
     *    <li>Otherwise recurse into children via {@code getHandlers()}
     *        (works on {@code ExtHandler} subclasses like
     *        {@code DelayedHandler}).</li>
     *    <li>Then recurse into every {@code Handler}-typed field via
     *        reflection — the only way to reach a handler wrapped inside
     *        Quarkus dev-mode's anonymous {@code ExtHandler} (captured as
     *        the synthetic {@code val$delegate} field).</li>
     *  </ol>
     *
     *  <p>{@code visited} is an identity-set guarding against cyclic
     *  handler graphs — the JBoss LogManager does not create cycles
     *  in practice, but reflection on internal fields can surface
     *  self-references that would otherwise infinite-loop. */
    private static int walk( Handler handler, Predicate< Handler > visitor,
                             java.util.Set< Handler > visited )
    {
        if ( handler == null || !visited.add( handler ) )
        {
            return 0;
        }

        // 1. Offer to the visitor. A match consumes this branch.
        if ( visitor.test( handler ) )
        {
            return 1;
        }

        int consumed = 0;

        // 2. Recurse via getHandlers() (ExtHandler / DelayedHandler children).
        try
        {
            Object result = handler.getClass().getMethod( "getHandlers" ).invoke( handler );
            if ( result instanceof Handler[] children )
            {
                for ( Handler child : children )
                {
                    consumed += walk( child, visitor, visited );
                }
            }
        }
        catch ( ReflectiveOperationException ignored )
        {
            // No getHandlers() — leaf or hidden wrapper. Field reflection below handles it.
        }

        // 3. Recurse into reflected Handler-typed fields (incl. synthetic
        //    val$delegate on Quarkus dev-mode anonymous wrappers). We walk
        //    the inheritance chain so superclass-declared fields are also
        //    seen.
        Class< ? > cls = handler.getClass();
        while ( cls != null && cls != Object.class )
        {
            for ( Field f : cls.getDeclaredFields() )
            {
                if ( Handler.class.isAssignableFrom( f.getType() ) )
                {
                    try
                    {
                        f.setAccessible( true );
                        Object value = f.get( handler );
                        if ( value instanceof Handler nested )
                        {
                            consumed += walk( nested, visitor, visited );
                        }
                    }
                    catch ( ReflectiveOperationException | RuntimeException ignored )
                    {
                        // Module access denied or similar — skip and continue.
                    }
                }
            }
            cls = cls.getSuperclass();
        }

        return consumed;
    }
}
