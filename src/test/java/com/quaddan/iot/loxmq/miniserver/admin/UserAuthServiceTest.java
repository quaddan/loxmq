/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quaddan.iot.loxmq.config.LoxoneConfig;
import com.quaddan.iot.loxmq.miniserver.crypto.KeyAndSalt;
import com.quaddan.iot.loxmq.miniserver.crypto.LoxoneCryptoService;
import com.quaddan.iot.loxmq.miniserver.http.LoxoneJsonParser;
import com.quaddan.iot.loxmq.miniserver.http.MiniserverHttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
 * Unit tests for {@link UserAuthService}.
 *
 * <p>Focus :
 * <ul>
 *   <li>Wire format of the three update commands.</li>
 *   <li>Password / visu-password hashing recipe — delegates to
 *       {@link LoxoneCryptoService#passwordHash} and feeds the hex result
 *       into the URL path.</li>
 *   <li>Self-guard on main-password (visu / access code are unguarded).</li>
 *   <li>Input validation : null / blank / non-numeric / too short for
 *       access codes.</li>
 *   <li>Sensitive-data hygiene : we never assert on the plaintext in the
 *       payload — only on the hex hash. Implicit guard against accidental
 *       plaintext leakage in command lines.</li>
 * </ul>
 */
@DisplayName( "UserAuthService — wire format + safety guards" )
class UserAuthServiceTest
{
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String BINDING_USER_NAME = "loxmq";
    private static final String BINDING_USER_B64  = Base64.getEncoder()
                                                          .encodeToString( BINDING_USER_NAME.getBytes( StandardCharsets.UTF_8 ) );

    /** Constant salt / key / hashAlg returned by the mocked
     *  getkey2 fetch. Numbers don't matter — only that they are
     *  stable so the test can verify the hash deterministically. */
    private static final KeyAndSalt FAKE_KEY_AND_SALT = new KeyAndSalt(
            "0102030405060708090a0b0c0d0e0f10",        // hex key
            "abcd1234",                                  // salt
            "SHA256" );

    private static JsonNode ok() throws Exception
    {
        return MAPPER.readTree( "{\"value\":\"ok\",\"Code\":\"200\"}" );
    }

    private static UserDetail userDetail( String uuid, String name )
    {
        return new UserDetail( uuid, name, "", "", "", "", "", "", "", "", "", "", "", "",
                               "", "", "", "", "", 0L, 0, null, null, null, false, false, List.of(), List.of() );
    }

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

    private MiniserverAdminCommandClient client;
    private UserService                  userService;
    private MiniserverHttpClient         http;
    private LoxoneCryptoService          crypto;
    private LoxoneJsonParser             parser;
    private LoxoneConfig                 config;
    private UserAuthService              authSvc;

    @BeforeEach
    void setUp() throws Exception
    {
        client      = mock( MiniserverAdminCommandClient.class );
        userService = mock( UserService.class );
        http        = mock( MiniserverHttpClient.class );
        crypto      = mock( LoxoneCryptoService.class );
        parser      = mock( LoxoneJsonParser.class );
        config      = mockConfig( BINDING_USER_B64 );

        authSvc             = new UserAuthService();
        authSvc.adminClient = client;
        authSvc.userService = userService;
        authSvc.httpClient  = http;
        authSvc.crypto      = crypto;
        authSvc.parser      = parser;
        authSvc.config      = config;

        // Default stubs : getkey2 round-trip returns a stable salt+key,
        // crypto computes a known fake hash so tests can assert on the
        // wire format without re-implementing the algorithm.
        when( http.fetchKeyAndSalt( anyString() ) ).thenReturn( "fake-body" );
        when( parser.parseKeyAndSalt( anyString() ) ).thenReturn( FAKE_KEY_AND_SALT );
        when( crypto.passwordHash( anyString(), any( KeyAndSalt.class ) ) )
                .thenReturn( "DEADBEEFCAFEBABE0102030405060708090A0B0C0D0E0F1011121314151617" );
    }

    // ============================================================
    //  updatePassword
    // ============================================================

    @Test
    @DisplayName( "updatePassword — sends updateuserpwdh/{uuid}/{hash}" )
    void updatePasswordHappyPath() throws Exception
    {
        String uuid = "0a5fa72f-018b-0050-1900000000000000";
        when( userService.getUser( uuid ) ).thenReturn( userDetail( uuid, "Bob" ) );
        when( client.sendAndAwait( anyString(), any( Duration.class ) ) ).thenReturn( ok() );

        authSvc.updatePassword( uuid, "S3cretPw!" );

        // getkey2 was called with the TARGET user's name (not the binding user)
        verify( http ).fetchKeyAndSalt( "Bob" );
        // Hash recipe was applied
        verify( crypto ).passwordHash( "S3cretPw!", FAKE_KEY_AND_SALT );
        // The wire command embeds the uuid + the hex hash
        ArgumentCaptor< String > cmd = ArgumentCaptor.forClass( String.class );
        verify( client ).sendAndAwait( cmd.capture(), any( Duration.class ) );
        assertThat( cmd.getValue() )
                .isEqualTo( "updateuserpwdh/" + uuid
                            + "/DEADBEEFCAFEBABE0102030405060708090A0B0C0D0E0F1011121314151617" );
        // CRITICAL — never the plaintext password in the command
        assertThat( cmd.getValue() ).doesNotContain( "S3cretPw" );
    }

    @Test
    @DisplayName( "updatePassword(self) → IllegalArgumentException, NO command sent" )
    void updatePasswordRefusesSelf()
    {
        String uuid = "0a5fa72f-018b-0050-1900000000000000";
        when( userService.getUser( uuid ) ).thenReturn( userDetail( uuid, BINDING_USER_NAME ) );

        assertThatThrownBy( () -> authSvc.updatePassword( uuid, "newpw" ) )
                .isInstanceOf( IllegalArgumentException.class )
                .hasMessageContaining( "binding's own login" );

        verify( client, never() ).sendAndAwait( anyString(), any( Duration.class ) );
        verify( crypto, never() ).passwordHash( anyString(), any( KeyAndSalt.class ) );
    }

    @Test
    @DisplayName( "updatePassword(null / blank password) → IllegalArgumentException" )
    void updatePasswordRejectsBlank()
    {
        String uuid = "0a5fa72f-018b-0050-1900000000000000";
        assertThatThrownBy( () -> authSvc.updatePassword( uuid, null ) )
                .isInstanceOf( IllegalArgumentException.class );
        assertThatThrownBy( () -> authSvc.updatePassword( uuid, "  " ) )
                .isInstanceOf( IllegalArgumentException.class );
        verify( userService, never() ).getUser( anyString() );
    }

    @Test
    @DisplayName( "updatePassword(short uuid) → IllegalArgumentException early" )
    void updatePasswordRejectsBadUuid()
    {
        assertThatThrownBy( () -> authSvc.updatePassword( "short", "pw" ) )
                .isInstanceOf( IllegalArgumentException.class );
        verify( userService, never() ).getUser( anyString() );
    }

    @Test
    @DisplayName( "updatePassword — Miniserver returns 4xx → AdminCommandException" )
    void updatePasswordFailsOnBadCode() throws Exception
    {
        String uuid = "0a5fa72f-018b-0050-1900000000000000";
        when( userService.getUser( uuid ) ).thenReturn( userDetail( uuid, "Bob" ) );
        when( client.sendAndAwait( anyString(), any( Duration.class ) ) ).thenReturn(
                MAPPER.readTree( "{\"value\":\"\",\"Code\":\"403\"}" ) );

        assertThatThrownBy( () -> authSvc.updatePassword( uuid, "pw" ) )
                .isInstanceOf( AdminCommandException.class )
                .hasMessageContaining( "Code=403" );
    }

    @Test
    @DisplayName( "updatePassword — getkey2 failure surfaces as AdminCommandException" )
    void updatePasswordWrapsGetkey2Failure() throws Exception
    {
        String uuid = "0a5fa72f-018b-0050-1900000000000000";
        when( userService.getUser( uuid ) ).thenReturn( userDetail( uuid, "Bob" ) );
        when( http.fetchKeyAndSalt( "Bob" ) ).thenThrow( new RuntimeException( "boom" ) );

        assertThatThrownBy( () -> authSvc.updatePassword( uuid, "pw" ) )
                .isInstanceOf( AdminCommandException.class )
                .hasMessageContaining( "getkey2 lookup failed" );

        verify( client, never() ).sendAndAwait( anyString(), any( Duration.class ) );
    }

    // ============================================================
    //  updateVisuPassword
    // ============================================================

    @Test
    @DisplayName( "updateVisuPassword — sends updateuservisupwdh/{uuid}/{hash}" )
    void updateVisuPasswordHappyPath() throws Exception
    {
        String uuid = "0a5fa72f-018b-0050-1900000000000000";
        when( userService.getUser( uuid ) ).thenReturn( userDetail( uuid, "Bob" ) );
        when( client.sendAndAwait( anyString(), any( Duration.class ) ) ).thenReturn( ok() );

        authSvc.updateVisuPassword( uuid, "visu-pin" );

        ArgumentCaptor< String > cmd = ArgumentCaptor.forClass( String.class );
        verify( client ).sendAndAwait( cmd.capture(), any( Duration.class ) );
        assertThat( cmd.getValue() ).startsWith( "updateuservisupwdh/" + uuid + "/" );
        assertThat( cmd.getValue() ).doesNotContain( "visu-pin" );
    }

    @Test
    @DisplayName( "updateVisuPassword(self) is allowed — visu is secondary credential" )
    void updateVisuPasswordAllowsSelf() throws Exception
    {
        String uuid = "0a5fa72f-018b-0050-1900000000000000";
        when( userService.getUser( uuid ) ).thenReturn( userDetail( uuid, BINDING_USER_NAME ) );
        when( client.sendAndAwait( anyString(), any( Duration.class ) ) ).thenReturn( ok() );

        authSvc.updateVisuPassword( uuid, "anything" );

        verify( client ).sendAndAwait( anyString(), any( Duration.class ) );
    }

    @Test
    @DisplayName( "updateVisuPassword(blank) → IllegalArgumentException" )
    void updateVisuPasswordRejectsBlank()
    {
        String uuid = "0a5fa72f-018b-0050-1900000000000000";
        assertThatThrownBy( () -> authSvc.updateVisuPassword( uuid, "" ) )
                .isInstanceOf( IllegalArgumentException.class );
    }

    // ============================================================
    //  updateAccessCode
    // ============================================================

    @Test
    @DisplayName( "updateAccessCode(4-digit code) — sends updateuseraccesscode/{uuid}/{code} URL-encoded" )
    void updateAccessCodeHappyPath() throws Exception
    {
        String uuid = "0a5fa72f-018b-0050-1900000000000000";
        when( userService.getUser( uuid ) ).thenReturn( userDetail( uuid, "Bob" ) );
        when( client.sendAndAwait( anyString(), any( Duration.class ) ) ).thenReturn( ok() );

        authSvc.updateAccessCode( uuid, "1234" );

        ArgumentCaptor< String > cmd = ArgumentCaptor.forClass( String.class );
        verify( client ).sendAndAwait( cmd.capture(), any( Duration.class ) );
        assertThat( cmd.getValue() ).isEqualTo( "updateuseraccesscode/" + uuid + "/1234" );
        // Crypto / HTTP getkey2 should NOT be touched — access code is
        // sent as-is.
        verify( http, never() ).fetchKeyAndSalt( anyString() );
        verify( crypto, never() ).passwordHash( anyString(), any( KeyAndSalt.class ) );
    }

    @Test
    @DisplayName( "updateAccessCode rejects non-digit input" )
    void updateAccessCodeRejectsNonDigits()
    {
        String uuid = "0a5fa72f-018b-0050-1900000000000000";
        assertThatThrownBy( () -> authSvc.updateAccessCode( uuid, "abcd" ) )
                .isInstanceOf( IllegalArgumentException.class )
                .hasMessageContaining( "digits" );
        assertThatThrownBy( () -> authSvc.updateAccessCode( uuid, "12 34" ) )
                .isInstanceOf( IllegalArgumentException.class );
        assertThatThrownBy( () -> authSvc.updateAccessCode( uuid, "12,34" ) )
                .isInstanceOf( IllegalArgumentException.class );
    }

    @Test
    @DisplayName( "updateAccessCode rejects too-short or too-long codes" )
    void updateAccessCodeRejectsBadLength()
    {
        String uuid = "0a5fa72f-018b-0050-1900000000000000";
        assertThatThrownBy( () -> authSvc.updateAccessCode( uuid, "12" ) )
                .isInstanceOf( IllegalArgumentException.class )
                .hasMessageContaining( "4 and 12" );
        assertThatThrownBy( () -> authSvc.updateAccessCode( uuid, "1234567890123" ) )
                .isInstanceOf( IllegalArgumentException.class );
    }

    @Test
    @DisplayName( "updateAccessCode(null / blank) → IllegalArgumentException" )
    void updateAccessCodeRejectsBlank()
    {
        String uuid = "0a5fa72f-018b-0050-1900000000000000";
        assertThatThrownBy( () -> authSvc.updateAccessCode( uuid, null ) )
                .isInstanceOf( IllegalArgumentException.class );
        assertThatThrownBy( () -> authSvc.updateAccessCode( uuid, "  " ) )
                .isInstanceOf( IllegalArgumentException.class );
    }

    @Test
    @DisplayName( "updateAccessCode(self) allowed — same as visu" )
    void updateAccessCodeAllowsSelf() throws Exception
    {
        String uuid = "0a5fa72f-018b-0050-1900000000000000";
        when( userService.getUser( uuid ) ).thenReturn( userDetail( uuid, BINDING_USER_NAME ) );
        when( client.sendAndAwait( anyString(), any( Duration.class ) ) ).thenReturn( ok() );

        authSvc.updateAccessCode( uuid, "9876" );

        verify( client ).sendAndAwait( anyString(), any( Duration.class ) );
    }

    @Test
    @DisplayName( "updateAccessCode — short uuid rejected early" )
    void updateAccessCodeRejectsBadUuid()
    {
        assertThatThrownBy( () -> authSvc.updateAccessCode( "short", "1234" ) )
                .isInstanceOf( IllegalArgumentException.class );
        verify( userService, never() ).getUser( anyString() );
    }
}
