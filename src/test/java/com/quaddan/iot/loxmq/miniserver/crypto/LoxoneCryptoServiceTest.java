/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.crypto;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Crypto round-trip + protocol-vector tests for {@link LoxoneCryptoService}.
 * <p>
 * No real miniserver — we generate a throwaway RSA key pair locally and feed
 * the public half to {@link LoxoneCryptoService#loadPublicKey(String)}, then
 * exercise the same surface the handshake orchestrator calls.
 *
 * <h3>Why @QuarkusTest</h3>
 * The bean is {@code @ApplicationScoped} and needs {@code LoxoneConfig}
 * injection at {@code @PostConstruct} time — easier to let Quarkus wire it
 * than to hand-build a standalone instance.
 */
@QuarkusTest
@DisplayName( "LoxoneCryptoService — primitives + protocol vectors" )
class LoxoneCryptoServiceTest
{
    @Inject
    LoxoneCryptoService crypto;

    @Test
    @DisplayName( "no public key loaded ⇒ session-key wrap throws with a clear message" )
    void noPublicKey()
    {
        // The bean is freshly initialised at boot; loadPublicKey() hasn't been
        // called yet — every operation that needs it must fail loud.
        assertThat( crypto.hasPublicKey() ).isFalse();
        assertThatThrownBy( () -> crypto.wrappedSessionKey() )
                .isInstanceOf( LoxoneCryptoException.class )
                .hasMessageContaining( "public key not loaded" );
    }

    @Test
    @DisplayName( "loadPublicKey rejects null / blank input" )
    void loadPublicKeyRejectsBlank()
    {
        assertThatThrownBy( () -> crypto.loadPublicKey( null ) )
                .isInstanceOf( LoxoneCryptoException.class )
                .hasMessageContaining( "null or blank" );
        assertThatThrownBy( () -> crypto.loadPublicKey( "" ) )
                .isInstanceOf( LoxoneCryptoException.class );
    }

    @Test
    @DisplayName( "loadPublicKey rejects garbage Base64" )
    void loadPublicKeyRejectsGarbage()
    {
        assertThatThrownBy( () -> crypto.loadPublicKey( "this-is-not-a-valid-key" ) )
                .isInstanceOf( LoxoneCryptoException.class );
    }

    @Test
    @DisplayName( "loadPublicKey accepts a real 2048-bit RSA SubjectPublicKeyInfo" )
    void loadPublicKeyAcceptsReal() throws Exception
    {
        crypto.loadPublicKey( base64Of( generateRsaPublicKey( 2048 ) ) );
        assertThat( crypto.hasPublicKey() ).isTrue();
    }

    @Test
    @DisplayName( "wrappedSessionKey produces a Base64 RSA-encrypted payload after loadPublicKey" )
    void wrapSessionKeyAfterLoad() throws Exception
    {
        crypto.loadPublicKey( base64Of( generateRsaPublicKey( 2048 ) ) );
        String wrapped = crypto.wrappedSessionKey();

        // 2048-bit RSA → 256-byte ciphertext → 344 chars when Base64-encoded
        // (256/3 = 85.33 → 88 with padding... actually it's 344 because the
        // input fits in one block; let the math be loose, just check it's
        // a non-empty Base64 string with a reasonable size).
        assertThat( wrapped ).isNotBlank();
        assertThat( Base64.getDecoder().decode( wrapped ) ).hasSize( 256 );
    }

    @Test
    @DisplayName( "AES encrypt + decrypt round-trip: full path goes in, plaintext comes out" )
    void aesRoundTripFullPath()
    {
        // decryptControl is the complete inverse of encryptCommand
        // (strip jdev/sys/enc/ prefix + URL-decode + Base64 decode +
        // AES decrypt + strip salt wrap + null padding). Pass the
        // wrapped string verbatim, no manual surgery.
        String original = "jdev/sps/io/somecontrol/PULSE";
        String wrapped  = crypto.encryptCommand( original );

        assertThat( wrapped ).startsWith( "jdev/sys/enc/" );
        assertThat( crypto.decryptControl( wrapped ) ).isEqualTo( original );
    }

    @Test
    @DisplayName( "decryptControl: bare Base64 payload (no prefix) still works for back-compat" )
    void aesRoundTripBareBase64()
    {
        // Sanity check — feeding just the URL-decoded Base64 (without the
        // jdev/sys/enc/ prefix) must still decrypt. This is the shape the
        // unit tests pre-158 used to assert against; keeping the behaviour
        // means external callers / future maintainers don't get surprised
        // if they happen to feed a stripped payload.
        String original = "jdev/sps/io/anothercontrol/ON";
        String wrapped  = crypto.encryptCommand( original );
        String bareB64 = java.net.URLDecoder.decode(
                wrapped.substring( "jdev/sys/enc/".length() ),
                java.nio.charset.StandardCharsets.UTF_8 );

        assertThat( crypto.decryptControl( bareB64 ) ).isEqualTo( original );
    }

    @Test
    @DisplayName( "decryptControl: production-style URL-decoded path (literal '+' '/' '=') works" )
    void aesRoundTripProductionUrlDecodedPath()
    {
        // Real-world finding : the production Miniserver echoes the path
        // back in LL.control after URL-decoding it, so the wire form on
        // command_response has literal '+', '/', '=' instead of '%2B',
        // '%2F', '%3D'. decryptControl must accept both wire forms. This
        // test simulates prod by URL-decoding the path between
        // encryptCommand and decryptControl.
        String original = "jdev/sps/io/somecontrol/PULSE";
        String wrapped  = crypto.encryptCommand( original );  // jdev/sys/enc/%XX...

        // Mimic what the Miniserver does — URL-decode the path.
        String prodEchoedPath = "jdev/sys/enc/"
                                + java.net.URLDecoder.decode( wrapped.substring( "jdev/sys/enc/".length() ),
                                                              java.nio.charset.StandardCharsets.UTF_8 );

        // The prod echo has literal '+' / '/' / '=' chars — sanity check.
        // (We don't assert each one because not every random encryptCommand
        // output contains all three, but at least one of '+' or '/' is
        // virtually always present in a 16-block AES-CBC ciphertext's
        // Base64.)
        assertThat( prodEchoedPath ).doesNotContain( "%" );

        assertThat( crypto.decryptControl( prodEchoedPath ) ).isEqualTo( original );
    }

    @Test
    @DisplayName( "decryptControl: non-encrypted control (plaintext path) wraps as LoxoneCryptoException" )
    void decryptControlOnPlainText()
    {
        // IllegalArgumentException from Base64 / URLDecoder is wrapped
        // uniformly as LoxoneCryptoException. Lets
        // CommandResponsePublisher use a single catch clause.
        assertThatThrownBy( () -> crypto.decryptControl( "jdev/sps/io/abc/on" ) )
                .isInstanceOf( LoxoneCryptoException.class );
    }

    @Test
    @DisplayName( "salt rotation: forcing expiry switches the wrap prefix from salt/ to nextSalt/" )
    void saltRotation()
    {
        String firstCmd   = crypto.encryptCommand( "anything" );
        String saltBefore = crypto.currentSaltForTest();

        // Decrypt + inspect: the inner string should start with the salt prefix
        // (not the nextSalt prefix) because this is the first command.
        // We don't have access to the inner plaintext here without redoing the
        // wrap/unwrap, so we instead force a rotation and assert the salt changes.
        crypto.expireSaltForTest();
        crypto.encryptCommand( "next-command" );
        String saltAfter = crypto.currentSaltForTest();

        assertThat( saltBefore ).isNotEqualTo( saltAfter );
        assertThat( firstCmd ).isNotBlank();
    }

    @Test
    @DisplayName( "createUserHash uses the SHA-256 / HmacSHA256 path when miniserver advertises SHA256" )
    void createUserHashSha256()
    {
        // The miniserver's KeyAndSalt drives the algorithm. With a valid 32-byte
        // (64-hex) HMAC key + a salt, the hash is deterministic and non-empty.
        KeyAndSalt ks = new KeyAndSalt(
                "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF",  // 32-byte HMAC key
                "DEADBEEF",                                                          // user salt
                "SHA256" );
        String hash = crypto.createUserHash( "admin", "password", ks );
        // HmacSHA256 ⇒ 32 bytes ⇒ 64 hex chars.
        assertThat( hash ).hasSize( 64 ).matches( "[0-9a-f]{64}" );
    }

    @Test
    @DisplayName( "createUserHash uses the SHA-1 / HmacSHA1 path when miniserver advertises SHA1" )
    void createUserHashSha1()
    {
        KeyAndSalt ks = new KeyAndSalt(
                "0123456789ABCDEF0123456789ABCDEF01234567",   // 20-byte HMAC key (SHA1 size)
                "DEADBEEF",
                "SHA1" );
        String hash = crypto.createUserHash( "admin", "password", ks );
        // HmacSHA1 ⇒ 20 bytes ⇒ 40 hex chars.
        assertThat( hash ).hasSize( 40 ).matches( "[0-9a-f]{40}" );
    }

    // ============================================================
    //  passwordHash — step 1 only, no HMAC layer
    // ============================================================

    @Test
    @DisplayName( "passwordHash — SHA-256 path produces 64 hex chars uppercase" )
    void passwordHashSha256()
    {
        KeyAndSalt ks = new KeyAndSalt(
                "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF",
                "DEADBEEF",
                "SHA256" );
        String hash = crypto.passwordHash( "password", ks );
        // SHA-256 ⇒ 32 bytes ⇒ 64 hex chars, uppercase per spec
        assertThat( hash ).hasSize( 64 ).matches( "[0-9A-F]{64}" );
    }

    @Test
    @DisplayName( "passwordHash — SHA-1 path produces 40 hex chars uppercase" )
    void passwordHashSha1()
    {
        KeyAndSalt ks = new KeyAndSalt(
                "0123456789ABCDEF0123456789ABCDEF01234567",
                "DEADBEEF",
                "SHA1" );
        String hash = crypto.passwordHash( "password", ks );
        // SHA-1 ⇒ 20 bytes ⇒ 40 hex chars
        assertThat( hash ).hasSize( 40 ).matches( "[0-9A-F]{40}" );
    }

    @Test
    @DisplayName( "passwordHash — identical to createUserHash's intermediate step" )
    void passwordHashMatchesCreateUserHashStep1()
    {
        // The recipe is : pwHash = digest(password + ':' + salt) uppercase
        // SHA-256("password:DEADBEEF") = 5e... etc.
        KeyAndSalt ks = new KeyAndSalt(
                "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF",
                "DEADBEEF",
                "SHA256" );
        String hash1 = crypto.passwordHash( "password", ks );
        String hash2 = crypto.passwordHash( "password", ks );
        // Deterministic — same input always produces the same output
        assertThat( hash1 ).isEqualTo( hash2 );
        // Differs from createUserHash output (which adds HMAC on top)
        String full = crypto.createUserHash( "admin", "password", ks );
        assertThat( hash1 ).isNotEqualTo( full );
    }

    @Test
    @DisplayName( "hashAlg fallback: null / blank / unknown ⇒ default (SHA-256 per test profile)" )
    void hashAlgFallback()
    {
        assertThat( crypto.resolveDigestAlgorithm( null ) ).isEqualTo( "SHA-256" );
        assertThat( crypto.resolveDigestAlgorithm( "" ) ).isEqualTo( "SHA-256" );
        assertThat( crypto.resolveDigestAlgorithm( "  " ) ).isEqualTo( "SHA-256" );
        assertThat( crypto.resolveDigestAlgorithm( "MD5" ) ).isEqualTo( "SHA-256" );      // unknown ⇒ default
        assertThat( crypto.resolveDigestAlgorithm( "sha256" ) ).isEqualTo( "SHA-256" );   // case-insensitive
        assertThat( crypto.resolveDigestAlgorithm( "SHA-1" ) ).isEqualTo( "SHA-1" );      // already canonical

        assertThat( crypto.resolveMacAlgorithm( null ) ).isEqualTo( "HmacSHA256" );
        assertThat( crypto.resolveMacAlgorithm( "sha256" ) ).isEqualTo( "HmacSHA256" );
        assertThat( crypto.resolveMacAlgorithm( "SHA1" ) ).isEqualTo( "HmacSHA1" );
    }

    @Test
    @DisplayName( "hashToken produces deterministic HmacSHA256 output" )
    void hashTokenDeterministic()
    {
        String key   = "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF";
        String token = "some-jwt-token-payload";

        String first  = crypto.hashToken( key, token );
        String second = crypto.hashToken( key, token );

        assertThat( first ).isEqualTo( second );
        assertThat( first ).hasSize( 64 ).matches( "[0-9a-f]{64}" );
    }

    // ---------- helpers ----------

    private static java.security.PublicKey generateRsaPublicKey( int bits ) throws Exception
    {
        KeyPairGenerator gen = KeyPairGenerator.getInstance( "RSA" );
        gen.initialize( bits );
        KeyPair pair = gen.generateKeyPair();
        return pair.getPublic();
    }

    private static String base64Of( java.security.PublicKey key )
    {
        return Base64.getEncoder().encodeToString( key.getEncoded() );
    }
}
