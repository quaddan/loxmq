/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.message;

import java.util.List;

/**
 * Decoded payloads emitted by {@link BinaryStatesDecoder} — one record per
 * Loxone event-table identifier.
 *
 * <p>Records map 1-to-1 to the {@code EvData}, {@code EvDataText},
 * {@code EvDataDaytimer} and {@code EvDataWeather} typedef structs of
 * V17.0 §"WsBinHdr". Field order, types and units match the spec exactly —
 * this is the boundary between the protocol layer (binary) and the rest of
 * the app (typed Java records).
 *
 * <p>The {@link DecodedStates} sealed marker closes the hierarchy so the
 * MQTT publisher can do an exhaustive {@code switch} pattern-match
 * without a default case — the compiler will flag any future addition.
 */
public final class DecodedMessages
{
    private DecodedMessages() { throw new AssertionError(); }

    /** Sealed marker for the four concrete state payloads the binary decoder emits. */
    public sealed interface DecodedStates
            permits ValueStates, TextStates, DayTimerStates, WeatherStates
    {
    }

    /** Common envelope attached to every {@link DecodedStates} payload — lets
     *  downstream subscribers tag what they receive with provenance metadata
     *  ({@code source} = the Loxone app id, so multiple miniservers can publish
     *  to the same broker without collisions). */
    public record Header(String version,
                         Long timestamp,
                         String source,
                         Integer type,
                         Integer weight)
    {
    }

    // ---------------------------------------------------------------------
    // Value-States (identifier 2)
    // ---------------------------------------------------------------------

    public record ValueState(String uuid, Double value)
    {
    }

    public record ValueStates(Header header,
                              List< ValueState > values) implements DecodedStates
    {
    }

    // ---------------------------------------------------------------------
    // Text-States (identifier 3)
    // ---------------------------------------------------------------------

    public record TextState(String uuid, String value)
    {
    }

    public record TextStates(Header header,
                             List< TextState > values) implements DecodedStates
    {
    }

    // ---------------------------------------------------------------------
    // DayTimer-States (identifier 4)
    // ---------------------------------------------------------------------

    public record DayTimerState(Integer mode,
                                Integer fromTimeMinutesSinceMidnight,
                                Integer toTimeMinutesSinceMidnight,
                                Boolean needActivate,
                                Double value)
    {
    }

    public record DayTimerStates(Header header,
                                 String uuid,
                                 Double defaultValue,
                                 Integer numberOfDayTimerState,
                                 List< DayTimerState > values) implements DecodedStates
    {
    }

    // ---------------------------------------------------------------------
    // Weather-States (identifier 7)
    // ---------------------------------------------------------------------

    public record WeatherState(Integer timeStamp,
                               Integer weatherType,
                               Integer windDirection,
                               Integer solarRadiation,
                               Integer relativeHumidity,
                               Double temperature,
                               Double perceivedTemperature,
                               Double dewPoint,
                               Double precipitation,
                               Double windSpeed,
                               Double barometricPressure)
    {
    }

    public record WeatherStates(Header header,
                                String uuid,
                                Integer lastUpdate,
                                Integer numberOfWeatherState,
                                List< WeatherState > values) implements DecodedStates
    {
    }
}
