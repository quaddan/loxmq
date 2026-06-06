/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.identity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link MiniserverVersion} — the parser + comparator. Pure unit
 * tests, no Quarkus boot needed.
 */
@DisplayName( "MiniserverVersion — parse + compare" )
class MiniserverVersionTest
{
    @Test
    @DisplayName( "parses a canonical 4-part version" )
    void parsesCanonical()
    {
        MiniserverVersion v = MiniserverVersion.parse( "12.2.11.5" );
        assertThat( v.major() ).isEqualTo( 12 );
        assertThat( v.minor() ).isEqualTo( 2 );
        assertThat( v.patch() ).isEqualTo( 11 );
        assertThat( v.build() ).isEqualTo( 5 );
    }

    @Test
    @DisplayName( "parses a version with zero-padded parts (15.6.05.11)" )
    void parsesZeroPadded()
    {
        // Loxone occasionally ships zero-padded patch values — Integer.parseInt
        // handles them, but the test pins the behaviour so a future regex
        // tightening cannot regress silently.
        MiniserverVersion v = MiniserverVersion.parse( "15.6.05.11" );
        assertThat( v.patch() ).isEqualTo( 5 );
        assertThat( v.build() ).isEqualTo( 11 );
    }

    @Test
    @DisplayName( "trims surrounding whitespace" )
    void trimsWhitespace()
    {
        assertThat( MiniserverVersion.parse( "  17.0.1.0  " ) )
                .isEqualTo( new MiniserverVersion( 17, 0, 1, 0 ) );
    }

    @Test
    @DisplayName( "rejects null and blank input" )
    void rejectsNullOrBlank()
    {
        assertThatThrownBy( () -> MiniserverVersion.parse( null ) )
                .isInstanceOf( IllegalArgumentException.class );
        assertThatThrownBy( () -> MiniserverVersion.parse( "" ) )
                .isInstanceOf( IllegalArgumentException.class );
        assertThatThrownBy( () -> MiniserverVersion.parse( "   " ) )
                .isInstanceOf( IllegalArgumentException.class );
    }

    @Test
    @DisplayName( "rejects non-4-part versions" )
    void rejectsWrongShape()
    {
        assertThatThrownBy( () -> MiniserverVersion.parse( "12.2.11" ) )
                .isInstanceOf( IllegalArgumentException.class )
                .hasMessageContaining( "four" );
        assertThatThrownBy( () -> MiniserverVersion.parse( "12.2.11.5.0" ) )
                .isInstanceOf( IllegalArgumentException.class );
    }

    @Test
    @DisplayName( "rejects non-integer parts" )
    void rejectsNonInteger()
    {
        assertThatThrownBy( () -> MiniserverVersion.parse( "12.beta.11.5" ) )
                .isInstanceOf( IllegalArgumentException.class )
                .hasMessageContaining( "non-integer" );
    }

    @Test
    @DisplayName( "rejects negative parts" )
    void rejectsNegative()
    {
        assertThatThrownBy( () -> MiniserverVersion.parse( "-1.0.0.0" ) )
                .isInstanceOf( IllegalArgumentException.class )
                .hasMessageContaining( "non-negative" );
    }

    @Test
    @DisplayName( "natural ordering: major > minor > patch > build" )
    void ordering()
    {
        MiniserverVersion v_12_2_11_5 = MiniserverVersion.parse( "12.2.11.5" );
        MiniserverVersion v_12_2_11_6 = MiniserverVersion.parse( "12.2.11.6" );
        MiniserverVersion v_12_2_12_0 = MiniserverVersion.parse( "12.2.12.0" );
        MiniserverVersion v_12_3_0_0  = MiniserverVersion.parse( "12.3.0.0" );
        MiniserverVersion v_15_6_5_11 = MiniserverVersion.parse( "15.6.5.11" );

        assertThat( v_12_2_11_5 ).isLessThan( v_12_2_11_6 );
        assertThat( v_12_2_11_6 ).isLessThan( v_12_2_12_0 );
        assertThat( v_12_2_12_0 ).isLessThan( v_12_3_0_0 );
        assertThat( v_12_3_0_0 ).isLessThan( v_15_6_5_11 );
    }

    @Test
    @DisplayName( "toString drops zero-padding" )
    void toStringFormat()
    {
        assertThat( MiniserverVersion.parse( "15.6.05.11" ).toString() )
                .isEqualTo( "15.6.5.11" );
    }
}
