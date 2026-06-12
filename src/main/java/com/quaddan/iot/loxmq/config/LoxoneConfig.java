/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.config;

import com.quaddan.iot.loxmq.miniserver.admin.AdminCommandException;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

/**
 * Root configuration mapping for the binding (prefix {@code loxone.*}).
 * <p>
 * An immutable tree of SmallRye {@link ConfigMapping} interfaces. The
 * generated implementation is native-image friendly and validated eagerly
 * at first injection.
 *
 * <h3>Design principles</h3>
 * <ul>
 *   <li><b>Defaults via {@link WithDefault} for protocol-stable values.</b>
 *       The Loxone command stems ({@code jdev/cfg/apiKey},
 *       {@code jdev/sys/getkey2}, …) and the crypto algorithm names
 *       ({@code AES/CBC/PKCS5Padding}, {@code SHA-256}, …) are stable across
 *       firmware versions; the operator's environment-specific properties
 *       file only has to set what actually varies (host, credentials, app id,
 *       topic root).</li>
 *   <li><b>{@link Duration} and {@link LocalTime} where they make sense.</b>
 *       Time-of-day is modelled as a single {@link LocalTime} rather than
 *       separate hour/min/sec int fields.</li>
 *   <li><b>{@link Optional} for fields that may legitimately be unset.</b>
 *       Empty strings on non-optional fields would fail SmallRye Config
 *       validation; using {@code Optional} keeps the model honest.</li>
 *   <li><b>Named entries for state types.</b> The {@code transport.topics
 *       .publish.states.type_2.{topic,qos,retain}} keys (type_3, type_4, type_7)
 *       map to four explicit nested interfaces with semantic names —
 *       {@code valueStates}, {@code textStates}, {@code dayTimerStates},
 *       {@code weatherStates}. Same data, clearer.</li>
 *   <li><b>Bean Validation runs eagerly.</b> A missing required field, an
 *       out-of-range port, or a typo in {@code transport.connection.protocol}
 *       surfaces at first injection as
 *       {@code io.smallrye.config.ConfigValidationException}, before any
 *       business code runs.</li>
 * </ul>
 */
@ConfigMapping( prefix = "loxone" )
public interface LoxoneConfig
{
    @NotNull
    @Valid
    Boot boot();

    @NotNull
    @Valid
    Management management();

    @NotNull
    @Valid
    Miniserver miniserver();

    @NotNull
    @Valid
    Transport transport();

    // ==========================================================================
    //  MANAGEMENT — operator-facing UI / API surface
    // ==========================================================================

    /**
     * Knobs governing the operator-facing surface (dashboard + REST + health
     * + metrics endpoints). All optional — defaults match the historical
     * behaviour from before the dashboard host became configurable.
     */
    interface Management
    {
        /**
         * FQDN to display in the startup log lines ("Management UI ⇨ …")
         * and in dashboard self-references. Decouples the bind address
         * ({@code quarkus.http.host}, typically {@code 0.0.0.0}) from the
         * URL an operator would actually paste in a browser.
         *
         * <p>When unset, the literal value of {@code quarkus.http.host}
         * is shown (typically {@code 0.0.0.0}). That's honest but
         * unhelpful — the operator has to translate manually.
         *
         * <p>Set per profile to the real externally-reachable hostname.
         * Examples:
         * <ul>
         *   <li>dev:     {@code localhost}</li>
         *   <li>staging: {@code binding-staging.example.com}</li>
         *   <li>prod:    {@code binding.example.com}</li>
         * </ul>
         *
         * <p>Decoupled from the Miniserver hostname (which an operator might
         * reuse as a DNS alias) — without this knob, the dashboard link could
         * route the browser to the Miniserver web UI instead of to ourselves.
         */
        Optional< String > publicHost();
    }

    // ==========================================================================
    //  BOOT
    // ==========================================================================

