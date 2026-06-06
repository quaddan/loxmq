/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.util.templates;

import io.quarkus.qute.TemplateExtension;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Qute {@link TemplateExtension} methods exposed to the dashboard
 * template. Adds a {@code .formatLocal} accessor on {@link Instant}
 * (and friends) so the dashboard renders timestamps in the operator's
 * local timezone rather than the JVM-default UTC of
 * {@code Instant.toString()} (which produces values like
 * {@code 2026-05-19T20:24:33.563Z}).
 *
 * <h3>Why a template extension and not a Java-side format pass</h3>
 * Most timestamps on the dashboard come from beans
 * ({@code BootstrapTracker}, {@code SessionTracker},
 * {@code MiniserverToken}) that legitimately want to expose
 * {@link Instant} on their public API. Forcing the dashboard to
 * receive pre-formatted strings would either pollute those beans
 * with view-specific helpers, or require parallel formatted-string
 * fields on the template params (one per timestamp). The
 * {@code @TemplateExtension} approach keeps the beans pure and lets
 * the view layer do the timezone conversion at render time, which is
 * exactly what view layers are for.
 *
 * <h3>Format</h3>
 * {@code yyyy-MM-dd HH:mm:ss} — no timezone suffix because the
 * operator viewing the dashboard is in the JVM's local timezone by
 * definition; appending {@code CEST} / {@code CET} adds noise without
 * adding information. Sub-second precision dropped because the
 * dashboard never needs millisecond granularity (use {@code /q/metrics}
 * + Grafana for that).
 *
 * <p>Usage from the template:
 * <pre>{@code
 *   {bootstrap.startedAt.get().formatLocal}
 *   {lastKeepaliveResponseAt.get().formatLocal}
 * }</pre>
 */
public final class DashboardTemplateExtensions
{
    /** Format pattern shared across all template renders. Thread-safe per
     *  the java.time spec. Bound to a zone lazily per call (see below). */
    private static final DateTimeFormatter PATTERN =
            DateTimeFormatter.ofPattern( "yyyy-MM-dd HH:mm:ss" );

    private DashboardTemplateExtensions() { }

    /** Format an {@link Instant} as {@code yyyy-MM-dd HH:mm:ss} in the
     *  JVM's local timezone. Null returns {@code "—"} so the template
     *  can call {@code .formatLocal} without first checking presence.
     *
     *  <h4>Why we resolve the zone on EVERY call</h4>
     *  Native-image pitfall: a constant like
     *  {@code PATTERN.withZone(ZoneId.systemDefault())} captures the
     *  zone at class-init time, which in native mode is BUILD time.
     *  The Mandrel builder container typically runs in UTC, so the
     *  zone is baked in as UTC and the dashboard stays in UTC at
     *  runtime regardless of the deploy host's {@code TZ} env var.
     *  Resolving {@code ZoneId.systemDefault()} on every call dodges
     *  the snapshot — the value is computed at render time from the
     *  binary's runtime environment (TZ / /etc/localtime), which is
     *  what the operator expects.
     *  <p>
     *  Cost: one {@code ZoneId.systemDefault()} lookup per format
     *  call. The lookup is cached internally by the JDK's
     *  {@code TimeZone.getDefault()} — negligible. */
    @TemplateExtension
    public static String formatLocal( Instant instant )
    {
        if ( instant == null )
        { return "—"; }
        return PATTERN.withZone( ZoneId.systemDefault() ).format( instant );
    }

    /** Convenience for displaying a {@link Duration} as a human
     *  millisecond count, e.g. {@code 1850 ms}. Used for handshake
     *  duration + RTT badges that currently render the raw number.
     *  Null returns {@code "—"}. */
    @TemplateExtension
    public static String formatMs( Duration duration )
    {
        return duration == null ? "—" : duration.toMillis() + " ms";
    }
}
