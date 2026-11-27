/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the WebSocket-lifecycle fix for issue #31 (periodic reconnect leaking
 * the previous Miniserver socket): a reconnect must never overwrite a live
 * socket without tearing its TCP connection down, and a graceful close that
 * cannot complete (half-open link) must fall back to a hard {@code abort()}.
 *
 * <p>Pure POJO test — drives {@link JdkMiniserverWebSocket} directly with a
 * fake {@link WebSocket}, no CDI and no real network.
 */
class JdkMiniserverWebSocketTest
{
    @Test
    @DisplayName( "close() falls back to abort() when the graceful close cannot complete (half-open link)" )
    void closeAbortsWhenGracefulCloseFails()
    {
        JdkMiniserverWebSocket ws   = new JdkMiniserverWebSocket();
        FakeWebSocket          fake = new FakeWebSocket();
        // sendClose never completes cleanly — models the Miniserver gone silent.
        fake.closeResult = CompletableFuture.failedFuture( new IllegalStateException( "no close reply" ) );
        ws.socket = fake;

        ws.close( "test" );

        assertThat( fake.aborted ).as( "TCP hard-aborted after graceful close failed" ).isTrue();
        assertThat( ws.socket ).as( "socket reference cleared" ).isNull();
    }

    @Test
    @DisplayName( "close() does NOT abort when the graceful close completes normally" )
    void closeDoesNotAbortOnCleanClose()
    {
        JdkMiniserverWebSocket ws   = new JdkMiniserverWebSocket();
        FakeWebSocket          fake = new FakeWebSocket();
        fake.closeResult = CompletableFuture.completedFuture( fake );
        ws.socket = fake;

        ws.close( "test" );

        assertThat( fake.aborted ).as( "no hard abort on a clean close" ).isFalse();
        assertThat( ws.socket ).isNull();
    }

    @Test
    @DisplayName( "a reconnect aborts a lingering previous socket instead of leaking it" )
    void abortsLingeringSocketBeforeReconnect()
    {
        JdkMiniserverWebSocket ws   = new JdkMiniserverWebSocket();
        FakeWebSocket          fake = new FakeWebSocket();
        ws.socket = fake;

        // The first thing connect() does on a reconnect.
        ws.abortLingeringSocket();

        assertThat( fake.aborted ).as( "previous socket hard-closed before reconnecting" ).isTrue();
        assertThat( ws.socket ).isNull();
    }

    @Test
    @DisplayName( "abortLingeringSocket() is a no-op when there is no socket" )
    void abortLingeringSocketIsNoOpWhenAbsent()
    {
        JdkMiniserverWebSocket ws = new JdkMiniserverWebSocket();
        ws.socket = null;

        ws.abortLingeringSocket();   // must not throw

        assertThat( ws.socket ).isNull();
    }

    // ==========================================================================
    //  Minimal fake java.net.http.WebSocket — records abort(), controls close()
    // ==========================================================================

    private static final class FakeWebSocket implements WebSocket
    {
        volatile boolean                        aborted     = false;
        CompletableFuture< WebSocket >          closeResult = CompletableFuture.completedFuture( null );

        @Override
        public void abort()
        {
            aborted = true;
        }

        @Override
        public CompletableFuture< WebSocket > sendClose( int statusCode, String reason )
        {
            return closeResult;
        }

        @Override
        public CompletableFuture< WebSocket > sendText( CharSequence data, boolean last )
        {
            return CompletableFuture.completedFuture( this );
        }

        @Override
        public CompletableFuture< WebSocket > sendBinary( ByteBuffer data, boolean last )
        {
            return CompletableFuture.completedFuture( this );
        }

        @Override
        public CompletableFuture< WebSocket > sendPing( ByteBuffer message )
        {
            return CompletableFuture.completedFuture( this );
        }

        @Override
        public CompletableFuture< WebSocket > sendPong( ByteBuffer message )
        {
            return CompletableFuture.completedFuture( this );
        }

        @Override
        public void request( long n )
        {
        }

        @Override
        public String getSubprotocol()
        {
            return "remotecontrol";
        }

        @Override
        public boolean isOutputClosed()
        {
            return aborted;
        }

        @Override
        public boolean isInputClosed()
        {
            return aborted;
        }
    }
}
