<img src="src/main/resources/META-INF/resources/images/loxmq-logo.svg" alt="loxmq">

# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog 1.1.0](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning 2.0](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.2.0] - 2026-11-02

### Capabilities

- **New `/system` page — run Miniserver diagnostic commands.**
  - Curated, domain-grouped catalogue: **System** `jdev/sys`, **PLC** `jdev/sps`,
    **Config/Network** `jdev/cfg`, **CAN bus** `jdev/bus` (~42 commands, FR/EN/DE).
  - Pick a command → **Send** → the Miniserver's **raw `LL` JSON reply** verbatim.
    Each is typed `read-only` / `action` / `destructive`; destructive ones
    (`logoff`, `reboot`, `sps/restart`) prompt a confirm.
  - **Save** button downloads the reply to a file named after the command
    (`.json` / `.xml` / `.txt`, inferred from the content).
  - Channel: `POST /api/v1/system-command {command}` →
    `MiniserverAdminCommandClient.sendResolvedPath` (HTTPS admin channel, any
    `jdev/`-prefixed path) → `200` / `409` NOT_RUNNING / `502`.
  - **Server-side allow-list:** `system-commands.json` is the single source of
    truth for UI *and* backend — `SystemCommandRegistry` runs a command iff it is
    listed (exact, or `<cmd>/<value>` for the parameterised `setdatetime`), so
    user / token / crypto commands are refused by construction (fail-closed, no
    regex blocklist). Catalogue sourced from the operator's own
    `docs/loxone/Loxone.postman_collection.json` + a community list (**not** the
    vendored PDFs).

- **New `/commands` page — send commands to known Loxone controls.**
  - Same room → category → control cascade as `/states`, extended with
    **sub-controls** (e.g. a `LightControllerV2`'s Dimmer / Switch outputs),
    named `Parent › Child` and addressed by their own `uuidAction` (channel
    suffix included, e.g. `…/AI2`).
  - **UUID field** doubles as filter/selector (type a control *or* state UUID →
    auto-selects the commandable control, `Unknown` in red if nothing matches)
    and as readout of the selected control's UUID.
  - Per-type **Command** picker from `control-commands.json` — **29 commandable
    types** (Switch…Window, `UpDown*`, Alarm/SmokeAlarm, `IRoomControllerV2`,
    `Central*`, …; tokens strictly from `docs/loxone/*`, matrix in
    `docs/control-command-coverage.md`) + a free-form **Custom command** (guarded:
    requires a non-empty UUID).
  - **Current-state** readout (name + formatted value + unit) seeded by
    `GET /api/v1/state/current`, kept live via the SSE stream.
  - Send: `POST /api/v1/commands {uuid, command}` →
    `SessionOrchestrator.sendCommand` builds `jdev/sps/io/<uuid>/<command>`,
    AES-encrypts and sends it **directly on the WS** (not MQTT), synchronously →
    `200` SENT / `409` NOT_RUNNING / `502` SEND_FAILED, audited in `commands.log`
    (label `rest`). New read-model `LiveStateSnapshot` / `StateSnapshotResource`.

- **Runtime kill-switch for inbound command processing.**
  - Toggle on `/commands` + `GET` / `PUT /api/v1/commands/inbound` enable/disable
    processing of **received** MQTT commands (`…/command` + `…/api`) instance-wide.
  - `InboundCommandGate` (in-memory, default enabled, resets on restart); when
    disabled, `CommandSubscriber` drops with `DROP-DISABLED` +
    `binding.mqtt.inbound.dropped{reason="disabled"}` — outbound sends unaffected.

- **Dashboard — on-demand diagnostics + fresher info.**
  - **Run SD-card test** (`POST /api/v1/sdcard/test`) and **Check firmware**
    (`POST /api/v1/firmware/check`) re-run **without restarting** — logic extracted
    into `SdCardHealthService.runSelfTest()` / `FirmwareUpdateService.runCheck()`,
    reused by both the RUNNING observer and the endpoints. Each result carries a
    *"tested / checked at"* freshness chip.
  - Session row shows a *"since"* chip (`SessionTracker.stateChangedAt()`); Token
    panel a **Created at** row (`tokenAcquiredAt()` — preserved across a same-JWT
    refresh, cleared on DISCONNECTED/CLOSED).

- **Logs — near-real-time tail + old-log management.**
  - **Incremental polling:** `GET /api/v1/logs/{name}?since=<byteOffset>` returns
    **only the appended bytes** + `X-Log-Size` (new EOF) and `X-Log-Reset` (rotation
    / truncation, or client >`RESYNC_LIMIT` = 2 MB behind → resync to a fresh tail).
  - 1 s cadence, purely client-driven → **zero backend work when the page is
    closed**, deltas instead of the whole tail.
  - **Show/hide old (rotated) logs** toggle (hidden by default) + **delete old
    logs** (`DELETE /api/v1/logs/old`, rotation backups only — an active `name.log`
    never matches) → `{deleted, freedBytes}`.

- **Resilient reconnection after a Miniserver reboot.**
  - Undocumented WS close code **4009** → `MINISERVER_OUT_OF_SERVICE` /
    `ReconnectPolicy.OUT_OF_SERVICE`: wait **30 s**, then retry every **15 s**
    (instead of the generic exponential backoff).
  - **KeepAlive missed-response watchdog** declares the link dead after repeated
    unanswered keepalives (baseline `max(armedAt, lastResponseAt)`, fixing a
    post-reconnect false-trip loop). (`LoxoneCloseCode`, `ReconnectScheduler`,
    `KeepAliveScheduler`, `SessionOrchestrator`.)

- **Inbound-command flood protection.** Received MQTT commands are dispatched on a
  **bounded single-thread executor, off the HiveMQ/Netty event loop**, so a burst
  on `…/command` can't block the transport; overflow → `DROP-QUEUE-FULL` +
  `binding.mqtt.inbound.dropped{reason="queue_full"}`.

### Changed

- **Consistent action-button palette across all management pages** — **blue** =
  neutral action (refresh, view/edit, save, reconnect, diagnostics), **green** =
  create/add (`+ New user` / `+ New group`, `Add tag`, `Add schedule`), **red** =
  destructive (disconnect, delete, reboot, delete old logs), **grey** = secondary
  (cancel). Assorted spacing/centering/wording fixes on `/commands`, `/users`,
  `/schedules`, `/logs`, dashboard.
- **`LoxApp3MetadataResolver.parse()` now recurses into `subControls`**, so a
  sub-control's states (e.g. a Dimmer's `position`) resolve to a named, formatted
  control in the live-states enrichment too — not only in the new command surface.
