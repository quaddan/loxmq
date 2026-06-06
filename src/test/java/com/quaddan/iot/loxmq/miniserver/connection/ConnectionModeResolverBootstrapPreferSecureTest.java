/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.connection;

import com.quaddan.iot.loxmq.miniserver.state.MiniserverState;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the {@code bootstrap-prefer-secure} opt-in branch of
 * {@link ConnectionModeResolver#effective}.
 *
 * <p>The base {@link ConnectionModeResolverTest} covers the conservative
 * default (bootstrap pending → PLAIN). This class flips the knob to
 * {@code true} and asserts the resolver returns SECURE for the same
 * empty-identity state, AND that {@link ConnectionModeResolver#downgradeReason}
 * is empty because there's no downgrade happening anymore.
 */
@QuarkusTest
@TestProfile( ConnectionModeResolverBootstrapPreferSecureTest.BootstrapSecureProfile.class )
@DisplayName( "ConnectionModeResolver — bootstrap-prefer-secure=true opt-in" )
class ConnectionModeResolverBootstrapPreferSecureTest
{
    @Inject
    ConnectionModeResolver resolver;

    @Inject
    MiniserverState state;

    public static class BootstrapSecureProfile implements QuarkusTestProfile
    {
        @Override
        public Map< String, String > getConfigOverrides()
        {
            // secure=true + bootstrap-prefer-secure=true — the operator
            // explicitly trusts the configured port to be TLS-ready even
            // before identity has been fetched. This is the production
            // shape for setups where the miniserver port is TLS-only
            // (Gen2 with separate :80/HTTP and :443/HTTPS listeners).
            return Map.of(
                    "loxone.miniserver.connection.secure", "true",
                    "loxone.miniserver.connection.bootstrap-prefer-secure", "true"
                         );
        }
    }

    @AfterEach
    void resetState()
    {
        state.clear();
    }

    @Test
    @DisplayName( "bootstrap-prefer-secure=true + empty identity ⇒ SECURE, no downgrade reason" )
    void emptyIdentityResolvesSecureWhenOptedIn()
    {
        assertThat( resolver.preferred() ).isTrue();
        assertThat( resolver.effective() )
                .as( "operator opted into bootstrap-secure → resolver should not downgrade pre-bootstrap" )
                .isEqualTo( ConnectionMode.SECURE );
        assertThat( resolver.downgradeReason() )
                .as( "no downgrade happening, so no reason to expose to the operator" )
                .isEmpty();
    }
}
