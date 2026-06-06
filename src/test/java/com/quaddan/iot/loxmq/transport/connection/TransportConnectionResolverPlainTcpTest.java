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
 * Second {@link TransportConnectionResolver} test class — exercises the
 * remaining 3 matrix cells (tcp+plain, tcp+secure, ws+plain). Each
 * {@link TestProfile} costs a Quarkus boot, so we keep the assertions
 * dense here — three nested test profile classes covered in one boot
 * each via {@code @Nested}.
 */
@QuarkusTest
@TestProfile( TransportConnectionResolverPlainTcpTest.TcpPlainProfile.class )
@DisplayName( "TransportConnectionResolver — tcp + plain = tcp" )
class TransportConnectionResolverPlainTcpTest
{
    @Inject
    TransportConnectionResolver resolver;

    public static class TcpPlainProfile implements QuarkusTestProfile
    {
        @Override
        public Map< String, String > getConfigOverrides()
        {
            return Map.of(
                    "loxone.transport.connection.protocol", "tcp",
                    "loxone.transport.connection.secure", "false",
                    "loxone.transport.connection.host", "127.0.0.1",
                    "loxone.transport.connection.port", "1883"
                    // No path — tcp doesn't need one.
                         );
        }
    }

    @Test
    @DisplayName( "tcp + secure=false ⇒ tcp + URI tcp://host:port (no path)" )
    void tcpPlain()
    {
        assertThat( resolver.family() ).isEqualTo( TransportFamily.TCP );
        assertThat( resolver.mode() ).isEqualTo( ConnectionMode.PLAIN );
        assertThat( resolver.effectiveProtocol() ).isEqualTo( "tcp" );
        // Path is intentionally omitted for tcp variants — HiveMQ's raw-TCP
        // connector doesn't take one.
        assertThat( resolver.effectiveUri().toString() ).isEqualTo( "tcp://127.0.0.1:1883" );
    }
}
