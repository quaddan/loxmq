<img src="src/main/resources/META-INF/resources/images/loxmq-logo.svg" alt="loxmq">

# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog 1.1.0](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning 2.0](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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

[Unreleased]: https://github.com/quaddan/loxmq/compare/v1.1.0...HEAD
[1.1.0]: https://github.com/quaddan/loxmq/compare/v1.0.2...v1.1.0
[1.0.2]: https://github.com/quaddan/loxmq/compare/v1.0.1...v1.0.2
[1.0.1]: https://github.com/quaddan/loxmq/compare/v1.0.0...v1.0.1
