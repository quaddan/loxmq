/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Pure-JUnit unit tests for {@link LoxApp3MetadataResolver} — parsing
 * a LoxAPP3-shaped JSON fixture + printf unit extraction. Doesn't boot
 * Quarkus : we instantiate the resolver manually and inject Jackson by
 * reflection. Keeps the test fast (~30 ms) and avoids dragging the CDI
 * container for what's a pure parsing function.
 *
 * <h3>Why a pure JUnit test (not @QuarkusTest)</h3>
 * The parser is a static-shape function on a JSON string — no need for
 * the CDI container, the observer infrastructure, or the LoxApp3Cache.
 * Existing test pattern in the project (cf. {@code MessageHeaderTest},
 * {@code BinaryStatesDecoderTest}) for similar pure-data tests.
 */
@DisplayName( "LoxApp3MetadataResolver — parsing + unit extraction" )
class LoxApp3MetadataResolverTest
{
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Mini-LoxAPP3 fixture : 1 room + 1 cat + 2 controls. The first has a
     * single state-UUID ("value"), the second has TWO state-UUIDs sharing
     * the same name (array form). Covers both index patterns the parser
     * has to handle.
     */
    private static final String FIXTURE_LOXAPP3 = """
                                                  {
                                                    "rooms": {
                                                      "0fcf3edd-008b-9e8a-7a3c0eebec45a5fb": { "name": "Salon" }
                                                    },
                                                    "cats": {
                                                      "00000000-0000-002e-2000000000000000": { "name": "Température" }
                                                    },
                                                    "controls": {
                                                      "1c40574b-0349-a847-07ffc10e78392737": {
                                                        "name": "Sonde Salon",
                                                        "room": "0fcf3edd-008b-9e8a-7a3c0eebec45a5fb",
                                                        "cat":  "00000000-0000-002e-2000000000000000",
                                                        "details": { "format": "%.1f°C" },
                                                        "states": {
                                                          "value": "state-uuid-temperature-1"
                                                        }
                                                      },
                                                      "1c40574b-0349-a848-08ffc10e78392737": {
                                                        "name": "HVAC Cuisine",
                                                        "room": "0fcf3edd-008b-9e8a-7a3c0eebec45a5fb",
                                                        "cat":  "00000000-0000-002e-2000000000000000",
                                                        "details": { "format": "%d%%" },
                                                        "states": {
                                                          "active": ["state-uuid-hvac-a", "state-uuid-hvac-b"]
                                                        }
                                                      }
                                                    }
                                                  }""";

    private LoxApp3MetadataResolver newResolver()
    {
        LoxApp3MetadataResolver r = new LoxApp3MetadataResolver();
        // No CDI — we inject the mapper directly via reflection.
        try
        {
            var f = LoxApp3MetadataResolver.class.getDeclaredField( "jsonMapper" );
            f.setAccessible( true );
            f.set( r, JSON );
        }
        catch ( Exception e )
        {
            throw new RuntimeException( e );
        }
        return r;
    }

    @Test
    @DisplayName( "parse(fixture) — index contains both single-state and array-state UUIDs" )
    void parseFixture() throws Exception
    {
        var                            r   = newResolver();
        Map< String, ControlMetadata > idx = r.parse( FIXTURE_LOXAPP3 );

        // 3 state-UUIDs au total : 1 single + 2 array.
        assertThat( idx ).hasSize( 3 );

        ControlMetadata temp = idx.get( "state-uuid-temperature-1" );
        assertThat( temp ).isNotNull();
        assertThat( temp.controlName() ).isEqualTo( "Sonde Salon" );
        assertThat( temp.roomName() ).isEqualTo( "Salon" );
        assertThat( temp.catName() ).isEqualTo( "Température" );
        assertThat( temp.format() ).isEqualTo( "%.1f°C" );
        assertThat( temp.unit() ).isEqualTo( "°C" );

        // Array-state: both UUIDs point to the SAME Control.
        ControlMetadata h1 = idx.get( "state-uuid-hvac-a" );
        ControlMetadata h2 = idx.get( "state-uuid-hvac-b" );
        assertThat( h1 ).isSameAs( h2 );
        assertThat( h1.controlName() ).isEqualTo( "HVAC Cuisine" );
        assertThat( h1.unit() ).isEqualTo( "%" );    // %% → %
    }

    @Test
    @DisplayName( "parse(missing room/cat refs) → controlName preserved, room/cat = UNKNOWN" )
    void parseMissingReferences() throws Exception
    {
        var r = newResolver();
        Map< String, ControlMetadata > idx = r.parse( """
                                                      {
                                                        "rooms": {},
                                                        "cats": {},
                                                        "controls": {
                                                          "abc": {
                                                            "name": "Orphelin",
                                                            "room": "missing-room",
                                                            "cat":  "missing-cat",
                                                            "details": { "format": "%.0f" },
                                                            "states": { "value": "state-orphan" }
                                                          }
                                                        }
                                                      }""" );

        ControlMetadata m = idx.get( "state-orphan" );
        assertThat( m ).isNotNull();
        assertThat( m.controlName() ).isEqualTo( "Orphelin" );
        assertThat( m.roomName() ).isEqualTo( "UNKNOWN" );
        assertThat( m.catName() ).isEqualTo( "UNKNOWN" );
        assertThat( m.unit() ).isEmpty();    // "%.0f" → ""
    }

