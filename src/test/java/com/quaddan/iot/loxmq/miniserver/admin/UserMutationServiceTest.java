/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quaddan.iot.loxmq.config.LoxoneConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
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
 * Unit tests for {@link UserMutationService} — focus on the safety
 * guards (admin protection + self protection) and the wire-format of
 * the mutation commands. Audit logging is verified by inspecting the
 * log output side-channel (not strictly required at unit level — it's
 * a side effect — but good to know the call site doesn't throw on the
 * audit calls themselves).
 *
 * <p>Covers {@code createUser}, {@code deleteUser}, {@code editUser}
 * plus the self-protection guard on {@code disable}.
 */
@DisplayName( "UserMutationService — guards + wire builders" )
class UserMutationServiceTest
{
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The binding user — base64-wrapped because the config field is
     *  expected to be base64-encoded at rest. Used to test the
     *  self-protection guard. */
    private static final String BINDING_USER_NAME = "loxmq";
    private static final String BINDING_USER_B64  = Base64.getEncoder()
                                                          .encodeToString( BINDING_USER_NAME.getBytes( StandardCharsets.UTF_8 ) );

    private static JsonNode ok( String valueLiteral ) throws Exception
    {
        return MAPPER.readTree( "{\"value\":" + valueLiteral + ",\"Code\":\"200\"}" );
    }

    /** Build a UserDetail with the bare minimum to satisfy the
     *  admin-protection branch. */
    private static UserDetail userDetail( String uuid, String name, boolean isAdmin )
    {
        return new UserDetail( uuid, name, "", "", "", "", "", "", "", "", "", "", "", "",
                               "", "", "", "", "", 0L, 0, null, null, null, isAdmin, false,
                               List.of(), List.of() );
    }

    private MiniserverAdminCommandClient client;
    private UserService                  userService;
    private LoxoneConfig                 config;
    private UserMutationService          mutations;

    @BeforeEach
    void setUp()
    {
        client                = mock( MiniserverAdminCommandClient.class );
        userService           = mock( UserService.class );
        config                = mockConfig( BINDING_USER_B64 );
        mutations             = new UserMutationService();
        mutations.adminClient = client;
        mutations.userService = userService;
        mutations.config      = config;
        mutations.jsonMapper  = MAPPER;

        // unwrapValue is used by createUser to extract the new uuid —
        // stub it through to the actual ObjectMapper so we don't have to
        // re-mock its behaviour on every test.
        when( client.unwrapValue( any( JsonNode.class ) ) )
                .thenAnswer( inv ->
                             {
                                 JsonNode ll = inv.getArgument( 0 );
                                 if ( ll == null )
                                 { return MAPPER.nullNode(); }
                                 JsonNode value = ll.path( "value" );
                                 if ( !value.isTextual() )
                                 { return value; }
                                 String asText = value.asText( "" );
                                 if ( asText.isEmpty() )
                                 { return MAPPER.nullNode(); }
                                 char first = asText.charAt( 0 );
                                 if ( first != '{' && first != '[' )
                                 { return value; }
                                 return MAPPER.readTree( asText );
                             } );
    }

    /** Build a deep mock-chain mirroring {@code config.miniserver()
     *  .security().credentials().user()}. We only need that one method
     *  → trivial to build with Mockito chained mocks. */
    private static LoxoneConfig mockConfig( String userB64 )
    {
        var creds = mock( LoxoneConfig.Miniserver.Security.Credentials.class );
        when( creds.user() ).thenReturn( userB64 );
        var sec = mock( LoxoneConfig.Miniserver.Security.class );
        when( sec.credentials() ).thenReturn( creds );
        var ms = mock( LoxoneConfig.Miniserver.class );
        when( ms.security() ).thenReturn( sec );
        var cfg = mock( LoxoneConfig.class );
        when( cfg.miniserver() ).thenReturn( ms );
        return cfg;
    }

    // ============================================================
    //  create
    // ============================================================

