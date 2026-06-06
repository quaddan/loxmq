/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ScheduleService} — exercise the wire-format
 * builders (URL encoding, validation) and the read-shape parsing
 * (expanded calMode attributes returned by V17.0 Miniserver).
 *
 * <p>{@link MiniserverAdminCommandClient} is mocked ; the service's
 * responsibility ends at "build the right path + parse the right shape",
 * which is what we assert here.
 */
@DisplayName( "ScheduleService — wire-format builders + read-shape parsing" )
class ScheduleServiceTest
{
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Common reply shape : {@code {"value":<v>,"Code":"200"}}. Saves a
     *  text-block per test ; readability > literal-JSON-fidelity. */
    private static JsonNode ok( String valueJsonOrLiteral ) throws Exception
    {
        return MAPPER.readTree( "{\"value\":" + valueJsonOrLiteral + ",\"Code\":\"200\"}" );
    }

    private MiniserverAdminCommandClient client;
    private ScheduleService              service;

    @BeforeEach
    void setUp()
    {
        client              = mock( MiniserverAdminCommandClient.class );
        service             = new ScheduleService();
        service.adminClient = client;

        // Stub unwrapValue with the same logic the real implementation
        // has : re-parse `value` if it's a JSON-encoded string. Lets the
        // tests stay close to the wire shape.
        when( client.unwrapValue( any( JsonNode.class ) ) ).thenAnswer( inv ->
                                                                        {
                                                                            JsonNode ll = inv.getArgument( 0 );
                                                                            if ( ll == null )
                                                                            { return MAPPER.nullNode(); }
                                                                            JsonNode v = ll.path( "value" );
                                                                            if ( !v.isTextual() )
                                                                            { return v; }
                                                                            String s = v.asText( "" );
                                                                            if ( s.isEmpty() )
                                                                            { return MAPPER.nullNode(); }
                                                                            char c = s.charAt( 0 );
                                                                            if ( c != '{' && c != '[' )
                                                                            { return v; }
                                                                            try { return MAPPER.readTree( s ); } catch ( Exception e ) { return v; }
                                                                        } );
    }

    // ============================================================
    //  list — native array (older firmware shape)
    // ============================================================

    @Test
    @DisplayName( "list() parses native-array value with expanded fields → ScheduleEntry records" )
    void listParsesExpandedEntries() throws Exception
    {
        // Real V17.0 shape captured against a Miniserver
        // (cf. operator log 2026-05-27 21:04:28).
        String body = "["
                      + "{\"uuid\":\"abc-1\",\"name\":\"Période de chauffage\",\"operatingMode\":10,\"calMode\":4,"
                      + " \"startMonth\":10,\"startDay\":1,\"endMonth\":5,\"endDay\":31},"
                      + "{\"uuid\":\"abc-2\",\"name\":\"Lundi de Pâques\",\"operatingMode\":0,\"calMode\":1,"
                      + " \"easterOffset\":1},"
                      + "{\"uuid\":\"abc-3\",\"name\":\"Jour de l'An\",\"operatingMode\":0,\"calMode\":0,"
                      + " \"startMonth\":1,\"startDay\":1}"
                      + "]";
        when( client.sendAndAwait( anyString(), any( Duration.class ) ) ).thenReturn( ok( body ) );

        List< ScheduleEntry > out = service.list();

        assertThat( out ).hasSize( 3 );

        ScheduleEntry e0 = out.get( 0 );
        assertThat( e0.uuid() ).isEqualTo( "abc-1" );
        assertThat( e0.name() ).isEqualTo( "Période de chauffage" );
        assertThat( e0.operatingMode() ).isEqualTo( 10 );
        assertThat( e0.calMode() ).isEqualTo( 4 );
        assertThat( e0.calModeAttrs() ).containsExactlyInAnyOrderEntriesOf( Map.of(
                "startMonth", 10, "startDay", 1, "endMonth", 5, "endDay", 31 ) );

        ScheduleEntry e1 = out.get( 1 );
        assertThat( e1.calMode() ).isEqualTo( 1 );
        assertThat( e1.calModeAttrs() ).containsExactly( Map.entry( "easterOffset", 1 ) );

        ScheduleEntry e2 = out.get( 2 );
        assertThat( e2.calMode() ).isEqualTo( 0 );
        assertThat( e2.calModeAttrs() ).containsExactlyInAnyOrderEntriesOf( Map.of(
                "startMonth", 1, "startDay", 1 ) );

        verify( client ).sendAndAwait( "calendargetentries", Duration.ofSeconds( 8 ) );
    }

    // ============================================================
    //  list — string-encoded array (V17.0 actual wire shape)
    // ============================================================

