/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.transport.connection;

import com.quaddan.iot.loxmq.miniserver.connection.ConnectionMode;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Confirms backward compatibility: configs that pre-date the secure-mode
 * split still work. An operator who left {@code protocol=wss} (the legacy
 * pinned-secure form) without adding the new {@code secure} flag should
 * keep getting wss behaviour — because the resolver picks the family
 * from {@code wss} (= WS) and the default {@code secure=true} gives back
 * {@code wss} on the wire.
 */
@QuarkusTest
@TestProfile( TransportConnectionResolverLegacyValuesTest.LegacyWssProfile.class )
@DisplayName( "TransportConnectionResolver — legacy protocol=wss still works" )
class TransportConnectionResolverLegacyValuesTest
{
    @Inject
    TransportConnectionResolver resolver;

    public static class LegacyWssProfile implements QuarkusTestProfile
    {
        @Override
        public Map< String, String > getConfigOverrides()
        {
            // Mimic an older config that pins wss directly, with no `secure`
            // field — the default (secure=true) kicks in.
            return Map.of(
                    "loxone.transport.connection.protocol", "wss",
                    "loxone.transport.connection.host", "broker.example.com",
                    "loxone.transport.connection.port", "8084",
                    "loxone.transport.connection.path", "/mqtt"
                         );
        }
    }

    @Test
    @DisplayName( "protocol=wss + default secure=true ⇒ effective=wss (no behaviour change)" )
    void legacyWssBackwardCompat()
    {
        assertThat( resolver.preferred() ).isTrue();        // @WithDefault("true") kicks in
        assertThat( resolver.family() ).isEqualTo( TransportFamily.WS );
        assertThat( resolver.mode() ).isEqualTo( ConnectionMode.SECURE );
        assertThat( resolver.effectiveProtocol() ).isEqualTo( "wss" );
        assertThat( resolver.effectiveUri().toString() )
                .isEqualTo( "wss://broker.example.com:8084/mqtt" );
    }
}
