/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.testresources;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.ServerWebSocket;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Full HTTP + WebSocket fake Loxone Miniserver.
 * <p>
 * Single Vert.x {@link HttpServer} on a random local port handles both
 * the HTTP bootstrap endpoints AND the WebSocket-based handshake the
 * session orchestrator goes through to reach
 * {@code SessionState.RUNNING}. No crypto on the server side — the
 * binding's outgoing commands are AES-encrypted with a session key the
 * fake server never decrypts. The fake server just responds with the
 * canned next-step reply on each text frame received, matching the
 * deterministic order the orchestrator's state machine sends them in.
 *
 * <h3>Why no decryption</h3>
 * The real protocol has the binding encrypt commands but the server's
 * replies travel in plain JSON (verified empirically against the real
 * Miniserver, and against {@code SessionOrchestratorTest}'s in-JVM
 * fakes which queue plain text replies). So the dialog is:
 * <pre>
 *   binding → fake : jdev/sys/keyexchange/&lt;rsa-wrap-of-aes-key&gt;
 *   fake    → binding : {"LL":{"value":"ack","Code":"200"}}
 *   binding → fake : jdev/sys/enc/&lt;aes-cbc-encrypted "getjwt/..."&gt;
 *   fake    → binding : {"LL":{"value":{token,...},"Code":"200"}}
 *   ... and so on, scripted by frame count.
 * </pre>
 * The fake doesn't need to know what's inside the encrypted blobs to
 * pick a reply — the orchestrator sends commands in a fixed order
 * (keyexchange → getjwt → LoxAPPversion3 → data/LoxAPP3.json →
 * enablebinstatusupdate), so a frame counter is enough.
 *
 * <h3>HTTP endpoints</h3>
 * Same canned identity as {@link FakeMiniserverHttpResource} plus the
 * {@code getkey2/{user}} reply (HTTP, not WS — verified live, see
 * {@code MiniserverHttpClient#fetchKeyAndSalt} javadoc).
 *
 * <h3>State-event auto-broadcast</h3>
 * Right after acking {@code enablebinstatusupdate}, the fake pushes a
 * canned Value-State event-table as a binary frame pair (8-byte
 * {@code WsBinHdr} header + 24-byte body — 16-byte UUID + 8-byte LE
 * double). The UUID and value live in the public constants
 * {@link #AUTO_VALUE_STATE_UUID} / {@link #AUTO_VALUE_STATE_VALUE}; the
 * {@code StateEventsRoundTripIT} subscribes to the matching MQTT topic
 * and asserts the publish round-trips correctly through
 * {@code BinaryStatesDecoder} → {@code StatesPublisher} → MQTT.
 *
 * <h3>What this still does NOT cover</h3>
 * <ul>
 *   <li>Crypto round-trip — the fake doesn't validate the session-key
 *       envelope or the AES-CBC encryption of commands. Those code
 *       paths are unit-tested in {@code LoxoneCryptoServiceTest}; a
 *       full IT round-trip is the 🟢 crypto TODO item (~300 LOC).</li>
 *   <li>Text / DayTimer / Weather state events — only Value (id=2) is
 *       auto-broadcast. The decoding logic for the other three is
 *       unit-tested in {@code BinaryStatesDecoderTest}.</li>
 *   <li>Token refresh + killtoken — handled in-JVM by
 *       {@code TokenRefreshSchedulerTest}.</li>
 * </ul>
 */
public final class FakeMiniserverFullResource implements QuarkusTestResourceLifecycleManager
{
    /** Sentinel string baked into the fake's command-response reply so
     *  the round-trip IT can assert on a known substring without
     *  reverse-engineering the full envelope. Public so the IT can
     *  reference it directly — avoids drift if we ever tweak the
     *  literal. */
    public static final String COMMAND_RESPONSE_MARKER = "fake-miniserver-command-response-marker";

    // ------------------------------------------------------------------------
    //  Canned Value-State auto-broadcast
    //
    //  Right after acknowledging `enablebinstatusupdate`, the fake pushes a
    //  single Value-State event-table as a binary frame pair (header + body)
    //  — the same shape a real miniserver would emit once the binding
    //  subscribes to state updates. The IT (StateEventsRoundTripIT) asserts
    //  the round-trip binary-frame → BinaryStatesDecoder → ValueStatesEvent →
    //  StatesPublisher → MQTT `…/states/type_2/{uuid}`.
    //
    //  The UUID/value are public constants so the IT can build the expected
    //  topic + assert on the payload without drift.
    // ------------------------------------------------------------------------
    /** Test UUID — chosen for memorable hex layout, not collision-resistant.
     *  The format must match a real Loxone control UUID (8-4-4-16 hex
     *  characters with hyphens). */
    public static final String AUTO_VALUE_STATE_UUID  = "12345678-aabb-ccdd-1122334455667788";
    /** Test value — picked to be non-zero and non-integer so a serializer
     *  that accidentally rounds or drops it is easy to spot. */
    public static final double AUTO_VALUE_STATE_VALUE = 42.5d;

    /** Same canned identity as the HTTP-only resource — kept in sync on
     *  purpose so ITs that exercise both BOOTSTRAP and HANDSHAKE see the
     *  same miniserver identity (snr / version / key). */
    private static final String API_KEY_RESPONSE = """
                                                   {"LL":{"control":"dev/cfg/apiKey","value":\
                                                   "{'snr':'50:4F:94:AA:BB:CC','version':'17.0.3.31','key':'DEADBEEF',\
                                                   'isInTrust':false,'local':true,'address':'127.0.0.1','httpsStatus':1}",\
                                                   "Code":"200"}}""";

    /** {@code getkey2/{user}} reply — the canned salt+key the binding's
     *  crypto uses to compute the password HMAC hash. The salt + hashAlg
     *  values don't matter to us (we never validate the hash); the
     *  binding accepts any well-formed reply here. */
    private static final String KEY_AND_SALT_RESPONSE = """
                                                        {"LL":{"control":"dev/sys/getkey2/YWRtaW4=","value":\
                                                        {"key":"0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF",\
                                                        "salt":"DEADBEEF","hashAlg":"SHA256"},"code":"200"}}""";

    /** {@code getkey/{user}} (without the 2) reply — used by
     *  MiniserverAdminCommandClient.fetchHashKey() to fetch the HMAC key
     *  that signs admin commands. The binding does not validate the
     *  returned content, only extracts LL.value (hex string). Spec V17
     *  §"Refresh JWT token" — distinct endpoint from getkey2. */
    private static final String GET_KEY_RESPONSE = """
                                                   {"LL":{"control":"dev/sys/getkey/YWRtaW4=","value":\
                                                   "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF",\
                                                   "code":"200"}}""";

    // =====================================================================
    //  Admin fixtures for happy-path ITs
    //
    //  To exercise the admin commands (post-RUNNING) without mocking the
    //  whole crypto chain, we route by HTTP URL content:
    //    GET /jdev/sps/{commandPath}?autht=...&user=...
    //  The binding sends the HMAC hash in the query param; we ignore it
    //  and return a canned payload by commandPath. The binding accepts
    //  the response without verifying the server-side signature (it is
    //  the binding that SIGNS, the miniserver verifies elsewhere).
    // =====================================================================

    /** {@code getuserlist2} fixture — 2 users (1 admin + 1 viewer). */
    private static final String GET_USER_LIST_2_RESPONSE = """
                                                           {"LL":{"control":"jdev/sps/getuserlist2","value":\
                                                           [{"name":"admin","uuid":"12eebb90-00a1-3073-ffff88c561c84c44",\
                                                             "isAdmin":true,"userState":0},\
                                                            {"name":"alice","uuid":"0a5fa72f-018b-0050-1900000000000000",\
                                                             "isAdmin":false,"userState":0}],"Code":"200"}}""";

    /** {@code getgrouplist} fixture — 2 groups. */
    private static final String GET_GROUP_LIST_RESPONSE = """
                                                          {"LL":{"control":"jdev/sps/getgrouplist","value":\
                                                          [{"name":"Administrators","description":"admin",\
                                                            "uuid":"0fa5b50c-0181-12e1-ffff112233445566",\
                                                            "type":1,"userRights":4294967295},\
                                                           {"name":"Famille","description":"familie",\
                                                            "uuid":"0fa5b50c-0181-12e2-ffff112233445566",\
                                                            "type":0,"userRights":4095}],"Code":"200"}}""";

    /** {@code getuser/admin-uuid} fixture — full UserDetail with
     *  lowercase usergroups (V17 spec p.5-6). */
    private static final String GET_USER_ADMIN_RESPONSE = """
                                                          {"LL":{"control":"jdev/sps/getuser/12eebb90-00a1-3073-ffff88c561c84c44",\
                                                          "value":{\
                                                          "uuid":"12eebb90-00a1-3073-ffff88c561c84c44",\
                                                          "name":"admin","desc":"admin","userid":"admin",\
                                                          "firstname":"","lastname":"","email":"","phone":"",\
                                                          "isAdmin":true,"changePassword":false,\
                                                          "userState":0,"lastedit":549208860,\
                                                          "usergroups":[\
                                                            {"name":"Administrators","uuid":"0fa5b50c-0181-12e1-ffff112233445566"}],\
                                                          "nfcTags":[]},"Code":"200"}}""";

    /** {@code calendargetentries} fixture — 1 seasonal entry calMode 4
     *  (yearly timespan) + 1 one-shot entry calMode 2 (specific date). */
    private static final String CALENDAR_GET_ENTRIES_RESPONSE = """
                                                                {"LL":{"control":"jdev/sps/calendargetentries","value":\
                                                                [{"uuid":"abc-0001","name":"Vacances été",\
                                                                  "operatingMode":10,"calMode":4,\
                                                                  "calModeAttrs":{"startMonth":7,"startDay":1,\
                                                                                  "endMonth":8,"endDay":31}},\
                                                                 {"uuid":"abc-0002","name":"Noel 2026",\
                                                                  "operatingMode":11,"calMode":2,\
                                                                  "calModeAttrs":{"startYear":2026,"startMonth":12,"startDay":25}}],\
                                                                "Code":"200"}}""";

    /** Generic OK reply for admin mutation commands (addoredituser,
     *  assignusertogroup, calendarcreateentry, etc.) — the binding only
     *  parses the Code, not the value. */
    private static final String GENERIC_OK_RESPONSE = """
                                                      {"LL":{"control":"jdev/sps/generic","value":"ok","Code":"200"}}""";

    private Vertx      vertx;
    private HttpServer server;

    private static volatile String  miniserverHost;
    private static volatile Integer miniserverPort;

    // ------------------------------------------------------------------------
    //  Crypto round-trip state
    //
    //  The fake now keeps the RSA private key (previously discarded after the
    //  /jdev/sys/getPublicKey response was built) and uses it to:
    //   - RSA-decrypt the `jdev/sys/keyexchange/{base64}` payload arriving at
    //     the start of the WS handshake → extract the AES session key + IV
    //     the binding generated.
    //   - AES-decrypt every subsequent `jdev/sys/enc/{url-encoded-base64}`
    //     frame, strip the `salt/.../` (or `nextSalt/.../.../`) preamble and
    //     the trailing \0, and append the resulting plaintext command to
    //     {@link #decryptedCommands} for the IT to assert on.
    //
    //  Reset between test class runs because Quarkus tears the resource down
    //  and restarts it.
    // ------------------------------------------------------------------------
    private static volatile KeyPair         fakeKeyPair;
    private static volatile SecretKey       decryptedAesKey;
    private static volatile IvParameterSpec decryptedIv;
    private static final    List< String >  decryptedCommands = new CopyOnWriteArrayList<>();

    public static String host() { return miniserverHost; }

    public static int port() { return miniserverPort; }

    /** Plaintext recovered from every outbound {@code jdev/sys/enc/…} frame
     *  the binding sent since {@link #start()} — first entry is typically
     *  the handshake's {@code getjwt/…}, subsequent ones are post-RUNNING
     *  commands driven from the test (MQTT publish on {@code …/command} or
     *  {@code …/api}). The list is appended in arrival order. Returned as
     *  an immutable snapshot to avoid the IT iterating a live list. */
    public static List< String > decryptedCommands()
    {
        return List.copyOf( decryptedCommands );
    }

    @Override
    public Map< String, String > start()
    {
        try
        {
            // Same fresh-RSA-per-test-class trick as FakeMiniserverHttpResource:
            // 2048-bit key wrapped in PEM CERTIFICATE markers so
            // LoxoneCryptoService.loadPublicKey can parse it. The fake doesn't
            // need the matching private key — we never decrypt anything.
            String publicKeyResponse = buildGetPublicKeyResponse();

            // validUntil is in seconds since the Loxone epoch
            // (2009-01-01T00:00:00Z). Set it 1h in the future from the JVM's
            // current time so the binding accepts the token without flagging
            // it as already-expired. Same arithmetic as
            // SessionOrchestratorTest#runHandshakeToRunning.
            long validUntil = ChronoUnit.SECONDS.between(
                    LocalDateTime.of( 2009, 1, 1, 0, 0, 0 )
                                 .atZone( ZoneId.of( "UTC" ) ).toInstant(),
                    Instant.now().plusSeconds( 3600 ) );

            // Canned replies — keyed by a substring that uniquely
            // identifies the command type the binding just sent.
            // Content-based routing (not order-based!) survives both
            // handshake paths:
            //   - cache miss → 5 frames (keyexchange, enc(getjwt),
            //                  LoxAPPversion3, data/LoxAPP3.json, enablebin)
            //   - cache hit  → 4 frames (data/LoxAPP3.json skipped)
            // Order-based scripting drifts by one when a previous IT run
            // populated the cache directory — observed live, was the
            // root cause of an intermittent failure-after-RUNNING.
            String keyExchangeReply = """
                                      {"LL":{"control":"jdev/sys/keyexchange/...","value":"ack","Code":"200"}}""";
            String getjwtReply = """
                                 {"LL":{"control":"jdev/sys/getjwt/...","value":\
                                 {"token":"abc.def.ghi",\
                                 "key":"0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF",\
                                 "validUntil":%d,"tokenRights":4,"unsecurePass":false},\
                                 "Code":"200"}}""".formatted( validUntil );
            // LoxAPP3 fixture enriched to exercise
            // LoxApp3MetadataResolver. The `lastModified` is bumped
            // to "2026-05-25 18:00:00" to invalidate any leftover
            // cache entry from a previous fixture revision.
            //
            // Minimal structure covering the 3 levels LiveStatesPageIT
            // needs to verify the Topology serializes correctly in
            // native mode (without registered reflection it would fall
            // back to "{\"rooms\":[],...}" — the regression this IT
            // catches).
            String loxAppVersionReply = """
                                        {"LL":{"control":"jdev/sps/LoxAPPversion3","value":"2026-05-25 18:00:00","Code":"200"}}""";
            String loxApp3Reply = """
                                  {"lastModified":"2026-05-25 18:00:00",\
                                  "msInfo":{"serialNr":"50:4F:94:AA:BB:CC"},\
                                  "rooms":{\
                                  "0a000001-0000-0001-aaaaaaaaaaaaaaaa":{"name":"Salon"}},\
                                  "cats":{\
                                  "0a000002-0000-0001-aaaaaaaaaaaaaaaa":{"name":"Temperature"}},\
                                  "controls":{\
                                  "0a000003-0000-0001-aaaaaaaaaaaaaaaa":{\
                                  "name":"Sonde Salon",\
                                  "room":"0a000001-0000-0001-aaaaaaaaaaaaaaaa",\
                                  "cat":"0a000002-0000-0001-aaaaaaaaaaaaaaaa",\
                                  "details":{"format":"%.1f\\u00b0C"},\
                                  "states":{"value":"0a000004-0000-0001-aaaaaaaaaaaaaaaa"}}},\
                                  "fixture":"fake LoxAPP3.json contents"}""";
            String enableBinReply = """
                                    {"LL":{"control":"jdev/sps/enablebinstatusupdate","value":"1","Code":"200"}}""";

            // Reply for any post-handshake encrypted command — used by
            // CommandRoundTripIT to assert MQTT → encrypted WS → reply →
            // MQTT /command_response round-trip. The literal value is
            // arbitrary; the IT asserts on the COMMAND_RESPONSE_MARKER
            // substring to prove the chain is wired end-to-end. The
            // orchestrator's onText case RUNNING fires this verbatim as
            // a MiniserverCommandResponseEvent.
            String commandResponseReply = """
                                          {"LL":{"control":"jdev/sys/enc/echoed-encrypted-control",\
                                          "value":"%s","Code":"200"}}""".formatted( COMMAND_RESPONSE_MARKER );

            vertx  = Vertx.vertx();
            server = vertx.createHttpServer();

            server.requestHandler( req ->
                                   {
                                       String path = req.path();
                                       switch ( path )
                                       {
                                           case "/jdev/cfg/apiKey" -> req.response().end( API_KEY_RESPONSE );
                                           case "/jdev/sys/getPublicKey" -> req.response().end( publicKeyResponse );
                                           default ->
                                           {
                                               // getkey2/{user} — variable suffix, prefix-match.
                                               if ( path.startsWith( "/jdev/sys/getkey2/" ) )
                                               {
                                                   req.response().end( KEY_AND_SALT_RESPONSE );
                                               }
                                               // getkey/{user} (without the 2) — used by the
                                               // admin client to fetch the HMAC key that signs
                                               // admin commands. Spec V17 §"Refresh JWT token".
                                               else if ( path.startsWith( "/jdev/sys/getkey/" ) )
                                               {
                                                   req.response().end( GET_KEY_RESPONSE );
                                               }
                                               // Admin commands GET /jdev/sps/{cmd}?autht=…
                                               // We route by cmd content; we ignore the
                                               // autht/user query params (the binding signs,
                                               // the miniserver verifies — here we simulate the
                                               // miniserver "server side" that accepts everything).
                                               else if ( path.startsWith( "/jdev/sps/" ) )
                                               {
                                                   req.response().end( routeAdminCommand(
                                                           path.substring( "/jdev/sps/".length() ) ) );
                                               }
                                               else
                                               {
                                                   // Surface unknown HTTP probes as 404 rather than
                                                   // hanging — makes regressions in the URL routing
                                                   // visible immediately.
                                                   req.response().setStatusCode( 404 ).end();
                                               }
                                           }
                                       }
                                   } );

            server.webSocketHandler( ( ServerWebSocket ws ) ->
                                     {
                                         if ( !"/ws/rfc6455".equals( ws.path() ) )
                                         {
                                             rejectWrongPath( ws );
                                             return;
                                         }
                                         // Per-connection state: the FIRST jdev/sys/enc/* frame is
                                         // the encrypted getjwt sent during handshake — we reply
                                         // with the token JSON. Subsequent jdev/sys/enc/* frames
                                         // are Phase-6b commands published by the MQTT subscriber
                                         // → we reply with a generic command-response so the
                                         // orchestrator's case RUNNING re-publishes it on the
                                         // /command_response topic. This is what
                                         // CommandRoundTripIT asserts on.
                                         AtomicBoolean getjwtSeen = new AtomicBoolean( false );
                                         ws.textMessageHandler( text ->
                                                                {
                                                                    // Route by command substring. Content-based, not
                                                                    // counter-based — survives the cache-hit vs cache-miss
                                                                    // path the orchestrator might take on LoxAPP3.
                                                                    if ( text.contains( "jdev/sys/keyexchange" ) )
                                                                    {
                                                                        // Extract the AES session key the binding generated
                                                                        // so we can decrypt every subsequent jdev/sys/enc/…
                                                                        // frame. Silent on failure to avoid breaking ITs
                                                                        // that don't care about the decrypt path (e.g.
                                                                        // CommandRoundTripIT, StateEventsRoundTripIT) — the
                                                                        // only consequence is decryptedCommands() stays
                                                                        // empty for that run.
                                                                        try { unwrapSessionKey( text ); }
                                                                        catch ( RuntimeException ignored ) { /* see comment */ }
                                                                        ws.writeTextMessage( keyExchangeReply );
                                                                    }
                                                                    else if ( text.contains( "jdev/sys/enc/" ) )
                                                                    {
                                                                        // Capture the decrypted command for IT assertions.
                                                                        // Null when the keyexchange step failed (or hasn't
                                                                        // arrived) — that case is also benign.
                                                                        String plain = decryptCommand( text );
                                                                        if ( plain != null )
                                                                        {
                                                                            decryptedCommands.add( plain );
                                                                        }
                                                                        if ( getjwtSeen.compareAndSet( false, true ) )
                                                                        {
                                                                            ws.writeTextMessage( getjwtReply );
                                                                        }
                                                                        else
                                                                        {
                                                                            // Post-handshake encrypted command → echo a
                                                                            // command-response. The orchestrator's
                                                                            // onText/RUNNING fires this verbatim as a
                                                                            // MiniserverCommandResponseEvent.
                                                                            ws.writeTextMessage( commandResponseReply );
                                                                        }
                                                                    }
                                                                    else if ( text.contains( "LoxAPPversion3" ) )
                                                                    {
                                                                        ws.writeTextMessage( loxAppVersionReply );
                                                                    }
                                                                    else if ( text.contains( "data/LoxAPP3.json" ) )
                                                                    {
                                                                        ws.writeTextMessage( loxApp3Reply );
                                                                    }
                                                                    else if ( text.contains( "enablebinstatusupdate" ) )
                                                                    {
                                                                        ws.writeTextMessage( enableBinReply );

                                                                        // Emit a canned Value-State event-table right after
                                                                        // ack. Two consecutive binary frames per the
                                                                        // Loxone wire protocol (Communicating with the
                                                                        // Miniserver V17.0 §Message Header p. 18):
                                                                        //   Frame A : 8-byte WsBinHdr, identifier=2 (Value-State),
                                                                        //             length=24 (one event).
                                                                        //   Frame B : 24-byte payload — 16 bytes UUID + 8 bytes
                                                                        //             double, little-endian.
                                                                        // Done unconditionally so any IT pointing
                                                                        // FakeMiniserverFullResource at a real broker
                                                                        // (StateEventsRoundTripIT) sees a publish on
                                                                        // `…/states/type_2/{AUTO_VALUE_STATE_UUID}`.
                                                                        ws.writeBinaryMessage( buildValueStateHeader( 24 ) );
                                                                        ws.writeBinaryMessage( buildSingleValueStatePayload(
                                                                                AUTO_VALUE_STATE_UUID, AUTO_VALUE_STATE_VALUE ) );
                                                                    }
                                                                    // Anything else (keepalive, post-RUNNING traffic we
                                                                    // don't model) — silently absorb. The binding won't
                                                                    // wait for a reply it didn't ask for.
                                                                } );
                                         ws.exceptionHandler( err ->
                                                              { /* benign — client closed */ } );
                                     } );

            // listen(0, ...) → OS-assigned port. Block on the listen future
            // so start() doesn't return before the server is actually accepting
            // connections.
            server.listen( 0, "127.0.0.1" )
                  .toCompletionStage().toCompletableFuture().get( 5, TimeUnit.SECONDS );

            miniserverHost = "127.0.0.1";
            miniserverPort = server.actualPort();
            return Map.of(
                    "loxone.miniserver.connection.host", miniserverHost,
                    "loxone.miniserver.connection.port", String.valueOf( miniserverPort ),
                    // HTTP-only — Vert.x without TLS config can't serve https/wss.
                    "loxone.miniserver.connection.secure", "false"
                         );
        }
        catch ( Exception e )
        {
            throw new RuntimeException( "Failed to start FakeMiniserverFullResource", e );
        }
    }

    @Override
    public void stop()
    {
        try
        {
            if ( server != null )
            {
                server.close().toCompletionStage().toCompletableFuture().get( 5, TimeUnit.SECONDS );
                server = null;
            }
            if ( vertx != null )
            {
                vertx.close().toCompletionStage().toCompletableFuture().get( 5, TimeUnit.SECONDS );
                vertx = null;
            }
        }
        catch ( Exception e )
        {
            // Best-effort — the next test resource start() will create a fresh
            // Vertx context anyway.
        }
        miniserverHost = null;
        miniserverPort = null;

        // Reset the crypto round-trip state so the next IT class doesn't
        // inherit decrypted commands from this one.
        fakeKeyPair     = null;
        decryptedAesKey = null;
        decryptedIv     = null;
        decryptedCommands.clear();
    }

    private static String buildGetPublicKeyResponse() throws Exception
    {
        // Store the keypair on the static field so the WS handler can
        // RSA-decrypt the keyexchange payload later. A previous
        // implementation discarded the private key after extracting the
        // public Base64.
        KeyPairGenerator gen = KeyPairGenerator.getInstance( "RSA" );
        gen.initialize( 2048 );
        fakeKeyPair = gen.generateKeyPair();
        String base64 = Base64.getEncoder().encodeToString( fakeKeyPair.getPublic().getEncoded() );
        return """
               {"LL":{"control":"dev/sys/getPublicKey",\
               "value":"-----BEGIN CERTIFICATE-----%s-----END CERTIFICATE-----",\
               "Code":"200"}}""".formatted( base64 );
    }

    // ------------------------------------------------------------------------
    //  Crypto helpers — match LoxoneCryptoService.wrappedSessionKey()
    //  and LoxoneCryptoService.encryptCommand() byte-for-byte.
    // ------------------------------------------------------------------------

    /** Decrypt the {@code jdev/sys/keyexchange/{base64}} text frame the
     *  binding sends as the very first WS message. Side-effect: stores the
     *  AES session key + IV that the binding generated, so subsequent
     *  {@code jdev/sys/enc/…} frames can be decrypted. */
    private static void unwrapSessionKey( String text )
    {
        try
        {
            int    idx     = text.indexOf( "jdev/sys/keyexchange/" );
            String b64     = text.substring( idx + "jdev/sys/keyexchange/".length() );
            byte[] wrapped = Base64.getDecoder().decode( b64 );

            Cipher rsa = Cipher.getInstance( "RSA/ECB/PKCS1Padding" );
            rsa.init( Cipher.DECRYPT_MODE, fakeKeyPair.getPrivate() );
            byte[] plain = rsa.doFinal( wrapped );

            String plainText = new String( plain, StandardCharsets.UTF_8 );
            int    sep       = plainText.indexOf( ':' );
            if ( sep < 0 )
            {
                throw new IllegalStateException(
                        "RSA-unwrapped keyexchange payload missing ':' separator — got " + plainText );
            }
            byte[] keyBytes = HexFormat.of().parseHex( plainText.substring( 0, sep ) );
            byte[] ivBytes  = HexFormat.of().parseHex( plainText.substring( sep + 1 ) );
            decryptedAesKey = new SecretKeySpec( keyBytes, "AES" );
            decryptedIv     = new IvParameterSpec( ivBytes );
        }
        catch ( Exception e )
        {
            throw new RuntimeException( "Failed to unwrap session key on fake side", e );
        }
    }

    /**
     * Reject a WebSocket opened on the wrong path (defensive — the ITs
     * always connect to {@code /ws/rfc6455}, so this branch is never hit
     * in practice). {@link ServerWebSocket#reject()} is deprecated in
     * favour of the {@code ServerWebSocketHandshake} API, but migrating to
     * a handshake handler for a dead defensive branch isn't worth the
     * churn — so the deprecation is suppressed narrowly here.
     */
    @SuppressWarnings( "deprecation" )
    private static void rejectWrongPath( ServerWebSocket ws )
    {
        ws.reject();
    }

    /**
     * Content-based routing for admin commands sent on
     * {@code GET /jdev/sps/{commandPath}}. {@code commandPath} has
     * already been stripped of its prefix but may contain slashes
     * (multi-segment).
     *
     * <p>Strategy: prefix match from most specific to most generic.
     * Mutations (create/update/delete/assign/remove) return
     * {@link #GENERIC_OK_RESPONSE}; reads return pre-canned fixtures.
     * Anything unrecognized → generic ack (the binding accepts but the
     * IT will notice the absence of expected data on the payload side).
     */
    private static String routeAdminCommand( String commandPath )
    {
        // Specific reads first (path starts with the verb before '/').
        if ( commandPath.equals( "getuserlist2" ) )
        { return GET_USER_LIST_2_RESPONSE; }
        if ( commandPath.equals( "getgrouplist" ) )
        { return GET_GROUP_LIST_RESPONSE; }
        if ( commandPath.startsWith( "getuser/12eebb90-00a1-3073-ffff88c561c84c44" ) )
        { return GET_USER_ADMIN_RESPONSE; }
        if ( commandPath.equals( "calendargetentries" ) )
        { return CALENDAR_GET_ENTRIES_RESPONSE; }
        // Mutations — generic 200 ok. Includes:
        //   calendarcreateentry/{...} | calendarupdateentry/{...} |
        //   calendardeleteentry/{...} | addoredituser/{...} |
        //   assignusertogroup/{u}/{g} | removeuserfromgroup/{u}/{g} | etc.
        return GENERIC_OK_RESPONSE;
    }

    /** AES-decrypt a {@code jdev/sys/enc/{url-encoded-base64}} frame and
     *  return the original cleartext command (after stripping the
     *  {@code salt/…/} or {@code nextSalt/…/…/} preamble and trailing
     *  {@code \0} padding). Returns {@code null} if {@link #unwrapSessionKey}
     *  hasn't been called yet — the IT can use that to distinguish a frame
     *  arriving before the handshake from one after. */
    private static String decryptCommand( String text )
    {
        if ( decryptedAesKey == null || decryptedIv == null )
        { return null; }
        try
        {
            int    idx        = text.indexOf( "jdev/sys/enc/" );
            String urlEncoded = text.substring( idx + "jdev/sys/enc/".length() );
            String b64        = URLDecoder.decode( urlEncoded, StandardCharsets.UTF_8 );
            byte[] ciphertext = Base64.getDecoder().decode( b64 );

            Cipher aes = Cipher.getInstance( "AES/CBC/PKCS5Padding" );
            aes.init( Cipher.DECRYPT_MODE, decryptedAesKey, decryptedIv );
            byte[] plain  = aes.doFinal( ciphertext );
            String result = new String( plain, StandardCharsets.UTF_8 );

            // Strip null padding + salt/nextSalt preamble (cf. LoxoneCryptoService.decryptControl).
            result = result.replaceAll( "\0+", "" );
            result = result.replaceFirst( "^salt/[^/]+/", "" );
            result = result.replaceFirst( "^nextSalt/[^/]+/[^/]+/", "" );
            return result;
        }
        catch ( Exception e )
        {
            throw new RuntimeException( "Failed to decrypt jdev/sys/enc payload on fake side", e );
        }
    }

    // ------------------------------------------------------------------------
    //  Binary frame builders — Loxone wire protocol §"Message Header" p. 18
    //  + §"Event Table of Value States" p. 21 of Communicating with the
    //  Miniserver V17.0.
    // ------------------------------------------------------------------------

    /**
     * Build the 8-byte {@code WsBinHdr} prefix announcing a Value-State
     * event-table of {@code payloadLength} bytes. Layout:
     * <pre>
     *   byte 0   : 0x03 (binType — Loxone protocol marker)
     *   byte 1   : 0x02 (identifier — EVENT_TABLE_OF_VALUE_STATES)
     *   byte 2   : 0x00 (info flags — exact length, not estimated)
     *   byte 3   : 0x00 (reserved)
     *   bytes4-7 : payloadLength as uint32 little-endian
     * </pre>
     */
    private static Buffer buildValueStateHeader( int payloadLength )
    {
        ByteBuffer bb = ByteBuffer.allocate( 8 ).order( ByteOrder.LITTLE_ENDIAN );
        bb.put( ( byte ) 0x03 );
        bb.put( ( byte ) 0x02 );
        bb.put( ( byte ) 0x00 );
        bb.put( ( byte ) 0x00 );
        bb.putInt( payloadLength );
        return Buffer.buffer( bb.array() );
    }

    /**
     * Build a 24-byte Value-State payload — one event = 16-byte UUID +
     * 8-byte little-endian double. The UUID format must match what
     * {@code MessageHelper.getUUID} parses:
     * <pre>
     *   data1 (uint32 LE) - data2 (uint16 LE) - data3 (uint16 LE) -
     *   data4 (8 raw bytes, big-endian aspect — they're just bytes)
     * </pre>
     * Example: UUID {@code 12345678-aabb-ccdd-1122334455667788} encodes as
     * the bytes {@code 78 56 34 12 BB AA DD CC 11 22 33 44 55 66 77 88}.
     */
    private static Buffer buildSingleValueStatePayload( String uuid, double value )
    {
        ByteBuffer bb = ByteBuffer.allocate( 24 ).order( ByteOrder.LITTLE_ENDIAN );

        // Parse "xxxxxxxx-xxxx-xxxx-xxxxxxxxxxxxxxxx" into the 4 fields
        // (matching MessageHelper.getUUID's read order).
        String[] parts = uuid.split( "-" );
        if ( parts.length != 4
             || parts[ 0 ].length() != 8
             || parts[ 1 ].length() != 4
             || parts[ 2 ].length() != 4
             || parts[ 3 ].length() != 16 )
        {
            throw new IllegalArgumentException(
                    "UUID must be in the Loxone 8-4-4-16 hex layout, got: " + uuid );
        }
        bb.putInt( ( int ) Long.parseLong( parts[ 0 ], 16 ) );
        bb.putShort( ( short ) Integer.parseInt( parts[ 1 ], 16 ) );
        bb.putShort( ( short ) Integer.parseInt( parts[ 2 ], 16 ) );
        // The 8 trailing bytes are written raw (no endianness — they're
        // just bytes side-by-side in the buffer).
        for ( int i = 0; i < 8; i++ )
        {
            int b = Integer.parseInt( parts[ 3 ].substring( i * 2, i * 2 + 2 ), 16 );
            bb.put( ( byte ) b );
        }
        bb.putDouble( value );
        return Buffer.buffer( bb.array() );
    }
}
