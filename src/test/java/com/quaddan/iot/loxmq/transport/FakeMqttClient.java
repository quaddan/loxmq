/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.transport;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * In-memory {@link MqttClient} for tests — captures every publish + every
 * subscribe, lets tests assert what would have hit the wire.
 *
 * <p>Same pattern as {@code FakeMiniserverWebSocket} for the WebSocket
 * side. Installed via
 * {@code QuarkusMock.installMockForType(fake, HiveMqClient.class)} in
 * each test that wants to short-circuit the real broker.
 *
 * <h3>Why extends, not just implements</h3>
 * {@link io.quarkus.test.junit.QuarkusMock#installMockForType} requires
 * the mock to be assignable from the bean's <i>concrete</i> type, so the
 * fake extends {@link HiveMqClient} (not just implements
 * {@link MqttClient}). The parent's {@code @PostConstruct} would build a
 * real HiveMQ client + try to allocate Netty resources — we work around
 * this by overriding every interface method without ever calling
 * {@code super}. The parent's {@code @Inject} fields stay null, which is
 * fine: they're never dereferenced.
 *
 * <h3>Thread-safety</h3>
 * The publishers / subscribers in production fire from the WS reader
 * thread + the Quarkus async dispatcher; the test then asserts from the
 * JUnit main thread. Concurrent collections + an {@link AtomicBoolean}
 * keep that race-safe.
 */
public class FakeMqttClient extends HiveMqClient
{
    public record Publish(String topic, int qos, boolean retain, byte[] payload)
    {
    }

    public record Subscription(String topicFilter, int qos, MqttMessageHandler handler)
    {
    }

    private final AtomicBoolean                     connected       = new AtomicBoolean( false );
    private final List< Publish >                   publishes       = new CopyOnWriteArrayList<>();
    private final List< Subscription >              subscriptions   = new CopyOnWriteArrayList<>();
    /** Unsubscribe audit trail. Each entry is a topic filter passed to
     *  {@link #unsubscribe}. Used by tests to verify the
     *  CommandSubscriber correctly drops the previous callback before
     *  re-subscribing on a reconnect. */
    private final List< String >                    unsubscribes    = new CopyOnWriteArrayList<>();
    /** Captured per-topic handlers — useful when a test wants to inject a
     *  fake inbound MQTT message and verify the subscriber's behaviour. */
    private final Map< String, MqttMessageHandler > handlersByTopic = new ConcurrentHashMap<>();

    @Override
    public void connect()
    {
        connected.set( true );
    }

    @Override
    public void disconnect()
    {
        connected.set( false );
    }

    @Override
    public boolean isConnected()
    {
        return connected.get();
    }

    @Override
    public void publish( String topic, int qos, boolean retain, byte[] payload )
    {
        publishes.add( new Publish( topic, qos, retain, payload ) );
    }

    @Override
    public void subscribe( String topicFilter, int qos, MqttMessageHandler handler )
    {
        subscriptions.add( new Subscription( topicFilter, qos, handler ) );
        handlersByTopic.put( topicFilter, handler );
    }

    /** Drop the per-topic handler. Tests can assert that the subscriber
     *  correctly unsubscribes before re-subscribing on a reconnect,
     *  ensuring no callback accumulation. The {@link #subscriptions}
     *  history is preserved (it's an audit trail) but
     *  {@link #handlersByTopic} is updated so {@link #deliverInbound}
     *  routes to the most-recent handler. The {@link #unsubscribes}
     *  audit trail records every call so tests can assert the order of
     *  operations. */
    @Override
    public void unsubscribe( String topicFilter )
    {
        unsubscribes.add( topicFilter );
        handlersByTopic.remove( topicFilter );
    }

    /** Audit of unsubscribe calls in order. */
    public List< String > unsubscribes()
    {
        return List.copyOf( unsubscribes );
    }

    // -------- test API --------------------------------------------------

    public List< Publish > publishes()
    {
        return List.copyOf( publishes );
    }

    public List< Subscription > subscriptions()
    {
        return List.copyOf( subscriptions );
    }

    /** Find the most recent publish on {@code topic}; null if none. */
    public Publish lastOn( String topic )
    {
        Publish found = null;
        for ( Publish p : publishes )
        {
            if ( p.topic().equals( topic ) )
            {
                found = p;
            }
        }
        return found;
    }

    /** Simulate an inbound broker LIVE message on a previously-subscribed
     *  filter (retained=false). Useful for command-subscriber tests. */
    public void deliverInbound( String topicFilter, String topic, byte[] payload )
    {
        deliverInbound( topicFilter, topic, payload, false );
    }

    /** Simulate an inbound broker message with the retained flag of your
     *  choice. Exercises the retained-drop path in {@code CommandSubscriber}. */
    public void deliverInbound( String topicFilter, String topic, byte[] payload, boolean retained )
    {
        MqttMessageHandler handler = handlersByTopic.get( topicFilter );
        if ( handler != null )
        {
            handler.accept( topic, payload, retained );
        }
    }

    public void clear()
    {
        publishes.clear();
        subscriptions.clear();
        handlersByTopic.clear();
    }
}
