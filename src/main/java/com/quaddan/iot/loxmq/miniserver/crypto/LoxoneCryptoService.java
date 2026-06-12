/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.crypto;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.jboss.logging.Logger;

import com.quaddan.iot.loxmq.config.LoxoneConfig;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Cryptographic operations for the Miniserver session.
 * <p>
 * Loxone handshake crypto (RSA key exchange, AES-CBC session cipher, HMAC
 * token hashing), structured around Quarkus idioms:
 * <ul>
 *   <li>{@link ApplicationScoped} CDI bean — ArC-friendly lifecycle.</li>
 *   <li>{@link LoxoneCryptoException} unchecked wrapper. Fault Tolerance
 *       rules (retry, circuit breaker) key off this type.</li>
 *   <li>Public key is no longer pulled from a mutable config field. The
 *       caller (the handshake orchestrator) passes it in via
 *       {@link #loadPublicKey(String)} after the {@code jdev/sys/getPublicKey}
 *       call. Until that lands, every crypto operation that needs the key
 *       throws {@link LoxoneCryptoException} with a clear message.</li>
 *   <li>Default algorithms come from {@link LoxoneConfig} (configurable, but
 *       protocol-stable so 99% of operators never override).</li>
 * </ul>
 *
 * <h3>Thread-safety contract</h3>
 * <ul>
 *   <li>{@link Cipher}, {@link Mac}, {@link MessageDigest} are NOT thread-safe.
 *       This bean instantiates a fresh one per call — cost is ~50 µs vs the
 *       hours of debugging that sharing them across the WebSocket reader, the
 *       MQTT callback, the keep-alive timer and the refresh-token timer
 *       would otherwise cause (random {@link BadPaddingException}s).</li>
 *   <li>{@link SecretKey} and {@link IvParameterSpec} are immutable session
 *       data — safe to cache.</li>
 *   <li>Salt rotation is read-modify-write (read current salt → maybe
 *       regenerate → format prefix). Serialised under {@code synchronized}
 *       on {@code this} to avoid two concurrent outbound commands embedding
 *       stale or duplicate salts (miniserver would reject one of them).</li>
 *   <li>{@link SecureRandom} is thread-safe — single instance shared.</li>
 * </ul>
 *
 * <h3>Protocol reference</h3>
 * §"Encryption" + §"Authentication via tokens" of
 * {@code 1700_Communicating-with-the-Miniserver.pdf} (V17.0).
 */
@ApplicationScoped
public class LoxoneCryptoService
{
    private static final Logger    LOG          = Logger.getLogger(LoxoneCryptoService.class);
    private static final HexFormat HEX          = HexFormat.of();

    /**
     * Matches the {@code \0} null-terminator plus any AES-block padding bytes
     * left in a decrypted control string. Compiled once.
     */
    private static final Pattern   NULL_PADDING = Pattern.compile("\0+.*$");

    @Inject
    LoxoneConfig                   config;

    // ---------- immutable session state (set once at @PostConstruct) ----------

    private SecureRandom    secureRandom;
    private SecretKey       aesKey;
    private IvParameterSpec ivSpec;

    private String          aesTransformation;          // e.g. "AES/CBC/PKCS5Padding"
    private String          rsaTransformation;          // e.g. "RSA/ECB/PKCS1Padding"
    private String          defaultDigestAlgorithm;     // e.g. "SHA-256"
    private String          defaultMacAlgorithm;        // e.g. "HmacSHA256"

    private Pattern         saltPrefixPattern;         // strip leading "salt/<salt>/"
    private Pattern         nextSaltPrefixPattern;     // strip leading "nextSalt/<prev>/<new>/"

    // ---------- mutable session state ----------

    /** RSA public key — null until {@link #loadPublicKey(String)} succeeds. */
    private volatile PublicKey miniserverRsaPublicKey;

    /** Current outbound-command salt (URL-encoded hex). */
    private String             currentSalt;

    /** Epoch-seconds at which {@link #currentSalt} was generated. */
    private long               currentSaltCreatedAt;

    // ==========================================================================
    //  Init
    // ==========================================================================

    @PostConstruct
    void init()
    {
        LOG.debug("Initialising LoxoneCryptoService...");

        // Cache config-driven transformation strings.
        aesTransformation      = config.miniserver().crypto().encryptCommand().transformation();
        rsaTransformation      = config.miniserver().crypto().encryptKey().transformation();
        defaultDigestAlgorithm = config.miniserver().crypto().hashPassword().algo();
        defaultMacAlgorithm    = config.miniserver().crypto().hashUserPassword().algo();

        // Generate session-scoped AES key + IV (the miniserver will learn them
        // via the RSA-wrapped key exchange).
        secureRandom = new SecureRandom();
        aesKey       = generateAesKey();
        ivSpec       = generateRandomIv();

        // Initial salt.
        currentSalt = generateNewSalt();

        // Pre-compile salt-prefix strip patterns. The prefixes themselves
        // are config-driven so this happens once at @PostConstruct.
        saltPrefixPattern     = Pattern.compile(
                                                "^" + Pattern.quote(config.miniserver().cmd().prefix().salt())
                                                + "[^/]*/");
        nextSaltPrefixPattern = Pattern.compile(
                                                "^" + Pattern.quote(config.miniserver().cmd().prefix().nextSalt())
                                                + "[^/]*/[^/]*/");

        LOG.debug("LoxoneCryptoService initialised.");
    }

    // ==========================================================================
    //  Public API
    // ==========================================================================

    /**
     * Parse and cache the Miniserver's RSA public key (Base64-encoded
     * SubjectPublicKeyInfo DER, as returned by {@code jdev/sys/getPublicKey}).
     * Idempotent; the orchestrator calls this once per handshake.
     */
    public void loadPublicKey(String base64Der)
    {
        if (base64Der == null || base64Der.isBlank())
        {
            throw new LoxoneCryptoException(
                                            "Cannot load Miniserver public key: payload is null or blank");
        }
        try
        {
            KeyFactory         keyFactory = KeyFactory.getInstance(
                                                                   config.miniserver().crypto().encryptKey().algo());
            byte[]             keyData    = Base64.getDecoder().decode(base64Der);
            X509EncodedKeySpec spec       = new X509EncodedKeySpec(keyData);
            miniserverRsaPublicKey = keyFactory.generatePublic(spec);
            LOG.debugf("Miniserver public key loaded (%d bits)",
                       ((java.security.interfaces.RSAPublicKey) miniserverRsaPublicKey).getModulus().bitLength());
        }
        catch (NoSuchAlgorithmException | InvalidKeySpecException | IllegalArgumentException e)
        {
            throw new LoxoneCryptoException("Failed to parse Miniserver public key", e);
        }
    }

    /** Has {@link #loadPublicKey(String)} succeeded yet? */
    public boolean hasPublicKey()
    {
        return miniserverRsaPublicKey != null;
    }

    /**
     * Build the {@code keyexchange} payload: the AES key + IV (colon-separated,
     * hex-encoded) wrapped with the Miniserver's RSA public key, then Base64.
     * The result is sent as
     * {@code jdev/sys/keyexchange/{base64-of-encrypted-session-key}}.
     */
    public String wrappedSessionKey()
    {
        requirePublicKey();
        String plain = HEX.formatHex(aesKey.getEncoded()) + ":" + HEX.formatHex(ivSpec.getIV());
        try
        {
            Cipher rsa = Cipher.getInstance(rsaTransformation);
            rsa.init(Cipher.PUBLIC_KEY, miniserverRsaPublicKey);
            byte[] wrapped = rsa.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(wrapped);
        }
        catch (NoSuchAlgorithmException
               | NoSuchPaddingException
               | InvalidKeyException
               | IllegalBlockSizeException
               | BadPaddingException e)
        {
            throw new LoxoneCryptoException("Failed to RSA-wrap the AES session key", e);
        }
    }

    /**
     * Builds the user-credential hash sent to {@code jdev/sys/getjwt/...}.
     * <p>
     * Two-step per protocol §"Authentication":
     * <ol>
     *   <li>digest({@code password + ":" + userSalt}) → {@code hashPassword}
     *       (hex, uppercase)</li>
     *   <li>HMAC({@code key}, {@code user + ":" + hashPassword}) → final hash</li>
     * </ol>
     * The {@code hashAlg} in the response steers which JCE algorithm to use
     * (SHA-1 / SHA-256, HmacSHA1 / HmacSHA256). Falls back to the config
     * defaults if the field is absent (older firmware).
     */
    public String createUserHash(String miniserverUser,
                                 String miniserverPassword,
                                 KeyAndSalt keyAndSalt)
    {
        String digestAlgo     = resolveDigestAlgorithm(keyAndSalt.hashAlg());
        String macAlgo        = resolveMacAlgorithm(keyAndSalt.hashAlg());

        String hashedPassword = passwordHash(miniserverPassword, keyAndSalt);
        return hmac(miniserverUser + ":" + hashedPassword, keyAndSalt.key(), macAlgo);
    }

    /**
     * Step 1 of the password recipe — the form actually <em>stored</em> on
     * the Miniserver. Used by the {@code updateuserpwdh} /
     * {@code updateuservisupwdh} commands ; identical to the inner step of
     * {@link #createUserHash}.
     *
     * <p>Recipe : {@code digest(plaintext + ":" + salt).toUpperCase()},
     * where the digest algorithm comes from
     * {@link KeyAndSalt#hashAlg()} (with the same SHA-1 / SHA-256
     * fallback semantics as {@link #createUserHash}).
     *
     * <p>The result is hex-uppercase and URL-safe — can be embedded
     * directly in the {@code jdev/sps/updateuser*pwdh/{uuid}/{hash}}
     * path without any further encoding.
     */
    public String passwordHash(String plaintext, KeyAndSalt keyAndSalt)
    {
        String digestAlgo = resolveDigestAlgorithm(keyAndSalt.hashAlg());
        return digest(plaintext + ":" + keyAndSalt.salt(), digestAlgo).toUpperCase();
    }

    /**
     * HMACs a token (or any string) under the given hex-encoded key, using the
     * configured {@link #defaultMacAlgorithm}. Used to sign each subsequent
     * use of the JWT token in {@code jdev/sys/authwithtoken/{hash}/{user}}.
     */
    public String hashToken(String hexKey, String token)
    {
        return hmac(token, hexKey, defaultMacAlgorithm);
    }

    /**
     * Encrypt an outbound command — wraps it in
     * {@code [salt|nextSalt]/<...>/<command>\0}, AES-CBC encrypts, Base64s,
     * URL-encodes, then prepends {@code jdev/sys/enc/}.
     * <p>
     * Thread-safe by construction (fresh Cipher per call). The salt-rotation
     * read-modify-write is serialised separately (see {@link #wrapCommand}).
     */
    public String encryptCommand(String command)
    {
        String toEncrypt = wrapCommand(command);
        try
        {
            Cipher cipher  = newAesCipher(Cipher.ENCRYPT_MODE);
            byte[] enc     = cipher.doFinal(toEncrypt.getBytes(StandardCharsets.UTF_8));
            String b64     = Base64.getEncoder().encodeToString(enc);
            String urlSafe = URLEncoder.encode(b64, StandardCharsets.UTF_8);
            return config.miniserver().cmd().encrypt() + urlSafe;
        }
        catch (IllegalBlockSizeException
               | BadPaddingException
               | NoSuchAlgorithmException
               | NoSuchPaddingException
               | InvalidKeyException
               | InvalidAlgorithmParameterException e)
        {
            throw new LoxoneCryptoException("Failed to encrypt command: " + command, e);
        }
    }

    /**
     * Decrypt the {@code control} field of a command response — the **full
     * inverse** of {@link #encryptCommand}.
     *
     * <p>The miniserver echoes the original encrypted command verbatim in
     * the {@code LL.control} field of its response — i.e. the exact string
     * that {@link #encryptCommand} produced :
     * {@code jdev/sys/enc/<urlencoded-base64>}.
     *
     * <p>Steps applied (mirror image of {@link #encryptCommand}) :
     * <ol>
     *   <li>strip the {@code jdev/sys/enc/} prefix (from
     *       {@code config.miniserver().cmd().encrypt()}) if present —
     *       commands that didn't go through the encrypted channel obviously
     *       won't carry it ;</li>
     *   <li>URL-decode (we URL-encoded so that {@code +} / {@code /} /
     *       {@code =} survived the path component) ;</li>
     *   <li>Base64-decode ;</li>
     *   <li>AES-CBC decrypt with the session key ;</li>
     *   <li>strip the {@code \0} terminator + any AES padding ;</li>
     *   <li>strip the {@code salt/<salt>/} or {@code nextSalt/<prev>/<new>/}
     *       wrap that {@link #wrapCommand} prepended.</li>
     * </ol>
     *
     * <p>Throws {@link LoxoneCryptoException} on any failure (malformed
     * URL escape, non-Base64 input, AES padding error, …) — the caller
     * (currently {@code CommandResponsePublisher}) decides whether to
     * fall back to verbatim publication.
     *
     * <p><b>Why a complete inverse</b> — when this method handled only
     * steps 3-6, the caller had to strip the prefix and URL-decode
     * itself. Real-world responses arriving on {@code command_response}
     * were published Base64-encrypted because
     * {@code CommandResponsePublisher} called this method with the full
     * path string, the Base64 decode of which threw
     * {@link IllegalArgumentException} and tripped the verbatim
     * fallback. Making this method the complete inverse lets the
     * publisher stay symmetric and dumb.
     */
    public String decryptControl(String controlEncrypted)
    {
        try
        {
            // Step 1 — strip the jdev/sys/enc/ prefix if present.
            String encryptPrefix = config.miniserver().cmd().encrypt();
            String payload       = controlEncrypted;
            if (encryptPrefix != null && payload.startsWith(encryptPrefix))
            {
                payload = payload.substring(encryptPrefix.length());
            }
            // Step 2 — URL-decode iff the form is still URL-encoded.
            //
            // Real-world finding : the production Miniserver
            // URL-DECODES the path when echoing it back in {@code LL.control}.
            // So in prod we receive the raw Base64 with literal '+', '/',
            // '=' chars — no '%XX' sequences. Running URLDecoder on that
            // form would convert the literal '+' (a valid Base64 char) to
            // ' ' (space), corrupting the input → BadPaddingException →
            // verbatim fallback in CommandResponsePublisher → the encrypted
            // control would still be published. Detect by '%' presence :
            // URL-encoded form would have %2B / %2F / %3D ; URL-decoded
            // form has none. Cheap, deterministic, no false positives
            // (Base64 alphabet never contains '%').
            if (payload.indexOf('%') >= 0)
            {
                payload = URLDecoder.decode(payload, StandardCharsets.UTF_8);
            }

            // Step 3-6 — Base64 → AES decrypt → strip padding + salt wrap.
            Cipher cipher  = newAesCipher(Cipher.DECRYPT_MODE);
            byte[] decoded = Base64.getDecoder().decode(payload);
            byte[] plain   = cipher.doFinal(decoded);
            String result  = new String(plain, StandardCharsets.UTF_8);

            result = NULL_PADDING.matcher(result).replaceAll("");
            result = saltPrefixPattern.matcher(result).replaceFirst("");
            result = nextSaltPrefixPattern.matcher(result).replaceFirst("");
            return result;
        }
        catch (IllegalBlockSizeException
               | BadPaddingException
               | NoSuchAlgorithmException
               | NoSuchPaddingException
               | InvalidKeyException
               | InvalidAlgorithmParameterException
               | IllegalArgumentException e)
        {
            // IllegalArgumentException covers both URLDecoder ("invalid %XX
            // escape") and Base64.decode ("invalid Base64 sequence") — both
            // mean "this isn't an encrypted control", same outcome as the
            // crypto failures above. Wrapping uniformly keeps the caller's
            // catch simple.
            throw new LoxoneCryptoException("Failed to decrypt response control: " + controlEncrypted, e);
        }
    }

    // ==========================================================================
    //  Internals
    // ==========================================================================

    private void requirePublicKey()
    {
        if (miniserverRsaPublicKey == null)
        {
            throw new LoxoneCryptoException(
                                            "Miniserver RSA public key not loaded yet — call loadPublicKey() first " +
                                            "(typically handshake step 2)");
        }
    }

    private SecretKey generateAesKey()
    {
        String aesAlgo = config.miniserver().crypto().encryptCommand().algo();
        int    bits    = config.miniserver().crypto().encryptCommand().keySize();
        try
        {
            int maxAllowed = Cipher.getMaxAllowedKeyLength(aesAlgo);
            if (maxAllowed < bits)
            {
                throw new LoxoneCryptoException(
                                                "JCE allows max " + maxAllowed
                                                + "-bit "
                                                + aesAlgo
                                                +
                                                " but config requested "
                                                + bits
                                                + ". Enable unlimited-strength JCE.");
            }
            KeyGenerator kg = KeyGenerator.getInstance(aesAlgo);
            kg.init(bits);
            return kg.generateKey();
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new LoxoneCryptoException("AES key generation failed", e);
        }
    }

    private IvParameterSpec generateRandomIv()
    {
        byte[] iv = new byte[config.miniserver().crypto().sessionKey().initVectorLength()];
        secureRandom.nextBytes(iv);
        return new IvParameterSpec(iv);
    }

    private Cipher newAesCipher(int mode)
                                          throws NoSuchPaddingException,
                                          NoSuchAlgorithmException,
                                          InvalidAlgorithmParameterException,
                                          InvalidKeyException
    {
        Cipher c = Cipher.getInstance(aesTransformation);
        c.init(mode, aesKey, ivSpec);
        return c;
    }

    /**
     * Salt rotation. {@code synchronized} because read-modify-write of
     * {@link #currentSalt} must not race outbound commands.
     */
    private synchronized String wrapCommand(String command)
    {
        if (saltExpired())
        {
            String prev = currentSalt;
            currentSalt = generateNewSalt();
            return config.miniserver().cmd().prefix().nextSalt() + prev + "/" + currentSalt + "/" + command + "\0";
        }
        return config.miniserver().cmd().prefix().salt() + currentSalt + "/" + command + "\0";
    }

    private String generateNewSalt()
    {
        byte[] bytes = new byte[config.miniserver().crypto().encryptCommand().saltLength()];
        secureRandom.nextBytes(bytes);
        currentSaltCreatedAt = Instant.now().getEpochSecond();
        return URLEncoder.encode(HEX.formatHex(bytes), StandardCharsets.UTF_8);
    }

    private boolean saltExpired()
    {
        long ageSec = Instant.now().getEpochSecond() - currentSaltCreatedAt;
        long maxSec = config.miniserver().crypto().salt().maxAge().toSeconds();
        return ageSec > maxSec;
    }

    private String digest(String input, String algorithm)
    {
        try
        {
            MessageDigest md = MessageDigest.getInstance(algorithm);
            return HEX.formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new LoxoneCryptoException("Digest algorithm not available: " + algorithm, e);
        }
    }

    private String hmac(String input, String hexKey, String algorithm)
    {
        try
        {
            SecretKeySpec keySpec = new SecretKeySpec(HEX.parseHex(hexKey), algorithm);
            Mac           mac     = Mac.getInstance(algorithm);
            mac.init(keySpec);
            return HEX.formatHex(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException | InvalidKeyException | IllegalArgumentException e)
        {
            throw new LoxoneCryptoException("HMAC failed (alg=" + algorithm + ")", e);
        }
    }

    /**
     * Maps a server-advertised {@code hashAlg} to its JCE
     * {@link MessageDigest} name. {@code "SHA1"} ⇒ {@code "SHA-1"};
     * {@code "SHA256"} ⇒ {@code "SHA-256"}. Falls back to the configured
     * default for any unknown / missing value.
     */
    String resolveDigestAlgorithm(String serverHashAlg)
    {
        if (serverHashAlg == null || serverHashAlg.isBlank())
        {
            return defaultDigestAlgorithm;
        }
        return switch (serverHashAlg.trim().toUpperCase())
        {
            case "SHA1", "SHA-1" -> "SHA-1";
            case "SHA256", "SHA-256" -> "SHA-256";
            default -> {
                LOG.warnf("Miniserver advertised unknown hashAlg='%s'; falling back to %s",
                          serverHashAlg,
                          defaultDigestAlgorithm);
                yield defaultDigestAlgorithm;
            }
        };
    }

    /**
     * Maps a server-advertised {@code hashAlg} to its JCE
     * {@link Mac} name. Same fallback semantics as
     * {@link #resolveDigestAlgorithm(String)}.
     */
    String resolveMacAlgorithm(String serverHashAlg)
    {
        if (serverHashAlg == null || serverHashAlg.isBlank())
        {
            return defaultMacAlgorithm;
        }
        return switch (serverHashAlg.trim().toUpperCase())
        {
            case "SHA1", "SHA-1" -> "HmacSHA1";
            case "SHA256", "SHA-256" -> "HmacSHA256";
            default -> defaultMacAlgorithm;
        };
    }

    // ==========================================================================
    //  Test hooks
    // ==========================================================================

    /** Visible-for-testing: current salt (URL-encoded hex). */
    String currentSaltForTest()
    {
        return currentSalt;
    }

    /** Visible-for-testing: force a salt rotation on next encrypt. */
    void expireSaltForTest()
    {
        currentSaltCreatedAt = 0L;
    }
}
