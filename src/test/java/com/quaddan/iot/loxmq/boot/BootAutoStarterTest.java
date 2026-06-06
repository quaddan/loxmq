/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.boot;

import com.quaddan.iot.loxmq.miniserver.identity.MiniserverIdentity;
import com.quaddan.iot.loxmq.miniserver.session.MiniserverToken;
import com.quaddan.iot.loxmq.config.LoxoneConfig;
import com.quaddan.iot.loxmq.miniserver.bootstrap.BootstrapException;
import com.quaddan.iot.loxmq.miniserver.bootstrap.BootstrapOrchestrator;
import com.quaddan.iot.loxmq.miniserver.session.SessionException;
import com.quaddan.iot.loxmq.miniserver.session.SessionOrchestrator;
import com.quaddan.iot.loxmq.transport.MqttClient;
import com.quaddan.iot.loxmq.transport.MqttMessageHandler;
import com.quaddan.iot.loxmq.transport.TransportException;
import jakarta.enterprise.inject.Vetoed;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link BootAutoStarter} — verifies the boot chain is
 * executed in strict {@code MQTT → Bootstrap → Session} order, and that
 * each failure mode short-circuits the downstream steps.
 *
 * <p>Pure JVM unit test — no Quarkus runtime. We instantiate the bean
 * directly, hand it stub dependencies that record the call order, and
 * call {@link BootAutoStarter#onStart(io.quarkus.runtime.StartupEvent)}.
 * The {@link io.quarkus.runtime.StartupEvent} parameter is passed as
 * {@code null} since the observer doesn't dereference it.
 */
@DisplayName( "BootAutoStarter — MQTT → Bootstrap → Session chain" )
class BootAutoStarterTest
{
    @Test
    @DisplayName( "auto-start=false → nothing fires" )
    void disabled_NoCallsMade()
    {
        BootAutoStarter  starter = newStarter( false );
        RecordingMqtt    mqtt    = ( RecordingMqtt ) starter.mqtt;
        RecordingBoot    boot    = ( RecordingBoot ) starter.bootstrap;
        RecordingSession sess    = ( RecordingSession ) starter.session;

        starter.onStart( null );

        assertThat( mqtt.connectCalls ).isZero();
        assertThat( boot.runCalls ).isZero();
        assertThat( sess.connectCalls ).isZero();
    }

    @Test
    @DisplayName( "auto-start=true, happy path → 3 calls in MQTT → Bootstrap → Session order" )
    void happyPath_ChainAllThreeInOrder()
    {
        List< String >  callOrder = new ArrayList<>();
        BootAutoStarter starter   = newStarter( true );
        ( ( RecordingMqtt ) starter.mqtt ).recorder       = () -> callOrder.add( "mqtt" );
        ( ( RecordingBoot ) starter.bootstrap ).recorder  = () -> callOrder.add( "bootstrap" );
        ( ( RecordingSession ) starter.session ).recorder = () -> callOrder.add( "session" );

        starter.onStart( null );

        assertThat( callOrder ).containsExactly( "mqtt", "bootstrap", "session" );
    }

    @Test
    @DisplayName( "MQTT throws → bootstrap and session NOT called" )
    void mqttFails_BootstrapAndSessionSkipped()
    {
        BootAutoStarter starter = newStarter( true );
        ( ( RecordingMqtt ) starter.mqtt ).failure = new TransportException( "broker unreachable" );

        starter.onStart( null );  // must not throw

        assertThat( ( ( RecordingBoot ) starter.bootstrap ).runCalls ).isZero();
        assertThat( ( ( RecordingSession ) starter.session ).connectCalls ).isZero();
    }

    @Test
    @DisplayName( "Bootstrap throws → session NOT called" )
    void bootstrapFails_SessionSkipped()
    {
        BootAutoStarter starter = newStarter( true );
        ( ( RecordingBoot ) starter.bootstrap ).failure = new BootstrapException( "apiKey HTTP 503" );

        starter.onStart( null );

        // MQTT did run; session did not.
        assertThat( ( ( RecordingMqtt ) starter.mqtt ).connectCalls ).isOne();
        assertThat( ( ( RecordingSession ) starter.session ).connectCalls ).isZero();
    }

    @Test
    @DisplayName( "Session throws → chain logs but doesn't propagate" )
    void sessionFails_NoPropagation()
    {
        BootAutoStarter starter = newStarter( true );
        ( ( RecordingSession ) starter.session ).failure = new SessionException( "handshake timeout" );

        starter.onStart( null );  // must not throw

        // All three steps were attempted.
        assertThat( ( ( RecordingMqtt ) starter.mqtt ).connectCalls ).isOne();
        assertThat( ( ( RecordingBoot ) starter.bootstrap ).runCalls ).isOne();
        assertThat( ( ( RecordingSession ) starter.session ).connectCalls ).isOne();
    }

    // -------------------------------------------------------------------------
    //  Halt-on-failure tests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName( "halt-on-failure=true, MQTT throws → shutdownTrigger(1) fired in NORMAL mode" )
    void mqttFails_HaltOnFailure_ExitsJvm()
    {
        AtomicInteger   lastExitCode = new AtomicInteger( -1 );
        BootAutoStarter starter      = newStarterWithHalt( /* autoStart */ true, /* halt */ true );
        starter.shutdownTrigger = lastExitCode::set;
        // Explicitly pin NORMAL launch mode so the test doesn't
        // accidentally hit the DEV-mode suppression branch.
        starter.launchModeSupplier                 = () -> io.quarkus.runtime.LaunchMode.NORMAL;
        ( ( RecordingMqtt ) starter.mqtt ).failure = new TransportException( "broker unreachable" );

        starter.onStart( null );

        assertThat( lastExitCode.get() )
                .as( "MQTT fail at first attempt → halt-on-failure → exit(1)" )
                .isEqualTo( 1 );
    }

    @Test
    @DisplayName( "halt-on-failure=true in DEV mode → exit suppressed" )
    void anyFailure_HaltOnFailure_DevMode_NoExit()
    {
        AtomicInteger   lastExitCode = new AtomicInteger( -1 );
        BootAutoStarter starter      = newStarterWithHalt( true, true );
        starter.shutdownTrigger                         = lastExitCode::set;
        starter.launchModeSupplier                      = () -> io.quarkus.runtime.LaunchMode.DEVELOPMENT;
        ( ( RecordingBoot ) starter.bootstrap ).failure = new BootstrapException( "apiKey HTTP 503" );

        starter.onStart( null );

        assertThat( lastExitCode.get() )
                .as( "DEV mode must NOT trigger the shutdown (Quarkus.asyncExit would be a no-op anyway, "
                     + "but we skip the call so the WARN log is accurate)" )
                .isEqualTo( -1 );
    }

    @Test
    @DisplayName( "halt-on-failure=true, Bootstrap throws → shutdownTrigger(1) fired in NORMAL mode" )
    void bootstrapFails_HaltOnFailure_ExitsJvm()
    {
        AtomicInteger   lastExitCode = new AtomicInteger( -1 );
        BootAutoStarter starter      = newStarterWithHalt( true, true );
        starter.shutdownTrigger                         = lastExitCode::set;
        starter.launchModeSupplier                      = () -> io.quarkus.runtime.LaunchMode.NORMAL;
        ( ( RecordingBoot ) starter.bootstrap ).failure = new BootstrapException( "apiKey HTTP 503" );

        starter.onStart( null );

        assertThat( lastExitCode.get() ).isEqualTo( 1 );
        // MQTT did run, session did not — chain aborts at the failing step.
        assertThat( ( ( RecordingMqtt ) starter.mqtt ).connectCalls ).isOne();
        assertThat( ( ( RecordingSession ) starter.session ).connectCalls ).isZero();
    }

    @Test
    @DisplayName( "halt-on-failure=true, Session throws → shutdownTrigger(1) fired in NORMAL mode" )
    void sessionFails_HaltOnFailure_ExitsJvm()
    {
        AtomicInteger   lastExitCode = new AtomicInteger( -1 );
        BootAutoStarter starter      = newStarterWithHalt( true, true );
        starter.shutdownTrigger                          = lastExitCode::set;
        starter.launchModeSupplier                       = () -> io.quarkus.runtime.LaunchMode.NORMAL;
        ( ( RecordingSession ) starter.session ).failure = new SessionException( "handshake timeout" );

        starter.onStart( null );

        assertThat( lastExitCode.get() ).isEqualTo( 1 );
    }

    @Test
    @DisplayName( "halt-on-failure=false (fail-soft) → no shutdown even on failure" )
    void anyFailure_HaltDisabled_StaysUp()
    {
        AtomicInteger   lastExitCode = new AtomicInteger( -1 );
        BootAutoStarter starter      = newStarterWithHalt( true, false );
        starter.shutdownTrigger                         = lastExitCode::set;
        starter.launchModeSupplier                      = () -> io.quarkus.runtime.LaunchMode.NORMAL;
        ( ( RecordingBoot ) starter.bootstrap ).failure = new BootstrapException( "apiKey HTTP 503" );

        starter.onStart( null );

        assertThat( lastExitCode.get() )
                .as( "halt-on-failure=false → shutdownTrigger MUST NOT fire" )
                .isEqualTo( -1 );
    }

    @Test
    @DisplayName( "halt-on-failure=true, happy path → no shutdown" )
    void happyPath_HaltOnFailure_NoShutdown()
    {
        AtomicInteger   lastExitCode = new AtomicInteger( -1 );
        BootAutoStarter starter      = newStarterWithHalt( true, true );
        starter.shutdownTrigger    = lastExitCode::set;
        starter.launchModeSupplier = () -> io.quarkus.runtime.LaunchMode.NORMAL;

        starter.onStart( null );

        assertThat( lastExitCode.get() )
                .as( "successful boot must not trigger shutdown even with halt-on-failure=true" )
                .isEqualTo( -1 );
    }

    // -------------------------------------------------------------------------
    //  Helpers — stub-bean assembly
    // -------------------------------------------------------------------------

    /** Build a starter with recording fakes wired in. The
     *  {@link LoxoneConfig} stub returns the {@code autoStart} value
     *  passed by the test; {@code haltOnFailure} defaults to false so
     *  the chain tests don't get an unexpected JVM exit path. */
    private static BootAutoStarter newStarter( boolean autoStart )
    {
        return newStarterWithHalt( autoStart, false );
    }

    /** Same as {@link #newStarter} but explicitly controls
     *  {@code boot().haltOnFailure()} for the fail-fast tests. */
    private static BootAutoStarter newStarterWithHalt( boolean autoStart, boolean haltOnFailure )
    {
        BootAutoStarter s = new BootAutoStarter();
        s.config    = new ConfigStub( autoStart, haltOnFailure );
        s.mqtt      = new RecordingMqtt();
        s.bootstrap = new RecordingBoot();
        s.session   = new RecordingSession();
        return s;
    }

    /** Minimal config stub — only {@code boot()} accessors are consulted
     *  by the bean. Other branches return null and would NPE if touched,
     *  surfacing any unintended config dereference in the test.
     *  <p>
     *  {@code Boot} has two methods ({@code autoStart} +
     *  {@code haltOnFailure}) — uses an explicit anonymous class. Tests
     *  default {@code haltOnFailure=false} so a failing-step test doesn't
     *  call {@code Quarkus.asyncExit} in the BootAutoStarter default path.
     *  The dedicated halt test below overrides via constructor.
     */
    private static final class ConfigStub implements LoxoneConfig
    {
        private final Boot boot;

        ConfigStub( boolean autoStart )
        {
            this( autoStart, false );
        }

        ConfigStub( boolean autoStart, boolean haltOnFailure )
        {
            this.boot = new Boot()
            {
                @Override
                public boolean autoStart() { return autoStart; }

                @Override
                public boolean haltOnFailure() { return haltOnFailure; }
            };
        }

        @Override
        public Boot boot() { return boot; }

        @Override
        public Management management() { return java.util.Optional::empty; }

        @Override
        public Miniserver miniserver() { return null; }

        @Override
        public Transport transport() { return null; }
    }

    /** Recording {@link MqttClient}: counts {@code connect()} invocations,
     *  optionally throws a configured failure, optionally appends to a
     *  shared call-order recorder. */
    private static final class RecordingMqtt implements MqttClient
    {
        int              connectCalls = 0;
        RuntimeException failure      = null;
        Runnable         recorder     = () ->
        { };

        @Override
        public void connect()
        {
            connectCalls++;
            recorder.run();
            if ( failure != null )
            { throw failure; }
        }

        @Override
        public void disconnect() { /* no-op */ }

        @Override
        public boolean isConnected() { return failure == null; }

        @Override
        public void publish( String t, int q, boolean r, byte[] p ) { /* no-op */ }

        @Override
        public void subscribe( String t, int q, MqttMessageHandler h ) { /* no-op */ }

        @Override
        public void unsubscribe( String t ) { /* no-op */ }
    }

    /** Recording {@link BootstrapOrchestrator}: extends the production
     *  class so it remains assignable to the {@code @Inject} field type,
     *  but overrides {@link BootstrapOrchestrator#run()} without ever
     *  calling {@code super} (the parent's {@code @Inject} fields stay
     *  null — never dereferenced here).
     *
     *  <p>{@link Vetoed} keeps ArC from scanning the class and treating
     *  it as a second {@code @ApplicationScoped} bean of the same type
     *  during {@code @QuarkusTest} runs (the parent is
     *  {@code @ApplicationScoped}; without {@code @Vetoed} ArC reports
     *  "Ambiguous dependencies for type BootstrapOrchestrator"). */
    @Vetoed
    private static final class RecordingBoot extends BootstrapOrchestrator
    {
        int              runCalls = 0;
        RuntimeException failure  = null;
        Runnable         recorder = () ->
        { };

        @Override
        public MiniserverIdentity run()
        {
            runCalls++;
            recorder.run();
            if ( failure != null )
            { throw failure; }
            return null;
        }
    }

    /** Recording {@link SessionOrchestrator} — same extends-but-don't-call-super
     *  pattern as {@link RecordingBoot}. {@link Vetoed} for the same
     *  ArC scan-and-disambiguate reason. */
    @Vetoed
    private static final class RecordingSession extends SessionOrchestrator
    {
        int              connectCalls = 0;
        RuntimeException failure      = null;
        Runnable         recorder     = () ->
        { };

        @Override
        public MiniserverToken connectAndWait( long timeoutSeconds )
        {
            connectCalls++;
            recorder.run();
            if ( failure != null )
            { throw failure; }
            return null;
        }
    }
}