    /**
     * Boot-time orchestration. The master switch
     * {@link #autoStart() loxone.boot.auto-start} chains the three subsystems
     * — MQTT → Miniserver bootstrap → Miniserver session — in strict order
     * at {@code StartupEvent}.
     *
     * <h3>Why a master switch on top of the per-service flags?</h3>
     * {@link Miniserver#autoConnect()} and {@link Transport#autoConnect()}
     * are <em>independent</em> {@code StartupEvent} observers. CDI does not
     * specify an ordering between observers, so the two can fire in any
     * order — and they will if both are set to {@code true}. For the
     * production deployment we want a deterministic sequence:
     * <ol>
     *   <li>MQTT first — the binding publishes the {@code app_info} +
     *       {@code loxapp3} retained messages right after the miniserver
     *       session reaches RUNNING. If MQTT isn't connected by then,
     *       those publishes are silently dropped.</li>
     *   <li>Miniserver bootstrap (HTTP) — must succeed before the
     *       WebSocket session can be opened.</li>
     *   <li>Miniserver session (WebSocket) — needs the public key and the
     *       miniserver identity from the bootstrap step.</li>
     * </ol>
     *
     * <h3>Interaction with the per-service flags</h3>
     * When {@code boot.auto-start=true}, the per-service flags
     * ({@link Transport#autoConnect()}, {@link Miniserver#autoConnect()})
     * are <strong>ignored</strong> — the {@code BootAutoStarter} bean is
     * the single source of truth. Documenting this contract here so
     * operators don't get bitten by two beans racing to connect MQTT.
     *
     * <p>Failures are logged WARN and abort the chain at the failing step
     * — the binding stays up so the operator can fix the issue and
     * re-trigger the remaining steps manually via the management API.
     */
    interface Boot
    {
        /**
         * If {@code true}, run the full boot sequence
         * {@code MQTT → bootstrap → session} at {@code StartupEvent}.
         * <p>
         * Default {@code true}: production deployments want the binding
         * to come up swinging — connect MQTT, bootstrap the miniserver,
         * open the WebSocket session — without manual intervention. The
         * {@code BootAutoStarter} is fail-soft: any step that fails is
         * logged WARN and the binding stays up so the operator can
         * recover via the dashboard / management API.
         * <p>
         * Set to {@code false} in environments where the operator
         * explicitly drives connectivity (e.g. the {@code test} profile
         * has it off to avoid hitting fake test resources during
         * {@code @QuarkusTest} runs). Override per-deployment via
         * {@code LOXONE_BOOT_AUTO_START=false}.
         */
        @WithDefault( "true" )
        boolean autoStart();

        /**
         * Exit the JVM with code 1 if the boot chain (MQTT → bootstrap →
         * session) fails at any step on its first attempt.
         *
         * <p>Default {@code true}: a first-attempt failure is almost always
         * a config or infrastructure error (wrong host, missing cert,
         * miniserver down, wrong creds) — keeping the binding up serves no
         * purpose because there's no auto-retry of bootstrap (only the WS
         * session reconnects via {@code ReconnectScheduler} after a
         * successful initial RUNNING). Exiting hands control back to
         * systemd's {@code Restart=on-failure} policy: 3 restart attempts
         * in 5 min (see {@code service/loxmq-jvm.service}
         * or {@code service/loxmq-native.service} — both carry the same restart policy), then
         * stop trying. The operator sees the failure in
         * {@code systemctl status} and fixes the config.
         *
         * <p>Set to {@code false} to preserve the historical fail-soft
         * behaviour — the binding stays up after a boot failure, dashboard
         * and management API stay reachable, and the operator manually
         * retries via {@code POST /api/v1/bootstrap} +
         * {@code /api/v1/connect}. Useful in dev or in environments with
         * out-of-band monitoring that triggers retries externally.
         *
         * <p>Once the binding has reached Boot 3/3 ✓ RUNNING, this knob
         * is no longer relevant — subsequent WS drops are handled by
         * {@code ReconnectScheduler} (retry forever with exponential
         * backoff), never trigger a JVM exit. Scope = first-attempt
         * failure semantics only.
         */
        @WithDefault( "true" )
        boolean haltOnFailure();
    }

    // ==========================================================================
    //  MINISERVER
    // ==========================================================================

    interface Miniserver
    {
        @NotNull
        @Valid
        Connection connection();

        @NotNull
        @Valid
        App app();

        @NotNull
        @Valid
        Security security();

