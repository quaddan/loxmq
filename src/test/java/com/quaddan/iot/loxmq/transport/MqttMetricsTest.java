/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.transport;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link MqttMetrics} — verifies meters are registered with
 * the right names + tags, and that the push entry points
 * ({@code recordPublish / recordDrop / recordRtt}) increment / record
 * on the right meter.
 *
 * <h3>Gauges aren't asserted here</h3>
 * The three gauges (session state, broker connected, keepalive armed)
 * are pull-style — their values are reflected via the real underlying
 * beans ({@code SessionTracker}, {@code MqttClient},
 * {@code KeepAliveScheduler}). Validating them in isolation requires
 * mocking those beans, which adds noise for little extra coverage —
 * the gauge registration itself is verified via the assertion that
 * the meters are present in the registry. Behaviour is exercised
 * via {@code SessionTrackerTest} + {@code KeepAliveSchedulerTest}.
 */
@QuarkusTest
@DisplayName( "MqttMetrics — counters + timers registration & push behaviour" )
class MqttMetricsTest
{
    @Inject
    MqttMetrics   metrics;
    @Inject
    MeterRegistry registry;

    @Test
    @DisplayName( "All meters are registered at @PostConstruct with the expected names" )
    void allMetersRegistered()
    {
        assertThat( registry.find( MqttMetrics.M_PUBLISHES ).counter() ).isNotNull();
        // Three drop counters — one per reason. Each is registered with a
        // distinct `reason` tag so Prometheus can split them.
        assertThat( registry.find( MqttMetrics.M_DROPPED ).tag( "reason", MqttMetrics.REASON_RETAINED ).counter() ).isNotNull();
        assertThat( registry.find( MqttMetrics.M_DROPPED ).tag( "reason", MqttMetrics.REASON_OVERSIZED ).counter() ).isNotNull();
        assertThat( registry.find( MqttMetrics.M_DROPPED ).tag( "reason", MqttMetrics.REASON_MALFORMED ).counter() ).isNotNull();
        assertThat( registry.find( MqttMetrics.M_HANDSHAKE_TIMER ).timer() ).isNotNull();
        assertThat( registry.find( MqttMetrics.M_RTT_TIMER ).timer() ).isNotNull();
        // Gauges — assert presence; the supplier is exercised via the
        // pull path on a real Prometheus scrape.
        assertThat( registry.find( MqttMetrics.M_SESSION_STATE ).gauge() ).isNotNull();
        assertThat( registry.find( MqttMetrics.M_BROKER_CONNECTED ).gauge() ).isNotNull();
        assertThat( registry.find( MqttMetrics.M_KEEPALIVE_ARMED ).gauge() ).isNotNull();
    }

    @Test
    @DisplayName( "recordPublish increments the publish counter" )
    void recordPublish_Increments()
    {
        Counter c      = registry.find( MqttMetrics.M_PUBLISHES ).counter();
        double  before = c.count();
        metrics.recordPublish();
        metrics.recordPublish();
        assertThat( c.count() - before ).isEqualTo( 2d );
    }

    @Test
    @DisplayName( "recordDrop routes to the right tagged counter per reason" )
    void recordDrop_RoutesByReason()
    {
        Counter retained  = registry.find( MqttMetrics.M_DROPPED ).tag( "reason", MqttMetrics.REASON_RETAINED ).counter();
        Counter oversized = registry.find( MqttMetrics.M_DROPPED ).tag( "reason", MqttMetrics.REASON_OVERSIZED ).counter();
        Counter malformed = registry.find( MqttMetrics.M_DROPPED ).tag( "reason", MqttMetrics.REASON_MALFORMED ).counter();

        double r0 = retained.count();
        double o0 = oversized.count();
        double m0 = malformed.count();

        metrics.recordDrop( MqttMetrics.REASON_RETAINED );
        metrics.recordDrop( MqttMetrics.REASON_RETAINED );
        metrics.recordDrop( MqttMetrics.REASON_OVERSIZED );
        metrics.recordDrop( MqttMetrics.REASON_MALFORMED );

        assertThat( retained.count() - r0 ).isEqualTo( 2d );
        assertThat( oversized.count() - o0 ).isEqualTo( 1d );
        assertThat( malformed.count() - m0 ).isEqualTo( 1d );
    }

    @Test
    @DisplayName( "recordDrop with unknown reason: no counter incremented, no exception" )
    void recordDrop_UnknownReason_NoOp()
    {
        Counter retained = registry.find( MqttMetrics.M_DROPPED ).tag( "reason", MqttMetrics.REASON_RETAINED ).counter();
        double  before   = retained.count();

        metrics.recordDrop( "typo-in-reason" );

        // No counter side-effect; just a WARN log.
        assertThat( retained.count() - before ).isEqualTo( 0d );
    }

    @Test
    @DisplayName( "recordRtt records the duration in the keepalive RTT timer" )
    void recordRtt_RecordsObservation()
    {
        Timer t           = registry.find( MqttMetrics.M_RTT_TIMER ).timer();
        long  countBefore = t.count();

        metrics.recordRtt( Duration.ofMillis( 42 ) );
        metrics.recordRtt( Duration.ofMillis( 84 ) );

        assertThat( t.count() - countBefore ).isEqualTo( 2L );
        // Sum is at least 126 ms; Micrometer may add bucket granularity on
        // top so we use a lower-bound check.
        assertThat( t.totalTime( java.util.concurrent.TimeUnit.MILLISECONDS ) ).isGreaterThanOrEqualTo( 126d );
    }
}
