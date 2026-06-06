/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.transport;

import com.quaddan.iot.loxmq.miniserver.message.MiniserverOutOfServiceEvent;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link MqttReconnectScheduler}: verifies the OOS-triggered
 * reconnect chain with short delays so the suite stays fast.
 *
 * <p>Profile overrides {@code initial-delay=PT0.3S} and
 * {@code retry-interval=PT0.2S} so each test takes &lt; 1 s. The behaviour
 * we care about is structural:
 * <ul>
 *   <li>Trigger schedules a connect after {@code initial-delay}.</li>
 *   <li>On {@link TransportException}, the chain retries every
 *       {@code retry-interval}.</li>
 *   <li>On success, the chain stops; {@code isPending} returns false.</li>
 *   <li>A second trigger while one is already running is a no-op (single
 *       in-flight).</li>
 * </ul>
 */
@QuarkusTest
@TestProfile( MqttReconnectSchedulerTest.ShortDelaysProfile.class )
@DisplayName( "MqttReconnectScheduler — trigger / retry / single-flight" )
class MqttReconnectSchedulerTest
{
    public static class ShortDelaysProfile implements QuarkusTestProfile
    {
        @Override
        public Map< String, String > getConfigOverrides()
        {
            return Map.of(
                    "loxone.transport.reconnection.out-of-service.initial-delay", "PT0.3S",
                    "loxone.transport.reconnection.out-of-service.retry-interval", "PT0.2S"
                         );
        }
    }

    @Inject
    MqttReconnectScheduler               scheduler;
    @Inject
    Event< MiniserverOutOfServiceEvent > oosBus;

    /** Programmable MQTT client: count connect/disconnect calls, optionally
     *  throw on connect until N successful retries happen. */
    static class CountingMqttClient extends HiveMqClient
    {
        final AtomicInteger connectCount    = new AtomicInteger();
        final AtomicInteger disconnectCount = new AtomicInteger();
        int failConnectAttempts;

        @Override
        public boolean isConnected() { return false; }

        @Override
        public void publish( String t, int q, boolean r, byte[] p ) { /* no-op */ }

        @Override
        public void subscribe( String t, int q, MqttMessageHandler h ) { /* no-op */ }

        @Override
        public void connect()
        {
            int n = connectCount.incrementAndGet();
            if ( n <= failConnectAttempts )
            {
                throw new TransportException( "simulated connect failure #" + n );
            }
        }

        @Override
        public void disconnect()
        {
            disconnectCount.incrementAndGet();
        }
    }

    private CountingMqttClient fake;

    @BeforeEach
    void installFake()
    {
        fake = new CountingMqttClient();
        QuarkusMock.installMockForType( fake, HiveMqClient.class );
    }

    @AfterEach
    void cleanup()
    {
        scheduler.cancel();
    }

    @Test
    @DisplayName( "trigger: disconnect once, then a single successful connect after initial-delay" )
    @Timeout( value = 5, unit = TimeUnit.SECONDS )
    void firstConnectSucceeds() throws Exception
    {
        scheduler.triggerOutOfServiceReconnect();
        assertThat( fake.disconnectCount.get() ).isEqualTo( 1 );
        assertThat( scheduler.isPending() ).isTrue();

        // Wait past the initial-delay (300 ms) for the connect to happen.
        Thread.sleep( 600 );
        assertThat( fake.connectCount.get() ).isEqualTo( 1 );
        assertThat( scheduler.attemptCount() ).isEqualTo( 1 );
        assertThat( scheduler.isPending() ).isFalse();
    }

    @Test
    @DisplayName( "trigger: failing connects retry every retry-interval until success" )
    @Timeout( value = 5, unit = TimeUnit.SECONDS )
    void failingConnectsRetry() throws Exception
    {
        fake.failConnectAttempts = 2;          // first 2 attempts throw, 3rd succeeds
        scheduler.triggerOutOfServiceReconnect();

        // initial (300 ms) + retry (200 ms) + retry (200 ms) ≈ 700 ms.
        // Also poll on isPending so the assertion doesn't race against the
        // scheduler thread's post-success cleanup (it sets pending=null
        // AFTER mqtt.connect() returns, so connectCount can hit 3 before
        // pending is cleared).
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos( 1500 );
        while ( System.nanoTime() < deadline
                && ( fake.connectCount.get() < 3 || scheduler.isPending() ) )
        {
            Thread.sleep( 50 );
        }
        assertThat( fake.connectCount.get() ).isEqualTo( 3 );
        assertThat( scheduler.attemptCount() ).isEqualTo( 3 );
        assertThat( scheduler.isPending() ).isFalse();
    }

    @Test
    @DisplayName( "duplicate trigger while a chain is pending is a no-op (single in-flight)" )
    @Timeout( value = 5, unit = TimeUnit.SECONDS )
    void duplicateTriggerIsIgnored() throws Exception
    {
        scheduler.triggerOutOfServiceReconnect();
        // Right away: another trigger. Must NOT cause a second disconnect.
        scheduler.triggerOutOfServiceReconnect();
        assertThat( fake.disconnectCount.get() ).isEqualTo( 1 );
        Thread.sleep( 600 );
        // Still only 1 successful connect even though we called trigger twice.
        assertThat( fake.connectCount.get() ).isEqualTo( 1 );
    }

    @Test
    @DisplayName( "OOS event on the CDI bus → MQTT NOT disconnected (independence from miniserver)" )
    @Timeout( value = 5, unit = TimeUnit.SECONDS )
    void cdiEventDoesNotTriggerScheduler() throws Exception
    {
        // A previous contract was: OOS CDI event → OutOfServiceMqttReconnector
        // → MqttReconnectScheduler.triggerOutOfServiceReconnect() → mqtt.disconnect().
        // That chain was broken at the reconnector level (MQTT and Miniserver
        // are independent dependencies — disconnecting the broker for a remote
        // miniserver reboot trips downstream consumers needlessly).
        // The scheduler itself is still wired (and its direct-call tests above
        // still verify the timing semantics), but the CDI path no longer reaches
        // triggerOutOfServiceReconnect.
        oosBus.fireAsync( new MiniserverOutOfServiceEvent( System.currentTimeMillis() ) );
        Thread.sleep( 300 );    // give the async observer time to run
        assertThat( fake.disconnectCount.get() )
                .as( "OOS CDI event must NOT cause a broker disconnect" )
                .isZero();
        assertThat( scheduler.isPending() )
                .as( "scheduler must NOT be armed by OOS CDI event" )
                .isFalse();
    }
}
