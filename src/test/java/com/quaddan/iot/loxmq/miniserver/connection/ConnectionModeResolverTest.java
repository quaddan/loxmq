/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.connection;

import com.quaddan.iot.loxmq.miniserver.identity.HttpsStatus;
import com.quaddan.iot.loxmq.miniserver.identity.MiniserverIdentity;
import com.quaddan.iot.loxmq.miniserver.identity.MiniserverVersion;
import com.quaddan.iot.loxmq.miniserver.state.MiniserverState;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.QuarkusTestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full decision matrix for {@link ConnectionModeResolver} — preference × identity
 * → effective mode + downgrade reason.
 * <p>
 * Boots Quarkus with a sub-profile that pins {@code loxone.miniserver.connection
 * .secure=true} so we can flip the {@link MiniserverState} between empty,
 * Gen1, Gen2-supported, Gen2-expired and Gen2-unknown to exercise every cell
 * of the matrix. The {@code secure=false} cells are covered in a separate
 * class to keep config switching simple.
 */
@QuarkusTest
@TestProfile( ConnectionModeResolverTest.SecurePreferredProfile.class )
@DisplayName( "ConnectionModeResolver — preference=SECURE × identity matrix" )
class ConnectionModeResolverTest
{
    @Inject
    ConnectionModeResolver resolver;

    @Inject
    MiniserverState state;

    public static class SecurePreferredProfile implements QuarkusTestProfile
    {
        @Override
        public Map< String, String > getConfigOverrides()
        {
            return Map.of( "loxone.miniserver.connection.secure", "true" );
        }
    }

    @AfterEach
    void resetState()
    {
        // MiniserverState is @ApplicationScoped — shared across tests in the
        // same Quarkus boot. Reset so per-test mutations don't leak.
        state.clear();
    }

    @Test
    @DisplayName( "preference SECURE, identity empty ⇒ PLAIN (bootstrap pending)" )
    void preferredButNoIdentity()
    {
        assertThat( resolver.preferred() ).isTrue();
        assertThat( resolver.effective() ).isEqualTo( ConnectionMode.PLAIN );
        assertThat( resolver.downgradeReason() )
                .hasValueSatisfying( r -> assertThat( r ).contains( "Bootstrap pending" ) );
    }

    @Test
    @DisplayName( "preference SECURE, Gen2 + SUPPORTED ⇒ SECURE, no downgrade" )
    void preferredAndSupported()
    {
        state.update( identityWith( HttpsStatus.SUPPORTED ) );
        assertThat( resolver.effective() ).isEqualTo( ConnectionMode.SECURE );
        assertThat( resolver.downgradeReason() ).isEmpty();
    }

    @Test
    @DisplayName( "preference SECURE, Gen1 (ABSENT) ⇒ PLAIN + 'Gen1 — no TLS support'" )
    void preferredButGen1()
    {
        state.update( identityWith( HttpsStatus.ABSENT ) );
        assertThat( resolver.effective() ).isEqualTo( ConnectionMode.PLAIN );
        assertThat( resolver.downgradeReason() )
                .hasValueSatisfying( r -> assertThat( r ).contains( "Gen1" ) );
    }

    @Test
    @DisplayName( "preference SECURE, Gen2 + EXPIRED ⇒ PLAIN + 'EXPIRED certificate'" )
    void preferredButExpired()
    {
        state.update( identityWith( HttpsStatus.EXPIRED ) );
        assertThat( resolver.effective() ).isEqualTo( ConnectionMode.PLAIN );
        assertThat( resolver.downgradeReason() )
                .hasValueSatisfying( r -> assertThat( r ).containsIgnoringCase( "EXPIRED" ) );
    }

    @Test
    @DisplayName( "preference SECURE, Gen2 + UNKNOWN ⇒ PLAIN + defensive reason" )
    void preferredButUnknown()
    {
        state.update( identityWith( HttpsStatus.UNKNOWN ) );
        assertThat( resolver.effective() ).isEqualTo( ConnectionMode.PLAIN );
        assertThat( resolver.downgradeReason() )
                .hasValueSatisfying( r -> assertThat( r ).contains( "unknown httpsStatus" ) );
    }

    @Test
    @DisplayName( "schemes for both modes" )
    void schemes()
    {
        assertThat( ConnectionMode.PLAIN.httpScheme() ).isEqualTo( "http" );
        assertThat( ConnectionMode.PLAIN.wsScheme() ).isEqualTo( "ws" );
        assertThat( ConnectionMode.SECURE.httpScheme() ).isEqualTo( "https" );
        assertThat( ConnectionMode.SECURE.wsScheme() ).isEqualTo( "wss" );
    }

    // ---------- helpers ----------

    private MiniserverIdentity identityWith( HttpsStatus status )
    {
        return MiniserverIdentity.from(
                "50:4F:94:AA:BB:CC",
                MiniserverVersion.parse( "15.6.5.11" ),
                "deadbeef",
                false,
                true,
                "127.0.0.1",
                rawFor( status ) );
    }

    private static Optional< Integer > rawFor( HttpsStatus status )
    {
        return switch ( status )
        {
            case ABSENT -> Optional.empty();
            case SUPPORTED -> Optional.of( 1 );
            case EXPIRED -> Optional.of( 2 );
            case UNKNOWN -> Optional.of( 99 );
        };
    }
}