    @Test
    @DisplayName( "createUser(name) — sends addoredituser without uuid, returns CreatedUser" )
    void createUserHappyPath() throws Exception
    {
        when( client.sendAndAwait( anyString(), any( Duration.class ) ) ).thenReturn(
                MAPPER.readTree( "{\"value\":{\"uuid\":\"0a5fa72f-018b-0050-19abc\",\"name\":\"Alice\"},\"Code\":\"200\"}" ) );

        EditUserRequest spec = new EditUserRequest( "Alice", null, null, null, null, null,
                                                    null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null );
        CreatedUser created = mutations.createUser( spec );

        assertThat( created.uuid() ).isEqualTo( "0a5fa72f-018b-0050-19abc" );
        assertThat( created.name() ).isEqualTo( "Alice" );

        ArgumentCaptor< String > cmd = ArgumentCaptor.forClass( String.class );
        verify( client ).sendAndAwait( cmd.capture(), any( Duration.class ) );
        assertThat( cmd.getValue() ).startsWith( "addoredituser/" );
        // Decode the URL-encoded JSON body and check the name field is
        // there + no uuid field (create path strips it).
        String body = URLDecoder.decode( cmd.getValue().substring( "addoredituser/".length() ),
                                         StandardCharsets.UTF_8 );
        JsonNode parsed = MAPPER.readTree( body );
        assertThat( parsed.path( "name" ).asText() ).isEqualTo( "Alice" );
        assertThat( parsed.has( "uuid" ) ).isFalse();
    }

    @Test
    @DisplayName( "createUser(spec with metadata) — forwards non-null fields" )
    void createUserForwardsMetadata() throws Exception
    {
        when( client.sendAndAwait( anyString(), any( Duration.class ) ) ).thenReturn(
                MAPPER.readTree( "{\"value\":{\"uuid\":\"0a5fa72f-018b-0050-19def\",\"name\":\"Bob\"},\"Code\":\"200\"}" ) );

        EditUserRequest spec = new EditUserRequest(
                "Bob", "Acme employee", null, "Bob", "Builder", "bob@acme.io",
                null, null, "Acme", null, null, "Lead", null, null, null, null,
                null, null, 0, null, null, null, true );
        mutations.createUser( spec );

        ArgumentCaptor< String > cmd = ArgumentCaptor.forClass( String.class );
        verify( client ).sendAndAwait( cmd.capture(), any( Duration.class ) );
        String body = URLDecoder.decode( cmd.getValue().substring( "addoredituser/".length() ),
                                         StandardCharsets.UTF_8 );
        JsonNode parsed = MAPPER.readTree( body );
        assertThat( parsed.path( "name" ).asText() ).isEqualTo( "Bob" );
        assertThat( parsed.path( "desc" ).asText() ).isEqualTo( "Acme employee" );
        assertThat( parsed.path( "firstname" ).asText() ).isEqualTo( "Bob" );
        assertThat( parsed.path( "lastname" ).asText() ).isEqualTo( "Builder" );
        assertThat( parsed.path( "email" ).asText() ).isEqualTo( "bob@acme.io" );
        assertThat( parsed.path( "company" ).asText() ).isEqualTo( "Acme" );
        assertThat( parsed.path( "title" ).asText() ).isEqualTo( "Lead" );
        assertThat( parsed.path( "userState" ).asInt() ).isEqualTo( 0 );
        assertThat( parsed.path( "changePassword" ).asBoolean() ).isTrue();
        // null fields are NOT in the JSON
        assertThat( parsed.has( "phone" ) ).isFalse();
        assertThat( parsed.has( "department" ) ).isFalse();
        assertThat( parsed.has( "uuid" ) ).isFalse();
    }

    @Test
    @DisplayName( "createUser(null) → IllegalArgumentException" )
    void createUserRejectsNull()
    {
        assertThatThrownBy( () -> mutations.createUser( null ) )
                .isInstanceOf( IllegalArgumentException.class )
                .hasMessageContaining( "Name is required" );
        verify( client, never() ).sendAndAwait( anyString(), any( Duration.class ) );
    }

    @Test
    @DisplayName( "createUser(blank name) → IllegalArgumentException" )
    void createUserRejectsBlankName()
    {
        EditUserRequest blank = new EditUserRequest( "  ", null, null, null, null, null,
                                                     null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null );
        assertThatThrownBy( () -> mutations.createUser( blank ) )
                .isInstanceOf( IllegalArgumentException.class )
                .hasMessageContaining( "Name is required" );
        verify( client, never() ).sendAndAwait( anyString(), any( Duration.class ) );
    }

