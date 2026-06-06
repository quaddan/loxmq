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
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Companion to {@link LoxApp3CacheTest}: verifies that setting
 * {@code loxone.miniserver.cache.ttl=PT0S} disables the TTL safety net
 * entirely — only the {@code lastModified} match decides hit / miss,
 * no matter how old the on-disk marker is.
 *
 * <h3>Why a separate test class</h3>
 * The TTL is read fresh on every {@code isHit()} call from
 * {@code config.miniserver().cache().ttl()}, but the config value is
 * frozen at @QuarkusTest startup. Toggling between {@code P7D} and
 * {@code PT0S} mid-test would require config-source rewiring; spinning
 * a fresh @QuarkusTest with a dedicated profile is simpler.
 */
@QuarkusTest
@TestProfile( LoxApp3CacheTtlDisabledTest.TtlDisabledProfile.class )
@DisplayName( "LoxApp3Cache — TTL safety net disabled (ttl=PT0S)" )
class LoxApp3CacheTtlDisabledTest
{
    private static final String TEST_APP_ID = "99999999-aaaa-bbbb-cccc-dddddddddddd";
    private static final Path   CACHE_ROOT  = Path.of( System.getProperty( "java.io.tmpdir" ),
                                                       "loxone-cache-ttl-test-" + System.nanoTime() );

    public static class TtlDisabledProfile implements QuarkusTestProfile
    {
        @Override
        public Map< String, String > getConfigOverrides()
        {
            return Map.of(
                    "loxone.miniserver.cache.directory", CACHE_ROOT.toString(),
                    "loxone.miniserver.app.id", TEST_APP_ID,
                    "loxone.miniserver.cache.ttl", "PT0S"
                         );
        }
    }

    @Inject
    LoxApp3Cache cache;

    @BeforeEach
    void wipe() { cache.clear(); }

    @AfterEach
    void cleanup() { cache.clear(); }

    @Test
    @DisplayName( "ttl=PT0S: ancient marker still produces a HIT when lastModified matches" )
    void ttlDisabled_AncientMarkerStillHits() throws IOException
    {
        cache.store( "payload", "2026-05-19 00:00:00" );

        // Backdate the marker by a year — way past anything a non-zero
        // TTL would tolerate. With ttl=PT0S the TTL branch is skipped
        // entirely so the cache still hits on the lastModified match.
        Path     marker  = Path.of( cache.directoryPath(), LoxApp3Cache.LAST_MODIFIED_FILE );
        FileTime ancient = FileTime.from( Instant.now().minus( Duration.ofDays( 365 ) ) );
        Files.setLastModifiedTime( marker, ancient );

        assertThat( cache.isHit( "2026-05-19 00:00:00" ) ).isTrue();
    }
}
