/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.health;

import com.quaddan.iot.loxmq.config.LoxoneConfig;
import com.quaddan.iot.loxmq.transport.MqttClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;

/**
 * Readiness probe for the MQTT side of the bridge.
 * <p>
 * Returns {@code UP} when {@link MqttClient#isConnected()} is true — i.e. the
 * HiveMQ client believes the transport session is alive (operator-alive flag
 * AND HiveMQ's own connection state both green). Returns {@code DOWN} during
 * the initial connect, during reconnect attempts, after a USER-initiated
 * disconnect, and on shutdown.
 *
 * <p>The {@code data} payload always exposes the broker URI and the active
 * {@code mqtt-mode} ({@code SINGLE} / {@code BATCH}) for monitoring stacks
 * that want to alert on a specific topology.
 */
@Readiness
@ApplicationScoped
public class MqttReadinessCheck implements HealthCheck
{
    @Inject
    LoxoneConfig config;
    @Inject
    MqttClient   mqtt;

    @Override
    public HealthCheckResponse call()
    {
        boolean isUp = mqtt.isConnected();

        HealthCheckResponseBuilder b = HealthCheckResponse.named( "mqtt-broker" )
                                                          .status( isUp )
                                                          .withData( "broker.uri",
                                                                     "%s://%s:%d%s".formatted(
                                                                             config.transport().connection().protocol(),
                                                                             config.transport().connection().host(),
                                                                             config.transport().connection().port(),
                                                                             config.transport().connection().path().orElse( "" ) ) )
                                                          .withData( "mqtt.mode", config.transport().mode() )
                                                          .withData( "reason", isUp
                                                                               ? "HiveMQ client CONNECTED — fire-and-forget publishes accepted"
                                                                               : "HiveMQ client not connected (initial boot, reconnect in progress, or shutdown)" );

        return b.build();
    }
}