    @Test
    @DisplayName( "list() parses STRING-ENCODED JSON value → entries" )
    void listParsesStringEncodedJsonValue() throws Exception
    {
        // V17.0 wraps the array as a JSON-encoded string inside `value`.
        // The unwrapValue helper (stubbed in @BeforeEach) re-parses it.
        // Shape verbatim from prod log 2026-05-27 21:04:28.
        String innerArrayLiteral = "[{\"uuid\":\"x\",\"name\":\"X\",\"operatingMode\":1,\"calMode\":0,\"startMonth\":3,\"startDay\":15}]";
        String body              = MAPPER.writeValueAsString( innerArrayLiteral );    // wraps + escapes the string
        when( client.sendAndAwait( anyString(), any( Duration.class ) ) ).thenReturn( ok( body ) );

        List< ScheduleEntry > out = service.list();

        assertThat( out ).hasSize( 1 );
        assertThat( out.get( 0 ).name() ).isEqualTo( "X" );
        assertThat( out.get( 0 ).calModeAttrs() )
                .containsExactlyInAnyOrderEntriesOf( Map.of( "startMonth", 3, "startDay", 15 ) );
    }

    @Test
    @DisplayName( "list() with non-array, non-JSON value but Code=200 → empty list (defensive)" )
    void listHandlesMalformedValue() throws Exception
    {
        when( client.sendAndAwait( anyString(), any( Duration.class ) ) ).thenReturn( ok( "\"oops\"" ) );

        assertThat( service.list() ).isEmpty();
    }

    @Test
    @DisplayName( "list() with Code=403 → AdminCommandException" )
    void listSurfacesForbidden() throws Exception
    {
        // Symmetric to UserServiceTest.listUsersSurfacesForbidden.
        // The calendar requires Op-Modes (0x80); a binding user without
        // that bit would see a 403. The forbidden path used to be
        // silently swallowed and returned an empty list.
        JsonNode forbidden = MAPPER.readTree( "{\"value\":\"\",\"Code\":\"403\"}" );
        when( client.sendAndAwait( anyString(), any( Duration.class ) ) ).thenReturn( forbidden );

        assertThatThrownBy( () -> service.list() )
                .isInstanceOf( AdminCommandException.class )
                .hasMessageContaining( "Code=403" )
                .hasMessageContaining( "lacks the required permission" );
    }

    @Test
    @DisplayName( "list() with empty array → empty list" )
    void listHandlesEmptyArray() throws Exception
    {
        when( client.sendAndAwait( anyString(), any( Duration.class ) ) ).thenReturn( ok( "[]" ) );

        assertThat( service.list() ).isEmpty();
    }

    @Test
    @DisplayName( "list() ignores attribute keys outside the spec V14.4 whitelist" )
    void listIgnoresUnknownAttrKeys() throws Exception
    {
        String body = "["
                      + "{\"uuid\":\"x\",\"name\":\"X\",\"operatingMode\":1,\"calMode\":0,"
                      + " \"startMonth\":3,\"startDay\":15,\"somethingNew\":42}"
                      + "]";
        when( client.sendAndAwait( anyString(), any( Duration.class ) ) ).thenReturn( ok( body ) );

        List< ScheduleEntry > out = service.list();
        assertThat( out ).hasSize( 1 );
        // "somethingNew" not in the whitelist → not in the Map.
        assertThat( out.get( 0 ).calModeAttrs() ).doesNotContainKey( "somethingNew" );
    }

    // ============================================================
    //  create
    // ============================================================

    @Test
    @DisplayName( "create() builds calendarcreateentry/{name}/{opMode}/{calMode}/{attr}" )
    void createBuildsCommand() throws Exception
    {
        when( client.sendAndAwait( anyString(), any( Duration.class ) ) ).thenReturn( ok( "\"ok\"" ) );

        service.create( "Holidays", 2, CalendarMode.SPECIFIC_DATE, "2026/07/15" );

        ArgumentCaptor< String > cmd = ArgumentCaptor.forClass( String.class );
        verify( client ).sendAndAwait( cmd.capture(), any( Duration.class ) );
        assertThat( cmd.getValue() )
                .startsWith( "calendarcreateentry/" )
                .contains( "Holidays" )
                .contains( "/2/" )                  // opMode + calMode SPECIFIC_DATE=2
                .endsWith( "2026%2F07%2F15" );      // attr URL-encoded
    }

    @Test
    @DisplayName( "create() URL-encodes name with spaces / accented chars" )
    void createEncodesName() throws Exception
    {
        when( client.sendAndAwait( anyString(), any( Duration.class ) ) ).thenReturn( ok( "\"ok\"" ) );

        service.create( "Vacances été", 2, CalendarMode.YEARLY_DATE, "7/15" );

        ArgumentCaptor< String > cmd = ArgumentCaptor.forClass( String.class );
        verify( client ).sendAndAwait( cmd.capture(), any( Duration.class ) );
        // URLEncoder uses + for spaces; é = UTF-8 0xC3 0xA9.
        assertThat( cmd.getValue() ).contains( "Vacances+%C3%A9t%C3%A9" );
    }

