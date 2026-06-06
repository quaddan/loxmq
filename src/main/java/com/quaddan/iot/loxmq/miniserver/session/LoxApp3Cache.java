/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.session;

import com.quaddan.iot.loxmq.config.LoxoneConfig;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * On-disk cache for the LoxAPP3 structure file.
 * <p>
 * The structure file is the catalogue of every Loxone control, room, cat,
 * etc. in the operator's installation — typically several hundred KB,
 * occasionally a few MB. Re-downloading it on every reconnect is wasteful;
 * the miniserver exposes a {@code jdev/sps/LoxAPPversion3} probe that
 * returns a {@code lastModified} timestamp the binding can compare against
 * the cache to skip the download.
 *
 * <h3>Layout</h3>
 * <pre>{@code
 * <config.miniserver.cache.directory> /
 *     <config.miniserver.app.id> /
 *         lastModified         (text file, single line — the cache key)
 *         LoxAPP3.json         (the cached file)
 * }</pre>
 *
 * One subdirectory per miniserver app id so the same dev workstation can
 * point at multiple miniservers without their caches colliding.
 *
 * <h3>Two-level invalidation</h3>
 * <ol>
 *   <li><b>{@code lastModified} match</b> — the primary key. The
 *       miniserver's probe response carries the structure-file's
 *       timestamp; we match it against the {@code lastModified} text
 *       file on disk.</li>
 *   <li><b>TTL</b> — a safety net for the case where the miniserver
 *       firmware ships structure-file changes <em>without</em>
 *       bumping the timestamp (observed in the wild on some upgrade
 *       paths). Configured via
 *       {@code loxone.miniserver.cache.ttl} (default {@code P7D}).
 *       The on-disk {@code lastModified} text file's filesystem
 *       mtime is the freshness reference — if it is older than the
 *       TTL the cache is forced into a miss, triggering a fresh
 *       download.</li>
 * </ol>
 * Both checks must pass for a hit. Set {@code ttl=PT0S} to disable
 * the TTL safety net entirely (always download, debugging only).
 *
 * <h3>Failure modes</h3>
 * The cache is an OPTIMISATION, never a precondition. Any I/O error
 * (directory unreadable, file missing, etc.) is logged as a warning and
 * the binding falls through to a full {@code data/LoxAPP3.json} download.
 *
 * <h3>Concurrency</h3>
 * {@code @ApplicationScoped}, but each read or write is a fresh atomic
 * operation; concurrent reads are safe by definition (just file I/O),
 * concurrent writes serialise on the underlying filesystem.
 *
 * <h3>{@link Startup} — eager initialization</h3>
 * The session orchestrator first touches this bean from the JDK WebSocket
 * reader thread (inside {@code onStructureVersionReply}), which is NOT a
 * Quarkus-managed thread. ArC's default lazy-init can fail on that thread
 * in dev-mode (intermittent classloader-context issues observed against a
 * real Miniserver — produced
 * {@code "Error injecting LoxoneConfig LoxApp3Cache.config"} on the first
 * attempt after a fresh boot, then succeeded on the next). Annotating
 * with {@link Startup} forces the bean to be created + injected during
 * Quarkus startup on the main thread, where the classloader context is
 * always sane. By the time any WS callback can reach it, the bean is
 * already wired.
 */
@ApplicationScoped
@Startup
public class LoxApp3Cache
{
    private static final Logger LOG = Logger.getLogger( LoxApp3Cache.class );

    /** Cache key file name (single line: the {@code lastModified} string). */
    static final String LAST_MODIFIED_FILE = "lastModified";

    /** Cached structure file name — must match what the miniserver returns. */
    static final String STRUCTURE_FILE = "LoxAPP3.json";

    @Inject
    LoxoneConfig config;

    /**
     * Force eager delegate creation + {@code @Inject} field resolution at
     * Quarkus startup time on the main thread. Without this, ArC only
     * creates the client proxy eagerly (per {@link Startup}) while the
     * underlying delegate is materialised lazily on first method call —
     * which can land on the JDK WebSocket reader thread (a non-Quarkus
     * thread), where the dev-mode classloader context intermittently
     * causes {@code "Error injecting LoxoneConfig LoxApp3Cache.config"}.
     * <p>
     * Touching {@link #config} here forces the field-injection to happen
     * during {@code @PostConstruct} (on the main Quarkus thread, sane
     * classloader context). By the time any WS callback can call
     * {@link #isHit} or {@link #load}, the delegate is fully wired.
     */
    @PostConstruct
    void init()
    {
        Path dir = cacheDir();    // touches config.miniserver().cache().directory() AND .app().id()
        LOG.infof( "LoxApp3 cache ready, directory=%s", dir );
    }

