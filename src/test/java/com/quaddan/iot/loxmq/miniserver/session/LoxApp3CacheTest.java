/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.session;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link LoxApp3Cache} — file I/O round-trip, miss
 * detection, store/load + clear semantics.
 *
 * <h3>Per-test cache directory</h3>
 * The cache directory lives under
 * {@code <java.io.tmpdir>/loxone-cache-test-<uuid>/} and is wiped after
 * each test (via {@code @AfterEach + @TempDir}). Reusing a stable
 * directory across tests would risk leaking state between cases — easier
 * to keep each test fully self-contained.
 *
 * <h3>Why @QuarkusTest</h3>
 * The bean is {@code @ApplicationScoped} and reads {@code LoxoneConfig}
 * for the cache directory + app id. Booting Quarkus is the cheapest way
 * to wire those.
 */
@QuarkusTest
@TestProfile( LoxApp3CacheTest.IsolatedCacheDirProfile.class )
@DisplayName( "LoxApp3Cache — file round-trip, miss + hit, clear" )
class LoxApp3CacheTest
{
    /** Fixed app id so the cache subdirectory path is predictable. */
    private static final String TEST_APP_ID = "11111111-2222-3333-4444-555555555555";

    /** Pin the cache directory to a known location under /tmp. */
    private static final Path CACHE_ROOT = Path.of( System.getProperty( "java.io.tmpdir" ),
                                                    "loxone-cache-test-" + System.nanoTime() );

    public static class IsolatedCacheDirProfile implements QuarkusTestProfile
    {
        @Override
        public Map< String, String > getConfigOverrides()
        {
            return Map.of(
                    "loxone.miniserver.cache.directory", CACHE_ROOT.toString(),
                    "loxone.miniserver.app.id", TEST_APP_ID
                    // TTL not overridden — base default P7D applies. Tests
                    // that want a different TTL go through a sibling
                    // profile (see TtlDisabledCacheTest).
                         );
        }
    }

    @Inject
    LoxApp3Cache cache;

    @BeforeEach
    void wipe() throws IOException
    {
        // Use the production clear() — deletes both the marker and the
        // structure file. The per-app-id subdirectory itself may remain
        // (clear() doesn't remove directories) but isHit/load only check
        // file presence so this is sufficient for test isolation.
        cache.clear();
    }

    @AfterEach
    void cleanup() throws IOException
    {
        cache.clear();
    }

    @Test
    @DisplayName( "fresh cache: isHit returns false for any lastModified value" )
    void freshCacheIsMiss()
    {
        assertThat( cache.isHit( "2026-05-19 00:00:00" ) ).isFalse();
        assertThat( cache.load() ).isEmpty();
    }

    @Test
    @DisplayName( "isHit returns false for null / blank lastModified (defensive)" )
    void blankInputIsMiss()
    {
        assertThat( cache.isHit( null ) ).isFalse();
        assertThat( cache.isHit( "" ) ).isFalse();
        assertThat( cache.isHit( "   " ) ).isFalse();
    }

    @Test
    @DisplayName( "store + load round-trip: byte-for-byte fidelity" )
    void storeLoadRoundTrip()
    {
        String json = "{\"lastModified\":\"2026-05-19 00:00:00\",\"controls\":{},\"rooms\":{}}";
        cache.store( json, "2026-05-19 00:00:00" );

        assertThat( cache.isHit( "2026-05-19 00:00:00" ) ).isTrue();
        assertThat( cache.load() ).contains( json );
    }

    @Test
    @DisplayName( "store with blank lastModified is a no-op (would never re-hit)" )
    void storeBlankIsNoOp()
    {
        cache.store( "ignored content", "" );
        assertThat( cache.isHit( "" ) ).isFalse();
        assertThat( cache.load() ).isEmpty();
    }

    @Test
    @DisplayName( "isHit only matches the exact lastModified value" )
    void exactMatchOnly()
    {
        cache.store( "payload", "2026-05-19 00:00:00" );
        assertThat( cache.isHit( "2026-05-19 00:00:00" ) ).isTrue();
        assertThat( cache.isHit( "2026-05-19 00:00:01" ) ).isFalse();
        assertThat( cache.isHit( "2026-05-18 00:00:00" ) ).isFalse();
    }

    @Test
    @DisplayName( "store overwrites previous content" )
    void storeOverwrites()
    {
        cache.store( "v1", "2026-05-01 00:00:00" );
        cache.store( "v2", "2026-05-02 00:00:00" );

        assertThat( cache.isHit( "2026-05-01 00:00:00" ) ).isFalse();
        assertThat( cache.isHit( "2026-05-02 00:00:00" ) ).isTrue();
        assertThat( cache.load() ).contains( "v2" );
    }

    @Test
    @DisplayName( "clear wipes both marker and content; next isHit returns false" )
    void clearWipes()
    {
        cache.store( "payload", "2026-05-19 00:00:00" );
        cache.clear();

        assertThat( cache.isHit( "2026-05-19 00:00:00" ) ).isFalse();
        assertThat( cache.load() ).isEmpty();
    }

    @Test
    @DisplayName( "store creates the per-miniserver subdirectory + 2 files on demand" )
    void subdirectoryCreated() throws IOException
    {
        // Sanity-check: the path the cache uses matches what the test expects.
        // The cache's directoryPath() returns the per-app-id subdir; we cross-
        // reference it against the CACHE_ROOT/TEST_APP_ID we built locally.
        // If these diverge, the file lookups below would silently target the
        // wrong place and the assertions would fail in a confusing way.
        Path expected = Path.of( cache.directoryPath() );

        cache.store( "payload", "2026-05-19 00:00:00" );

        assertThat( Files.exists( expected ) ).isTrue();
        assertThat( Files.exists( expected.resolve( LoxApp3Cache.STRUCTURE_FILE ) ) ).isTrue();
        assertThat( Files.exists( expected.resolve( LoxApp3Cache.LAST_MODIFIED_FILE ) ) ).isTrue();
    }

    @Test
    @DisplayName( "directoryPath exposes the per-miniserver cache directory" )
    void directoryPathExposed()
    {
        assertThat( cache.directoryPath() )
                .contains( TEST_APP_ID )
                .endsWith( TEST_APP_ID );
    }

    // -------------------------------------------------------------------------
    //  TTL safety net
    // -------------------------------------------------------------------------

    @Test
    @DisplayName( "TTL: marker mtime within TTL ⇒ hit (matches lastModified)" )
    void ttlWithinWindow_StillHit() throws IOException
    {
        cache.store( "payload", "2026-05-19 00:00:00" );

        // The marker was just written — its mtime is "now-ish", well within
        // the default 7-day TTL set by the test profile (no override).
        assertThat( cache.isHit( "2026-05-19 00:00:00" ) ).isTrue();
    }

    @Test
    @DisplayName( "TTL: marker mtime older than TTL ⇒ miss even when lastModified matches" )
    void ttlExpired_ForcesMiss() throws IOException
    {
        cache.store( "payload", "2026-05-19 00:00:00" );

        // Backdate the marker file's mtime to 30 days ago — well past the
        // default 7-day TTL. lastModified still matches, but the safety
        // net must trip and force a miss.
        Path marker = Path.of( cache.directoryPath(), LoxApp3Cache.LAST_MODIFIED_FILE );
        java.nio.file.attribute.FileTime ancient =
                java.nio.file.attribute.FileTime.from(
                        java.time.Instant.now().minus( java.time.Duration.ofDays( 30 ) ) );
        Files.setLastModifiedTime( marker, ancient );

        assertThat( cache.isHit( "2026-05-19 00:00:00" ) ).isFalse();
    }
}
