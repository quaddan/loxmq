/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.util;

import io.quarkus.qute.TemplateGlobal;

/**
 * Qute globals available to every (checked) template.
 *
 * <h3>{@code cacheBust}</h3>
 * A per-build token appended as {@code ?v=<cacheBust>} to the static JS the
 * pages load ({@code /js/i18n.js}). Those assets are served with
 * {@code Cache-Control: public, immutable, max-age=86400}, so without a
 * changing URL a browser keeps the previous copy for up to a day — which,
 * after a release that adds i18n keys, shows raw keys (e.g. {@code
 * commands.panelTitle}) until the cache expires or the user hard-refreshes.
 *
 * <p>The value is computed once when this class is initialised — at native
 * image build time (or JVM class-load), i.e. once per build/deploy — so each
 * deployed binary advertises a distinct URL and the browser fetches the fresh
 * asset exactly when (and only when) it actually changed.
 */
public final class TemplateGlobals
{
    private TemplateGlobals()
    {
    }

    @TemplateGlobal
    public static final String cacheBust = Long.toHexString( System.currentTimeMillis() );
}
