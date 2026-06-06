/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.session;

import com.quaddan.iot.loxmq.miniserver.connection.MiniserverHttpClientFactory;
import com.quaddan.iot.loxmq.config.LoxoneConfig;
import io.quarkus.arc.DefaultBean;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Production {@link MiniserverWebSocket} implementation on top of the JDK's
 * {@link java.net.http.WebSocket}. Built-in since JDK 11, zero extra deps,
 * works for both {@code ws://} and {@code wss://}, native-image friendly.
 *
 * <h3>Why @{@link DefaultBean}</h3>
 * Marks this as the fallback CDI bean for {@link MiniserverWebSocket}.
 * Tests can declare a {@code @Mock @ApplicationScoped} alternative that
 * Quarkus selects instead, without having to qualify every {@code @Inject}.
 *
 * <h3>Multi-fragment text reassembly</h3>
 * The JDK WebSocket delivers text in possibly-multiple {@code onText} calls
 * (when {@code last == false}). Loxone single text frames are small but
 * a malformed JSON spanning fragments would otherwise be silently parsed
 * as garbage. We accumulate in a per-session {@link StringBuilder} and
 * forward the joined string only when {@code last == true}.
 *
 * <h3>Multi-fragment binary reassembly</h3>
 * Same problem on the binary path. Observed against a Gen2 Miniserver
 * firmware 17.0.3.31: a 5840-byte VALUE-STATES event-table arrived as
 * four {@code onBinary} calls of ~1460 bytes each (TCP segment size, MTU
 * 1500 minus headers), with {@code last=true} only on the last fragment.
 * Without reassembly the decoder saw each fragment as a standalone frame
 * and produced cascading "size not divisible by 24" warnings + garbage
 * text-state lengths (`577072481`, `1734439521`, …) from random bytes
 * being read as uint32. We accumulate in a per-session
 * {@link ByteArrayOutputStream} and forward the joined payload only when
 * {@code last == true}.
 */
@ApplicationScoped
@DefaultBean
public class JdkMiniserverWebSocket implements MiniserverWebSocket
{
    private static final Logger LOG = Logger.getLogger( JdkMiniserverWebSocket.class );

    @Inject
    LoxoneConfig config;

    @Inject
    MiniserverHttpClientFactory clientFactory;

    private          HttpClient httpClient;
    private volatile WebSocket  socket;

    @PostConstruct
    void init()
    {
        // The JDK WS reuses an HttpClient. We use a dedicated instance (not
        // MiniserverHttpClient's) because we want a different connect timeout
        // — the WS connect spans both the TCP handshake AND the HTTP Upgrade
        // roundtrip; give it more headroom than the 3-second HTTP timeout.
        // Route through MiniserverHttpClientFactory so the TLS posture
        // (skip-hostname-verification opt-in) applies to the WSS upgrade
        // the same way it applies to the REST bootstrap calls.
        httpClient = clientFactory.newHttpClient( Duration.ofSeconds( 10 ) );
    }

    @Override
    public void connect( URI uri, Listener listener )
    {
        LOG.infof( "WS connect → %s", uri );
        Duration handshakeTimeout = config.miniserver().connection().http().requestTimeout()
                                          .plusSeconds( 5 );
        try
        {
            socket = httpClient.newWebSocketBuilder()
                               .subprotocols( "remotecontrol" )
                               .connectTimeout( handshakeTimeout )
                               .buildAsync( uri, new JdkAdapter( listener ) )
                               .get( handshakeTimeout.toSeconds(), TimeUnit.SECONDS );
            // JDK WebSocket's listener.onOpen() fires BEFORE buildAsync's future completes
            // — by the time we get here, the orchestrator has already received onOpen.
            // No second invocation needed.
        }
        catch ( InterruptedException e )
        {
            Thread.currentThread().interrupt();
            throw new SessionException( "WS connect interrupted", e );
        }
        catch ( ExecutionException e )
        {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new SessionException( "WS connect failed: " + cause.getMessage(), cause );
        }
        catch ( TimeoutException e )
        {
            throw new SessionException( "WS connect timed out after " + handshakeTimeout, e );
        }
    }

    // sendText() + close() are `synchronized` to serialise outbound frames.
    // The JDK WebSocket forbids overlapping sends (IllegalStateException if a
    // send is issued before the prior CompletableFuture completes). In RUNNING
    // state there are up to four independent caller threads — the HiveMQ
    // event-loop (MQTT commands), the keepalive scheduler (~60s), the
    // token-refresh scheduler (~24h) and a REST worker (killToken). The
    // monitor guarantees one in-flight send at a time and honours the
    // thread-safety contract MiniserverWebSocket#sendText already advertises.
    @Override
    public synchronized void sendText( String text )
    {
        WebSocket s = socket;
        if ( s == null )
        {
            throw new SessionException( "WS not connected" );
        }
        try
        {
            s.sendText( text, true ).get( 5, TimeUnit.SECONDS );
        }
        catch ( InterruptedException e )
        {
            Thread.currentThread().interrupt();
            throw new SessionException( "WS send interrupted", e );
        }
        catch ( ExecutionException e )
        {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new SessionException( "WS send failed: " + cause.getMessage(), cause );
        }
        catch ( TimeoutException e )
        {
            throw new SessionException( "WS send timed out", e );
        }
    }

    @Override
    public synchronized void close( String reason )
    {
        WebSocket s = socket;
        socket = null;
        if ( s == null )
        {
            return;
        }
        try
        {
            s.sendClose( WebSocket.NORMAL_CLOSURE, reason ).get( 5, TimeUnit.SECONDS );
        }
        catch ( Exception e )
        {
            LOG.debugf( "Ignored error closing WS: %s", e.getMessage() );
        }
    }

    @Override
    public boolean isOpen()
    {
        WebSocket s = socket;
        return s != null && !s.isOutputClosed() && !s.isInputClosed();
    }

    // ==========================================================================
    //  JDK Listener adapter
    // ==========================================================================

    /**
     * Bridges JDK WebSocket callbacks into our simpler {@link Listener} shape,
     * with text-fragment reassembly.
     *
     * <h3>Non-static on purpose: race fix</h3>
     * The JDK's {@code buildAsync(uri, adapter)} returns a future that
     * completes AFTER the listener's {@code onOpen} has already fired
     * (on the WS reader thread). If we waited for {@code future.get()} in
     * {@link #connect} to assign {@link #socket}, our user's
     * {@code onOpen} → {@code sendText(...)} chain would dereference a
     * null socket. The adapter receives the {@code WebSocket} object in
     * its {@code onOpen} callback — we publish it to the outer's
     * {@link #socket} field there, BEFORE invoking the user's onOpen.
     */
    private class JdkAdapter implements WebSocket.Listener
    {
        private final Listener              listener;
        private final StringBuilder         textBuffer   = new StringBuilder();
        /** Per-WebSocket-message buffer for binary fragments. The JDK may
         *  split a single Loxone binary message into multiple {@code onBinary}
         *  calls (TCP segmentation). We append each fragment here and only
         *  forward to the listener when {@code last=true}. */
        private final ByteArrayOutputStream binaryBuffer = new ByteArrayOutputStream();

        JdkAdapter( Listener listener )
        {
            this.listener = listener;
        }

        @Override
        public void onOpen( WebSocket webSocket )
        {
            // Publish the WebSocket BEFORE the user's onOpen so any
            // sendText() the user issues immediately can find the socket.
            JdkMiniserverWebSocket.this.socket = webSocket;
            WebSocket.Listener.super.onOpen( webSocket );
            try { listener.onOpen(); }
            catch ( RuntimeException e ) { listener.onError( e ); }
        }

        @Override
        public CompletionStage< ? > onText( WebSocket webSocket, CharSequence data, boolean last )
        {
            textBuffer.append( data );
            if ( last )
            {
                String full = textBuffer.toString();
                textBuffer.setLength( 0 );
                try { listener.onText( full ); }
                catch ( RuntimeException e ) { listener.onError( e ); }
            }
            return WebSocket.Listener.super.onText( webSocket, data, last );
        }

        @Override
        public CompletionStage< ? > onBinary( WebSocket webSocket, ByteBuffer data, boolean last )
        {
            // Accumulate this fragment. The JDK WebSocket may split a single
            // Loxone binary message across multiple onBinary calls (typically
            // along TCP segment boundaries). Only the fragment with last=true
            // marks the end of the message — that's when we hand the joined
            // bytes to the listener so the decoder sees one complete frame.
            //
            // The Loxone protocol itself is "8-byte header frame, then payload
            // frame" — both end up as `last=true` messages here. Without this
            // reassembly the decoder saw TCP fragments as standalone Loxone
            // frames (sizes 1456 / 4380 / 5840 = ~1×MTU, ~3×MTU, ~4×MTU) and
            // produced cascading decoding failures.
            byte[] chunk = new byte[ data.remaining() ];
            data.get( chunk );
            binaryBuffer.write( chunk, 0, chunk.length );

            if ( last )
            {
                byte[] full = binaryBuffer.toByteArray();
                binaryBuffer.reset();
                try { listener.onBinary( full ); }
                catch ( RuntimeException e ) { listener.onError( e ); }
            }
            return WebSocket.Listener.super.onBinary( webSocket, data, last );
        }

        @Override
        public CompletionStage< ? > onClose( WebSocket webSocket, int statusCode, String reason )
        {
            try { listener.onClose( statusCode, reason ); }
            catch ( RuntimeException e ) { listener.onError( e ); }
            return CompletableFuture.completedFuture( null );
        }

        @Override
        public void onError( WebSocket webSocket, Throwable error )
        {
            try { listener.onError( error ); }
            catch ( RuntimeException ignored ) { /* listener already in error state */ }
        }
    }
}
