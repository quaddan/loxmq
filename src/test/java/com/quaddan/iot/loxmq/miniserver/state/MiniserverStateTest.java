/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.state;

import com.quaddan.iot.loxmq.miniserver.identity.MiniserverIdentity;
import com.quaddan.iot.loxmq.miniserver.identity.MiniserverVersion;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
@DisplayName( "MiniserverState — identity slot" )
class MiniserverStateTest
{
    @Inject
    MiniserverState state;

    @AfterEach
    void cleanup()
    {
        state.clear();
    }

    @Test
    @DisplayName( "initial state is empty" )
    void initial()
    {
        assertThat( state.identity() ).isEmpty();
    }

    @Test
    @DisplayName( "update populates the slot" )
    void updatePopulates()
    {
        MiniserverIdentity id = sampleGen2();
        state.update( id );
        assertThat( state.identity() ).contains( id );
    }

    @Test
    @DisplayName( "clear wipes the slot" )
    void clearWipes()
    {
        state.update( sampleGen2() );
        state.clear();
        assertThat( state.identity() ).isEmpty();
    }

    @Test
    @DisplayName( "successive updates replace the previous identity" )
    void successiveUpdates()
    {
        MiniserverIdentity first = MiniserverIdentity.from( "aa:bb:cc:dd:ee:ff", MiniserverVersion.parse( "12.0.0.0" ),
                                                            "key1", false, true, "1.2.3.4", Optional.empty() );
        MiniserverIdentity second = MiniserverIdentity.from( "11:22:33:44:55:66", MiniserverVersion.parse( "15.6.5.11" ),
                                                             "key2", false, true, "5.6.7.8", Optional.of( 1 ) );
        state.update( first );
        state.update( second );

        assertThat( state.identity() ).contains( second );
    }

    private static MiniserverIdentity sampleGen2()
    {
        return MiniserverIdentity.from(
                "50:4F:94:AA:BB:CC",
                MiniserverVersion.parse( "15.6.5.11" ),
                "deadbeef",
                false, true, "192.0.2.10",
                Optional.of( 1 ) );
    }
}
