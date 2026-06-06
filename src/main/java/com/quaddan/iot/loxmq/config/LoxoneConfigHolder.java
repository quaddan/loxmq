/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.config;

import io.quarkus.runtime.Startup;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Indirection layer between {@link LoxoneConfig} (a SmallRye Config
 * {@code @ConfigMapping} synthetic bean) and downstream consumers that
 * can't reliably {@code @Inject} it directly.
 *
 * <h3>Why this exists</h3>
 * ArC has a code-generation bug we hit on 2026-05-19 with the
 * MQTT publishers: any bean that combines {@code @ObservesAsync} methods
 * AND {@code @Inject}s a {@code @ConfigMapping} interface fails on every
 * event notification with
 * <pre>
 *   IllegalArgumentException: A synthetic injection point was not declared
 *   for required type [interface jakarta.enterprise.inject.spi.InjectionPoint]
 *     at io.quarkus.arc.runtime.ConfigMappingCreator.create:21
 * </pre>
 * Switching bean scope ({@code @ApplicationScoped} → {@code @Singleton})
 * doesn't help. Programmatic lookup via
 * {@code ConfigProvider.getConfig().unwrap(SmallRyeConfig.class)} fails
 * differently — Quarkus replaces the MicroProfile Config factory with
 * its own, and the ServiceLoader fallback throws
 * {@code ServiceConfigurationError: SmallRyeConfigFactory: QuarkusConfigFactory not a subtype}.
 *
 * <h3>The workaround</h3>
 * This holder is a {@code @Singleton @Startup} bean with no
 * {@code @ObservesAsync} methods. Two pieces matter:
 * <ul>
 *   <li><b>No {@code @ObservesAsync}</b> — ArC generates the bean
 *       accessor through the normal path, which correctly tracks the
 *       {@code InjectionPoint} for the {@link LoxoneConfig} field.</li>
 *   <li><b>{@code @Startup}</b> — forces eager creation at app boot, on
 *       the Quarkus main thread, where the synthetic-bean creation path
 *       has the full CDI context (including the {@code InjectionPoint}
 *       synthesis the {@code @ConfigMapping} creator needs). Once
 *       materialised, the bean stays in the {@code @Singleton} cache
 *       forever; subsequent {@code @Inject LoxoneConfigHolder} from
 *       async-observer-triggered creation chains just hit the cache,
 *       no synthetic-bean re-creation needed. Without {@code @Startup}
 *       the holder is lazy-created on first access — which can be the
 *       async dispatcher thread, hitting the same bug we're trying to
 *       work around.</li>
 * </ul>
 *
 * <p>Consumers that have {@code @ObservesAsync}
 * ({@code StatesPublisher}, {@code OutOfServiceMqttReconnector}) inject
 * this holder instead of {@link LoxoneConfig} and call {@link #get()}.
 *
 * <h3>Cost</h3>
 * One extra field lookup per config access. The holder is a singleton —
 * same object lifecycle as a direct {@code @Inject LoxoneConfig}. No
 * performance impact in practice.
 */
@Singleton
@Startup
public class LoxoneConfigHolder
{
    @Inject
    LoxoneConfig config;

    /** Returns the active {@link LoxoneConfig} mapping. Never null after
     *  ArC has materialised this bean. */
    public LoxoneConfig get()
    {
        return config;
    }
}
