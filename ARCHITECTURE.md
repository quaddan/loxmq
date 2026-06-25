<img src="src/main/resources/META-INF/resources/images/loxmq-logo.svg" alt="loxmq">

# ARCHITECTURE

Status: **feature-complete, production-viable native-image**. Full
observability stack (Micrometer + Health panel + KeepAlive RTT),
hardened inbound security path, standalone native binary (~50 ms
startup, ~50–80 MB RSS — see `NATIVE.md`).

Deferred work is tracked as GitHub issues (security items in
[SECURITY.md](./SECURITY.md) §4.3).

---

## 1. What this binding does

1. Open a WebSocket session with a Loxone Miniserver, complete the
   encrypted handshake (RSA + AES-CBC + JWT token), subscribe to
   binary state-update messages.
2. Decode each state event (Value / Text / DayTimer / Weather) and
   republish to an MQTT v5 broker on a topic derived from the
   device UUID + type.
3. Subscribe to a command topic on the broker, encrypt incoming
   commands and forward them to the Miniserver.

The binding additionally exposes an HTTP/HTTPS server endpoint (configurable
via `loxone.management.public-host`) with health, metrics, OpenAPI,
an operator dashboard with SSE auto-refresh, and a management API.
It is not purely headless.

## 2. Runtime stack

