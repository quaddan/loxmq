/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.transport;

/**
 * Test seam over the MQTT client layer. Production uses
 * {@link HiveMqClient} (HiveMQ MQTT v5 over Netty); tests swap in a
 * fake that captures publishes / subscribes in memory.
 *
 * <h3>Lifecycle</h3>
 * <ul>
 *   <li>{@link #connect()} — open the broker connection synchronously,
 *       send LWT, subscribe to the configured input topics. Throws
 *       {@link TransportException} on failure.</li>
 *   <li>{@link #disconnect()} — publish the "offline" presence message,
 *       close cleanly with RFC reason code {@code NORMAL_DISCONNECTION}.
 *       Idempotent: no-op if already disconnected.</li>
 *   <li>{@link #isConnected()} — true between a successful
 *       {@code connect()} and the first {@code disconnect()} (or
 *       broker-side close).</li>
 * </ul>
 *
 * <h3>Reconnect</h3>
 * Auto-reconnect (with exponential backoff) is wired into the production
 * impl via the HiveMQ builder. The test fake doesn't bother — its
 * {@code connect()} is synchronous and either succeeds immediately or
 * throws.
 */
public interface MqttClient
{
    /** Connect to the broker (sync, fail-fast). Throws on timeout / auth /
     *  TLS error. After a successful return, the binding has subscribed to
     *  the configured input topics and the LWT is armed. */
    void connect();

    /** Idempotent. Publishes the "offline" presence message before closing
     *  on a best-effort basis. */
    void disconnect();

    /** True between a successful {@link #connect()} and the next
     *  {@link #disconnect()} or broker-side close. */
    boolean isConnected();

    /**
     * Fire-and-forget publish. The HiveMQ async client buffers and
     * dispatches without blocking the caller; QoS 1/2 acknowledgements
     * are tracked internally — we don't expose the future because
     * subscribers (the state publishers) don't need it.
     *
     * @param topic  MQTT topic. Empty / null fails fast — bad config
     *               should surface immediately, not in a downstream log.
     * @param qos    0, 1 or 2.
     * @param retain whether to mark the publish as retained.
     * @param payload bytes (typically UTF-8 JSON).
     */
    void publish( String topic, int qos, boolean retain, byte[] payload );

    /**
     * Subscribe to {@code topicFilter}. Each received message invokes
     * {@code handler(topic, payload, retained)} on a HiveMQ worker
     * thread; the handler must be quick or hand off to its own
     * executor. See {@link MqttMessageHandler} for the rationale
     * behind the 3-arg signature (broker's retained flag matters for
     * the inbound command topics — see {@code CommandSubscriber}).
     */
    void subscribe( String topicFilter, int qos, MqttMessageHandler handler );

    /**
     * Unsubscribe from {@code topicFilter}. Tolerant : a no-op if the
     * client wasn't subscribed (HiveMQ resolves this cleanly via
     * UNSUBACK reason code 0x11 "No subscription existed", we just
     * swallow the resulting future failure).
     *
     * <p>Why exposed : the bind binds re-subscribes on every CONNACK
     * (clean-start drops broker-side state). Without an explicit
     * unsubscribe, the LOCAL callbacks registered in the HiveMQ client
     * accumulate across reconnects → 2 callbacks after the first
     * reconnect, 3 after the second, etc. Each accumulated callback
     * fires on every incoming message → duplicate command dispatch
     * (best case) or QoS-1 flow-control confusion (worst case, which
     * manifests as "the second command is silently dropped" after a
     * single reconnect).
     *
     * <p>Call before each subscribe to keep exactly one callback live
     * per topic filter.
     */
    void unsubscribe( String topicFilter );
}