    @Test
    @DisplayName( "create() rejects empty name" )
    void createRejectsEmptyName()
    {
        assertThatThrownBy( () -> service.create( "", 2, CalendarMode.YEARLY_DATE, "7/15" ) )
                .isInstanceOf( IllegalArgumentException.class )
                .hasMessageContaining( "blank" );
    }

    @Test
    @DisplayName( "create() rejects name with '/'" )
    void createRejectsSlashInName()
    {
        assertThatThrownBy( () -> service.create( "Foo/Bar", 2, CalendarMode.YEARLY_DATE, "7/15" ) )
                .isInstanceOf( IllegalArgumentException.class )
                .hasMessageContaining( "'/'" );
    }

    @Test
    @DisplayName( "create() rejects blank calModeAttr" )
    void createRejectsEmptyAttr()
    {
        assertThatThrownBy( () -> service.create( "Holidays", 2, CalendarMode.YEARLY_DATE, "" ) )
                .isInstanceOf( IllegalArgumentException.class )
                .hasMessageContaining( "calModeAttr" );
    }

    @Test
    @DisplayName( "create() throws when Miniserver returns Code != 200" )
    void createFailsOnBadCode() throws Exception
    {
        JsonNode bad = MAPPER.readTree( "{\"value\":\"nope\",\"Code\":\"401\"}" );
        when( client.sendAndAwait( anyString(), any( Duration.class ) ) ).thenReturn( bad );

        assertThatThrownBy( () ->
                                    service.create( "Holidays", 2, CalendarMode.YEARLY_DATE, "7/15" ) )
                .isInstanceOf( AdminCommandException.class )
                .hasMessageContaining( "Code=401" );
    }

    // ============================================================
    //  update + delete
    // ============================================================

    @Test
    @DisplayName( "update() builds calendarupdateentry/{uuid}/...; rejects bad uuid" )
    void updateBuildsCommandAndValidates() throws Exception
    {
        when( client.sendAndAwait( anyString(), any( Duration.class ) ) ).thenReturn( ok( "\"ok\"" ) );

        String uuid = "0a5fa72f-018b-0050-1900000000000000";
        service.update( uuid, "Renamed", 3, CalendarMode.WEEKDAY, "13/0/1" );

        ArgumentCaptor< String > cmd = ArgumentCaptor.forClass( String.class );
        verify( client ).sendAndAwait( cmd.capture(), any( Duration.class ) );
        assertThat( cmd.getValue() )
                .startsWith( "calendarupdateentry/" + uuid + "/" )
                .contains( "Renamed" )
                .contains( "/3/" )    // opMode
                .contains( "/5/" );   // WEEKDAY code

        assertThatThrownBy( () -> service.update( "short", "x", 2, CalendarMode.YEARLY_DATE, "7/15" ) )
                .isInstanceOf( IllegalArgumentException.class );
    }

    @Test
    @DisplayName( "delete() builds calendardeleteentry/{uuid}" )
    void deleteBuildsCommand() throws Exception
    {
        when( client.sendAndAwait( anyString(), any( Duration.class ) ) ).thenReturn( ok( "\"ok\"" ) );

        String uuid = "abc-123-def-456-aaaaaaaaaaaa1234";
        service.delete( uuid );

        verify( client ).sendAndAwait( "calendardeleteentry/" + uuid, Duration.ofSeconds( 8 ) );
    }

    // ============================================================
    //  heat / cool period
    // ============================================================

    @Test
    @DisplayName( "getHeatPeriod() returns the value field as-is" )
    void heatPeriodReturnsString() throws Exception
    {
        when( client.sendAndAwait( anyString(), any( Duration.class ) ) ).thenReturn( ok( "\"10-15/04-15\"" ) );

        assertThat( service.getHeatPeriod() ).isEqualTo( "10-15/04-15" );
        verify( client ).sendAndAwait( "calendargetheatperiod", Duration.ofSeconds( 8 ) );
    }

    @Test
    @DisplayName( "getCoolPeriod() returns the value field as-is" )
    void coolPeriodReturnsString() throws Exception
    {
        when( client.sendAndAwait( anyString(), any( Duration.class ) ) ).thenReturn( ok( "\"05-15/09-15\"" ) );

        assertThat( service.getCoolPeriod() ).isEqualTo( "05-15/09-15" );
        verify( client ).sendAndAwait( "calendargetcoolperiod", Duration.ofSeconds( 8 ) );
    }
}
