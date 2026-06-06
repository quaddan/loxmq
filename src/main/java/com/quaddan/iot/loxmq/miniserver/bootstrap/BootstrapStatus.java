/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.bootstrap;

/**
 * Current state of the bootstrap orchestrator. Exposed in
 * {@code /api/v1/state}, the dashboard, and the readiness probe.
 */
public enum BootstrapStatus
{
    /** Bootstrap has never been attempted (process just started). */
    NOT_STARTED,

    /** A bootstrap attempt is currently running. */
    IN_PROGRESS,

    /** Latest attempt succeeded; {@code MiniserverState.identity()} is populated. */
    SUCCESS,

    /** Latest attempt failed; see {@code lastError} for the reason. */
    FAILED
}