    @Test
    @DisplayName( "createUser — Miniserver returns 200 but no uuid → AdminCommandException" )
    void createUserFailsOnMissingUuid() throws Exception
    {
        when( client.sendAndAwait( anyString(), any( Duration.class ) ) ).thenReturn(
                MAPPER.readTree( "{\"value\":{\"name\":\"Alice\"},\"Code\":\"200\"}" ) );

        EditUserRequest spec = new EditUserRequest( "Alice", null, null, null, null, null,
                                                    null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null );
        assertThatThrownBy( () -> mutations.createUser( spec ) )
                .isInstanceOf( AdminCommandException.class )
                .hasMessageContaining( "no uuid" );
    }

    // ============================================================
    //  delete
    // ============================================================

    @Test
    @DisplayName( "deleteUser(non-admin, non-self) → sends deleteuser/{uuid}" )
    void deleteUserHappyPath() throws Exception
    {
        String uuid = "0a5fa72f-018b-0050-1900000000000000";
        when( userService.getUser( uuid ) ).thenReturn( userDetail( uuid, "Bob", false ) );
        when( client.sendAndAwait( anyString(), any( Duration.class ) ) ).thenReturn( ok( "\"ok\"" ) );

        mutations.deleteUser( uuid );

        verify( client ).sendAndAwait( "deleteuser/" + uuid, Duration.ofSeconds( 12 ) );
    }

    @Test
    @DisplayName( "deleteUser(admin) without force → IllegalArgumentException, NO command sent" )
    void deleteUserRefusesAdmin()
    {
        String uuid = "0a5fa72f-018b-0050-1900000000000000";
        when( userService.getUser( uuid ) ).thenReturn( userDetail( uuid, "Administrator", true ) );

        assertThatThrownBy( () -> mutations.deleteUser( uuid ) )
                .isInstanceOf( IllegalArgumentException.class )
                .hasMessageContaining( "admin" )
                .hasMessageContaining( "Administrator" )
                .hasMessageContaining( "force=true" );

        verify( client, never() ).sendAndAwait( anyString(), any( Duration.class ) );
    }

    @Test
    @DisplayName( "deleteUser(admin, force=true) — admin guard override → command sent" )
    void deleteUserForceAllowsAdmin() throws Exception
    {
        String uuid = "0a5fa72f-018b-0050-1900000000000000";
        when( userService.getUser( uuid ) ).thenReturn( userDetail( uuid, "Administrator", true ) );
        when( client.sendAndAwait( anyString(), any( Duration.class ) ) ).thenReturn( ok( "\"ok\"" ) );

        mutations.deleteUser( uuid, true );

        verify( client ).sendAndAwait( "deleteuser/" + uuid, Duration.ofSeconds( 12 ) );
    }

    @Test
    @DisplayName( "deleteUser(binding user) → IllegalArgumentException, NO command sent" )
    void deleteUserRefusesSelf()
    {
        String uuid = "0a5fa72f-018b-0050-1900000000000000";
        when( userService.getUser( uuid ) ).thenReturn(
                userDetail( uuid, BINDING_USER_NAME, false ) );

        assertThatThrownBy( () -> mutations.deleteUser( uuid ) )
                .isInstanceOf( IllegalArgumentException.class )
                .hasMessageContaining( "binding's own login" );

        verify( client, never() ).sendAndAwait( anyString(), any( Duration.class ) );
    }

    @Test
    @DisplayName( "deleteUser(self, force=true) — self-guard is UNCONDITIONAL" )
    void deleteUserForceStillRefusesSelf()
    {
        String uuid = "0a5fa72f-018b-0050-1900000000000000";
        when( userService.getUser( uuid ) ).thenReturn(
                userDetail( uuid, BINDING_USER_NAME, false ) );

        assertThatThrownBy( () -> mutations.deleteUser( uuid, true ) )
                .isInstanceOf( IllegalArgumentException.class )
                .hasMessageContaining( "binding's own login" );

        verify( client, never() ).sendAndAwait( anyString(), any( Duration.class ) );
    }

