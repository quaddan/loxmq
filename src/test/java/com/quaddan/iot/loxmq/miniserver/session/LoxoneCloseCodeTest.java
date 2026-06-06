/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Maps every documented {@code 4xxx} close code from
 * <i>Communicating with the Miniserver V17.0 §Websocket Close Codes</i>
 * to its expected {@link LoxoneCloseCode.ReconnectPolicy}.
 */
@DisplayName( "LoxoneCloseCode — code → policy mapping" )
class LoxoneCloseCodeTest
{
    @Test
    @DisplayName( "4003 LOGIN_BLOCKED → LONG_PAUSE" )
    void code4003()
    {
        assertThat( LoxoneCloseCode.from( 4003 ) ).isEqualTo( LoxoneCloseCode.LOGIN_BLOCKED );
        assertThat( LoxoneCloseCode.from( 4003 ).policy() )
                .isEqualTo( LoxoneCloseCode.ReconnectPolicy.LONG_PAUSE );
    }

    @Test
    @DisplayName( "4004 SOME_USER_CHANGED → NORMAL" )
    void code4004()
    {
        assertThat( LoxoneCloseCode.from( 4004 ) ).isEqualTo( LoxoneCloseCode.SOME_USER_CHANGED );
        assertThat( LoxoneCloseCode.from( 4004 ).policy() )
                .isEqualTo( LoxoneCloseCode.ReconnectPolicy.NORMAL );
    }

    @Test
    @DisplayName( "4005 CURRENT_USER_CHANGED → NORMAL" )
    void code4005()
    {
        assertThat( LoxoneCloseCode.from( 4005 ) ).isEqualTo( LoxoneCloseCode.CURRENT_USER_CHANGED );
        assertThat( LoxoneCloseCode.from( 4005 ).policy() )
                .isEqualTo( LoxoneCloseCode.ReconnectPolicy.NORMAL );
    }

    @Test
    @DisplayName( "4006 CURRENT_USER_DISABLED → DO_NOT_RECONNECT" )
    void code4006()
    {
        assertThat( LoxoneCloseCode.from( 4006 ) ).isEqualTo( LoxoneCloseCode.CURRENT_USER_DISABLED );
        assertThat( LoxoneCloseCode.from( 4006 ).policy() )
                .isEqualTo( LoxoneCloseCode.ReconnectPolicy.DO_NOT_RECONNECT );
    }

    @Test
    @DisplayName( "4007 MINISERVER_UPDATING → LONG_PAUSE" )
    void code4007()
    {
        assertThat( LoxoneCloseCode.from( 4007 ) ).isEqualTo( LoxoneCloseCode.MINISERVER_UPDATING );
        assertThat( LoxoneCloseCode.from( 4007 ).policy() )
                .isEqualTo( LoxoneCloseCode.ReconnectPolicy.LONG_PAUSE );
    }

    @Test
    @DisplayName( "4008 NO_EVENT_SLOTS → LONG_PAUSE" )
    void code4008()
    {
        assertThat( LoxoneCloseCode.from( 4008 ) ).isEqualTo( LoxoneCloseCode.NO_EVENT_SLOTS );
        assertThat( LoxoneCloseCode.from( 4008 ).policy() )
                .isEqualTo( LoxoneCloseCode.ReconnectPolicy.LONG_PAUSE );
    }

    @Test
    @DisplayName( "RFC 6455 standard codes (1000, 1001, 1006, etc.) → STANDARD_OR_UNKNOWN + NORMAL" )
    void rfcStandardCodes()
    {
        for ( int code : new int[]{ 1000, 1001, 1002, 1003, 1006, 1011 } )
        {
            assertThat( LoxoneCloseCode.from( code ) ).isEqualTo( LoxoneCloseCode.STANDARD_OR_UNKNOWN );
        }
        assertThat( LoxoneCloseCode.STANDARD_OR_UNKNOWN.policy() )
                .isEqualTo( LoxoneCloseCode.ReconnectPolicy.NORMAL );
    }

    @Test
    @DisplayName( "any unknown 4xxx code falls through to STANDARD_OR_UNKNOWN" )
    void unknown4xxx()
    {
        assertThat( LoxoneCloseCode.from( 4099 ) ).isEqualTo( LoxoneCloseCode.STANDARD_OR_UNKNOWN );
        assertThat( LoxoneCloseCode.from( 4001 ) ).isEqualTo( LoxoneCloseCode.STANDARD_OR_UNKNOWN );
    }

    @Test
    @DisplayName( "messages are operator-readable strings (non-empty)" )
    void messagesNonEmpty()
    {
        for ( LoxoneCloseCode lcc : LoxoneCloseCode.values() )
        {
            assertThat( lcc.message() ).isNotBlank();
            assertThat( lcc.policy() ).isNotNull();
        }
    }
}