- `NativeReflectionConfig` gains `LoxApp3MetadataResolver.StateRef` (the per-control
  state list is serialised manually into the `/commands` page). New cache-busting
  `TemplateGlobals.cacheBust` + `?v={cacheBust}` on `i18n.js` across every page.
- i18n (FR/EN/DE) added for every new user-facing string.

### Configuration

- `loxone.miniserver.reconnect.out-of-service.initial-delay` (default `PT30S`) and
  `…retry-interval` (default `PT15S`) — the OUT_OF_SERVICE reconnect cadence.
- `loxone.miniserver.ws.keepalive-max-missed` (default `3`) — missed keepalive
  responses before the watchdog declares the connection dead.
- `loxone.transport.security.inbound-queue-capacity` (default `256`) — bound of the
  inbound-command dispatch queue.
- `quarkus.native.resources.includes` now declares `system-commands.json` so the
  `/system` page's `SystemCommandRegistry` can read its allow-list from the native
  binary (parsed as a `JsonNode` tree — no reflective DTO binding).

### Tests

- `LoxApp3MetadataResolverTest` — `computeCommandSurface` exposes sub-controls and
  lists each control's named states.
- `CommandSubscriberTest` — inbound processing disabled ⇒ a well-formed command is
  dropped, no CDI event fired.
- `LogsResourceTest` — `deleteOld` removes rotation backups but keeps active logs;
  `?since=` returns only appended bytes + EOF offset and resyncs past EOF.
- `CommandsPageIT` — new native-parity IT: `/commands` embeds the serialized command
  surface (topology + `StateRef`) and `/api/v1/state/current` serves the live value.
- Reconnection / OUT_OF_SERVICE / flood / keepalive coverage
  (`LoxoneCloseCodeTest`, `ReconnectSchedulerTest`, `KeepAliveWatchdogTest`,
  `SessionOrchestratorTest`).

### Documentation

- **New [`CONFIGURATION.md`](./CONFIGURATION.md)** — a single, exhaustive
  reference for every `loxone.*` and operator-relevant `quarkus.*` knob (default +
  env-var override + meaning, grouped by domain), mirroring `LoxoneConfig.java` +
  `application.yaml`. Linked from README / INSTALL / FAQ / CLAUDE (and fixes the
  stale "full knob list" pointer that INSTALL used to make to FAQ).