    @Test
    @DisplayName( "deleteUser(self admin, force=true) — self-guard wins over admin-guard" )
    void deleteUserSelfAdminForceStillRefuses()
    {
        // Edge case : the binding user is also admin. Self-guard must
        // fire FIRST (it's checked before the admin guard in the impl).
        String uuid = "0a5fa72f-018b-0050-1900000000000000";
        when( userService.getUser( uuid ) ).thenReturn(
                userDetail( uuid, BINDING_USER_NAME, true ) );

        assertThatThrownBy( () -> mutations.deleteUser( uuid, true ) )
                .isInstanceOf( IllegalArgumentException.class )
                .hasMessageContaining( "binding's own login" );

        verify( client, never() ).sendAndAwait( anyString(), any( Duration.class ) );
    }

    @Test
    @DisplayName( "deleteUser(short uuid) → IllegalArgumentException early" )
    void deleteUserRejectsBadUuid()
    {
        assertThatThrownBy( () -> mutations.deleteUser( "short" ) )
                .isInstanceOf( IllegalArgumentException.class );
        verify( userService, never() ).getUser( anyString() );
        verify( client, never() ).sendAndAwait( anyString(), any( Duration.class ) );
    }

    // ============================================================
    //  edit metadata
    // ============================================================

    @Test
    @DisplayName( "editUser — sends addoredituser with uuid + only non-null fields" )
    void editUserSendsOnlyNonNullFields() throws Exception
    {
        String uuid = "0a5fa72f-018b-0050-1900000000000000";
        when( userService.getUser( uuid ) ).thenReturn( userDetail( uuid, "Bob", false ) );
        when( client.sendAndAwait( anyString(), any( Duration.class ) ) ).thenReturn( ok( "\"ok\"" ) );

        EditUserRequest patch = new EditUserRequest(
                null, "Updated desc", null, null, null, "bob@new.io",
                null, null, null, null, null, null, null,
                "slot1", null, null, null, null, null, null, null, null, null );
        mutations.editUser( uuid, patch );

        ArgumentCaptor< String > cmd = ArgumentCaptor.forClass( String.class );
        verify( client ).sendAndAwait( cmd.capture(), any( Duration.class ) );
        String body = URLDecoder.decode( cmd.getValue().substring( "addoredituser/".length() ),
                                         StandardCharsets.UTF_8 );
        JsonNode parsed = MAPPER.readTree( body );
        assertThat( parsed.path( "uuid" ).asText() ).isEqualTo( uuid );
        assertThat( parsed.path( "desc" ).asText() ).isEqualTo( "Updated desc" );
        assertThat( parsed.path( "email" ).asText() ).isEqualTo( "bob@new.io" );
        assertThat( parsed.path( "customField1" ).asText() ).isEqualTo( "slot1" );
        // null fields absent
        assertThat( parsed.has( "name" ) ).isFalse();
        assertThat( parsed.has( "firstname" ) ).isFalse();
        assertThat( parsed.has( "userGroups" ) ).isFalse();
        assertThat( parsed.has( "nfcTags" ) ).isFalse();
    }

    @Test
    @DisplayName( "editUser(null patch) → IllegalArgumentException" )
    void editUserRejectsNullPatch()
    {
        String uuid = "0a5fa72f-018b-0050-1900000000000000";
        assertThatThrownBy( () -> mutations.editUser( uuid, null ) )
                .isInstanceOf( IllegalArgumentException.class );
    }

    @Test
    @DisplayName( "editUser — non-rename patch on binding user is allowed" )
    void editUserAllowsNonRenameOnSelf() throws Exception
    {
        String uuid = "0a5fa72f-018b-0050-1900000000000000";
        when( userService.getUser( uuid ) ).thenReturn(
                userDetail( uuid, BINDING_USER_NAME, false ) );
        when( client.sendAndAwait( anyString(), any( Duration.class ) ) ).thenReturn( ok( "\"ok\"" ) );

        // Patch updates email but does NOT change the name.
        EditUserRequest patch = new EditUserRequest( null, null, null, null, null, "new@addr.io",
                                                     null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null );
        mutations.editUser( uuid, patch );

        verify( client ).sendAndAwait( anyString(), any( Duration.class ) );
    }

