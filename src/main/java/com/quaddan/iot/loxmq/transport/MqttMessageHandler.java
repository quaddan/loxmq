/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.transport;

/**
 * Functional-interface contract for MQTT inbound message handlers.
 * Replaces the previous {@code BiConsumer<String, byte[]>} signature
 * so callers receive the broker's {@code retained} flag in addition
 * to the topic and payload.
 *
 * <h3>Why retained matters</h3>
 * A message accidentally published with {@code retained=true} on the
 * binding's inbound {@code …/command} or {@code …/api} topic would
 * replay on every CONNACK — pushing an unwanted command into the
 * miniserver at each reconnect. The receiving subscriber inspects
 * the retained flag and drops the message with a WARN log; the
 * transport layer surfaces the information rather than swallowing it.
 *
 * <p>Production: {@code HiveMqClient} reads {@code publish.isRetain()}
 * from the HiveMQ {@code Mqtt5Publish} and passes it through.
 * Tests: {@code FakeMqttClient.deliverInbound(...)} accepts a retained
 * flag too so adversarial scenarios are exercised in unit tests.
 */
@FunctionalInterface
public interface MqttMessageHandler
{
    /**
     * Handle one inbound broker message.
     *
     * @param topic    fully-resolved topic the broker delivered the
     *                 message on (matches the subscription filter,
     *                 wildcards expanded).
     * @param payload  raw bytes — typically UTF-8 JSON.
     * @param retained {@code true} if the broker sent this message
     *                 because it was the topic's retained value at
     *                 subscribe time; {@code false} for a live publish.
     */
    void accept( String topic, byte[] payload, boolean retained );
}