        @NotNull
        @Valid
        Cmd cmd();

        @NotNull
        @Valid
        Crypto crypto();

        @NotNull
        @Valid
        Reconnect reconnect();

        @NotNull
        @Valid
        Cache cache();

        @NotNull
        @Valid
        Subscription subscription();

        /** Loxone-assigned state-table type ids to decode. Defaults to 2 (value),
         *  3 (text), 4 (daytimer). Add 7 to enable weather decoding. */
        @NotEmpty
        @WithDefault( "2,3,4" )
        List< Integer > statesToDecode();

        /**
         * If {@code true}, the binding runs {@code bootstrap} + {@code connect}
         * automatically at startup (Quarkus {@code StartupEvent}). If either
         * step fails, the binding still finishes booting — the dashboard and
         * the management API stay reachable so an operator can diagnose,
         * fix the config, and re-trigger manually via {@code POST /api/v1/connect}.
         * <p>
         * Default {@code false}: useful for the test profile and for first-
         * time dev iteration where you want to inspect config before
         * connecting. Production deployments typically flip this to
         * {@code true} via an environment variable
         * ({@code LOXONE_MINISERVER_AUTO_CONNECT=true}).
         */
        @WithDefault( "false" )
        boolean autoConnect();

        interface Connection
        {
            @NotBlank
            String host();

            @Min( 1 )
            int port();

            /**
             * Operator <i>preference</i> for the Miniserver transport.
             * <p>
             * This is not an absolute toggle — it expresses
             * what the operator <i>wants</i>. The effective scheme (http+ws vs
             * https+wss) is decided at runtime by
             * {@code ConnectionModeResolver}, which combines the preference with
             * the miniserver's own TLS readiness:
             * <ul>
             *   <li>{@code secure=false} ⇒ always plain (http + ws).</li>
             *   <li>{@code secure=true} + Gen1 hardware ⇒ plain (Gen1 cannot do
             *       TLS — no cert slot in the firmware). Logged once at handshake.</li>
             *   <li>{@code secure=true} + Gen2 hardware with valid cert ⇒
             *       https + wss. This is the production-ready posture.</li>
             *   <li>{@code secure=true} + Gen2 with missing / expired cert ⇒
             *       plain, with a clear downgrade reason on the dashboard.</li>
             * </ul>
             */
            @WithDefault( "true" )
            boolean secure();

            /**
             * Trust {@link #secure()} for the <b>bootstrap call too</b> — i.e.
             * the very first {@code GET /jdev/cfg/apiKey} that the binding
             * fires before any {@code MiniserverIdentity} is known.
             *
             * <p>Default {@code false} (conservative): the bootstrap goes out
             * in plain HTTP regardless of {@code secure}, then
             * {@code ConnectionModeResolver} re-resolves to SECURE if the
             * miniserver's {@code httpsStatus} field reports {@code SUPPORTED}.
             * Right default for the LAN direct-IP topology where the
             * configured {@code port} listens for HTTP plain on the same port
             * (legacy "443 everywhere" Loxone Config convention).
             *
             * <p>Set to {@code true} when the configured {@code port} is
             * TLS-only on the Miniserver — typically Gen2 with
             * {@code httpsStatus=SUPPORTED} where HTTP listens on :80 and
             * HTTPS on :443 (two listeners), and the operator points the
             * binding at :443 to force TLS end-to-end. Without this knob the
             * bootstrap call sends plain HTTP to the TLS-only listener and
             * fails with "header parser received no bytes".
             */
            @WithDefault( "false" )
            boolean bootstrapPreferSecure();

