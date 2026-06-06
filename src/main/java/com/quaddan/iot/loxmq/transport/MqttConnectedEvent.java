/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.transport;

/**
 * Fired by {@link HiveMqClient}'s {@code onConnected} listener on every
 * CONNACK — both the initial connect and every HiveMQ auto-reconnect
 * cycle.
 *
 * <p>Subscribed by {@code CommandSubscriber} so it can (re-)subscribe to
 * the configured inbound topics. The subscription state is per-MQTT-
 * session: a broker-side disconnect drops subscriptions, the
 * {@code clean-start=true} default we use means HiveMQ does NOT
 * preserve subscriptions across reconnect — so re-subscribing on every
 * CONNACK is mandatory.
 *
 * <p>Sync fire ({@code .fire()}). The handler is cheap (issues two
 * SUBSCRIBE frames), and ordering matters: we want subscriptions in
 * place before the operator triggers commands.
 */
public record MqttConnectedEvent()
{
}
