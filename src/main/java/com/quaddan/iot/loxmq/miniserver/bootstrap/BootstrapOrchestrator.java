/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.bootstrap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.quaddan.iot.loxmq.miniserver.crypto.LoxoneCryptoException;
import com.quaddan.iot.loxmq.miniserver.crypto.LoxoneCryptoService;
import com.quaddan.iot.loxmq.miniserver.http.CfgApiValue;
import com.quaddan.iot.loxmq.miniserver.http.InvalidLoxoneResponseException;
import com.quaddan.iot.loxmq.miniserver.http.LoxoneJsonParser;
import com.quaddan.iot.loxmq.miniserver.http.MiniserverHttpClient;
import com.quaddan.iot.loxmq.miniserver.identity.MiniserverIdentity;
import com.quaddan.iot.loxmq.miniserver.identity.MiniserverVersion;
import com.quaddan.iot.loxmq.miniserver.state.MiniserverState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Optional;

/**
 * Runs the synchronous HTTP bootstrap against the Miniserver:
 *
 * <ol>
 *   <li>{@code GET jdev/cfg/apiKey} → identity (serial, version, httpsStatus, …)</li>
 *   <li>{@code GET jdev/sys/getPublicKey} → RSA public key for the upcoming key
 *       exchange</li>
 * </ol>
 *
 * On success: populates {@link MiniserverState} (so the
 * {@code ConnectionModeResolver} re-evaluates to SECURE / PLAIN based on
 * {@code httpsStatus}) and loads the public key into
 * {@link LoxoneCryptoService} (so the keyexchange step can call
 * {@link LoxoneCryptoService#wrappedSessionKey()}).
 *
 * On failure: leaves {@link MiniserverState} untouched (atomic — partial
 * updates would be confusing) and records the failure in
 * {@link BootstrapTracker} for the operator dashboard / readiness probe.
 *
 * <h3>Atomicity</h3>
 * If step 1 succeeds but step 2 fails, we still update the identity (it's
 * legitimate data and the resolver / dashboard can already use it). The
 * crypto layer just won't have a public key — the session handshake then
 * refuses to keyexchange and surface the missing-key state, which is the
 * right behaviour.
 *
 * <h3>Scope</h3>
 * No WebSocket here — the session orchestrator adds session lifecycle on top
 * of this orchestrator. Bootstrap can be triggered manually via
 * {@code POST /api/v1/bootstrap} (and optionally at startup, via
 * {@code loxone.miniserver.bootstrap.on-startup}).
 */
@ApplicationScoped
public class BootstrapOrchestrator
{
    private static final Logger LOG = Logger.getLogger( BootstrapOrchestrator.class );

    @Inject
    MiniserverHttpClient httpClient;

    @Inject
    LoxoneJsonParser jsonParser;

    @Inject
    LoxoneCryptoService cryptoService;

    @Inject
    MiniserverState miniserverState;

    @Inject
    BootstrapTracker tracker;

    /**
     * Run a full bootstrap pass. Thread-safe at the granularity of one call
     * (the tracker / state / crypto beans serialise their writes); callers
     * are expected NOT to run two concurrent bootstraps — the WS session
     * lifecycle enforces that, and the manual trigger is a single HTTP POST
     * so concurrent runs are not a concern.
     *
     * @return the populated identity on success
     * @throws BootstrapException on any failure (HTTP, parse, crypto), with a
     *         human-readable message and the underlying cause chained.
     */
    public MiniserverIdentity run()
    {
        tracker.markStarted();
        LOG.info( "Bootstrap → starting" );
        try
        {
            MiniserverIdentity identity = fetchAndParseIdentity();
            miniserverState.update( identity );

            loadPublicKey();

            tracker.markSucceeded();
            LOG.infof( "Bootstrap → success in %s — identity=%s/%s gen=%s httpsStatus=%s",
                       tracker.lastDuration().orElse( null ),
                       identity.serial(), identity.version(),
                       identity.generation(), identity.httpsStatus() );
            return identity;
        }
        catch ( BootstrapException e )
        {
            tracker.markFailed( e.getMessage() );
            LOG.warnf( "Bootstrap → FAILED: %s", e.getMessage() );
            throw e;
        }
        catch ( RuntimeException e )
        {
            tracker.markFailed( e.getClass().getSimpleName() + ": " + e.getMessage() );
            LOG.warnf( e, "Bootstrap → FAILED (unexpected)" );
            throw new BootstrapException( "Unexpected failure during bootstrap: " + e.getMessage(), e );
        }
    }

    // ==========================================================================
    //  Step 1 — cfgApi
    // ==========================================================================

    private MiniserverIdentity fetchAndParseIdentity()
    {
        String body;
        try
        {
            body = httpClient.fetchCfgApi();
        }
        catch ( InvalidLoxoneResponseException e )
        {
            throw new BootstrapException(
                    "Step 1 (jdev/cfg/apiKey): HTTP call failed — " + e.getMessage(), e );
        }

        CfgApiValue value;
        try
        {
            value = jsonParser.parseCfgApi( body );
        }
        catch ( JsonProcessingException e )
        {
            throw new BootstrapException(
                    "Step 1 (jdev/cfg/apiKey): response could not be parsed — " + e.getOriginalMessage(), e );
        }
        catch ( InvalidLoxoneResponseException e )
        {
            throw new BootstrapException(
                    "Step 1 (jdev/cfg/apiKey): unexpected response shape — " + e.getMessage(), e );
        }

        // Translate the raw parsed value into a strongly-typed MiniserverIdentity.
        // The httpsStatus mapping → HttpsStatus → MiniserverGeneration lives in
        // MiniserverIdentity.from(), which is shared with the test fixtures.
        MiniserverVersion version;
        try
        {
            version = MiniserverVersion.parse( value.version() );
        }
        catch ( IllegalArgumentException e )
        {
            throw new BootstrapException(
                    "Step 1 (jdev/cfg/apiKey): version field '" + value.version() + "' is not a valid Loxone version", e );
        }

        return MiniserverIdentity.from(
                value.snr(),
                version,
                value.key(),
                Boolean.TRUE.equals( value.isInTrust() ),
                Boolean.TRUE.equals( value.local() ),
                value.address(),
                Optional.ofNullable( value.httpsStatus() ) );
    }

    // ==========================================================================
    //  Step 2 — getPublicKey
    // ==========================================================================

    private void loadPublicKey()
    {
        String body;
        try
        {
            body = httpClient.fetchPublicKey();
        }
        catch ( InvalidLoxoneResponseException e )
        {
            throw new BootstrapException(
                    "Step 2 (jdev/sys/getPublicKey): HTTP call failed — " + e.getMessage(), e );
        }

        String base64Der;
        try
        {
            base64Der = jsonParser.parsePublicKey( body );
        }
        catch ( JsonProcessingException e )
        {
            throw new BootstrapException(
                    "Step 2 (jdev/sys/getPublicKey): response could not be parsed — " + e.getOriginalMessage(), e );
        }
        catch ( InvalidLoxoneResponseException e )
        {
            throw new BootstrapException(
                    "Step 2 (jdev/sys/getPublicKey): unexpected response shape — " + e.getMessage(), e );
        }

        try
        {
            cryptoService.loadPublicKey( base64Der );
        }
        catch ( LoxoneCryptoException e )
        {
            throw new BootstrapException(
                    "Step 2 (jdev/sys/getPublicKey): key parse failed — " + e.getMessage(), e );
        }
    }
}
