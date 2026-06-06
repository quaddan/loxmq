/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.identity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link HttpsStatus#from(Optional)} — the mapping from the raw
 * {@code httpsStatus} JSON field to the strict enum. Reference:
 * {@code docs/Ask Miniserver if TLS protocol is supported.txt}.
 */
@DisplayName( "HttpsStatus — raw field → enum" )
class HttpsStatusTest
{
    @Test
    @DisplayName( "field absent (Gen1) ⇒ ABSENT" )
    void absent()
    {
        assertThat( HttpsStatus.from( Optional.empty() ) ).isEqualTo( HttpsStatus.ABSENT );
    }

    @Test
    @DisplayName( "httpsStatus=1 ⇒ SUPPORTED" )
    void supported()
    {
        assertThat( HttpsStatus.from( Optional.of( 1 ) ) ).isEqualTo( HttpsStatus.SUPPORTED );
    }

    @Test
    @DisplayName( "httpsStatus=2 ⇒ EXPIRED" )
    void expired()
    {
        assertThat( HttpsStatus.from( Optional.of( 2 ) ) ).isEqualTo( HttpsStatus.EXPIRED );
    }

    @Test
    @DisplayName( "any other value ⇒ UNKNOWN (defensive)" )
    void unknownValues()
    {
        assertThat( HttpsStatus.from( Optional.of( 0 ) ) ).isEqualTo( HttpsStatus.UNKNOWN );
        assertThat( HttpsStatus.from( Optional.of( 3 ) ) ).isEqualTo( HttpsStatus.UNKNOWN );
        assertThat( HttpsStatus.from( Optional.of( -1 ) ) ).isEqualTo( HttpsStatus.UNKNOWN );
        assertThat( HttpsStatus.from( Optional.of( 99 ) ) ).isEqualTo( HttpsStatus.UNKNOWN );
    }

    @Test
    @DisplayName( "generation derivation: ABSENT ⇒ GEN1, everything else ⇒ GEN2" )
    void generationDerivation()
    {
        assertThat( MiniserverGeneration.from( HttpsStatus.ABSENT ) ).isEqualTo( MiniserverGeneration.GEN1 );
        assertThat( MiniserverGeneration.from( HttpsStatus.SUPPORTED ) ).isEqualTo( MiniserverGeneration.GEN2 );
        assertThat( MiniserverGeneration.from( HttpsStatus.EXPIRED ) ).isEqualTo( MiniserverGeneration.GEN2 );
        assertThat( MiniserverGeneration.from( HttpsStatus.UNKNOWN ) ).isEqualTo( MiniserverGeneration.GEN2 );
    }
}
