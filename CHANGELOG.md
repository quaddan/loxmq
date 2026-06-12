<img src="src/main/resources/META-INF/resources/images/loxmq-logo.svg" alt="loxmq">

# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog 1.1.0](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning 2.0](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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

[Unreleased]: https://github.com/quaddan/loxmq/compare/v1.0.1...HEAD
[1.0.1]: https://github.com/quaddan/loxmq/compare/v1.0.0...v1.0.1