            /**
             * Skip hostname verification on TLS connections to the Miniserver,
             * keeping chain validation against the JVM truststore.
             *
             * <p>Default {@code false} (strict). When {@code true}, the
             * binding accepts a server cert whose CN/SAN doesn't match the
             * configured {@link #host()} as long as the chain validates
             * against the JVM truststore. Implementation: builds the
             * {@code HttpClient} (HTTP + WebSocket) with a custom
             * {@link javax.net.ssl.SSLParameters} whose
             * {@code endpointIdentificationAlgorithm} is null.
             *
             * <p>Use case: the Miniserver embeds a Loxone-issued Let's
             * Encrypt cert for {@code *.<serial>.dyndns.loxonecloud.com}.
             * The chain is trusted (Let's Encrypt root is in the default JVM
             * truststore) but the SAN doesn't include the operator's
             * preferred hostname (e.g. {@code miniserver.example.com}). This
             * knob lets the binding accept that cert — same posture as a
             * browser where the operator has clicked through the warning.
             *
             * <p><b>Security note:</b> this disables only hostname checking.
             * The cert still has to chain to a trusted root. The exposure is
             * a MITM attacker who can present a valid Let's Encrypt cert for
             * <i>any</i> domain on the LAN path between binding and
             * Miniserver. In a LAN+VPN deployment the exposure is minimal;
             * exposing the binding over a hostile network would not be.
             *
             * <p>A cleaner alternative is to upload a cert matching the
             * configured {@code host} to the Miniserver via Loxone Config
             * (Settings &gt; Network &gt; Certificates). With that done,
             * leave this knob at {@code false}.
             */
            @WithDefault( "false" )
            boolean tlsSkipHostnameVerification();

            @NotNull
            @Valid
            Http http();

            @NotNull
            @Valid
            Ws ws();

            interface Http
            {
                @NotNull
                @WithDefault( "PT3S" )
                Duration connectTimeout();

                @NotNull
                @WithDefault( "PT3S" )
                Duration requestTimeout();

                /**
                 * Cap on the number of admin HTTP commands in flight
                 * simultaneously towards the Miniserver. A Semaphore in
                 * {@link com.quaddan.iot.loxmq.miniserver.admin
                 * .MiniserverAdminCommandClient} serialises calls
                 * beyond that.
                 *
                 * <p>Rationale: V17 Miniservers have a limited HTTP
                 * pool (~6-8 slots empirically). Without a cap, a fast
                 * navigation burst between dashboard pages overloads
                 * the device and triggers throttling → ~30s perceived
                 * lag on the operator side.
                 *
                 * <p>4 = leaves headroom for WS event-tables (hot
                 * I/O path) + one slot for the session keepalive. If
                 * operator experience still shows lag, we can drop to
                 * 2-3 or raise to 5-6.
                 */
                @NotNull
                @WithDefault( "4" )
                Integer adminMaxConcurrent();

                /**
                 * How long an admin command waits in the Semaphore
                 * queue before failing fast with an
                 * {@link AdminCommandException}.
                 *
                 * <p>Must be > {@link #requestTimeout()} × 2 to let
                 * in-flight calls drain. Default = 30s: balance
                 * between "the user click doesn't look frozen" and
                 * "no false positives if the Miniserver takes time
                 * to digest".
                 */
                @NotNull
                @WithDefault( "PT30S" )
                Duration adminWaitTimeout();
            }

            interface Ws
            {
                @NotBlank
                @WithDefault( "/ws/rfc6455" )
                String path();

                @NotNull
                @WithDefault( "PT60S" )
                Duration keepaliveInterval();
            }
        }

        interface App
        {
            @NotBlank
            @Pattern( regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
                      message = "miniserver.app.id must be a hyphenated UUID (8-4-4-4-12)" )
            String id();

            @NotBlank
            String info();

