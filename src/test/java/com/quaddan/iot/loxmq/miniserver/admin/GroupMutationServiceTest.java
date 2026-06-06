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

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link GroupMutationService}.
 *
 * <p>Mirror of {@code UserMutationServiceTest} for the group side of
 * the user-management model. Focus:
 * <ul>
 *   <li>Wire format of {@code addeditgroup} (with / without uuid in
 *       body) and {@code deletegroup}.</li>
 *   <li><em>null = do not send</em> semantics on the patch fields.</li>
 *   <li>Validation: name required on create, blank rejected, uuid
 *       length on edit/delete.</li>
 *   <li>Code != 200 from the Miniserver → {@link AdminCommandException}
 *       surfaced.</li>
 * </ul>
 */
@DisplayName( "GroupMutationService — wire format + validation" )
class GroupMutationServiceTest
{
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode ok( String valueLiteral ) throws Exception
    {
        return MAPPER.readTree( "{\"value\":" + valueLiteral + ",\"Code\":\"200\"}" );
    }

    private MiniserverAdminCommandClient client;
    private GroupMutationService         service;

    @BeforeEach
    void setUp()
    {
        client              = mock( MiniserverAdminCommandClient.class );
        service             = new GroupMutationService();
        service.adminClient = client;
        service.jsonMapper  = MAPPER;

        // Mirror unwrapValue from the real adminClient.
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
    //  createGroup
    // ============================================================

    @Test
    @DisplayName( "createGroup(name) — sends addeditgroup without uuid, returns CreatedGroup" )
    void createHappyPath() throws Exception
    {
        when( client.sendAndAwait( anyString(), any( Duration.class ) ) ).thenReturn(
                MAPPER.readTree( "{\"value\":{\"uuid\":\"089396d4-0207-0119-1900000000000000\","
                                 + "\"name\":\"Visitors\"},\"Code\":\"200\"}" ) );

        EditGroupRequest spec    = new EditGroupRequest( "Visitors", "Guest access" );
        CreatedGroup     created = service.createGroup( spec );

        assertThat( created.uuid() ).isEqualTo( "089396d4-0207-0119-1900000000000000" );
        assertThat( created.name() ).isEqualTo( "Visitors" );

        ArgumentCaptor< String > cmd = ArgumentCaptor.forClass( String.class );
        verify( client ).sendAndAwait( cmd.capture(), any( Duration.class ) );
        assertThat( cmd.getValue() ).startsWith( "addeditusergroup/" );
        String body = URLDecoder.decode( cmd.getValue().substring( "addeditusergroup/".length() ),
                                         StandardCharsets.UTF_8 );
        JsonNode parsed = MAPPER.readTree( body );
        assertThat( parsed.path( "name" ).asText() ).isEqualTo( "Visitors" );
        assertThat( parsed.path( "description" ).asText() ).isEqualTo( "Guest access" );
        assertThat( parsed.has( "uuid" ) ).isFalse();
    }

    @Test
    @DisplayName( "createGroup(name only) — description absent from JSON" )
    void createMinimal() throws Exception
    {
        when( client.sendAndAwait( anyString(), any( Duration.class ) ) ).thenReturn(
                MAPPER.readTree( "{\"value\":{\"uuid\":\"089396d4-0207-0119-1900000000000000\","
                                 + "\"name\":\"Custom\"},\"Code\":\"200\"}" ) );

        service.createGroup( new EditGroupRequest( "Custom", null ) );

        ArgumentCaptor< String > cmd = ArgumentCaptor.forClass( String.class );
        verify( client ).sendAndAwait( cmd.capture(), any( Duration.class ) );
        String body = URLDecoder.decode( cmd.getValue().substring( "addeditusergroup/".length() ),
                                         StandardCharsets.UTF_8 );
        JsonNode parsed = MAPPER.readTree( body );
        assertThat( parsed.path( "name" ).asText() ).isEqualTo( "Custom" );
        assertThat( parsed.has( "description" ) ).isFalse();
    }

    @Test
    @DisplayName( "createGroup(null) → IllegalArgumentException" )
    void createRejectsNull()
    {
        assertThatThrownBy( () -> service.createGroup( null ) )
                .isInstanceOf( IllegalArgumentException.class )
                .hasMessageContaining( "Name is required" );
        verify( client, never() ).sendAndAwait( anyString(), any( Duration.class ) );
    }

    @Test
    @DisplayName( "createGroup(blank name) → IllegalArgumentException" )
    void createRejectsBlankName()
    {
        assertThatThrownBy( () -> service.createGroup( new EditGroupRequest( "   ", null ) ) )
                .isInstanceOf( IllegalArgumentException.class )
                .hasMessageContaining( "Name is required" );
        assertThatThrownBy( () -> service.createGroup( new EditGroupRequest( null, "desc" ) ) )
                .isInstanceOf( IllegalArgumentException.class );
        verify( client, never() ).sendAndAwait( anyString(), any( Duration.class ) );
    }

    @Test
    @DisplayName( "createGroup — Miniserver returns 200 but no uuid → AdminCommandException" )
    void createFailsOnMissingUuid() throws Exception
    {
        when( client.sendAndAwait( anyString(), any( Duration.class ) ) ).thenReturn(
                MAPPER.readTree( "{\"value\":{\"name\":\"X\"},\"Code\":\"200\"}" ) );

        assertThatThrownBy( () -> service.createGroup( new EditGroupRequest( "X", null ) ) )
                .isInstanceOf( AdminCommandException.class )
                .hasMessageContaining( "no uuid" );
    }

    // ============================================================
    //  editGroup
    // ============================================================

    @Test
    @DisplayName( "editGroup — sends addeditgroup with uuid + only non-null fields" )
    void editSendsOnlyNonNullFields() throws Exception
    {
        String uuid = "089396d4-0207-0119-1900000000000000";
        when( client.sendAndAwait( anyString(), any( Duration.class ) ) ).thenReturn( ok( "\"ok\"" ) );

        service.editGroup( uuid, new EditGroupRequest( null, "Updated desc" ) );

        ArgumentCaptor< String > cmd = ArgumentCaptor.forClass( String.class );
        verify( client ).sendAndAwait( cmd.capture(), any( Duration.class ) );
        String body = URLDecoder.decode( cmd.getValue().substring( "addeditusergroup/".length() ),
                                         StandardCharsets.UTF_8 );
        JsonNode parsed = MAPPER.readTree( body );
        assertThat( parsed.path( "uuid" ).asText() ).isEqualTo( uuid );
        assertThat( parsed.path( "description" ).asText() ).isEqualTo( "Updated desc" );
        // name was null → must NOT be in the JSON
        assertThat( parsed.has( "name" ) ).isFalse();
    }

    @Test
    @DisplayName( "editGroup — rename only" )
    void editRenameOnly() throws Exception
    {
        String uuid = "089396d4-0207-0119-1900000000000000";
        when( client.sendAndAwait( anyString(), any( Duration.class ) ) ).thenReturn( ok( "\"ok\"" ) );

        service.editGroup( uuid, new EditGroupRequest( "Renamed", null ) );

        ArgumentCaptor< String > cmd = ArgumentCaptor.forClass( String.class );
        verify( client ).sendAndAwait( cmd.capture(), any( Duration.class ) );
        String body = URLDecoder.decode( cmd.getValue().substring( "addeditusergroup/".length() ),
                                         StandardCharsets.UTF_8 );
        JsonNode parsed = MAPPER.readTree( body );
        assertThat( parsed.path( "uuid" ).asText() ).isEqualTo( uuid );
        assertThat( parsed.path( "name" ).asText() ).isEqualTo( "Renamed" );
        assertThat( parsed.has( "description" ) ).isFalse();
    }

    @Test
    @DisplayName( "editGroup(null patch) → IllegalArgumentException" )
    void editRejectsNullPatch()
    {
        String uuid = "089396d4-0207-0119-1900000000000000";
        assertThatThrownBy( () -> service.editGroup( uuid, null ) )
                .isInstanceOf( IllegalArgumentException.class );
    }

    @Test
    @DisplayName( "editGroup(short uuid) → IllegalArgumentException early" )
    void editRejectsShortUuid()
    {
        assertThatThrownBy( () ->
                                    service.editGroup( "short", new EditGroupRequest( "X", null ) ) )
                .isInstanceOf( IllegalArgumentException.class );
        verify( client, never() ).sendAndAwait( anyString(), any( Duration.class ) );
    }

    @Test
    @DisplayName( "editGroup — Miniserver Code != 200 → AdminCommandException" )
    void editFailsOnBadCode() throws Exception
    {
        String   uuid = "089396d4-0207-0119-1900000000000000";
        JsonNode bad  = MAPPER.readTree( "{\"value\":\"nope\",\"Code\":\"403\"}" );
        when( client.sendAndAwait( anyString(), any( Duration.class ) ) ).thenReturn( bad );

        assertThatThrownBy( () ->
                                    service.editGroup( uuid, new EditGroupRequest( "X", null ) ) )
                .isInstanceOf( AdminCommandException.class )
                .hasMessageContaining( "Code=403" );
    }

    // ============================================================
    //  deleteGroup
    // ============================================================

    @Test
    @DisplayName( "deleteGroup(uuid) — sends deletegroup/{uuid}" )
    void deleteHappyPath() throws Exception
    {
        String uuid = "089396d4-0207-0119-1900000000000000";
        when( client.sendAndAwait( anyString(), any( Duration.class ) ) ).thenReturn( ok( "\"ok\"" ) );

        service.deleteGroup( uuid );

        verify( client ).sendAndAwait( "deleteusergroup/" + uuid, Duration.ofSeconds( 12 ) );
    }

    @Test
    @DisplayName( "deleteGroup(short uuid) → IllegalArgumentException" )
    void deleteRejectsShortUuid()
    {
        assertThatThrownBy( () -> service.deleteGroup( "short" ) )
                .isInstanceOf( IllegalArgumentException.class );
        verify( client, never() ).sendAndAwait( anyString(), any( Duration.class ) );
    }

    @Test
    @DisplayName( "deleteGroup — Miniserver refuses (system group) → AdminCommandException" )
    void deleteFailsOnBadCode() throws Exception
    {
        String   uuid = "089396d4-0207-0119-1900000000000000";
        JsonNode bad  = MAPPER.readTree( "{\"value\":\"\",\"Code\":\"400\"}" );
        when( client.sendAndAwait( anyString(), any( Duration.class ) ) ).thenReturn( bad );

        assertThatThrownBy( () -> service.deleteGroup( uuid ) )
                .isInstanceOf( AdminCommandException.class )
                .hasMessageContaining( "Code=400" );
    }
}