- `README.md`, `ARCHITECTURE.md`, `ADMIN.md`, `RUNBOOK.md`, `TESTS.md`, `NATIVE.md`,
  `FAQ.md`, `SECURITY.md`, `INSTALL.md` and `TODO.md` updated for the above (the new
  `/system` page, the `/commands` surface, the kill-switch, incremental logs,
  reconnection resilience). FAQ gains a *"terminate loxmq's TLS on a reverse-proxy"*
  entry; the README Capabilities list and screenshot gallery now cover all seven
  pages. Note on native parity: `-Pnative,integration` leaves `skipITs=true` (the
  `native` profile is declared after `integration` in `pom.xml` and wins) — pass
  `-DskipITs=false` to actually run the ITs against the native binary.

## [1.1.0] - 2026-06-25

### Capabilities

- **Responsive web UI.** The five management pages (dashboard, `/states`,
  `/schedules`, `/users`, `/logs`) now adapt to phones and narrow desktop
  windows. A shared `css/mobile.css` overlay (loaded after each page's inline
  styles) collapses the header and grids, shrinks buttons, makes the tab bar
  scrollable, and wraps every wide table in a horizontally scrollable
  `.table-scroll` frame so nothing overflows the viewport. Includes the iOS
  WebKit `<select>` fix (`width:100%`, since Mobile Safari sizes a `<select>`
  to its widest `<option>` and ignores `max-width`).
- **Firmware up-to-date check.** Once per session (on `MiniserverConnectedEvent`)
  the new `FirmwareUpdateService` fetches Loxone's public
  `https://update.loxone.com/updatecheck.xml`, picks the `<update>` block for
  the detected generation (`ms` for GEN1, `ms2` for GEN2), decodes the packed
  `LatestRelease` version (`17000331` → `17.0.3.31`) and compares it with the
  installed `MiniserverVersion`. The dashboard Identity panel shows a green
  *up to date* badge or a yellow *update available* badge with the newest
  version. Best-effort and isolated — the only outbound internet call in the
  otherwise LAN-only binding; any failure is swallowed and simply yields no
  badge. The Compact channel (`msc`) is not handled (a Compact is seen as
  GEN2).
- **Next token-refresh timestamp** is now displayed in the dashboard Token
  panel, just below *Expires at*.

### Changed

- **Quarkus 3.36.3 → 3.37.0** (minor bump). Stack version references synced
  across `README.md` (badge + stack table), `ARCHITECTURE.md`, `FAQ.md` and
  the `pom.xml` comments.
- **Token refresh is now anchored at `token.refresh.delay-time`.** Previously
  the refresh fired at `now + token.refresh.period`, ignoring the configured
  time-of-day (dead config). `TokenRefreshScheduler` now computes the date
  `now + period` lands on and schedules the refresh at `delay-time` that day
  (rolling to the next day if the instant has already passed), and exposes the
  next-refresh `Instant` for the dashboard.
- **Dashboard refinements:**
  - `PLAIN` / `SECURE` badges on both the Miniserver WebSocket row and the
    MQTT Protocol row (the Miniserver HTTP row already had one).
  - QoS shown as a coloured badge — green `2`, yellow `1`, red `0`.
  - *Local* rendered as a badge (green `YES` / yellow `NO`) matching the
    secure/plain colours.
  - Miniserver and MQTT host values set in **bold**; MQTT panel reordered to
    Host → Port → Protocol.
  - The red used by the `down`/error badges now matches the *Disconnect*
    buttons (`#b62324`).
  - Removed three redundant/technical rows: *Rights* (Token), *Permission*
    (Miniserver connection) and *Address* (Identity — duplicated the
    connection host).

## [1.0.2] - 2026-06-21

### Capabilities

- **Version tags now also publish a GitHub Release** with downloadable
  assets — fast-jar zip, native Linux x86-64 binary, `env.example`, the two
  systemd units and `SHA256SUMS.txt` — assembled by the same workflow that
  pushes the Docker images. Release notes are extracted from the matching
  CHANGELOG section, so write the entry before pushing the tag. (The v1.0.1
  release was assembled by hand with the same layout.)

### Changed

- **Quarkus 3.36.2 → 3.36.3** (patch bump). Stack version references synced
  across `README.md` (badge + stack table), `ARCHITECTURE.md`, `FAQ.md` and
  the `pom.xml` comments.
