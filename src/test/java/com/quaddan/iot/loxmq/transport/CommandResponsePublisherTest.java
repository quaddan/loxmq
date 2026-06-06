/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.transport;

import com.quaddan.iot.loxmq.config.LoxoneConfig;
import com.quaddan.iot.loxmq.miniserver.command.MiniserverCommandResponseEvent;
import com.quaddan.iot.loxmq.miniserver.crypto.LoxoneCryptoException;
import com.quaddan.iot.loxmq.miniserver.crypto.LoxoneCryptoService;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that a {@link MiniserverCommandResponseEvent} fired on the CDI
 * bus reaches the MQTT layer on the configured {@code command_response}
 * topic — with {@code LL.control} decrypted when the miniserver echoes
 * the encrypted form back.
 */
@QuarkusTest
@DisplayName( "CommandResponsePublisher — CDI event → MQTT publish on command_response topic" )
class CommandResponsePublisherTest
{
    @Inject
    LoxoneConfig                            config;
    @Inject
    Event< MiniserverCommandResponseEvent > bus;

    private FakeMqttClient fake;

    @BeforeEach
    void install()
    {
        fake = new FakeMqttClient();
        QuarkusMock.installMockForType( fake, HiveMqClient.class );
        fake.connect();
    }

    @Test
    @DisplayName( "unencrypted control (no /enc/ prefix, Base64 decode fails) → published verbatim" )
    void unencryptedControlPublishedVerbatim()
    {
        // "jdev/sps/io/abc/on" — plaintext path, doesn't start with
        // jdev/sys/enc/ so step 1 strip is a no-op, then Base64 decode
        // of an 18-char path containing '/' throws IllegalArgumentException
        // inside decryptControl → wrapped as LoxoneCryptoException →
        // publisher falls back to verbatim.
        String reply = "{\"LL\":{\"control\":\"jdev/sps/io/abc/on\",\"value\":\"1\",\"Code\":\"200\"}}";
        bus.fire( new MiniserverCommandResponseEvent( reply ) );

        var spec = config.transport().topics().publish().commandResponse();
        var pub  = fake.lastOn( spec.topic() );
        assertThat( pub ).isNotNull();
        assertThat( new String( pub.payload(), StandardCharsets.UTF_8 ) ).isEqualTo( reply );
        assertThat( pub.qos() ).isEqualTo( spec.qos() );
        assertThat( pub.retain() ).isEqualTo( spec.retain() );
    }

    @Test
    @DisplayName( "encrypted LL.control → decrypted before MQTT publish" )
    void encryptedControlDecryptedBeforePublish()
    {
        // Install a crypto mock that pretends "RW5jcnlwdGVkQmxvYg==" is
        // the Base64 of an AES-encrypted command and decrypts to the
        // clear path "jdev/sps/io/abc/on" (mirrors what the real
        // miniserver echoes back for /jdev/sps/enc/... commands).
        LoxoneCryptoService cryptoMock = mock( LoxoneCryptoService.class );
        when( cryptoMock.decryptControl( "RW5jcnlwdGVkQmxvYg==" ) ).thenReturn( "jdev/sps/io/abc/on" );
        QuarkusMock.installMockForType( cryptoMock, LoxoneCryptoService.class );

        String reply = "{\"LL\":{\"control\":\"RW5jcnlwdGVkQmxvYg==\",\"value\":\"1\",\"Code\":\"200\"}}";
        bus.fire( new MiniserverCommandResponseEvent( reply ) );

        var spec = config.transport().topics().publish().commandResponse();
        var pub  = fake.lastOn( spec.topic() );
        assertThat( pub ).isNotNull();
        String published = new String( pub.payload(), StandardCharsets.UTF_8 );
        assertThat( published )
                .contains( "\"control\":\"jdev/sps/io/abc/on\"" )
                .contains( "\"value\":\"1\"" )
                .contains( "\"Code\":\"200\"" )
                .doesNotContain( "RW5jcnlwdGVkQmxvYg==" );
    }

    @Test
    @DisplayName( "decrypt throws (e.g. AES key mismatch) → published verbatim" )
    void decryptFailureFallsBackToVerbatim()
    {
        LoxoneCryptoService cryptoMock = mock( LoxoneCryptoService.class );
        when( cryptoMock.decryptControl( anyString() ) )
                .thenThrow( new LoxoneCryptoException( "key mismatch", new RuntimeException() ) );
        QuarkusMock.installMockForType( cryptoMock, LoxoneCryptoService.class );

        String reply = "{\"LL\":{\"control\":\"SomeBase64Looking==\",\"value\":\"1\",\"Code\":\"200\"}}";
        bus.fire( new MiniserverCommandResponseEvent( reply ) );

        var spec = config.transport().topics().publish().commandResponse();
        var pub  = fake.lastOn( spec.topic() );
        assertThat( new String( pub.payload(), StandardCharsets.UTF_8 ) ).isEqualTo( reply );
    }

    @Test
    @DisplayName( "MQTT disconnected → response dropped (no publish, no exception)" )
    void dropWhenDisconnected()
    {
        fake.disconnect();
        bus.fire( new MiniserverCommandResponseEvent( "anything" ) );
        assertThat( fake.publishes() ).isEmpty();
    }
}