    @Test
    @DisplayName( "parse({}) → empty index, no crash" )
    void parseEmpty() throws Exception
    {
        var                            r   = newResolver();
        Map< String, ControlMetadata > idx = r.parse( "{}" );
        assertThat( idx ).isEmpty();
    }

    @Test
    @DisplayName( "parse(malformed control) → skipped, others still indexed" )
    void parseTolerantToMalformedEntry() throws Exception
    {
        var r = newResolver();
        // 1st control has a "states" that is a string (should be an
        // object) → silently ignored. 2nd control is well-formed →
        // indexed.
        assertThatNoException().isThrownBy( () ->
                                            {
                                                Map< String, ControlMetadata > idx = r.parse( """
                                                                                              {
                                                                                                "controls": {
                                                                                                  "broken": { "states": "not-an-object" },
                                                                                                  "ok": {
                                                                                                    "name": "OK",
                                                                                                    "states": { "value": "state-ok" }
                                                                                                  }
                                                                                                }
                                                                                              }""" );
                                                assertThat( idx ).containsOnlyKeys( "state-ok" );
                                            } );
    }

    // =====================================================================
    //  operatingModes parsing
    // =====================================================================

    @Test
    @DisplayName( "parseOperatingModes — id (int) → display name extracted from LoxAPP3 root" )
    void parseOpModesHappy() throws Exception
    {
        var r = newResolver();
        Map< Integer, String > out = r.parseOperatingModes( """
                                                            {
                                                              "operatingModes": {
                                                                "0":  "Au bureau",
                                                                "1":  "Salon",
                                                                "10": "Période de chauffage"
                                                              }
                                                            }""" );
        assertThat( out ).hasSize( 3 )
                         .containsEntry( 0, "Au bureau" )
                         .containsEntry( 1, "Salon" )
                         .containsEntry( 10, "Période de chauffage" );
    }

    @Test
    @DisplayName( "parseOperatingModes — missing block → empty map, no crash" )
    void parseOpModesMissing() throws Exception
    {
        var r = newResolver();
        assertThat( r.parseOperatingModes( "{}" ) ).isEmpty();
    }

    @Test
    @DisplayName( "parseOperatingModes — tolerates non-int keys + blank values (silent skip)" )
    void parseOpModesTolerant() throws Exception
    {
        var r = newResolver();
        Map< Integer, String > out = r.parseOperatingModes( """
                                                            {
                                                              "operatingModes": {
                                                                "0":     "Valid",
                                                                "notInt":"Garbage key",
                                                                "1":     "",
                                                                "2":     "Also valid"
                                                              }
                                                            }""" );
        assertThat( out ).hasSize( 2 )
                         .containsEntry( 0, "Valid" )
                         .containsEntry( 2, "Also valid" );
    }

    @Test
    @DisplayName( "parseOperatingModes — object value form {\"name\":\"…\"} accepted (firmware drift)" )
    void parseOpModesObjectValue() throws Exception
    {
        var r = newResolver();
        Map< Integer, String > out = r.parseOperatingModes( """
                                                            {
                                                              "operatingModes": {
                                                                "5": { "name": "Wrapped form" }
                                                              }
                                                            }""" );
        assertThat( out ).containsExactly( Map.entry( 5, "Wrapped form" ) );
    }

    // =====================================================================
    //  Unit extraction (private static — tested via the wrapper class)
    // =====================================================================

    @Test
    @DisplayName( "extractUnit — format Loxone typiques" )
    void unitExtraction() throws Exception
    {
        // The method is package-private static — direct access from
        // the same package (the test is in miniserver.session too).
        var m = LoxApp3MetadataResolver.class.getDeclaredMethod( "extractUnit", String.class );
        m.setAccessible( true );

        assertThat( m.invoke( null, "%.1f°C" ) ).isEqualTo( "°C" );
        assertThat( m.invoke( null, "%.0f%%" ) ).isEqualTo( "%" );    // %% → %
        assertThat( m.invoke( null, "%.2flx" ) ).isEqualTo( "lx" );
        assertThat( m.invoke( null, "%dW" ) ).isEqualTo( "W" );
        assertThat( m.invoke( null, "%.1f" ) ).isEqualTo( "" );      // no unit
        assertThat( m.invoke( null, "" ) ).isEqualTo( "" );
        assertThat( m.invoke( null, ( Object ) null ) ).isEqualTo( "" );
        assertThat( m.invoke( null, "no-printf-here" ) ).isEqualTo( "" );
    }

    @Test
    @DisplayName( "ControlMetadata.unknown() is an identity singleton" )
    void unknownSentinel()
    {
        ControlMetadata u1 = ControlMetadata.unknown();
        ControlMetadata u2 = ControlMetadata.unknown();
        assertThat( u1 ).isSameAs( u2 );
        assertThat( u1.isUnknown() ).isTrue();
        assertThat( u1.controlName() ).isEqualTo( "UNKNOWN" );

        ControlMetadata regular = new ControlMetadata( "x", "y", "z", "", "" );
        assertThat( regular.isUnknown() ).isFalse();
    }
}
