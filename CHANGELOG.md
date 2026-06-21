<img src="src/main/resources/META-INF/resources/images/loxmq-logo.svg" alt="loxmq">

# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog 1.1.0](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning 2.0](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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

[Unreleased]: https://github.com/quaddan/loxmq/compare/v1.0.2...HEAD
[1.0.2]: https://github.com/quaddan/loxmq/compare/v1.0.1...v1.0.2
[1.0.1]: https://github.com/quaddan/loxmq/compare/v1.0.0...v1.0.1