    @Test
    @DisplayName( "editUser(rename of binding user) → IllegalArgumentException, NO command" )
    void editUserRefusesRenameOfSelf()
    {
        String uuid = "0a5fa72f-018b-0050-1900000000000000";
        when( userService.getUser( uuid ) ).thenReturn(
                userDetail( uuid, BINDING_USER_NAME, false ) );

        EditUserRequest patch = new EditUserRequest( "renamed", null, null, null, null, null,
                                                     null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null );
        assertThatThrownBy( () -> mutations.editUser( uuid, patch ) )
                .isInstanceOf( IllegalArgumentException.class )
                .hasMessageContaining( "rename binding user" );
        verify( client, never() ).sendAndAwait( anyString(), any( Duration.class ) );
    }

    @Test
    @DisplayName( "editUser — same-name patch on self is NOT a rename → allowed" )
    void editUserAllowsSameNameOnSelf() throws Exception
    {
        String uuid = "0a5fa72f-018b-0050-1900000000000000";
        when( userService.getUser( uuid ) ).thenReturn(
                userDetail( uuid, BINDING_USER_NAME, false ) );
        when( client.sendAndAwait( anyString(), any( Duration.class ) ) ).thenReturn( ok( "\"ok\"" ) );

        EditUserRequest patch = new EditUserRequest(
                BINDING_USER_NAME, "desc", null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null );
        mutations.editUser( uuid, patch );

        verify( client ).sendAndAwait( anyString(), any( Duration.class ) );
    }

    // ============================================================
    //  disable — happy path + admin guard + self guard
    // ============================================================

    @Test
    @DisplayName( "disable(non-admin, non-self) → sends addoredituser with userState=1" )
    void disableHappyPath() throws Exception
    {
        String uuid = "0a5fa72f-018b-0050-1900000000000000";
        when( userService.getUser( uuid ) ).thenReturn( userDetail( uuid, "Bob", false ) );
        when( client.sendAndAwait( anyString(), any( Duration.class ) ) ).thenReturn( ok( "\"ok\"" ) );

        mutations.disable( uuid );

        ArgumentCaptor< String > cmd = ArgumentCaptor.forClass( String.class );
        verify( client ).sendAndAwait( cmd.capture(), any( Duration.class ) );
        assertThat( cmd.getValue() )
                .startsWith( "addoredituser/" )
                .contains( "userState" )
                // The JSON body is URL-encoded — "userState":1 becomes
                // "%22userState%22%3A1" — assert on the encoded sequence.
                .contains( "%22userState%22%3A1" )
                .contains( uuid );
    }

    @Test
    @DisplayName( "disable(admin user) without force → IllegalArgumentException, NO command sent" )
    void disableRefusesAdmin()
    {
        String uuid = "0a5fa72f-018b-0050-1900000000000000";
        when( userService.getUser( uuid ) ).thenReturn( userDetail( uuid, "Administrator", true ) );

        assertThatThrownBy( () -> mutations.disable( uuid ) )
                .isInstanceOf( IllegalArgumentException.class )
                .hasMessageContaining( "admin" )
                .hasMessageContaining( "Administrator" )
                .hasMessageContaining( "force=true" );

        verify( client, never() ).sendAndAwait( anyString(), any( Duration.class ) );
    }

    @Test
    @DisplayName( "disable(admin, force=true) — admin guard override → command sent" )
    void disableForceAllowsAdmin() throws Exception
    {
        String uuid = "0a5fa72f-018b-0050-1900000000000000";
        when( userService.getUser( uuid ) ).thenReturn( userDetail( uuid, "Administrator", true ) );
        when( client.sendAndAwait( anyString(), any( Duration.class ) ) ).thenReturn( ok( "\"ok\"" ) );

        mutations.disable( uuid, true );

        verify( client ).sendAndAwait( anyString(), any( Duration.class ) );
    }

