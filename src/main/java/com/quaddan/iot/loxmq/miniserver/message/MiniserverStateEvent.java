/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.message;

/**
 * Sealed marker for the closed set of CDI events that carry decoded miniserver
 * state updates. Captures the conceptual progression "raw binary frame →
 * decoded state event" in the type system.
 *
 * <p>The MQTT publisher observes the concrete subtypes
 * ({@link ValueStatesEvent}, {@link TextStatesEvent},
 * {@link DayTimerStatesEvent}, {@link WeatherStatesEvent}) via
 * {@code @ObservesAsync}. The sealed interface is documentation and
 * exhaustiveness, not a dispatch mechanism — adding a new state event
 * requires editing this {@code permits} clause, which is the point.
 */
public sealed interface MiniserverStateEvent
        permits ValueStatesEvent,
                        TextStatesEvent,
                        DayTimerStatesEvent,
                        WeatherStatesEvent
{
}