            /**
             * Bitmask of rights requested when acquiring a JWT via
             * {@code jdev/sys/getjwt/{hash}/{user}/{permission}/...}.
             *
             * <p><b>NB</b>: despite the wording of the V17.0 spec
             * §"Permissions" ("the permission for the web (ID = 2) and the
             * permission for the app (ID = 4)" as if they were single IDs),
             * this field is in fact a <strong>bitmask</strong>.
             * Empirically (test 2026-05-27):
             * <ul>
             *   <li>{@code permission=4} → token with bits App + Op-Modes
             *       + AD + Adopt-UI (non-destructive subset).</li>
             *   <li>{@code permission=2047} → bits 0-10 inclusive (= 0x7FF).</li>
             *   <li>{@code permission=4095} → bits 0-11 (= 0xFFF), <b>including
             *       User-Mgmt (0x800)</b> required for the {@code /users}
             *       surface.</li>
             * </ul>
             *
             * <p>The Miniserver grants {@code requested AND user-rights} —
             * impossible to obtain a bit the user doesn't have, regardless
             * of the requested value.
             *
             * <p>Default {@code 4095} covers the bits used by the
             * binding:
             * <ul>
             *   <li>0x004 App      — long-lived token</li>
             *   <li>0x080 Op-Modes — {@code /schedules}</li>
             *   <li>0x800 User-Mgmt — {@code /users}</li>
             * </ul>
             * + headroom for Admin / Config / AD / Adopt-UI / Web /
             * Edit User / FTP / Expert / Sys-WS (useful or harmless).
             *
             * <p>Bits intentionally not requested (≥ 0x1000): Device-Mgmt,
             * Plugin-Mgmt, Trust-JWT-Auth, Trigger Update/Backup —
             * destructive and unused.
             *
             * <p>Reference: full bit table in
             * {@code docs/loxone/1700_Communicating-with-the-Miniserver.pdf}
             * §"List of permission bit-flags" (p. 16).
             */
            @Min( 1 )
            @WithDefault( "4095" )
            int permission();
        }

        interface Security
        {
            @NotNull
            @Valid
            Credentials credentials();

            @NotNull
            @Valid
            Token token();

            interface Credentials
            {
                /** Base64-encoded username. Decoded once at startup. */
                @NotBlank
                String user();

                /** Base64-encoded password. Decoded once at startup. */
                @NotBlank
                String password();
            }

            interface Token
            {
                @NotNull
                @Valid
                Refresh refresh();

                interface Refresh
                {
                    /** Local-time of day for the daily token refresh (e.g. {@code 04:30:00}).
                     *  A single {@link LocalTime} rather than an hour/minutes/seconds triple. */
                    @NotNull
                    @WithDefault( "04:30:00" )
                    LocalTime delayTime();

                    /** Refresh period — defaults to 24h. */
                    @NotNull
                    @WithDefault( "PT24H" )
                    Duration period();
                }
            }
        }

        /**
         * Loxone protocol command stems. Defaults come straight from
         * the V17.0 reference; operators rarely have a reason to override.
         */
        interface Cmd
        {
            @NotBlank
            @WithDefault( "jdev/cfg/apiKey" )
            String getCfgApi();

            @NotBlank
            @WithDefault( "jdev/sys/getPublicKey" )
            String getPublicKey();

            @NotBlank
            @WithDefault( "jdev/sys/keyexchange/" )
            String keyExchange();

            @NotBlank
            @WithDefault( "jdev/sys/getkey2" )
            String getKeyAndSalt();

            @NotBlank
            @WithDefault( "jdev/sys/getkey" )
            String getKey();

            @NotBlank
            @WithDefault( "jdev/sys/getjwt/" )
            String requestToken();

            @NotBlank
            @WithDefault( "jdev/sys/refreshjwt" )
            String refreshToken();

            @NotBlank
            @WithDefault( "jdev/sys/killtoken/" )
            String killToken();

            @NotBlank
            @WithDefault( "jdev/sps/enablebinstatusupdate" )
            String requestStatusUpdate();

            @NotBlank
            @WithDefault( "data/LoxAPP3.json" )
            String requestStructureFile();

            @NotBlank
            @WithDefault( "jdev/sps/LoxAPPversion3" )
            String requestStructureFileVersion();

            @NotBlank
            @WithDefault( "jdev/sys/enc/" )
            String encrypt();

            @NotBlank
            @WithDefault( "keepalive" )
            String keepalive();

            @NotNull
            @Valid
            Prefix prefix();

            interface Prefix
            {
                @NotBlank
                @WithDefault( "jdev/sps/io/" )
                String root();

                @NotBlank
                @WithDefault( "salt/" )
                String salt();

                @NotBlank
                @WithDefault( "nextSalt/" )
                String nextSalt();
            }
        }

        /**
         * Crypto algorithm names + parameters. The defaults are validated
         * against Loxone Miniserver Gen 2 firmware 14.x — 17.x.
         */
        interface Crypto
        {
            @NotNull
            @Valid
            EncryptCommand encryptCommand();

