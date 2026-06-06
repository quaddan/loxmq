/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.transport.publish;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.quaddan.iot.loxmq.config.LoxoneConfig;
import com.quaddan.iot.loxmq.config.LoxoneConfigHolder;
import com.quaddan.iot.loxmq.miniserver.command.MiniserverCommandResponseEvent;
import com.quaddan.iot.loxmq.miniserver.crypto.LoxoneCryptoException;
import com.quaddan.iot.loxmq.miniserver.crypto.LoxoneCryptoService;
import com.quaddan.iot.loxmq.transport.MqttClient;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;

/**
 * Republishes miniserver command responses (text frames received while
 * the session is in {@code RUNNING}) on the configured
 * {@code transport.topics.publish.command-response} topic.
 *
 * <h3>Why a separate bean</h3>
 * Symmetric with {@code CommandSubscriber} on the inbound side. Keeps
 * the {@code StatesPublisher} focused on state-event-table translation
 * (its dispatch table is non-trivial); the command-response side is a
 * thin "rewrite + forward" — a one-class bean fits.
 *
 * <h3>Payload — decrypt {@code LL.control}</h3>
 * The miniserver echoes back the original command in {@code LL.control}.
 * For commands sent via the encrypted channel
 * ({@code jdev/sps/enc/SALT/{ciphertext}}, cf. {@link
 * LoxoneCryptoService#encryptCommand}) the {@code control} field comes
 * back as the **Base64 of the AES-encrypted command** — opaque to
 * downstream consumers if forwarded verbatim. The publisher inverts the
 * transform via {@link LoxoneCryptoService#decryptControl} so consumers
 * see the clear command path (e.g. {@code jdev/sps/io/abc/on}).
 * <p>
 * Decrypt is **best-effort + defensive**:
 * <ul>
 *   <li>Commands sent in clear (no {@code /enc/} prefix) come back with a
 *       plaintext {@code control} → Base64 decode fails →
 *       {@link IllegalArgumentException} → publish verbatim.</li>
 *   <li>Commands sent encrypted but with a different session key (e.g.
 *       state replay after reconnect) → AES decrypt fails →
 *       {@link LoxoneCryptoException} → publish verbatim.</li>
 *   <li>Malformed JSON envelope → {@link JsonProcessingException} →
 *       publish verbatim + WARN (this one is unexpected, deserves a log).</li>
 * </ul>
 * In every fallback case downstream consumers see the same opaque
 * payload as before the decrypt was added, so nothing regresses.
 *
 * <h3>Why {@code @Singleton} + holder</h3>
 * Caught by {@code CommandRoundTripIT}: when this bean is
 * lazy-created on the WS reader thread (via the SYNC observer firing
 * from {@code SessionOrchestrator.onText} on the first command
 * response after RUNNING), ArC's {@code @ConfigMapping}
 * synthetic-bean producer for {@code LoxoneConfig} throws
 * "Error injecting LoxoneConfig" because the SmallRye config
 * resolver isn't bound to that thread's TCCL. Same family of bugs as
 * the {@link LoxoneConfigHolder} rounds 1-4 + the
 * {@code AppInfoPublisher} {@code @Startup} fix.
 * <p>
 * The fix is exactly the {@link StatesPublisher} pattern:
 * {@code @Singleton} (cheap scope, no proxy) + go through
 * {@code LoxoneConfigHolder} which is itself eagerly created at
 * boot ({@code @Startup}) so its {@code LoxoneConfig} field is
 * resolved on the Quarkus main thread — fine even when the holder is
 * later consumed from a non-main thread.
 */
@Singleton
public class CommandResponsePublisher
{
    private static final Logger LOG = Logger.getLogger( CommandResponsePublisher.class );

    @Inject
    LoxoneConfigHolder  configHolder;
    @Inject
    MqttClient          mqtt;
    @Inject
    LoxoneCryptoService crypto;
    @Inject
    ObjectMapper        jsonMapper;

    private LoxoneConfig config() { return configHolder.get(); }

    /** Sync observer — fired by {@code SessionOrchestrator.onText} when
     *  in RUNNING state. The WS reader thread carries the publish call
     *  through; HiveMQ's async client buffers the actual network write,
     *  so this doesn't block subsequent text frames. */
    public void onCommandResponse( @Observes MiniserverCommandResponseEvent event )
    {
        if ( !mqtt.isConnected() )
        {
            LOG.debug( "MQTT not connected — command response dropped" );
            return;
        }
        String payload = decryptControlIfNeeded( event.response() );

        var spec = config().transport().topics().publish().commandResponse();
        mqtt.publish( spec.topic(),
                      spec.qos(),
                      spec.retain(),
                      payload.getBytes( StandardCharsets.UTF_8 ) );
        // DEBUG (not TRACE) — 🚂 marks a publish, wrapped in 🕹 to mark
        // it as a command-response payload.
        LOG.debugf( "🚂 Published ⇨ 🕹%s🕹 ⏏ Topic ⇨ %s ⏏ QoS ⇨ %d ⏏ Retained ⇨ %s",
                    payload, spec.topic(), ( Integer ) spec.qos(), ( Boolean ) spec.retain() );
    }

    /**
     * Parse the {@code {"LL":{"control":"…","value":"…","Code":"…"}}}
     * envelope, decrypt the {@code control} field if it's the
     * Base64-AES form the miniserver echoes back for encrypted
     * commands, then re-serialise. See class-level Javadoc for the
     * fallback contract.
     *
     * @param raw verbatim text frame from the miniserver
     * @return payload to publish — either rewritten with decrypted
     *         control, or the original {@code raw} if decryption isn't
     *         applicable / failed
     */
    private String decryptControlIfNeeded( String raw )
    {
        JsonNode root;
        try
        {
            root = jsonMapper.readTree( raw );
        }
        catch ( JsonProcessingException ex )
        {
            // Malformed JSON — unexpected, deserves a log. Publish
            // verbatim so downstream still gets something.
            LOG.warnf( ex, "Command response is not valid JSON — publish verbatim: %s", raw );
            return raw;
        }
        if ( !( root.path( "LL" ) instanceof ObjectNode llNode ) )
        {
            return raw; // No LL wrapper — nothing to decrypt
        }
        JsonNode controlNode = llNode.path( "control" );
        if ( !controlNode.isTextual() )
        {
            return raw; // No control field — nothing to decrypt
        }
        String control = controlNode.asText();
        String decrypted;
        try
        {
            decrypted = crypto.decryptControl( control );
        }
        catch ( LoxoneCryptoException ex )
        {
            // control was sent in clear (no /enc/ prefix → step 1 strip
            // is a no-op + Base64 decode of plain path fails) or AES key
            // mismatch or non-Base64 input. Expected case — many
            // commands ride the unencrypted channel. Publish verbatim,
            // no warning. decryptControl wraps IllegalArgumentException
            // as LoxoneCryptoException so a single catch covers all
            // "not encrypted" outcomes.
            LOG.debugf( "control field not encrypted (decrypt declined: %s) — publish verbatim",
                        ex.getMessage() );
            return raw;
        }
        llNode.put( "control", decrypted );
        try
        {
            return jsonMapper.writeValueAsString( root );
        }
        catch ( JsonProcessingException ex )
        {
            // Should never happen — we just successfully read this tree.
            LOG.warnf( ex, "Failed to re-serialise command response — publish verbatim" );
            return raw;
        }
    }
}
