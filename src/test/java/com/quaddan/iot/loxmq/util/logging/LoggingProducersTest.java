/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.util.logging;

import org.jboss.logmanager.ExtLogRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.logging.Level;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the strict-WARN gate {@link LoggingProducers#WARN_ONLY}
 * that backs {@code warn.log}. Pure assertions on the
 * {@link java.util.logging.Filter} contract — no Quarkus, no CDI, no
 * handler wiring.
 * <p>
 * This locks the documented invariant: {@code warn.log} carries
 * <em>exactly</em> {@code WARN}; {@code ERROR}/{@code FATAL} are excluded
 * (they already live in {@code error.log}, and double-logging would muddy
 * the "recovered transients only" semantics of warn.log).
 * <p>
 * The complementary half of the fix — locating the {@code warn.log}
 * handler in the live JBoss handler tree and attaching this filter — is
 * exercised end-to-end whenever a {@code @QuarkusTest} boots: the bean
 * logs {@code "Installed strict-WARN filter on warn.log"} at startup
 * (see {@code LoggingProducers#installStrictWarnFilter}).
 */
@DisplayName( "LoggingProducers.WARN_ONLY — strict WARN gate for warn.log" )
class LoggingProducersTest
{
    /** Build a record at the given level, as the live handler would see it. */
    private static ExtLogRecord rec( Level level )
    {
        return new ExtLogRecord( level, "probe", LoggingProducersTest.class.getName() );
    }

    @Test
    @DisplayName( "accepts WARN (JBoss) and WARNING (JDK) — both intValue 900" )
    void acceptsWarn()
    {
        assertThat( LoggingProducers.WARN_ONLY.isLoggable( rec( org.jboss.logmanager.Level.WARN ) ) ).isTrue();
        assertThat( LoggingProducers.WARN_ONLY.isLoggable( rec( Level.WARNING ) ) ).isTrue();
    }

    @Test
    @DisplayName( "rejects ERROR / SEVERE / FATAL — those belong to error.log" )
    void rejectsErrorAndAbove()
    {
        assertThat( LoggingProducers.WARN_ONLY.isLoggable( rec( org.jboss.logmanager.Level.ERROR ) ) ).isFalse();
        assertThat( LoggingProducers.WARN_ONLY.isLoggable( rec( Level.SEVERE ) ) ).isFalse();
        assertThat( LoggingProducers.WARN_ONLY.isLoggable( rec( org.jboss.logmanager.Level.FATAL ) ) ).isFalse();
    }

    @Test
    @DisplayName( "rejects INFO and below" )
    void rejectsInfoAndBelow()
    {
        assertThat( LoggingProducers.WARN_ONLY.isLoggable( rec( Level.INFO ) ) ).isFalse();
        assertThat( LoggingProducers.WARN_ONLY.isLoggable( rec( org.jboss.logmanager.Level.DEBUG ) ) ).isFalse();
        assertThat( LoggingProducers.WARN_ONLY.isLoggable( rec( Level.FINE ) ) ).isFalse();
    }
}