            @NotNull
            @Valid
            EncryptKey encryptKey();

            @NotNull
            @Valid
            HashAlgo hashPassword();

            @NotNull
            @Valid
            HashAlgo hashUserPassword();

            @NotNull
            @Valid
            Salt salt();

            @NotNull
            @Valid
            SessionKey sessionKey();

            interface EncryptCommand
            {
                @NotBlank
                @WithDefault( "AES" )
                String algo();

                @NotBlank
                @WithDefault( "AES/CBC/PKCS5Padding" )
                String transformation();

                @Min( 128 )
                @WithDefault( "256" )
                int keySize();

                @Min( 1 )
                @WithDefault( "16" )
                int saltLength();
            }

            interface EncryptKey
            {
                @NotBlank
                @WithDefault( "RSA" )
                String algo();

                @NotBlank
                @WithDefault( "RSA/ECB/PKCS1Padding" )
                String transformation();
            }

            interface HashAlgo
            {
                /** JCA algorithm name. {@code hash-password} is a digest
                 *  ({@code SHA-256}); {@code hash-user-password} is a MAC
                 *  ({@code HmacSHA256}). */
                @NotBlank
                String algo();
            }

            interface Salt
            {
                /** How long a session salt stays valid before the binding
                 *  rotates it (default: 3600 s). */
                @NotNull
                @WithDefault( "PT1H" )
                Duration maxAge();
            }

            interface SessionKey
            {
                @Min( 1 )
                @WithDefault( "16" )
                int initVectorLength();
            }
        }

        interface Reconnect
        {
            @WithDefault( "true" )
            boolean enable();

            @NotNull
            @WithDefault( "PT1S" )
            Duration initialDelay();

            @NotNull
            @WithDefault( "PT2H" )
            Duration maxDelay();

            @WithDefault( "2.0" )
            double multiplier();

            @WithDefault( "0.2" )
            double jitterFactor();
        }

        interface Cache
        {
            @NotBlank
            @WithDefault( "cache" )
            String directory();

            /**
             * Time-to-live for the on-disk LoxAPP3 cache. Even when the
             * miniserver's {@code lastModified} timestamp matches, the
             * cache is considered <em>stale</em> if the file is older
             * than this duration on disk and a fresh download is forced.
             *
             * <p>Rationale (V17.0 review point 4): {@code lastModified}
             * is the only invalidation key today. A miniserver firmware
             * upgrade can ship structure-file changes WITHOUT bumping
             * the timestamp (observed in the wild — Loxone resets it on
             * some upgrade paths). A TTL provides a safety net: even a
             * "still-fresh-by-timestamp" cache is rechecked at most
             * every {@code ttl} duration.
             *
             * <p>Default {@code P7D} (7 days). Tune lower if your
             * installation changes frequently; tune up to {@code P30D}
             * for very stable installations. Set to {@code PT0S} to
             * effectively disable the cache (always download).
             */
            @WithDefault( "P7D" )
            Duration ttl();
        }

        interface Subscription
        {
            @WithDefault( "false" )
            boolean weather();
        }
    }

    // ==========================================================================
    //  TRANSPORT (MQTT v5)
    // ==========================================================================

    interface Transport
    {
        @NotBlank
        @Pattern( regexp = "BATCH|SINGLE",
                  message = "transport.mode must be BATCH or SINGLE" )
        @WithDefault( "SINGLE" )
        String mode();

        /**
         * If {@code true}, the binding connects to the MQTT broker at
         * startup ({@code StartupEvent}). Symmetric counterpart to
         * {@link Miniserver#autoConnect()}. Disabled by default in the test
         * and dev profiles; flip to {@code true} in production via env
         * ({@code LOXONE_TRANSPORT_AUTO_CONNECT=true}).
         *
         * <p>The two flags are independent — the operator can bring the
         * MQTT side up first to verify the broker route, then connect to
         * the miniserver, or vice versa. Out-of-order startup is fine:
         * publishes that arrive while MQTT is down are silently dropped
         * (logged at DEBUG), not buffered.
         */
        @WithDefault( "false" )
        boolean autoConnect();

        @NotNull
        @Valid
        Connection connection();