| Layer                | Choice                                                                                   | Why                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
|----------------------|------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| JVM                  | Java 25 LTS (`maven.compiler.release=25`)                                                | Quarkus officially supports LTS 17 / 21 / 25. We had initially aimed for Java 26 (latest non-LTS GA), but Mandrel/GraalVM doesn't yet handle bytecode v70 — the native image fails with "unsupported class file major version 70". The host JDK can stay on 26 (forward-compatible); only `--release` is pinned to 25. To bump as soon as Mandrel ships Java 26 support.                                                                                                                                                                                                                                           |
| Framework            | Quarkus 3.37.0                                                                           | Stable current. Follows the non-LTS line in rolling-current policy. The closest LTS branches are 3.20 and 3.27 (older) and 3.43 (planned).                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |
| CDI                  | Quarkus ArC                                                                              | Compile-time DI, native-friendly.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| Config               | SmallRye Config + `@ConfigMapping`                                                       | Compile-time validated, immutable interface tree. Constraint violations surface at first injection, never at runtime in a hot path.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
| HTTP server          | Vert.x (Quarkus default)                                                                 | Embedded, non-blocking, TLS via Quarkus TLS Registry.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| REST                 | Resteasy Reactive (`quarkus-rest`)                                                       | Modern, native-friendly, Jakarta REST.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| Templates            | Qute (`quarkus-qute` + `quarkus-rest-qute`)                                              | Type-safe templates at build-time for the dashboard.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| WebSocket            | Quarkus WebSockets Next                                                                  | Client (to talk to the Miniserver) and server in the same extension.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| Outbound HTTP        | `quarkus-rest-client`                                                                    | Used for synchronous bootstrap calls (`jdev/cfg/apiKey`, `jdev/sys/getPublicKey`).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 |
| MQTT v5              | `quarkus-hivemq-client` 2.5.0 (Quarkiverse extension wrapping HiveMQ MQTT Client 1.3.14) | Migrated from raw `com.hivemq:hivemq-mqtt-client`. The extension emits `RuntimeInitializedPackageBuildItem("com.hivemq")` required for the native image (the raw dep segfaults on build-time init of Netty's `UnpooledByteBufAllocator`). API unchanged — we still drive the builder directly. The bundled SmallRye Reactive Messaging connectors (`HiveMQMqttConnector` + generic `MqttConnector` pulled transitively by `smallrye-reactive-messaging-mqtt`) are both excluded via `quarkus.arc.exclude-types` (we use the raw builder API, not the stream abstraction).                                          |
| TLS                  | Quarkus TLS Registry                                                                     | Named TLS configs (server, miniserver, broker) referenced from `application.yaml`.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| Health               | SmallRye Health (MicroProfile Health 4)                                                  | `/q/health`, `/q/health/live`, `/q/health/ready`.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| Metrics              | Micrometer + Prometheus exporter                                                         | Replaces MicroProfile Metrics (deprecated upstream).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| OpenAPI              | SmallRye OpenAPI (MicroProfile OpenAPI 3)                                                | `/q/openapi` + `/q/swagger-ui`.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| Fault Tolerance      | SmallRye Fault Tolerance (MicroProfile FT)                                               | Retries, circuit breakers around miniserver / broker calls.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| Tracing (off by def) | Quarkus OpenTelemetry                                                                    | Wire an OTLP exporter at runtime when/if traces are needed.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| Validation           | Hibernate Validator (`quarkus-hibernate-validator`)                                      | Bean Validation on config + REST payloads.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |
| Logging              | JBoss Logging + Quarkus structured JSON                                                  | JSON output in staging/prod, simple text in dev.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| Tests                | JUnit 5 + Quarkus Test + Rest-Assured                                                    | `@QuarkusTest`, `@QuarkusIntegrationTest`, Mockito.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |

## 3. Top-level package map

```
com.quaddan.iot.loxmq
├── boot/         — Application + BootAutoStarter (MQTT → Bootstrap → Miniserver chain)
├── config/       — LoxoneConfig (@ConfigMapping interface tree) + LoxoneConfigHolder (snapshot bean)
├── health/       — MicroProfile Health checks (liveness + readiness × 2 components)
├── management/   — REST API (/api/v1/*, ManagementResource) + Qute UI pages:
│                   dashboard (/, DashboardResource) with Health panel,
│                   live states (/states, LiveStatesResource; live feed over
│                   SSE via StateStreamResource + LiveStateSseEnricher),
│                   logs (/logs), schedules (/schedules), users (/users) —
│                   the last three each a *PageResource + REST *Resource pair
├── transport/    — MQTT Publisher / subscriber (HiveMQ via Quarkiverse extension)
│   ├── publish/     — AppInfo, LoxApp3, States (SINGLE/BATCH), CommandResponse, OOS
│   ├── subscribe/   — CommandSubscriber (retained-drop + size-cap guards)
│   ├── connection/  — TransportFamily + TransportConnectionResolver (tcp/ssl/ws/wss)
│   ├── MqttClient + HiveMqClient
│   ├── MqttMetrics (Micrometer counters/gauges/timers)
│   └── TransportLifecycle, MqttReconnectScheduler, MqttConnectedEvent,
│       MqttDisconnectedEvent, MqttMessageHandler, TransportException
├── miniserver/
│   ├── admin/       — Synchronous admin command surface:
│   │                  MiniserverAdminCommandClient (HTTPS + autht, retry-once,
│   │                  Semaphore concurrency cap), ScheduleService (calendar CRUD),
│   │                  UserService (read + metadata helpers),
│   │                  UserMutationService (create/edit/delete/disable, +
│   │                  groups assign/remove), GroupMutationService (CRUD
│   │                  groups), UserAuthService (password/visu/access-code),
│   │                  UserNfcService (discover/add/remove), dedicated audit log.
│   ├── bootstrap/   — BootstrapOrchestrator (jdev/cfg/apiKey + getPublicKey)
│   ├── command/     — MiniserverCommand records + CDI events
│   ├── connection/  — ConnectionModeResolver + EndpointResolver + MiniserverHttpClientFactory
│   ├── crypto/      — AES/RSA/JWT/HMAC — LoxoneCryptoService
│   ├── http/        — MiniserverHttpClient + LoxoneJsonParser + Jackson DTOs (cfgApi / publicKey / keyAndSalt)
│   ├── identity/    — MiniserverIdentity, MiniserverVersion, HttpsStatus, MiniserverGeneration
│   ├── message/     — MessageHeader/MessageType + binary state-table decoder
│   │                  (Value/Text/DayTimer/Weather) + keepalive/OOS events
│   ├── session/     — SessionOrchestrator + state machine + SessionTracker
│   │                  + MiniserverWebSocket / JdkMiniserverWebSocket + MiniserverToken
│   │                  + KeepAliveScheduler (V17.0 §Detecting issues)
│   │                  + ReconnectScheduler + TokenRefreshScheduler + LoxApp3Cache
│   │                  + LoxApp3MetadataResolver (Topology snapshot for /states)
│   └── state/       — MiniserverState (shared identity slot)
│                       + SdCardHealthService (jdev/sys/sdtest, once/session)
│                       + FirmwareUpdateService (Loxone updatecheck.xml, once/session)
└── util/
    ├── logging/     — LoxmqConsoleFormatter (xterm-256 + emojis, JVM mode)
    │                  + LoggingProducers (custom formatter wiring) + LoggingReflectionConfig
    ├── templates/   — Qute @TemplateExtension (formatLocal, formatMs)
    ├── graal/       — @TargetClass substitutions (Target_JCTools)
    └── NativeReflectionConfig — reflection registration for native image
```

## 4. Configuration model

Configuration is exposed as a SmallRye interface tree `@ConfigMapping`
rooted at `LoxoneConfig` (prefix `loxone.*`). Source of properties, in
priority order:

1. `application.yaml` (compiled in, contains the defaults and the shape)
2. `application-{dev,staging,prod}.yml` (per-profile overlay)
3. Environment variables (UPPERCASE, dot → underscore)
4. Java system properties (-D)

SmallRye Config validates the mapping eagerly at the first injection —
any constraint violation surfaces as `ConfigValidationException`
before any business code runs.

The mapping tree is **immutable** — no setter that would drift
state at runtime.

## 5. CDI Eventing

The binding wires its lifecycle through CDI events: each significant
moment (`ExceptionEvent`, `MiniserverConnectedEvent`, …) fires a typed
event on Quarkus ArC, and each consumer subscribes via
`@Observes` / `@ObservesAsync`. Two notable semantics:

- **`ExceptionEvent` is no longer wired to `System.exit(1)`** in the
  Quarkus build. Exceptions classified as fatal call
  `Quarkus.asyncExit(1)` which goes through the full shutdown lifecycle
  (`@PreDestroy` etc.) before the JVM dies. The supervisor
  (systemd / Docker) always restarts the process.
- **Per-event async dispatch** is governed by the Quarkus event
  executor, not a hand-rolled `ExecutorService`. Less surface to
  maintain.

## 6. TLS plumbing

Three flows, all under TLS in staging/prod:

| Flow                  | Direction                              | TLS                       | Path                             |
|-----------------------|----------------------------------------|---------------------------|----------------------------------|
| Operator → Binding    | server                                 | HTTPS server (Quarkus)    | wildcard `*.example.com`         |
| Binding → Miniserver  | client (boot HTTP calls + WSS session) | Let's Encrypt trust chain | wildcard installed on miniserver |
| Binding → MQTT broker | client (WSS)                           | Let's Encrypt trust chain | wildcard installed on broker     |

The server cert is configured via Quarkus TLS Registry under the name
`server` (see `application.yaml`). For outbound calls the default JDK
truststore is enough — Let's Encrypt ISRG Root X1 + R3
are pre-trusted since JDK 8u251.

If the miniserver presents a self-signed or non-Let's-Encrypt
cert (for example the operator hasn't installed the wildcard), the
binding can be configured with a custom truststore via the keys
`quarkus.tls.miniserver.trust-store.*`. The binding doesn't wire that —
the assumption is that the wildcard installation procedure (see
operator docs) is followed.

## 7. Reconnection ownership

The two legs are reconnected by different actors, intentionally:

| Side          | Owner                          | Strategy                                                                                                                |
|---------------|--------------------------------|-------------------------------------------------------------------------------------------------------------------------|
| WS Miniserver | Custom `MiniserverReconnector` | Policy-driven via `LoxoneCloseCode → ReconnectPolicy` — three explicit policies, intermediate state observable in tests |
| MQTT broker   | HiveMQ MQTT Client lib         | Exponential backoff from `loxone.transport.reconnection.{minDelay,maxDelay}`, gated on `.automatic`                     |

The Miniserver side carries Loxone-specific close codes (4003-4008)
with business meaning (user disabled, structure-file version mismatch,
…). A lib cannot know those.

## 8. Native image

**Native is the production target**, not optional. The fast-jar
only exists as an iteration shortcut for `mvn quarkus:dev`. See
**`NATIVE.md`** (repo root) for the full guide — build command,
the 5 native-image pitfalls encountered + resolved, and the
workflow to keep the AOT build green.

Key invariants for the AOT build to remain functional:

- Any Jackson DTO (read by `readValue` / `treeToValue` or written by
  `writeValueAsBytes`) is registered in `util/NativeReflectionConfig`.
- The dashboard Qute template never calls `.name()` on enums
  or any inherited method — enum names are pre-computed in
  `DashboardResource.dashboard()` and passed to the template as `String`.
  Bean accessors are covered by `@TemplateData` (same file).
- The JCTools `arrayIndexScale` recompute substitutions live in
  `util/graal/Target_JCTools` (the standalone `org.jctools:jctools-core`
  pulled by Netty — Quarkus core only handles the shaded copy).
- `quarkus.arc.exclude-types` skips `HiveMQMqttConnector` (SmallRye
  Reactive Messaging connector we don't use, bundled by the
  Quarkiverse extension — its `@Inject ExecutionHolder` chains to
  a RUNTIME_INIT Vert.x bean from STATIC_INIT, crashing native).
- The `org.graalvm.nativeimage:svm:25.0.3` dep (provided) is the source
  of the `@TargetClass` / `@Alias` / `@RecomputeFieldValue` annotations.

Build: `./mvnw package -Pnative -Dquarkus.profile=dev -Dquarkus.native.container-build=true -DskipTests`

Observed metrics: ~50 ms startup (vs ~2 s JVM, ×40), ~50-80 MB
RSS (vs ~250 MB JVM, ×4), 88 MB binary, stable under burst load
(1700+ publishes/8 s in steady-state testing).

Deferred item (cosmetic): `LoxmqConsoleFormatter` doesn't install
in native because the `val$delegate` reflection on the
anonymous wrapper `LoggingSetupRecorder` relies on the synthetic
field metadata that GraalVM strips by default. The native binary
falls back to the default Quarkus console format — acceptable
since prod consumes the logs via journald, not by eye.

## 9. Tests

The test strategy has its dedicated document: **`TESTS.md`** (module
root). It covers:

- The two tiers (`@QuarkusTest` in-JVM vs `@QuarkusIntegrationTest`
  packaged-subprocess), when to use which.
- The three `@QuarkusTestResource` (Mosquitto via Testcontainers, fake
  HTTP miniserver, fake HTTP+WS Vert.x miniserver).
- The universal rule `@Observes` + SmallRye injection →
  `@Singleton + LoxoneConfigHolder` or direct `@Startup`.
- Command cheat-sheet (`mvn test`, `mvn verify -Pintegration`,
  filters `-Dtest=...` / `-Dit.test=...`).
- File map, decision tree to place a new test,
  anti-patterns encountered.

Current state: **245 unit + 21 IT** green. The IT tier covers the five
axes needed for prod cutover — boot, live broker (Testcontainers
Mosquitto), HTTP bootstrap, handshake → RUNNING, and end-to-end
command round-trip — and extends beyond the dev profile with
`BootProfileSmokeIT` (fast-jar subprocess on staging + prod) and
`BrokerCrashRecoveryIT` (broker crash + revive on the same port,
verifying end-to-end HiveMQ auto-reconnect and `online` republish),
plus state-event ITs (binary-frame → MQTT) and a crypto round-trip
(fake decrypts RSA + AES). The unit tier covers: logging formatter,
auto-start orchestrator, retained/size guards, cache TTL, KeepAlive
scheduler, RTT + handshake duration, Micrometer registry,
`StateStreamResource` (SSE dashboard), and `BootAutoStarter`
halt-on-failure (5 tests + 1 dev-mode suppression). Details in
`TESTS.md` §4.

## 10. Design rationale

The non-obvious design decisions (crypto handshake, reconnection
ownership, CDI ordering, Quarkus / Mandrel conventions) are documented
by section in this file and in `NATIVE.md`. The Loxone V17 spec PDFs
(vendor-copyrighted, **not redistributed in this repository** — obtain
them from Loxone) remain the authoritative source for the wire protocol.

## 11. Cross-cutting decisions

The cross-cutting concerns below shape the binding beyond the core
bridge:

### a. Custom logging

`LoxmqConsoleFormatter` extends JBoss `ExtFormatter` with an
xterm-256 palette, per-level emojis (🔴🟡🟢🔵🟤), wrappers
(💣🔥⚠️✅) and conditional method+line on DEBUG/TRACE only. Wired via `LoggingProducers` (`@Startup @PostConstruct`)
which descends by reflection into the synthetic field `val$delegate` of
the anonymous wrapper `LoggingSetupRecorder` from Quarkus to reach the
real `ConsoleHandler`. Not native-compatible — deferred, fallback to
Quarkus default on the native binary.

### b. Auto-start orchestrator

`BootAutoStarter` (`@ApplicationScoped` `@Observes @Priority(MAX_VALUE-100)
StartupEvent`) chains MQTT → Bootstrap → Miniserver in strict order
when `loxone.boot.auto-start=true` (default).
Resolves the CDI race where `TransportLifecycle` and `Application` observing
`StartupEvent` independently could fire in arbitrary order
— observed scenario: "session RUNNING but MQTT not yet" which silently
dropped the retained `app_info` + `loxapp3`. Fail-soft: a
failure at any step WARN-logs + stops the chain without
crashing the binding.

### c. Inbound security

Two guards on the MQTT handlers for topics `…/command` and `…/api`,
in the order `retained → size → JSON parse`:

1. **Retained drop** — a message accidentally published with
   `retained=true` would replay at each CONNACK. The spec requires
   `retained=false` on these topics. Drop + WARN with preview.
2. **Payload-size cap** — `loxone.transport.security.max-inbound-payload-bytes`
   (default 4096). Beyond: drop + WARN without attempting JSON
   parse (DoS protection by flooding).

### d. LoxAPP3 cache TTL safety net

`loxone.miniserver.cache.ttl` (Duration, default `P7D`) adds a
second level of invalidation to the disk cache. Before: only the
`lastModified` returned by `jdev/sps/LoxAPPversion3` decided
hit/miss. Observed: some Loxone firmware
upgrade paths ship a new structure-file without bumping the
timestamp → indefinitely stale cache. The TTL forces a re-download
when the file on disk exceeds `ttl` even if the `lastModified`
matches. Set to `PT0S` to disable (debug).

### e. Observability

Two parts:

- **KeepAlive sender + RTT measurement** — Protocol gap closed:
  the V17.0 spec §"Detecting issues" mandates periodic sending of
  `keepalive`, the binding had the decode-side but no sender
  scheduler. `KeepAliveScheduler` observes `MiniserverConnectedEvent`,
  arms a `ScheduledExecutorService` at `keepalive-interval` (PT60S
  by default), sends via `MiniserverWebSocket.sendText`, captures
  `lastSentAt`. `BinaryStatesDecoder` fires a new
  `MiniserverKeepAliveResponseEvent` when identifier 6 arrives →
  scheduler computes `arrived - sentAt = RTT`, stores in atomic ref.
  Auto-cancel on `sendText` exception (dead WS) → re-arm on next
  connected. `SessionTracker` also now captures the
  CONNECTING → RUNNING duration (handshake duration).

- **Micrometer metrics + Health dashboard** — `MqttMetrics`
  registers 7 meters at boot: counters `binding.mqtt.publishes` +
  `binding.mqtt.inbound.dropped{reason}`, gauges
  `binding.session.state.ordinal` + `broker.connected` + `keepalive.scheduled`,
  timers `miniserver.handshake.duration` + `miniserver.keepalive.rtt`
  (with percentiles 50/95/99). Push from `HiveMqClient.publish()`,
  `CommandSubscriber` × 3 drops, `KeepAliveScheduler.onResponse`.
  Observe `MiniserverConnectedEvent` for handshake duration. CDI
  cycle `MqttMetrics ↔ KeepAliveScheduler` broken via lazy `Instance<>`
  on the scheduler side. Health dashboard panel exposes the values
  on the HTML side (last RTT color-coded, last handshake duration,
  scheduler armed/idle).

### f. Production-viable native image

Documented in full in `NATIVE.md`. The key measures:

1. Migration `com.hivemq:hivemq-mqtt-client` → Quarkiverse
   `quarkus-hivemq-client:2.5.0` extension (resolves the Netty
   `UnpooledByteBufAllocator` STATIC_INIT clash). CDI exclusion
   `HiveMQMqttConnector` (resolves the Vertx STATIC_INIT race).
2. `@RegisterForReflection` on 11 Jackson DTOs +
   pre-compute of enum names in Java for the Qute template.
3. `@TargetClass` substitution for standalone JCTools
   (segfault under burst load resolved) + 9 outbound DecodedMessages
   records.
4. `NATIVE.md` codifies the pitfalls + the mandatory native rebuild
   workflow for non-trivial changes.

The native binary **is the reference artifact** for prod
deployment. The fast-jar remains for `mvn quarkus:dev`.

### g. Ops industrialization + cleanup

Themes consolidated during the real LXC Proxmox deployment.

**Naming convention** — The binding is called `loxmq` everywhere:
Maven artifactId, systemd unit, filesystem layout (`/opt/loxmq/`,
`/var/lib/loxmq/`, `/etc/loxmq/env`), Docker image, log files
(`application.log` + `commands.log`), deploy scripts. The systemd
units are split into `loxmq-jvm.service` / `loxmq-native.service`
with reciprocal `Conflicts=` to prevent launching them in parallel.

**Runtime robustness fixes — pitfalls already encountered** — Three
latent bugs that may reappear if you touch the logging or the
reconnect:

- `quarkus.log.file.enabled` (with `d`) required since Quarkus 3.27,
  but the repo used the old `enable` (no `d`) which emitted a
  deprecation WARN interpreted as "auto-enabled". Consequence:
  silently no log file produced until the key name is corrected.
- `ReconnectScheduler` re-arm asymmetry depending on the WS failure
  type — `webSocket.connect()` sync fail (TCP RST during a
  miniserver reboot) did not call `scheduleReconnect`, unlike
  handshake fails. Dead loop after the 1st fail. Fix:
  `failNow()` re-arms the scheduler if `before == CONNECTING`.
- `OutOfServiceMqttReconnector` closed the MQTT session for
  30 s on each Miniserver OUT_OF_SERVICE (initial design — wrong
  abstraction — MQTT and Miniserver are independent dependencies).
  Consequence: `…/status offline/online` flapping + command loss
  during the window. Fix: no longer disconnect MQTT, just
  publish the OOS notification.

**Writable paths under systemd** — `ProtectSystem=strict`
makes `/opt/loxmq/` readonly; the relative paths
`logs/` and `cache` from `quarkus.log.file.path` and
`loxone.miniserver.cache.directory` silently failed.
Externalized via `${LOG_DIR:logs}` + `${CACHE_DIR:cache}` with
`Environment="LOG_DIR=/var/lib/.../logs"` + `Environment="CACHE_DIR=/var/lib/.../cache"`
in the unit files. Without this fix: no log file, and LoxAPP3
cache re-downloads on each restart (~258 KB wasted).

**Log lifecycle** — `HiveMqClient.publish()` carries no redundant
DEBUG line (the four typed publishers already log each publish), and no
hardcoded `com.quaddan….transport` DEBUG category leaks into
staging/prod. A dedicated named file handler `commands.log` records the
"who sent which command when" audit (pipe-separated format:
`<ts>|<topic>|<ACCEPT|DROP-*>|<payload>`). File names are stable —
`application.log` + `commands.log`, with no `${app.launch.timestamp}`
suffix that would accumulate across restarts and no profile-specific
`staging.log`/`prod.log` — so ops commands stay portable across hosts.
Rotation is size-based — `max-file-size=20M` + `max-backup-index=30` →
up to 30 numbered backups per log (`application.log.1` … `.30`), bounded
by size (~620 MB per log family) rather than calendar age. The periodic
`file-suffix=.yyyy-MM-dd` knob is deliberately NOT used: JBoss LogManager's
periodic rotation never purges old dated files, so it cannot cap disk.

**Quarkus profile** — Critical bug fixed: `deploy_to_production.sh`
built with `-Dquarkus.profile=production`, but the actual file is
`application-prod.yaml`. Quarkus silently falls back to the
base config (= dev credentials/hosts in prod). Fix: `production` →
`prod` everywhere. And removal of `Environment="QUARKUS_PROFILE=prod"` from
the unit files (broke staging) — it is now `/etc/loxmq/env`
that sets the profile per LXC.

**Deploy scripts** — Full pipeline
for LXC bootstrap + release push (`{staging,prod}_bootstrap.sh` +
`deploy.sh` with `{staging,prod}_deploy.sh` wrappers). These scripts and
the systemd units are operator-specific and **not shipped in this public
repository**; the design notes below are kept for reference. Notable
changes:

- `apt-get install curl ca-certificates jq` at bootstrap (minimal
  Debian 13 Proxmox template doesn't ship curl, the `deploy.sh` health
  check produced a false FAIL)
- bootstrap from the PC via `ssh root@$IP 'bash -s' <<…` —
  no more manual scp + ssh + bash in the LXC
- fix `scp ../../target/*-runner …/application` which created a
  folder `application/` instead of a file when 2+ binaries
  coexisted in `target/` (snapshot + release). Fix: `ls -t … | head -1`
  to take the most recent by mtime + defensive cleanup of the
  sequel folder.

Net: the binding is now end-to-end deployable from the dev PC in 3
commands (`bootstrap → push_certs → deploy_to_*`), with ~5 minutes
wall-clock of which 3 minutes are native build. All writable paths
comply with `ProtectSystem=strict`, the log rotates cleanly, and
the systemd service is split jvm/native with mutual exclusion.

### h. Admin surface — Schedules · Users · Logs

The binding's **active** admin surface — Schedules (CRUD), Users/Groups
(audit + guarded mutations) and the Logs viewer — has its own document.
Its design (synchronous request/reply correlation over the encrypted WS,
the dedicated `audit.log`, the deliberate no-bearer-auth posture, the V17
protocol quirks absorbed, the single `compute-status` + lazy-enrichment UI
pattern) and the operator guide both live in **[ADMIN.md](./ADMIN.md)**.

### i. Dashboard layout

The home screen `/` uses 2 clear columns (Miniserver | MQTT Broker)
with a dedicated Token panel. Layer breakdown:

#### Backend layer — REST surface + Qute model

3 coordinated additions to enable the new UI without duplicating
business logic:

1. **`SessionOrchestrator.refreshToken()` public** (was
   package-private, called only by the periodic
   `TokenRefreshScheduler` — `token.refresh.period` (default 24 h)
   anchored at `token.refresh.delay-time`). Becomes public to allow
   manual triggering via REST. The 2 internal guards
   (`state == RUNNING` + `token != null`) protect against
   invalid calls from the UI side.

2. **`POST /api/v1/token/refresh`**:
   ```
   409 + error="not_running"  if state ≠ RUNNING
   409 + error="no_token"     if no local token
   200 + "refreshjwt sent"    otherwise (async — reply via
                                onTokenRefreshReply)
   ```
   Dashboard polls `/api/v1/state` to see the new `expiresAt`.

3. **`POST /api/v1/connect-with-bootstrap`**: atomic chain
   bootstrap + connect. Body shape differentiated by the
   step where it breaks:
   ```
   200 + bootstrap{durationMs,…} + token{…}             # all OK
   502 + step="bootstrap" + error="bootstrap_failed"    # 1st step
   502 + step="connect"   + error="handshake_failed"    # 2nd step
       + state + bootstrap.status=success               # → progress
   ```
   The operator sees immediately where the chain broke. If
   bootstrap fails, the connect step is NOT attempted (avoids
   the ambiguity "it bootstrapped but after that?").

4. **Token block in `DashboardResource.Templates.dashboard()`**:
   4 new sibling params (`tokenPresent`, `tokenExpiresAt`,
   `tokenRights`, `tokenExpired`). Decision: siblings instead
   of `Optional<MiniserverToken>` because Qute in native-image
   can't resolve inherited methods on enum / record getters
   (see historical note in `Templates` —
   same problem as `identity.httpsStatus.name()`). Pattern
   identical to the `identity` block.

#### Template layer — 2-column restructure

CSS grid `.dashboard-cols { grid-template-columns: 1fr 1fr }` with
mobile fallback to 1 column below 900px. Each column
`.dashboard-col` is a flex column that stacks its panels gap
1.5rem.

##### Decision: a single "Connect to Miniserver" button

Splitting bootstrap and connect into 2 separate buttons ("Run HTTP
bootstrap" then "Connect miniserver") would force the operator to know
the sequence. Instead, a single **`Connect to Miniserver`** button
styled `.btn-primary` (blue accent) POSTs to
`/api/v1/connect-with-bootstrap` and runs the chain in one action. The
2 underlying steps remain on the REST API side for scriptable ops who
want fine grain.

##### Decision: HTML5 `<details>` for Bootstrap status collapsed

The operator asked that the Bootstrap status block be visible
but collapsed in Config, with an arrow to expand. Solution
chosen: native HTML5 `<details><summary>` — no JS, no
state to manage on the browser side, built-in accessibility.

Custom style: native SVG marker removed (`summary::-webkit-details-marker
{ display: none }`) and replaced by a `::before` `▸`/`▾` that changes
depending on `details[open]`. Status badge visible in the `<summary>`
permanently (the operator sees `SUCCESS` / `FAILED` without expanding).

##### Decision: Token panel with buttons disabled if no token

At boot, `tokenPresent=false` (not yet connected). Rather than
hiding the whole panel, we display it with a placeholder
"NO TOKEN — connect first" and the 2 buttons **disabled** via the
HTML5 `disabled` attribute. The operator sees immediately that there
is a Token section but that it is not yet actionable —
continuous mental model between boot and RUNNING.

#### Tests layer — regression protection

An in-process `@QuarkusTest` covers 4 cases
without requiring a full-handshake FakeMiniserver:

- `/token/refresh` 409 not_running (boot auto-start=false → DISCONNECTED)
- `/token/refresh` 409 no_token (same, no local token)
- `/connect-with-bootstrap` 502 step=bootstrap (test miniserver = 127.0.0.1:80, unreachable → BootstrapException)
- `/connect-with-bootstrap` body shape: no `state` field when step=bootstrap (the session was not touched)

Not covered: 200 path and 502 step=connect — require a fake that
does apiKey OK + rejects keyexchange. Kept for a future iteration if
the value emerges.

#### Design rationale — the coherent home screen

| Alternative (dense grid)                                                      | Chosen (2-column)                                                    |
|-------------------------------------------------------------------------------|----------------------------------------------------------------------|
| 5 info-panels in 2×3 grid (Miniserver / Identity / MQTT / Health / Bootstrap) | 2 clear columns: Miniserver \| MQTT Broker                           |
| 2 scattered "Run bootstrap" + "Connect" buttons                               | 1 "Connect to Miniserver" button that chains atomically              |
| No visible Token section                                                      | Dedicated Token panel with expiresAt + next-refresh + VALID/EXPIRED badge |
| Refresh Token = no REST route (auto-only)                                     | `/token/refresh` endpoint + "Refresh token" button in the panel      |
| Bootstrap status = separate panel always visible                              | Bootstrap collapsed in Config — details one click away (`<details>`) |

#### Files touched

| File                                                      | Role                                                             |
|-----------------------------------------------------------|------------------------------------------------------------------|
| `miniserver/session/SessionOrchestrator.java`             | `refreshToken()` public                                          |
| `management/ManagementResource.java`                      | +2 endpoints `/token/refresh` + `/connect-with-bootstrap`        |
| `management/DashboardResource.java`                       | +4 siblings in Templates.dashboard()                             |
| `templates/DashboardResource/dashboard.html`              | 2 columns + Token panel + collapsible Bootstrap + associated CSS |
| `test/.../management/TokenAndBootstrapEndpointsTest.java` | 4 cases (`@QuarkusTest`)                                         |

## Spec references — Loxone V17 PDFs

5 Loxone V17 PDFs + 1 OpenAPI spec. **Authoritative** sources for the
protocol — always refer to them before modifying the code that touches
the Miniserver. These vendor documents are **not redistributed in this
repository**; obtain them from Loxone.

| Document                                            | Coverage                                                                                  | Used by                                                                                               |
|-----------------------------------------------------|-------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------|
| `1700_Communicating-with-the-Miniserver.pdf` (31 p) | WS protocol, handshake, AES/RSA crypto, JWT tokens, binary event-tables, keepalive        | Binding core (`miniserver/session/*`, `miniserver/crypto/*`, `miniserver/message/*`)                  |
| `1700_Structure-File.pdf` (157 p)                   | `LoxAPP3.json` schema + catalog of control types                                          | `LoxApp3MetadataResolver` (parses rooms/cats/controls)                                                |
| `1700_Usermanagement.pdf` (21 p, 2026-03-31)        | CRUD users / groups / NFC / permissions / multi-Miniserver trust                          | `UserService` + `UserMutationService` + `UserAuthService` + `UserNfcService` + `GroupMutationService` |
| `OperatingModeSchedule.pdf` (4 p, Nov 2023, V14.4)  | Operating calendar CRUD `jdev/sps/calendar*` + 6 calendar modes                           | `ScheduleService`                                                                                     |
| `API_Commands.pdf` (June 2022)                      | Touch Pure Flex textual DSL `SET(FB;Input;Value)`, `SETT5`, `MENU`, `VALUESELECT`         | **Not integrated** — reference if we observe a text-event in DSL format                               |
| `pms-access-api/` (OpenAPI 3.0.3 V1.0.1)            | 17 "PMS & Access" endpoints, hospitality niche: NFC door opening, room moods, room status | **Not integrated** — outside residential scope                                                        |

For any change in `miniserver/` or `miniserver/admin/`, cite
the precise section of the PDF in the commit/PR:

- "OperatingModeSchedule §Administrative Commands"
- "Usermanagement V17 §Required Rights, p. 13"
- "Communicating with the Miniserver §Message Header, 2nd byte"

The PDFs mention the **introduction version** of a feature
(`Available since 9.0`, `Updated in 10.2`, `Since V17.0`) — these
metadata must appear in the code or the comment so that
one knows which Miniserver generation the code targets.
