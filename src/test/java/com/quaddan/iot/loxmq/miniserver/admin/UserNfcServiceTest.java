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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link UserNfcService}.
 *
 * <p>Focus :
 * <ul>
 *   <li>Wire format of {@code discovernfc} / {@code addusernfc} /
 *       {@code removeusernfc}.</li>
 *   <li>Tag-ID validation (hex, optional {@code :} separators, length
 *       bounds).</li>
 *   <li>Friendly-name optionality — omitted from the path when null /
 *       blank.</li>
 *   <li>The 3 reply shapes for {@code discovernfc} (string, object,
 *       empty).</li>
 * </ul>
 */
@DisplayName( "UserNfcService — wire format + tag validation" )
class UserNfcServiceTest
{
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode ok() throws Exception
    {
        return MAPPER.readTree( "{\"value\":\"ok\",\"Code\":\"200\"}" );
    }

    private static UserDetail userDetail( String uuid, String name )
    {
        return new UserDetail( uuid, name, "", "", "", "", "", "", "", "", "", "", "", "",
                               "", "", "", "", "", 0L, 0, null, null, null, false, false, List.of(), List.of() );
    }

    private MiniserverAdminCommandClient client;
    private UserService                  userService;
    private UserNfcService               nfc;

    @BeforeEach
    void setUp() throws Exception
    {
        client      = mock( MiniserverAdminCommandClient.class );
        userService = mock( UserService.class );

        nfc             = new UserNfcService();
        nfc.adminClient = client;
        nfc.userService = userService;

        // unwrapValue mirror (mirror of MiniserverAdminCommandClient impl)
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
    //  discoverTag
    // ============================================================

    @Test
    @DisplayName( "discoverTag() — string value returned verbatim" )
    void discoverStringShape() throws Exception
    {
        when( client.sendAndAwait( anyString(), any( Duration.class ) ) ).thenReturn(
                MAPPER.readTree( "{\"value\":\"AA:BB:CC:DD\",\"Code\":\"200\"}" ) );

        String tagId = nfc.discoverTag();

        assertThat( tagId ).isEqualTo( "AA:BB:CC:DD" );
        verify( client ).sendAndAwait( "discovernfc", Duration.ofSeconds( 60 ) );
    }

    @Test
    @DisplayName( "discoverTag() — object value with tagId field" )
    void discoverObjectShapeTagId() throws Exception
    {
        when( client.sendAndAwait( anyString(), any( Duration.class ) ) ).thenReturn(
                MAPPER.readTree( "{\"value\":{\"tagId\":\"DEADBEEF\"},\"Code\":\"200\"}" ) );

        assertThat( nfc.discoverTag() ).isEqualTo( "DEADBEEF" );
    }

    @Test
    @DisplayName( "discoverTag() — object value with `id` fallback" )
    void discoverObjectShapeId() throws Exception
    {
        when( client.sendAndAwait( anyString(), any( Duration.class ) ) ).thenReturn(
                MAPPER.readTree( "{\"value\":{\"id\":\"ABCD\"},\"Code\":\"200\"}" ) );

        assertThat( nfc.discoverTag() ).isEqualTo( "ABCD" );
    }

    @Test
    @DisplayName( "discoverTag() — empty value → empty string (no tap before timeout)" )
    void discoverEmpty() throws Exception
    {
        when( client.sendAndAwait( anyString(), any( Duration.class ) ) ).thenReturn(
                MAPPER.readTree( "{\"value\":\"\",\"Code\":\"200\"}" ) );

        assertThat( nfc.discoverTag() ).isEmpty();
    }

    @Test
    @DisplayName( "discoverTag() — Miniserver returns Code != 200 → AdminCommandException" )
    void discoverFailsOnBadCode() throws Exception
    {
        when( client.sendAndAwait( anyString(), any( Duration.class ) ) ).thenReturn(
                MAPPER.readTree( "{\"value\":\"\",\"Code\":\"403\"}" ) );

        assertThatThrownBy( () -> nfc.discoverTag() )
                .isInstanceOf( AdminCommandException.class )
                .hasMessageContaining( "Code=403" );
    }

    // ============================================================
    //  addTagToUser
    // ============================================================

    @Test
    @DisplayName( "addTagToUser(uuid, tag, name) → addusernfc/{uuid}/{tag}/{enc(name)}" )
    void addWithName() throws Exception
    {
        String uuid = "0a5fa72f-018b-0050-1900000000000000";
        when( userService.getUser( uuid ) ).thenReturn( userDetail( uuid, "Bob" ) );
        when( client.sendAndAwait( anyString(), any( Duration.class ) ) ).thenReturn( ok() );

        nfc.addTagToUser( uuid, "AA:BB:CC:DD", "Bob's badge" );

        ArgumentCaptor< String > cmd = ArgumentCaptor.forClass( String.class );
        verify( client ).sendAndAwait( cmd.capture(), any( Duration.class ) );
        // Name is URL-encoded (space → '+')
        assertThat( cmd.getValue() ).isEqualTo(
                "addusernfc/" + uuid + "/AA:BB:CC:DD/Bob%27s+badge" );
    }

    @Test
    @DisplayName( "addTagToUser(uuid, tag, null) → addusernfc/{uuid}/{tag} (no name segment)" )
    void addWithoutName() throws Exception
    {
        String uuid = "0a5fa72f-018b-0050-1900000000000000";
        when( userService.getUser( uuid ) ).thenReturn( userDetail( uuid, "Bob" ) );
        when( client.sendAndAwait( anyString(), any( Duration.class ) ) ).thenReturn( ok() );

        nfc.addTagToUser( uuid, "AABBCCDD", null );

        ArgumentCaptor< String > cmd = ArgumentCaptor.forClass( String.class );
        verify( client ).sendAndAwait( cmd.capture(), any( Duration.class ) );
        assertThat( cmd.getValue() ).isEqualTo( "addusernfc/" + uuid + "/AABBCCDD" );
    }

    @Test
    @DisplayName( "addTagToUser(uuid, tag, blank) treats blank as missing → no name segment" )
    void addWithBlankName() throws Exception
    {
        String uuid = "0a5fa72f-018b-0050-1900000000000000";
        when( userService.getUser( uuid ) ).thenReturn( userDetail( uuid, "Bob" ) );
        when( client.sendAndAwait( anyString(), any( Duration.class ) ) ).thenReturn( ok() );

        nfc.addTagToUser( uuid, "AABBCCDD", "   " );

        ArgumentCaptor< String > cmd = ArgumentCaptor.forClass( String.class );
        verify( client ).sendAndAwait( cmd.capture(), any( Duration.class ) );
        assertThat( cmd.getValue() ).isEqualTo( "addusernfc/" + uuid + "/AABBCCDD" );
    }

    @Test
    @DisplayName( "addTagToUser — short uuid rejected early, no client call" )
    void addRejectsShortUuid()
    {
        assertThatThrownBy( () -> nfc.addTagToUser( "short", "AABB", "x" ) )
                .isInstanceOf( IllegalArgumentException.class );
        verify( userService, never() ).getUser( anyString() );
    }

    @Test
    @DisplayName( "addTagToUser — non-hex tag rejected" )
    void addRejectsNonHexTag()
    {
        String uuid = "0a5fa72f-018b-0050-1900000000000000";
        assertThatThrownBy( () -> nfc.addTagToUser( uuid, "GGHHIIJJ", null ) )
                .isInstanceOf( IllegalArgumentException.class )
                .hasMessageContaining( "hex" );
        assertThatThrownBy( () -> nfc.addTagToUser( uuid, "AABB CCDD", null ) )
                .isInstanceOf( IllegalArgumentException.class );
    }

    @Test
    @DisplayName( "addTagToUser — tag with `:` separators accepted" )
    void addAcceptsColonSeparators() throws Exception
    {
        String uuid = "0a5fa72f-018b-0050-1900000000000000";
        when( userService.getUser( uuid ) ).thenReturn( userDetail( uuid, "Bob" ) );
        when( client.sendAndAwait( anyString(), any( Duration.class ) ) ).thenReturn( ok() );

        nfc.addTagToUser( uuid, "AA:BB:CC:DD:EE:FF:00:11", null );
        verify( client ).sendAndAwait( anyString(), any( Duration.class ) );
    }

    @Test
    @DisplayName( "addTagToUser — empty tag rejected" )
    void addRejectsEmptyTag()
    {
        String uuid = "0a5fa72f-018b-0050-1900000000000000";
        assertThatThrownBy( () -> nfc.addTagToUser( uuid, "", null ) )
                .isInstanceOf( IllegalArgumentException.class );
        assertThatThrownBy( () -> nfc.addTagToUser( uuid, null, null ) )
                .isInstanceOf( IllegalArgumentException.class );
    }

    @Test
    @DisplayName( "addTagToUser — too-long name rejected" )
    void addRejectsLongName()
    {
        String uuid = "0a5fa72f-018b-0050-1900000000000000";
        when( userService.getUser( uuid ) ).thenReturn( userDetail( uuid, "Bob" ) );
        String tooLong = "a".repeat( 200 );
        assertThatThrownBy( () -> nfc.addTagToUser( uuid, "AABB", tooLong ) )
                .isInstanceOf( IllegalArgumentException.class );
    }

    // ============================================================
    //  removeTagFromUser
    // ============================================================

    @Test
    @DisplayName( "removeTagFromUser(uuid, tag) → removeusernfc/{uuid}/{tag}" )
    void removeHappyPath() throws Exception
    {
        String uuid = "0a5fa72f-018b-0050-1900000000000000";
        when( userService.getUser( uuid ) ).thenReturn( userDetail( uuid, "Bob" ) );
        when( client.sendAndAwait( anyString(), any( Duration.class ) ) ).thenReturn( ok() );

        nfc.removeTagFromUser( uuid, "AA:BB:CC:DD" );

        verify( client ).sendAndAwait( "removeusernfc/" + uuid + "/AA:BB:CC:DD",
                                       Duration.ofSeconds( 12 ) );
    }

    @Test
    @DisplayName( "removeTagFromUser — Miniserver Code != 200 → AdminCommandException" )
    void removeFailsOnBadCode() throws Exception
    {
        String uuid = "0a5fa72f-018b-0050-1900000000000000";
        when( userService.getUser( uuid ) ).thenReturn( userDetail( uuid, "Bob" ) );

        JsonNode bad = MAPPER.readTree( "{\"value\":\"\",\"Code\":\"404\"}" );
        when( client.sendAndAwait( anyString(), any( Duration.class ) ) ).thenReturn( bad );

        assertThatThrownBy( () -> nfc.removeTagFromUser( uuid, "AABB" ) )
                .isInstanceOf( AdminCommandException.class )
                .hasMessageContaining( "Code=404" );
    }

    @Test
    @DisplayName( "removeTagFromUser — short uuid rejected" )
    void removeRejectsShortUuid()
    {
        assertThatThrownBy( () -> nfc.removeTagFromUser( "short", "AABB" ) )
                .isInstanceOf( IllegalArgumentException.class );
    }

    @Test
    @DisplayName( "removeTagFromUser — non-hex tag rejected" )
    void removeRejectsNonHexTag()
    {
        String uuid = "0a5fa72f-018b-0050-1900000000000000";
        assertThatThrownBy( () -> nfc.removeTagFromUser( uuid, "XYZ" ) )
                .isInstanceOf( IllegalArgumentException.class );
    }
}