    /** Root cache directory ({@code <cache.directory>/<app.id>/}). */
    private Path cacheDir()
    {
        return Path.of( config.miniserver().cache().directory(),
                        config.miniserver().app().id() );
    }

    /**
     * Cache hit ⇔ all three conditions hold:
     * <ol>
     *   <li>the miniserver's {@code remoteLastModified} matches the
     *       on-disk {@code lastModified} marker;</li>
     *   <li>the cached JSON file is present;</li>
     *   <li>the marker's filesystem mtime is within the configured
     *       TTL ({@code loxone.miniserver.cache.ttl}).</li>
     * </ol>
     * Any failure (null/blank input, missing files, I/O error) returns
     * {@code false} — caller falls through to the full download path.
     */
    public boolean isHit( String remoteLastModified )
    {
        if ( remoteLastModified == null || remoteLastModified.isBlank() )
        {
            return false;
        }
        try
        {
            Path marker = cacheDir().resolve( LAST_MODIFIED_FILE );
            Path file   = cacheDir().resolve( STRUCTURE_FILE );
            if ( !Files.exists( marker ) || !Files.exists( file ) )
            {
                return false;
            }

            // (1) lastModified key match
            String cached = Files.readString( marker, StandardCharsets.UTF_8 ).trim();
            if ( !remoteLastModified.trim().equals( cached ) )
            {
                return false;
            }

            // (2) TTL safety net — guard against firmware upgrades that
            //     ship structure-file changes without bumping the
            //     timestamp. Read the mtime from the marker file (more
            //     stable than the JSON, which a future tool might rewrite
            //     in-place for unrelated reasons).
            Duration ttl = config.miniserver().cache().ttl();
            if ( !ttl.isZero() && !ttl.isNegative() )
            {
                FileTime mtime = Files.getLastModifiedTime( marker );
                Duration age   = Duration.between( mtime.toInstant(), Instant.now() );
                if ( age.compareTo( ttl ) > 0 )
                {
                    LOG.infof( "LoxApp3 cache TTL expired — age=%s exceeds ttl=%s (lastModified key still matched). Forcing re-download.",
                               age, ttl );
                    return false;
                }
            }
            return true;
        }
        catch ( IOException e )
        {
            LOG.warnf( "LoxApp3 cache probe failed (%s) — treating as miss", e.getMessage() );
            return false;
        }
    }

    /** Returns the cached LoxAPP3.json contents, or empty if unreadable. */
    public Optional< String > load()
    {
        try
        {
            Path file = cacheDir().resolve( STRUCTURE_FILE );
            if ( !Files.exists( file ) )
            {
                return Optional.empty();
            }
            return Optional.of( Files.readString( file, StandardCharsets.UTF_8 ) );
        }
        catch ( IOException e )
        {
            LOG.warnf( "LoxApp3 cache load failed (%s) — caller will fall through to download",
                       e.getMessage() );
            return Optional.empty();
        }
    }

    /**
     * Persist the freshly-downloaded JSON + its lastModified timestamp.
     * Creates the cache directory on demand. Errors are logged as warnings
     * — the in-memory copy is still usable for this session.
     */
    public void store( String json, String lastModified )
    {
        if ( lastModified == null || lastModified.isBlank() )
        {
            LOG.warn( "LoxApp3 cache store: lastModified is blank — skipping (a future probe would always miss)" );
            return;
        }
        try
        {
            Path dir = cacheDir();
            Files.createDirectories( dir );
            Files.writeString( dir.resolve( STRUCTURE_FILE ), json,
                               StandardCharsets.UTF_8 );
            Files.writeString( dir.resolve( LAST_MODIFIED_FILE ), lastModified,
                               StandardCharsets.UTF_8 );
            LOG.infof( "LoxApp3 cache stored: lastModified=%s, size=%d chars, dir=%s",
                       lastModified, ( Integer ) json.length(), dir );
        }
        catch ( IOException e )
        {
            LOG.warnf( "LoxApp3 cache store failed (%s) — next reconnect will re-download",
                       e.getMessage() );
        }
    }

    /** Wipe the cache for the current miniserver. Used by future operator endpoints. */
    public void clear()
    {
        try
        {
            Path dir = cacheDir();
            Files.deleteIfExists( dir.resolve( LAST_MODIFIED_FILE ) );
            Files.deleteIfExists( dir.resolve( STRUCTURE_FILE ) );
            LOG.infof( "LoxApp3 cache cleared at %s", dir );
        }
        catch ( IOException e )
        {
            LOG.warnf( "LoxApp3 cache clear failed (%s)", e.getMessage() );
        }
    }

    /** Path of the cache directory — used by the dashboard / state endpoint. */
    public String directoryPath()
    {
        return cacheDir().toString();
    }
}