        @NotNull
        @Valid
        Session session();

        @NotNull
        @Valid
        Security security();

        @NotNull
        @Valid
        Reconnection reconnection();

        @NotNull
        @Valid
        Topics topics();

        interface Connection
        {
            /**
             * Transport family for the MQTT broker. The actual <i>secured-or-not</i>
             * choice (tcp/ssl, ws/wss) is no longer pinned here since the new
             * {@link #secure()} preference takes over — this field just picks
             * the family. The legacy values (ssl/tls/mqtts/wss) are still
             * accepted to avoid breaking config files that pre-date the
             * secure-mode split; {@code TransportConnectionResolver} maps them
             * to their family equivalent.
             */
            @NotBlank
            @Pattern( regexp = "tcp|ssl|tls|mqtts|ws|wss",
                      message = "transport.connection.protocol must be one of: tcp ssl tls mqtts ws wss" )
            String protocol();

            /**
             * Operator preference: should the binding open the broker
             * connection over TLS?
             * <p>
             * The resolved transport scheme is computed by
             * {@code TransportConnectionResolver} from {@code (protocol, secure)}:
             * <ul>
             *   <li>{@code (tcp-family, true)}  → {@code ssl}</li>
             *   <li>{@code (tcp-family, false)} → {@code tcp}</li>
             *   <li>{@code (ws-family, true)}   → {@code wss}</li>
             *   <li>{@code (ws-family, false)}  → {@code ws}</li>
             * </ul>
             * Unlike {@code loxone.miniserver.connection.secure}, no auto-detection
             * downgrade happens — the broker doesn't expose its TLS readiness the
             * way Loxone Gen2 does via {@code httpsStatus}. If the operator
             * picks SECURE and the broker doesn't support TLS, the connect
             * will fail loudly at the TLS handshake — that's the right
             * behaviour (don't silently degrade broker security).
             */
            @WithDefault( "true" )
            boolean secure();

            @NotBlank
            String host();

            @Min( 1 )
            int port();

            /** URL path — only relevant for {@code ws}/{@code wss}. Held as
             *  {@link Optional} because SmallRye Config rejects {@code path=}
             *  (empty string) on non-Optional {@code String}. */
            Optional< String > path();

            @NotBlank
            String clientId();

            @NotNull
            @WithDefault( "PT3S" )
            Duration connectTimeout();

            @NotNull
            @WithDefault( "PT60S" )
            Duration keepaliveInterval();

            /** When true, the broker includes Reason String / User Properties
             *  in its failure responses. Mirrors Paho's
             *  {@code setRequestProblemInfo}. */
            @WithDefault( "true" )
            boolean requestProblemInformation();
        }

        interface Session
        {
            @WithDefault( "true" )
            boolean cleanStart();

            @NotNull
            @WithDefault( "PT0S" )
            Duration expiryInterval();
        }

        interface Security
        {
            @NotNull
            @Valid
            Credentials credentials();

            /**
             * Maximum allowed size in bytes for an inbound MQTT payload
             * on the {@code …/command} and {@code …/api} subscribed
             * topics. Anything larger is dropped with a WARN log; no
             * JSON parse is attempted (a 1 MB JSON blob would otherwise
             * burn CPU + memory for nothing).
             *
             * <p>Default {@code 4096} bytes — a typical command JSON
             * (UUID + short string command) is well under 200 bytes;
             * even an API connector SET with a verbose value runs
             * around 500 bytes. 4 KB is a comfortable ceiling for
             * legitimate payloads and a hard barrier against a
             * malicious or buggy publisher trying to flood the binding.
             *
             * <p>Tune up if you have unusually large API connector
             * payloads (rare). Setting to {@code 0} or a negative value
             * <strong>disables</strong> the check entirely
             * (NOT recommended in production).
             */
            @WithDefault( "4096" )
            int maxInboundPayloadBytes();

            interface Credentials
            {
                @WithDefault( "true" )
                boolean enable();

                Optional< String > user();

                Optional< String > password();
            }
        }

        interface Reconnection
        {
            @WithDefault( "true" )
            boolean automatic();

            @NotNull
            @WithDefault( "PT3S" )
            Duration minDelay();

