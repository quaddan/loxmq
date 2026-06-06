/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.health;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;

/**
 * Liveness probe.
 * <p>
 * Liveness answers "is the JVM still running?" — if this fails the orchestrator
 * (systemd / Docker / Kubernetes) should restart the process. The probe
 * returns {@code UP} unconditionally as long as the CDI container is up enough
 * to instantiate this bean — i.e. "the JVM can still serve HTTP". A binding
 * with a dropped miniserver session or a broker outage stays liveness {@code UP}
 * (only the readiness probes go {@code DOWN}); the systemd unit's
 * {@code Restart=on-failure} is meant for catastrophic process death, not
 * routine network blips that the binding's own reconnect schedulers handle.
 *
 * <p><b>Liveness vs readiness:</b> liveness reflects "process can serve any
 * request"; readiness ({@link MiniserverReadinessCheck}, {@link MqttReadinessCheck})
 * reflects "process can serve THIS specific kind of request". A binding with a
 * dropped miniserver session is alive (liveness UP) but not ready (readiness
 * DOWN until reconnect succeeds).
 */
@Liveness
@ApplicationScoped
public class MiniserverLivenessCheck implements HealthCheck
{
    @Override
    public HealthCheckResponse call()
    {
        return HealthCheckResponse.named( "miniserver-session-thread" )
                                  .up()
                                  .build();
    }
}
