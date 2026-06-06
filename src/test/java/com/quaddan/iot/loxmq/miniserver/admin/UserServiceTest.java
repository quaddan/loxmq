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

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link UserService} — JSON shape parsing for the
 * Miniserver user-management surface.
 *
 * <p>{@link MiniserverAdminCommandClient} mocked ; the service's job
 * is to translate Loxone-shaped JSON into the binding's records, which
 * is what we assert here.
 */
@DisplayName( "UserService — JSON shape parsing + tolerant defaults" )
class UserServiceTest
{
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode ok( String valueJsonOrLiteral ) throws Exception
    {
        return MAPPER.readTree( "{\"value\":" + valueJsonOrLiteral + ",\"Code\":\"200\"}" );
    }

    private MiniserverAdminCommandClient client;
    private UserService                  service;

    @BeforeEach
    void setUp()
    {
        client              = mock( MiniserverAdminCommandClient.class );
        service             = new UserService();
        service.adminClient = client;

        // Stub unwrapValue with the same logic as the real
        // implementation : re-parse `value` if it's a JSON-encoded
        // string. Required since UserService routes value reads
        // through this helper.
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
    //  listUsers
    // ============================================================

    @Test
    @DisplayName( "listUsers() parses getuserlist2 array → User records" )
    void listUsersHappyPath() throws Exception
    {
        String body = "["
                      + "{\"name\":\"Administrator\",\"uuid\":\"089396d4-0207-0119-1900000000000000\","
                      + "\"isAdmin\":true,\"userState\":0},"
                      + "{\"name\":\"Feuerwehr\",\"uuid\":\"0a5fa72f-018b-0050-1900000000000000\","
                      + "\"isAdmin\":false,\"userState\":0},"
                      + "{\"name\":\"admin\",\"uuid\":\"0ee1424f-006b-57d5-ffffeee000880187\","
                      + "\"isAdmin\":true,\"userState\":1,\"expirationAction\":1}"
                      + "]";
        when( client.sendAndAwait( anyString(), any( Duration.class ) ) ).thenReturn( ok( body ) );

        List< User > users = service.listUsers();

        assertThat( users ).hasSize( 3 );
        assertThat( users.get( 0 ).name() ).isEqualTo( "Administrator" );
        assertThat( users.get( 0 ).isAdmin() ).isTrue();
        assertThat( users.get( 0 ).expirationAction() ).isNull();
        assertThat( users.get( 2 ).expirationAction() ).isEqualTo( 1 );
        assertThat( users.get( 2 ).userState() ).isEqualTo( 1 );

        verify( client ).sendAndAwait( "getuserlist2", Duration.ofSeconds( 8 ) );
    }

    @Test
    @DisplayName( "listUsers() with non-array value but Code=200 → empty list (defensive)" )
    void listUsersDefensive() throws Exception
    {
        when( client.sendAndAwait( anyString(), any( Duration.class ) ) ).thenReturn( ok( "null" ) );
        assertThat( service.listUsers() ).isEmpty();
    }

    @Test
    @DisplayName( "listUsers() with Code=403 → AdminCommandException" )
    void listUsersSurfacesForbidden() throws Exception
    {
        // Without an explicit requireOk(), Code 403 used to be silently
        // swallowed: value="" parsed as NullNode, !isArray() was true,
        // fallback empty list → REST 200 [] → UI "0 users, 0 groups"
        // with no indication of the Miniserver refusal. requireOk runs
        // before the parse to surface it loud and clear.
        JsonNode forbidden = MAPPER.readTree( "{\"value\":\"\",\"Code\":\"403\"}" );
        when( client.sendAndAwait( anyString(), any( Duration.class ) ) ).thenReturn( forbidden );

        assertThatThrownBy( () -> service.listUsers() )
                .isInstanceOf( AdminCommandException.class )
                .hasMessageContaining( "Code=403" )
                .hasMessageContaining( "lacks the required permission" )
                .hasMessageContaining( "Loxone Config" );
    }

    @Test
    @DisplayName( "listGroups() with Code=403 → AdminCommandException" )
    void listGroupsSurfacesForbidden() throws Exception
    {
        JsonNode forbidden = MAPPER.readTree( "{\"value\":\"\",\"Code\":\"403\"}" );
        when( client.sendAndAwait( anyString(), any( Duration.class ) ) ).thenReturn( forbidden );

        assertThatThrownBy( () -> service.listGroups() )
                .isInstanceOf( AdminCommandException.class )
                .hasMessageContaining( "Code=403" );
    }

    @Test
    @DisplayName( "getUser() with Code=403 → AdminCommandException" )
    void getUserSurfacesForbidden() throws Exception
    {
        JsonNode forbidden = MAPPER.readTree( "{\"value\":\"\",\"Code\":\"403\"}" );
        when( client.sendAndAwait( anyString(), any( Duration.class ) ) ).thenReturn( forbidden );

        assertThatThrownBy( () ->
                                    service.getUser( "0a5fa72f-018b-0050-1900000000000000" ) )
                .isInstanceOf( AdminCommandException.class )
                .hasMessageContaining( "Code=403" );
    }

    @Test
    @DisplayName( "listUsers() with empty array → empty list" )
    void listUsersEmpty() throws Exception
    {
        when( client.sendAndAwait( anyString(), any( Duration.class ) ) ).thenReturn( ok( "[]" ) );
        assertThat( service.listUsers() ).isEmpty();
    }

    // ============================================================
    //  getUser
    // ============================================================

    @Test
    @DisplayName( "getUser() parses the full detail payload at value.* level" )
    void getUserParsesDetail() throws Exception
    {
        String body = "{"
                      + "\"uuid\":\"12eebb90-00a1-3073-ffff88c561c84c44\","
                      + "\"name\":\"admin\","
                      + "\"desc\":\"main account\","
                      + "\"userid\":\"1234 24 12 83\","
                      + "\"firstname\":\"Jane\","
                      + "\"lastname\":\"Doe\","
                      + "\"email\":\"x@example.org\","
                      + "\"phone\":\"+33\","
                      + "\"uniqueUserId\":\"u-001\","
                      + "\"company\":\"Example Co\","
                      + "\"department\":\"Ops\","
                      + "\"personalno\":\"p-1\","
                      + "\"title\":\"Dr\","
                      + "\"debitor\":\"D1\","
                      + "\"customField1\":\"a\","
                      + "\"customField2\":\"b\","
                      + "\"customField3\":\"c\","
                      + "\"customField4\":\"d\","
                      + "\"customField5\":\"e\","
                      + "\"lastedit\":472141393,"
                      + "\"userState\":0,"
                      + "\"isAdmin\":true,"
                      + "\"changePassword\":false,"
                      + "\"userGroups\":[\"g1\",\"g2\"],"
                      + "\"nfcTags\":[\"NFC-001\"]"
                      + "}";
        when( client.sendAndAwait( anyString(), any( Duration.class ) ) ).thenReturn( ok( body ) );

        UserDetail u = service.getUser( "12eebb90-00a1-3073-ffff88c561c84c44" );

        assertThat( u.name() ).isEqualTo( "admin" );
        assertThat( u.firstname() ).isEqualTo( "Jane" );
        assertThat( u.email() ).isEqualTo( "x@example.org" );
        assertThat( u.lastedit() ).isEqualTo( 472141393L );
        assertThat( u.isAdmin() ).isTrue();
        assertThat( u.userGroups() ).containsExactly( "g1", "g2" );
        assertThat( u.nfcTags() ).containsExactly( "NFC-001" );
    }

    @Test
    @DisplayName( "getUser() — surface validUntil/validFrom/expirationAction for userState=4" )
    void getUserParsesDateFields() throws Exception
    {
        String body = "{"
                      + "\"uuid\":\"12eebb90-00a1-3073-ffff88c561c84c44\","
                      + "\"name\":\"timer\","
                      + "\"userState\":4,"
                      + "\"validUntil\":549208860,"
                      + "\"validFrom\":549208680,"
                      + "\"expirationAction\":1,"
                      + "\"isAdmin\":false,"
                      + "\"changePassword\":false,"
                      + "\"userGroups\":[],"
                      + "\"nfcTags\":[]"
                      + "}";
        when( client.sendAndAwait( anyString(), any( Duration.class ) ) ).thenReturn( ok( body ) );

        UserDetail u = service.getUser( "12eebb90-00a1-3073-ffff88c561c84c44" );

        assertThat( u.userState() ).isEqualTo( 4 );
        assertThat( u.validUntil() ).isEqualTo( 549208860L );
        assertThat( u.validFrom() ).isEqualTo( 549208680L );
        assertThat( u.expirationAction() ).isEqualTo( 1 );
    }

    @Test
    @DisplayName( "getUser() — missing date fields → null (userState=0, no time limit)" )
    void getUserDateFieldsAbsent() throws Exception
    {
        String body = "{"
                      + "\"uuid\":\"12eebb90-00a1-3073-ffff88c561c84c44\","
                      + "\"name\":\"unrestricted\","
                      + "\"userState\":0,"
                      + "\"isAdmin\":false,"
                      + "\"changePassword\":false,"
                      + "\"userGroups\":[],"
                      + "\"nfcTags\":[]"
                      + "}";
        when( client.sendAndAwait( anyString(), any( Duration.class ) ) ).thenReturn( ok( body ) );

        UserDetail u = service.getUser( "12eebb90-00a1-3073-ffff88c561c84c44" );

        assertThat( u.userState() ).isEqualTo( 0 );
        assertThat( u.validUntil() ).isNull();
        assertThat( u.validFrom() ).isNull();
        assertThat( u.expirationAction() ).isNull();
    }

    @Test
    @DisplayName( "getUser() — UUID validation : short/null → IllegalArgumentException" )
    void getUserValidatesUuid()
    {
        assertThatThrownBy( () -> service.getUser( null ) )
                .isInstanceOf( IllegalArgumentException.class );
        assertThatThrownBy( () -> service.getUser( "short" ) )
                .isInstanceOf( IllegalArgumentException.class );
    }

    @Test
    @DisplayName( "getUser() — userGroups as objects with uuid field → extracted" )
    void getUserHandlesGroupObjects() throws Exception
    {
        String body = "{"
                      + "\"uuid\":\"12eebb90-00a1-3073-ffff88c561c84c44\","
                      + "\"name\":\"foo\","
                      + "\"userGroups\":[{\"uuid\":\"g1\",\"name\":\"Admins\"},{\"uuid\":\"g2\"}]"
                      + "}";
        when( client.sendAndAwait( anyString(), any( Duration.class ) ) ).thenReturn( ok( body ) );

        UserDetail u = service.getUser( "12eebb90-00a1-3073-ffff88c561c84c44" );
        assertThat( u.userGroups() ).containsExactly( "g1", "g2" );
    }

    @Test
    @DisplayName( "getUser() — usergroups (lowercase per V17 spec p.5-6) → extracted" )
    void getUserHandlesLowercaseUsergroups() throws Exception
    {
        // V17 spec shows the field as lowercase "usergroups". A
        // case-sensitive read of "userGroups" always returned empty →
        // Groups table showed "no members" regardless of actual
        // membership. This test pins the V17 spec shape so we can't
        // regress.
        String body = "{"
                      + "\"uuid\":\"12eebb90-00a1-3073-ffff88c561c84c44\","
                      + "\"name\":\"foo\","
                      + "\"usergroups\":["
                      + "  {\"name\":\"Administrators\",\"uuid\":\"g1\"},"
                      + "  {\"name\":\"Famille\",\"uuid\":\"g2\"}"
                      + "]"
                      + "}";
        when( client.sendAndAwait( anyString(), any( Duration.class ) ) ).thenReturn( ok( body ) );

        UserDetail u = service.getUser( "12eebb90-00a1-3073-ffff88c561c84c44" );
        assertThat( u.userGroups() ).containsExactly( "g1", "g2" );
    }

    @Test
    @DisplayName( "getUser() — tolerant : both spellings present, lowercase wins" )
    void getUserPrefersLowercaseUsergroupsWhenBothPresent() throws Exception
    {
        // Belt-and-suspenders : if Loxone ever ships a firmware variant
        // that emits BOTH names (one empty, one populated), the lowercase
        // one (spec-canonical) should take precedence when it has data.
        // Order in extractStringListTolerant : usergroups first.
        String body = "{"
                      + "\"uuid\":\"12eebb90-00a1-3073-ffff88c561c84c44\","
                      + "\"name\":\"foo\","
                      + "\"usergroups\":[\"spec-g1\"],"
                      + "\"userGroups\":[\"legacy-g1\"]"
                      + "}";
        when( client.sendAndAwait( anyString(), any( Duration.class ) ) ).thenReturn( ok( body ) );

        UserDetail u = service.getUser( "12eebb90-00a1-3073-ffff88c561c84c44" );
        assertThat( u.userGroups() ).containsExactly( "spec-g1" );
    }

    @Test
    @DisplayName( "getUser() — missing optional fields → empty string defaults" )
    void getUserDefaultsOnMissingFields() throws Exception
    {
        String body = "{\"uuid\":\"12eebb90-00a1-3073-ffff88c561c84c44\",\"name\":\"foo\"}";
        when( client.sendAndAwait( anyString(), any( Duration.class ) ) ).thenReturn( ok( body ) );

        UserDetail u = service.getUser( "12eebb90-00a1-3073-ffff88c561c84c44" );
        assertThat( u.name() ).isEqualTo( "foo" );
        assertThat( u.email() ).isEmpty();
        assertThat( u.phone() ).isEmpty();
        assertThat( u.userGroups() ).isEmpty();
        assertThat( u.nfcTags() ).isEmpty();
    }

    // ============================================================
    //  listGroups
    // ============================================================

    @Test
    @DisplayName( "listGroups() parses getgrouplist array → UserGroup records" )
    void listGroupsHappyPath() throws Exception
    {
        String body = "["
                      + "{\"uuid\":\"g1-uuid\",\"name\":\"Admins\",\"description\":\"Full access\"},"
                      + "{\"uuid\":\"g2-uuid\",\"name\":\"Visitors\"}"
                      + "]";
        when( client.sendAndAwait( anyString(), any( Duration.class ) ) ).thenReturn( ok( body ) );

        List< UserGroup > groups = service.listGroups();
        assertThat( groups ).hasSize( 2 );
        assertThat( groups.get( 0 ).name() ).isEqualTo( "Admins" );
        assertThat( groups.get( 0 ).descriptionOrEmpty() ).isEqualTo( "Full access" );
        assertThat( groups.get( 1 ).descriptionOrEmpty() ).isEmpty();

        verify( client ).sendAndAwait( "getgrouplist", Duration.ofSeconds( 8 ) );
    }

    @Test
    @DisplayName( "snapshot() returns users + groups in one map" )
    void snapshotBundlesBoth() throws Exception
    {
        when( client.sendAndAwait( anyString(), any( Duration.class ) ) )
                .thenReturn( ok( "[]" ) );

        java.util.Map< String, Object > snap = service.snapshot();
        assertThat( snap ).containsKeys( "users", "groups" );
    }

    // ============================================================
    //  Metadata helpers
    // ============================================================

    @Test
    @DisplayName( "getCustomFields() — legacy object shape parsed to slot map" )
    void getCustomFieldsObjectShape() throws Exception
    {
        String body = "{"
                      + "\"customField1\":\"Badge\","
                      + "\"customField2\":\"SIRET\","
                      + "\"customField3\":\"\","
                      + "\"customField5\":\"Extra\""
                      + "}";
        when( client.sendAndAwait( anyString(), any( Duration.class ) ) ).thenReturn( ok( body ) );

        java.util.Map< String, String > fields = service.getCustomFields();
        assertThat( fields )
                .containsEntry( "customField1", "Badge" )
                .containsEntry( "customField2", "SIRET" )
                .containsEntry( "customField3", "" )
                .containsEntry( "customField4", "" )    // missing → default empty
                .containsEntry( "customField5", "Extra" );
        verify( client ).sendAndAwait( "getcustomuserfields", Duration.ofSeconds( 8 ) );
    }

    @Test
    @DisplayName( "getCustomFields() — V17 array shape parsed to slot map" )
    void getCustomFieldsArrayShape() throws Exception
    {
        String body = "["
                      + "{\"slot\":1,\"name\":\"Badge\"},"
                      + "{\"slot\":3,\"name\":\"Sticker\"},"
                      + "{\"slot\":5,\"name\":\"NFC\"}"
                      + "]";
        when( client.sendAndAwait( anyString(), any( Duration.class ) ) ).thenReturn( ok( body ) );

        java.util.Map< String, String > fields = service.getCustomFields();
        assertThat( fields )
                .containsEntry( "customField1", "Badge" )
                .containsEntry( "customField2", "" )
                .containsEntry( "customField3", "Sticker" )
                .containsEntry( "customField5", "NFC" );
    }

    @Test
    @DisplayName( "getCustomFields() — empty value → all empty slots" )
    void getCustomFieldsEmpty() throws Exception
    {
        when( client.sendAndAwait( anyString(), any( Duration.class ) ) ).thenReturn( ok( "{}" ) );
        java.util.Map< String, String > fields = service.getCustomFields();
        assertThat( fields ).hasSize( 5 );
        assertThat( fields.values() ).allMatch( String::isEmpty );
    }

    @Test
    @DisplayName( "getUserPropertyOptions() parses {key:[strings]} into map of lists" )
    void getUserPropertyOptionsParses() throws Exception
    {
        String body = "{"
                      + "\"company\":[\"Acme\",\"Globex\"],"
                      + "\"department\":[\"Ops\",\"R&D\",\"Sales\"],"
                      + "\"title\":[]"
                      + "}";
        when( client.sendAndAwait( anyString(), any( Duration.class ) ) ).thenReturn( ok( body ) );

        java.util.Map< String, java.util.List< String > > opts = service.getUserPropertyOptions();
        assertThat( opts.get( "company" ) ).containsExactly( "Acme", "Globex" );
        assertThat( opts.get( "department" ) ).containsExactly( "Ops", "R&D", "Sales" );
        assertThat( opts.get( "title" ) ).isEmpty();
    }

    @Test
    @DisplayName( "isUserIdAvailable() — boolean true/false in value" )
    void checkUserIdBooleanShape() throws Exception
    {
        when( client.sendAndAwait( anyString(), any( Duration.class ) ) ).thenReturn( ok( "true" ) );
        assertThat( service.isUserIdAvailable( "alice" ) ).isTrue();

        when( client.sendAndAwait( anyString(), any( Duration.class ) ) ).thenReturn( ok( "false" ) );
        assertThat( service.isUserIdAvailable( "bob" ) ).isFalse();
    }

    @Test
    @DisplayName( "isUserIdAvailable() — numeric 0/1 shape" )
    void checkUserIdNumericShape() throws Exception
    {
        when( client.sendAndAwait( anyString(), any( Duration.class ) ) ).thenReturn( ok( "1" ) );
        assertThat( service.isUserIdAvailable( "alice" ) ).isTrue();

        when( client.sendAndAwait( anyString(), any( Duration.class ) ) ).thenReturn( ok( "0" ) );
        assertThat( service.isUserIdAvailable( "bob" ) ).isFalse();
    }

    @Test
    @DisplayName( "isUserIdAvailable() — \"true\"/\"false\" string shape" )
    void checkUserIdStringShape() throws Exception
    {
        when( client.sendAndAwait( anyString(), any( Duration.class ) ) ).thenReturn( ok( "\"true\"" ) );
        assertThat( service.isUserIdAvailable( "alice" ) ).isTrue();

        when( client.sendAndAwait( anyString(), any( Duration.class ) ) ).thenReturn( ok( "\"false\"" ) );
        assertThat( service.isUserIdAvailable( "bob" ) ).isFalse();
    }

    @Test
    @DisplayName( "isUserIdAvailable() URL-encodes the userid in the path" )
    void checkUserIdEncodesPath() throws Exception
    {
        when( client.sendAndAwait( anyString(), any( Duration.class ) ) ).thenReturn( ok( "true" ) );
        service.isUserIdAvailable( "a b/c" );
        // URLEncoder.encode treats space as '+' and '/' as %2F.
        verify( client ).sendAndAwait( "checkuserid/a+b%2Fc", Duration.ofSeconds( 8 ) );
    }

    @Test
    @DisplayName( "isUserIdAvailable() — null / blank rejected" )
    void checkUserIdRejectsBlank()
    {
        assertThatThrownBy( () -> service.isUserIdAvailable( null ) )
                .isInstanceOf( IllegalArgumentException.class );
        assertThatThrownBy( () -> service.isUserIdAvailable( "  " ) )
                .isInstanceOf( IllegalArgumentException.class );
    }

    @Test
    @DisplayName( "getControlPermissions() — object shape relayed as plain map" )
    void getControlPermissionsObjectShape() throws Exception
    {
        String body = "{"
                      + "\"control-uuid-1\":{\"permission\":7,\"name\":\"Light\"},"
                      + "\"control-uuid-2\":{\"permission\":1,\"name\":\"Door\"}"
                      + "}";
        when( client.sendAndAwait( anyString(), any( Duration.class ) ) ).thenReturn( ok( body ) );

        java.util.Map< String, Object > perms = service.getControlPermissions( "0a5fa72f-018b-0050-1900000000000000" );
        assertThat( perms ).containsKeys( "control-uuid-1", "control-uuid-2" );
        @SuppressWarnings( "unchecked" )
        java.util.Map< String, Object > inner = ( java.util.Map< String, Object > ) perms.get( "control-uuid-1" );
        assertThat( inner.get( "permission" ) ).isEqualTo( 7 );
        assertThat( inner.get( "name" ) ).isEqualTo( "Light" );
    }

    @Test
    @DisplayName( "getControlPermissions() — array shape wrapped under 'permissions' key" )
    void getControlPermissionsArrayShape() throws Exception
    {
        String body = "[{\"controlUuid\":\"c1\",\"permission\":3}]";
        when( client.sendAndAwait( anyString(), any( Duration.class ) ) ).thenReturn( ok( body ) );

        java.util.Map< String, Object > perms = service.getControlPermissions( "0a5fa72f-018b-0050-1900000000000000" );
        assertThat( perms ).containsKey( "permissions" );
    }

    @Test
    @DisplayName( "getControlPermissions() — short uuid rejected" )
    void getControlPermissionsRejectsBadUuid()
    {
        assertThatThrownBy( () -> service.getControlPermissions( "short" ) )
                .isInstanceOf( IllegalArgumentException.class );
    }
}