            @NotNull
            @WithDefault( "PT2M" )
            Duration maxDelay();

            @NotNull
            @Valid
            OutOfService outOfService();

            /**
             * Special MQTT reconnect timing applied after the binding observes
             * a miniserver OutOfService indicator (Loxone identifier 5). The
             * miniserver is about to reboot or apply a firmware update; the
             * binding's WS will close shortly and stale publishes would be
             * misleading. So:
             * <ol>
             *   <li>Disconnect MQTT (the LWT-style "offline" goes out).</li>
             *   <li>Wait {@link #initialDelay()} before the first reconnect
             *       attempt — enough for a typical Miniserver reboot.</li>
             *   <li>On reconnect failure, retry every
             *       {@link #retryInterval()} until success.</li>
             * </ol>
             * The HiveMQ auto-reconnect (for transient broker drops) and this
             * OOS-specific path don't overlap: HiveMQ doesn't auto-reconnect
             * after our explicit {@code disconnectWith().send()} call.
             */
            interface OutOfService
            {
                @NotNull
                @WithDefault( "PT30S" )
                Duration initialDelay();

                @NotNull
                @WithDefault( "PT15S" )
                Duration retryInterval();
            }
        }

        interface Topics
        {
            @NotBlank
            String root();

            @Min( 0 )
            @WithDefault( "2" )
            int qos();

            @NotNull
            @Valid
            Will will();

            @NotNull
            @Valid
            Publish publish();

            @NotNull
            @Valid
            Subscribe subscribe();

            /**
             * Last-Will-Testament settings. The MQTT broker publishes the
             * {@code messageOffline} payload to {@code topic} when the
             * binding's session goes away ungracefully (network drop, JVM
             * crash); the binding itself publishes {@code messageOnline} to
             * the same topic right after CONNACK so subscribers see a clean
             * "online/offline" toggle.
             *
             * <p>Convention: topic ends with {@code /status}, retain=true so
             * late subscribers immediately see the current presence.
             */
            interface Will
            {
                @WithDefault( "true" )
                boolean enable();

                @WithDefault( "true" )
                boolean retain();

                @NotBlank
                @WithDefault( "online" )
                String messageOnline();

                @NotBlank
                @WithDefault( "offline" )
                String messageOffline();

                /** Status topic. Defaults to {@code {root}/{app.id}/status} via
                 *  property interpolation in {@code application.yaml}. */
                @NotBlank
                String topic();

                @Min( 0 )
                int qos();
            }

            interface Publish
            {
                @NotNull
                @Valid
                TopicSpec appInfo();

                @NotNull
                @Valid
                TopicSpec commandResponse();

                @NotNull
                @Valid
                TopicSpec loxApp3();

                @NotNull
                @Valid
                TopicSpec outOfService();

                /** Type 2 in the Loxone V17.0 spec. */
                @NotNull
                @Valid
                TopicSpec valueStates();

                /** Type 3 in the Loxone V17.0 spec. */
                @NotNull
                @Valid
                TopicSpec textStates();

                /** Type 4 in the Loxone V17.0 spec. */
                @NotNull
                @Valid
                TopicSpec dayTimerStates();

                /** Type 7 in the Loxone V17.0 spec. Published only when
                 *  {@code miniserver.subscription.weather=true}. */
                @NotNull
                @Valid
                TopicSpec weatherStates();
            }

            interface Subscribe
            {
                @NotNull
                @Valid
                SubscribeSpec command();

                @NotNull
                @Valid
                SubscribeSpec api();
            }
        }
    }

    /**
     * Standard publish-topic descriptor (used by every entry under
     * {@code transport.topics.publish.*}). Kept as a top-level interface so
     * it can be shared across the multiple publish endpoints without
     * duplicating fields.
     */
    interface TopicSpec
    {
        @NotBlank
        String topic();

        @Min( 0 )
        int qos();

        @WithDefault( "false" )
        boolean retain();
    }

    /** Subscribe-topic descriptor. Same shape as {@link TopicSpec} minus retain. */
    interface SubscribeSpec
    {
        @NotBlank
        String topic();

        @Min( 0 )
        int qos();
    }
}
