/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.session;

import com.sun.net.httpserver.HttpServer;
import com.quaddan.iot.loxmq.miniserver.command.MiniserverApiConnectorSetCommand;
import com.quaddan.iot.loxmq.miniserver.command.MiniserverApiConnectorSetCommandEvent;
import com.quaddan.iot.loxmq.miniserver.command.MiniserverCommand;
import com.quaddan.iot.loxmq.miniserver.command.MiniserverCommandEvent;
import com.quaddan.iot.loxmq.miniserver.command.MiniserverCommandResponseEvent;
import com.quaddan.iot.loxmq.miniserver.crypto.LoxoneCryptoService;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives {@link SessionOrchestrator} through the full handshake using a
 * fake {@link MiniserverWebSocket} and an embedded HTTP server
 * (for the {@code getkey2} step which the orchestrator does over HTTP).
 *
 * <h3>Why a fake WS instead of an embedded server</h3>
 * The JDK doesn't ship an embedded WebSocket server; spinning one up
 * (Jetty/Vert.x/Tyrus) would add a test dep and make the test slower.
 * The {@link MiniserverWebSocket} interface was designed exactly for this
 * test seam — production injects {@link JdkMiniserverWebSocket}, the test
 * swaps in {@link FakeMiniserverWebSocket} via {@link QuarkusMock}.
 *
 * <h3>Test fixture: pre-loaded RSA key</h3>
 * The orchestrator refuses to connect if {@code LoxoneCryptoService.hasPublicKey()}
 * is false. Each test calls {@link #primeCrypto()} in {@code @BeforeEach}
 * to load a fresh public key — analogous to what the bootstrap
 * orchestrator would have done in production.
 */
@QuarkusTest
@TestProfile( SessionOrchestratorTest.FakeMiniserverProfile.class )
@DisplayName( "SessionOrchestrator — full handshake with fake WS + embedded HTTP server" )
class SessionOrchestratorTest
{
    /** Fixed port — embedded HTTP server for the getkey2 step. */
    private static final int        HTTP_PORT        = 19997;
    private static       HttpServer httpServer;
    private static       String     keyAndSaltResponseBody;
    private static       int        keyAndSaltStatus = 200;

    @Inject
    SessionOrchestrator orchestrator;

    @Inject
    SessionTracker tracker;

    @Inject
    LoxoneCryptoService crypto;

    @Inject
    LoxApp3Cache loxApp3Cache;

    @Inject
    Event< MiniserverCommandEvent > commandBus;

    @Inject
    Event< MiniserverApiConnectorSetCommandEvent > apiSetBus;

    @Inject
    CommandResponseRecorder responseRecorder;

    /** Production WS impl is swapped for a fake captured here for assertions. */
    private FakeMiniserverWebSocket fakeWs;

    /** Isolated cache dir for this test class — prevents cross-test contamination. */
    private static final java.nio.file.Path TEST_CACHE_DIR =
            java.nio.file.Path.of( System.getProperty( "java.io.tmpdir" ),
                                   "loxone-session-test-" + System.nanoTime() );

    public static class FakeMiniserverProfile implements QuarkusTestProfile
    {
        @Override
        public Map< String, String > getConfigOverrides()
        {
            // Point HTTP at the embedded server. secure=false so the orchestrator
            // uses ws:// (matched by FakeMiniserverWebSocket). Cache dir is
            // pinned to /tmp/loxone-session-test-<nano> so the LoxAPP3 cache
            // starts EMPTY for this test class (avoids cross-class contamination
            // with LoxApp3CacheTest which writes to a different /tmp path).
            return Map.of(
                    "loxone.miniserver.connection.host", "127.0.0.1",
                    "loxone.miniserver.connection.port", String.valueOf( HTTP_PORT ),
                    "loxone.miniserver.connection.secure", "false",
                    "loxone.miniserver.security.credentials.user", "YWRtaW4=",        // Base64("admin")
                    "loxone.miniserver.security.credentials.password", "cGFzc3dvcmQ=",    // Base64("password")
                    "loxone.miniserver.cache.directory", TEST_CACHE_DIR.toString()
                         );
        }
    }

    @BeforeAll
    static void startHttp() throws IOException
    {
        httpServer = HttpServer.create( new InetSocketAddress( "127.0.0.1", HTTP_PORT ), 0 );
        httpServer.createContext( "/", exchange ->
        {
            String body    = keyAndSaltResponseBody != null ? keyAndSaltResponseBody : "missing";
            byte[] payload = body.getBytes( StandardCharsets.UTF_8 );
            exchange.sendResponseHeaders( keyAndSaltStatus, payload.length );
            try ( OutputStream os = exchange.getResponseBody() )
            {
                os.write( payload );
            }
        } );
        httpServer.start();
    }

    @AfterAll
    static void stopHttp()
    {
        if ( httpServer != null )
        { httpServer.stop( 0 ); }
    }

    @BeforeEach
    void perTestSetup()
    {
        // Ensure cache is empty between tests so happyPathCacheMiss is
        // ACTUALLY a cache miss every run (otherwise a previous test's
        // stored lastModified value would trigger a cache HIT — wrong
        // codepath, wrong outbound frame count).
        loxApp3Cache.clear();

        fakeWs = new FakeMiniserverWebSocket();
        // Replace the production JdkMiniserverWebSocket with our fake.
        // QuarkusMock requires the mock to be assignable from the bean's
        // CONCRETE type — so the fake extends JdkMiniserverWebSocket
        // (not just implements MiniserverWebSocket). The @PostConstruct of
        // the parent will still run (allocating an HttpClient we'll never
        // use), but that's a cheap one-time cost per test class boot.
        QuarkusMock.installMockForType( fakeWs, JdkMiniserverWebSocket.class );
        keyAndSaltResponseBody = """
                                 {"LL":{"control":"dev/sys/getkey2/YWRtaW4=","value":\
                                 {"key":"0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF",\
                                 "salt":"DEADBEEF","hashAlg":"SHA256"},"code":"200"}}""";
        keyAndSaltStatus       = 200;
        primeCrypto();
        tracker.transition( SessionState.DISCONNECTED );
    }

    @AfterEach
    void perTestCleanup()
    {
        orchestrator.disconnect( "test teardown" );
        tracker.transition( SessionState.DISCONNECTED );
    }

    private void primeCrypto()
    {
        try
        {
            KeyPairGenerator g = KeyPairGenerator.getInstance( "RSA" );
            g.initialize( 2048 );
            KeyPair pair = g.generateKeyPair();
            crypto.loadPublicKey( Base64.getEncoder().encodeToString( pair.getPublic().getEncoded() ) );
        }
        catch ( Exception e )
        {
            throw new RuntimeException( e );
        }
    }

    // ==========================================================================
    //  Happy path
    // ==========================================================================

    @Test
    @DisplayName( "full handshake (cache miss): keyexchange → getkey2 → getjwt → version → file → status → RUNNING" )
    void happyPathCacheMiss()
    {
        // Compute the Loxone validUntil for "1 day from now": seconds since 2009-01-01 UTC.
        long validUntil = ChronoUnit.SECONDS.between(
                LocalDateTime.of( 2009, 1, 1, 0, 0, 0 ).atZone( ZoneId.of( "UTC" ) ).toInstant(),
                java.time.Instant.now().plusSeconds( 86_400 ) );

        // Scripted responses the fake WS will deliver in order:
        //  1. keyexchange ack
        //  2. token reply
        //  3. LoxAPPversion3 reply (some lastModified timestamp, doesn't match cache → miss)
        //  4. LoxAPP3.json (full structure file)
        //  5. enablebinstatusupdate ack
        fakeWs.queueReply( """
                           {"LL":{"control":"jdev/sys/keyexchange/...","value":"ack","Code":"200"}}""" );
        fakeWs.queueReply( """
                           {"LL":{"control":"jdev/sys/getjwt/...","value":\
                           {"token":"abc.def.ghi","key":"0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF",\
                           "validUntil":%d,"tokenRights":4,"unsecurePass":false},"Code":"200"}}""".formatted( validUntil ) );
        fakeWs.queueReply( """
                           {"LL":{"control":"jdev/sps/LoxAPPversion3","value":"2026-05-18 12:00:00","Code":"200"}}""" );
        fakeWs.queueReply( """
                           {"lastModified":"2026-05-18 12:00:00","fixture":"fake LoxAPP3.json contents"}""" );
        fakeWs.queueReply( """
                           {"LL":{"control":"jdev/sps/enablebinstatusupdate","value":"1","Code":"200"}}""" );

        MiniserverToken token = orchestrator.connectAndWait( 5 );

        assertThat( token.token() ).isEqualTo( "abc.def.ghi" );
        assertThat( token.tokenRights() ).isEqualTo( 4 );
        assertThat( tracker.state() ).isEqualTo( SessionState.RUNNING );
        assertThat( tracker.token() ).isPresent();
        assertThat( tracker.connectedAt() ).isPresent();

        // 5 outbound frames in order — covers the full handshake.
        assertThat( fakeWs.outboundFrames() ).hasSize( 5 );
        assertThat( fakeWs.outboundFrames().get( 0 ) ).startsWith( "jdev/sys/keyexchange/" );
        assertThat( fakeWs.outboundFrames().get( 1 ) ).startsWith( "jdev/sys/enc/" );          // encrypted getjwt
        assertThat( fakeWs.outboundFrames().get( 2 ) ).isEqualTo( "jdev/sps/LoxAPPversion3" );
        assertThat( fakeWs.outboundFrames().get( 3 ) ).isEqualTo( "data/LoxAPP3.json" );
        assertThat( fakeWs.outboundFrames().get( 4 ) ).isEqualTo( "jdev/sps/enablebinstatusupdate" );
    }

    // ==========================================================================
    //  Failure paths
    // ==========================================================================

    @Test
    @DisplayName( "keyexchange rejected (non-200) → FAILED + clear message" )
    void keyExchangeRejected()
    {
        fakeWs.queueReply( """
                           {"LL":{"control":"jdev/sys/keyexchange/...","value":"forbidden","Code":"401"}}""" );

        assertThatThrownBy( () -> orchestrator.connectAndWait( 5 ) )
                .isInstanceOf( SessionException.class )
                .hasMessageContaining( "keyexchange rejected" )
                .hasMessageContaining( "Code=401" );

        assertThat( tracker.state() ).isEqualTo( SessionState.FAILED );
        assertThat( tracker.lastError() ).hasValueSatisfying( e -> assertThat( e ).contains( "keyexchange" ) );
    }

    @Test
    @DisplayName( "getkey2 HTTP 503 → FAILED, getjwt never sent" )
    void getkey2HttpFailure()
    {
        fakeWs.queueReply( """
                           {"LL":{"control":"jdev/sys/keyexchange/...","value":"ack","Code":"200"}}""" );
        keyAndSaltStatus       = 503;
        keyAndSaltResponseBody = "service unavailable";

        assertThatThrownBy( () -> orchestrator.connectAndWait( 5 ) )
                .isInstanceOf( SessionException.class )
                .hasMessageContaining( "getkey2 failed" );

        // Only the keyexchange frame should have been sent.
        assertThat( fakeWs.outboundFrames() ).hasSize( 1 );
        assertThat( tracker.state() ).isEqualTo( SessionState.FAILED );
    }

    @Test
    @DisplayName( "getjwt reply missing token field → FAILED with parse message" )
    void getjwtMissingFields()
    {
        long validUntil = ChronoUnit.SECONDS.between(
                LocalDateTime.of( 2009, 1, 1, 0, 0, 0 ).atZone( ZoneId.of( "UTC" ) ).toInstant(),
                java.time.Instant.now().plusSeconds( 3600 ) );
        fakeWs.queueReply( """
                           {"LL":{"control":"jdev/sys/keyexchange/...","value":"ack","Code":"200"}}""" );
        fakeWs.queueReply( """
                           {"LL":{"control":"jdev/sys/getjwt/...","value":\
                           {"validUntil":%d,"tokenRights":4,"unsecurePass":false},"Code":"200"}}""".formatted( validUntil ) );

        assertThatThrownBy( () -> orchestrator.connectAndWait( 5 ) )
                .isInstanceOf( SessionException.class )
                .hasMessageContaining( "missing required field" );

        assertThat( tracker.state() ).isEqualTo( SessionState.FAILED );
    }

    @Test
    @DisplayName( "connect without bootstrap (no public key) → immediate failure" )
    void connectWithoutBootstrap()
    {
        // Override @BeforeEach's primeCrypto: we want crypto in a fresh boot
        // state. The simplest way without adding a public clear() to the prod
        // bean is to just create a NEW CryptoService instance via reflection —
        // which is also overkill for this test. Instead, we install a Mock
        // crypto where hasPublicKey() returns false.
        QuarkusMock.installMockForType(
                new LoxoneCryptoService()
                {
                    @Override
                    public boolean hasPublicKey() { return false; }
                },
                LoxoneCryptoService.class );

        assertThatThrownBy( () -> orchestrator.connectAndWait( 5 ) )
                .isInstanceOf( SessionException.class )
                .hasMessageContaining( "Public key not loaded" );

        assertThat( tracker.state() ).isEqualTo( SessionState.FAILED );
    }

    // ==========================================================================
    //  Outbound commands + command responses
    // ==========================================================================

    /** Drives the orchestrator through the happy-path handshake so the
     *  session reaches RUNNING. Returns nothing — the caller asserts on
     *  fakeWs.outboundFrames() / tracker.state() afterwards. */
    private void runHandshakeToRunning()
    {
        long validUntil = ChronoUnit.SECONDS.between(
                LocalDateTime.of( 2009, 1, 1, 0, 0, 0 ).atZone( ZoneId.of( "UTC" ) ).toInstant(),
                java.time.Instant.now().plusSeconds( 86_400 ) );

        fakeWs.queueReply( """
                           {"LL":{"control":"jdev/sys/keyexchange/...","value":"ack","Code":"200"}}""" );
        fakeWs.queueReply( """
                           {"LL":{"control":"jdev/sys/getjwt/...","value":\
                           {"token":"abc.def.ghi","key":"0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF",\
                           "validUntil":%d,"tokenRights":4,"unsecurePass":false},"Code":"200"}}""".formatted( validUntil ) );
        fakeWs.queueReply( """
                           {"LL":{"control":"jdev/sps/LoxAPPversion3","value":"2026-05-18 12:00:00","Code":"200"}}""" );
        fakeWs.queueReply( """
                           {"lastModified":"2026-05-18 12:00:00","fixture":"fake LoxAPP3.json contents"}""" );
        fakeWs.queueReply( """
                           {"LL":{"control":"jdev/sps/enablebinstatusupdate","value":"1","Code":"200"}}""" );

        orchestrator.connectAndWait( 5 );
        assertThat( tracker.state() ).isEqualTo( SessionState.RUNNING );
    }

    @Test
    @DisplayName( "MiniserverCommandEvent in RUNNING → encrypted jdev/sys/enc/ frame sent" )
    void commandEventInRunning_sendsEncryptedFrame()
    {
        runHandshakeToRunning();
        int beforeCount = fakeWs.outboundFrames().size();

        commandBus.fire( new MiniserverCommandEvent(
                new MiniserverCommand( "1072755d-024f-4540-ffff112233445566/AI1", "on" ) ) );

        // One new outbound frame, encrypted form (jdev/sys/enc/<base64>).
        assertThat( fakeWs.outboundFrames() ).hasSize( beforeCount + 1 );
        String sent = fakeWs.outboundFrames().get( beforeCount );
        assertThat( sent ).startsWith( "jdev/sys/enc/" );
    }

    @Test
    @DisplayName( "MiniserverApiConnectorSetCommandEvent in RUNNING → encrypted SET frame sent" )
    void apiSetEventInRunning_sendsEncryptedFrame()
    {
        runHandshakeToRunning();
        int beforeCount = fakeWs.outboundFrames().size();

        apiSetBus.fire( new MiniserverApiConnectorSetCommandEvent(
                new MiniserverApiConnectorSetCommand( "VTI-LumiereBureau", "Lico", "Lc1", "Pulse" ) ) );

        assertThat( fakeWs.outboundFrames() ).hasSize( beforeCount + 1 );
        assertThat( fakeWs.outboundFrames().get( beforeCount ) ).startsWith( "jdev/sys/enc/" );
    }

    @Test
    @DisplayName( "MiniserverCommandEvent while NOT in RUNNING ⇒ dropped (no outbound frame)" )
    void commandEventBeforeRunning_isDropped()
    {
        // Tracker stays at DISCONNECTED (no handshake driven). Command must
        // be dropped — no point queueing for a session that doesn't exist.
        int beforeCount = fakeWs.outboundFrames().size();
        commandBus.fire( new MiniserverCommandEvent(
                new MiniserverCommand( "any-uuid", "on" ) ) );
        assertThat( fakeWs.outboundFrames() ).hasSize( beforeCount );
    }

    @Test
    @DisplayName( "text frame in RUNNING state ⇒ MiniserverCommandResponseEvent fired (verbatim)" )
    void textFrameInRunning_firesCommandResponseEvent()
    {
        runHandshakeToRunning();
        responseRecorder.clear();

        // Queue a synthetic command-response reply and trigger its delivery
        // by sending any text on the fake WS (sendText delivers one queued
        // reply per call; the WS reader thread invokes the listener's
        // onText, which in RUNNING state hits the new default branch
        // → commandResponseEvent.fire).
        String reply = "{\"LL\":{\"control\":\"jdev/sps/io/abc/on\",\"value\":\"1\",\"Code\":\"200\"}}";
        fakeWs.queueReply( reply );
        // sendText is the trigger: any frame works. Use a no-op style frame.
        fakeWs.sendText( "test-trigger" );

        assertThat( responseRecorder.responses ).hasSize( 1 );
        assertThat( responseRecorder.responses.get( 0 ).response() ).isEqualTo( reply );
    }

    /** Captures MiniserverCommandResponseEvent so the test can assert.
     *  {@code @Singleton} rather than {@code @ApplicationScoped} — with
     *  the latter, ArC's normal-scope proxy interferes with sync observer
     *  delivery in @QuarkusTest inner classes (observed empirically:
     *  {@code commandEvent.fire(...)} returns cleanly but the observer
     *  method is never invoked). Same trick used in CommandSubscriberTest. */
    @Singleton
    public static class CommandResponseRecorder
    {
        final java.util.List< MiniserverCommandResponseEvent > responses =
                new java.util.concurrent.CopyOnWriteArrayList<>();

        public void on( @Observes MiniserverCommandResponseEvent event )
        {
            responses.add( event );
        }

        public void clear() { responses.clear(); }
    }

    // ==========================================================================
    //  Fake WebSocket
    // ==========================================================================

    /**
     * Records outbound text frames, lets the test queue inbound replies
     * delivered to the listener after each {@code sendText} call. Extends
     * {@link JdkMiniserverWebSocket} so {@link QuarkusMock} can substitute
     * it for the production bean (assignability requirement).
     */
    private static class FakeMiniserverWebSocket extends JdkMiniserverWebSocket
    {
        private final    java.util.List< String >        outbound    = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final    ConcurrentLinkedQueue< String > replies     = new ConcurrentLinkedQueue<>();
        private final    AtomicReference< Listener >     listenerRef = new AtomicReference<>();
        private volatile boolean                         open;

        void queueReply( String reply )
        {
            replies.add( reply );
        }

        java.util.List< String > outboundFrames()
        {
            return outbound;
        }

        @Override
        public void connect( URI uri, Listener listener )
        {
            listenerRef.set( listener );
            open = true;
            // JDK WS fires onOpen on its reader thread; we just call it inline
            // — the orchestrator must be thread-safe enough that this works.
            listener.onOpen();
        }

        @Override
        public void sendText( String text )
        {
            if ( !open )
            { throw new SessionException( "fake WS not open" ); }
            outbound.add( text );
            // Deliver the next queued reply, if any.
            String   reply = replies.poll();
            Listener l     = listenerRef.get();
            if ( reply != null && l != null )
            {
                l.onText( reply );
            }
        }

        @Override
        public void close( String reason )
        {
            open = false;
        }

        @Override
        public boolean isOpen()
        {
            return open;
        }
    }
}