    @Test
    @DisplayName( "disable(binding user) → IllegalArgumentException, NO command" )
    void disableRefusesSelf()
    {
        String uuid = "0a5fa72f-018b-0050-1900000000000000";
        when( userService.getUser( uuid ) ).thenReturn(
                userDetail( uuid, BINDING_USER_NAME, false ) );

        assertThatThrownBy( () -> mutations.disable( uuid ) )
                .isInstanceOf( IllegalArgumentException.class )
                .hasMessageContaining( "binding's own login" );

        verify( client, never() ).sendAndAwait( anyString(), any( Duration.class ) );
    }

    @Test
    @DisplayName( "disable(self, force=true) — self-guard is UNCONDITIONAL" )
    void disableForceStillRefusesSelf()
    {
        String uuid = "0a5fa72f-018b-0050-1900000000000000";
        when( userService.getUser( uuid ) ).thenReturn(
                userDetail( uuid, BINDING_USER_NAME, false ) );

        assertThatThrownBy( () -> mutations.disable( uuid, true ) )
                .isInstanceOf( IllegalArgumentException.class )
                .hasMessageContaining( "binding's own login" );

        verify( client, never() ).sendAndAwait( anyString(), any( Duration.class ) );
    }

    @Test
    @DisplayName( "disable(short uuid) → IllegalArgumentException, no getUser() call" )
    void disableRejectsBadUuid()
    {
        assertThatThrownBy( () -> mutations.disable( "short" ) )
                .isInstanceOf( IllegalArgumentException.class );
        verify( userService, never() ).getUser( anyString() );
        verify( client, never() ).sendAndAwait( anyString(), any( Duration.class ) );
    }

    @Test
    @DisplayName( "disable — Miniserver returns Code != 200 → AdminCommandException" )
    void disableFailsOnBadCode() throws Exception
    {
        String uuid = "0a5fa72f-018b-0050-1900000000000000";
        when( userService.getUser( uuid ) ).thenReturn( userDetail( uuid, "Bob", false ) );

        JsonNode bad = MAPPER.readTree( "{\"value\":\"nope\",\"Code\":\"403\"}" );
        when( client.sendAndAwait( anyString(), any( Duration.class ) ) ).thenReturn( bad );

        assertThatThrownBy( () -> mutations.disable( uuid ) )
                .isInstanceOf( AdminCommandException.class )
                .hasMessageContaining( "Code=403" );
    }

    // ============================================================
    //  assignToGroup / removeFromGroup
    // ============================================================

    @Test
    @DisplayName( "assignToGroup() builds assignusertogroup/{user}/{group}" )
    void assignBuildsCommand() throws Exception
    {
        when( client.sendAndAwait( anyString(), any( Duration.class ) ) ).thenReturn( ok( "\"ok\"" ) );

        String user  = "0a5fa72f-018b-0050-1900000000000000";
        String group = "089396d4-0207-0119-1900000000000000";
        mutations.assignToGroup( user, group );

        verify( client ).sendAndAwait(
                "assignusertogroup/" + user + "/" + group,
                Duration.ofSeconds( 12 ) );
    }

    @Test
    @DisplayName( "removeFromGroup() builds removeuserfromgroup/{user}/{group}" )
    void removeBuildsCommand() throws Exception
    {
        when( client.sendAndAwait( anyString(), any( Duration.class ) ) ).thenReturn( ok( "\"ok\"" ) );

        String user  = "0a5fa72f-018b-0050-1900000000000000";
        String group = "089396d4-0207-0119-1900000000000000";
        mutations.removeFromGroup( user, group );

        verify( client ).sendAndAwait(
                "removeuserfromgroup/" + user + "/" + group,
                Duration.ofSeconds( 12 ) );
    }

    @Test
    @DisplayName( "group operations reject malformed UUIDs" )
    void groupOpsValidateUuids()
    {
        assertThatThrownBy( () -> mutations.assignToGroup( "short", "0a5fa72f-018b-0050-1900000000000000" ) )
                .isInstanceOf( IllegalArgumentException.class );
        assertThatThrownBy( () -> mutations.assignToGroup( "0a5fa72f-018b-0050-1900000000000000", "short" ) )
                .isInstanceOf( IllegalArgumentException.class );
        assertThatThrownBy( () -> mutations.removeFromGroup( null, "0a5fa72f-018b-0050-1900000000000000" ) )
                .isInstanceOf( IllegalArgumentException.class );
    }
}