- **Testcontainers is no longer pinned in `pom.xml`.** The Quarkus 3.36.x
  BOM now manages it (resolves to 2.0.5), so the explicit
  `<testcontainers.version>` property and the dependency `<version>` were
  removed — a single BOM bump now carries the test-only dependency.
- Build plugin `spotless-maven-plugin` 3.6.0 → 3.7.0.
- Docker docs: the plain-broker (no-TLS) example now uses `ws` on `8083`
  (WebSocket without TLS — the baked `wss` default minus encryption, keeping
  the `/mqtt` path) instead of raw `tcp` on `1883`, consistently across
  `docker/README.md`, `docker-compose.published.yml` and `docker/DOCKERHUB.md`.

## [1.0.1] - 2026-06-12

### Capabilities

- **Published container images for third-party users — on Docker Hub.**
  Images live at [`quaddan/loxmq`](https://hub.docker.com/r/quaddan/loxmq):
  `:native` / `:<version>-native` — GraalVM native binary on
  `debian:13-slim`, non-root uid 185, ~175 MB, `linux/amd64`; and `:jvm` /
  `:<version>-jvm` (alias `:latest`) — Temurin 25 fast-jar, ~360 MB,
  multi-arch `amd64`+`arm64` (Raspberry Pi, ARM NAS) when built by the
  release workflow. End users pull and run via the new
  `docker-compose.published.yml` (pull, not build) — see `docker/README.md`.
  The two recipes behind the published images (`docker/Dockerfile.jvm`,
  `docker/Dockerfile.debian`) are committed for auditability and
  self-builds; the dev/UBI variants stay local.
- **`.github/workflows/release.yml` now targets Docker Hub** (was GHCR,
  where nothing had ever been published): same semver tag scheme via
  `docker/metadata-action`, multi-arch `:jvm` via QEMU, plus a final job
  that syncs the Hub repository description from the new
  `docker/DOCKERHUB.md`. Requires the `DOCKERHUB_USERNAME` /
  `DOCKERHUB_TOKEN` Actions secrets.
- **Dependabot quality-of-life.** Minor+patch Maven bumps now arrive as one
  grouped PR (majors stay separate for individual review); all GitHub
  Actions bumps are grouped too. A new
  `.github/workflows/dependabot-auto-merge.yml` auto-merges patch/minor and
  Actions PRs once CI is green — requires "Allow auto-merge" plus branch
  protection on the CI check; delete the file to review every dependency PR
  by hand.

### Configuration

- `loxone.miniserver.app.id` and `app.info` are now overridable per
  deployment in the `prod` profile via `LOXONE_MINISERVER_APP_ID` /
  `LOXONE_MINISERVER_APP_INFO`, so users set their own instance id without
  editing baked YAML. New keys documented in `.env.example`.
- Optional runtime config override: mount `./config/application.yaml` onto
  `/work/config` (Quarkus external config dir) to tweak runtime keys.

### Changed

- **Quarkus config resources renamed `.yml` → `.yaml`** — the base file,
  the three profile overlays and the test overlay. Every reference was
  updated to match: docs, Javadoc, and the operator-facing
  `UserAuthService` error message that still told the operator to edit
  `application.yml`.

### Documentation

- `docker/README.md` — registry switched to Docker Hub, direct `curl` URLs
  for the two run files, measured image sizes, and two findings from
  smoke-testing the published images: **plain-TCP broker** overrides for a
  LAN Mosquitto (`LOXONE_TRANSPORT_CONNECTION_PROTOCOL=tcp` +
  `LOXONE_TRANSPORT_CONNECTION_SECURE=false`, both runtime keys) and a
  *"container restarting in a loop?"* section explaining the fail-fast boot
  (`Boot N/3 ✗` → exit 1 → Docker restarts;
  `LOXONE_BOOT_HALT_ON_FAILURE=false` keeps it up for dashboard recovery).
- `README.md` — Docker Pulls badge; the Docker section points at Docker
  Hub. `INSTALL.md` §8 — same registry switch and measured sizes.

[Unreleased]: https://github.com/quaddan/loxmq/compare/v1.2.0...HEAD
[1.2.0]: https://github.com/quaddan/loxmq/compare/v1.1.0...v1.2.0
[1.1.0]: https://github.com/quaddan/loxmq/compare/v1.0.2...v1.1.0
[1.0.2]: https://github.com/quaddan/loxmq/compare/v1.0.1...v1.0.2
[1.0.1]: https://github.com/quaddan/loxmq/compare/v1.0.0...v1.0.1
