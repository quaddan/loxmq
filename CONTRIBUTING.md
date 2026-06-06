<img src="src/main/resources/META-INF/resources/images/loxmq-logo.svg" alt="loxmq">

# Contributing to loxmq

Thanks for considering a contribution to **loxmq** — the Loxone
Miniserver ↔ MQTT v5 binding. This guide describes how to set up your
environment, run the test suite, and submit a pull request.

## Prerequisites

- **JDK 25 LTS** (mandatory — the pom is pinned to
  `maven.compiler.release=25`; native build via Mandrel does not support
  bytecode v70+ JDK 26).
- **Maven 3.9+**.
- **Docker** — required for:
    - Integration tests (Mosquitto broker via Testcontainers).
    - Native binary build (GraalVM Mandrel container).
- Recommended: an IDE that understands Quarkus (IntelliJ IDEA, VS Code +
  Quarkus extension).

## Building

```bash
# Compile + package the JAR without running tests
./mvnw -DskipTests package

# Hot-reload dev mode (live coding, automatic reload on file changes)
./mvnw quarkus:dev
```

`./mvnw quarkus:dev` exposes the dashboard on `http://localhost:8080/` and
the dev UI on `http://localhost:8080/q/dev/`.

## Running tests

Three test scopes, ordered by runtime cost:

```bash
# Unit tests (in-JVM, ~10 s)
./mvnw test

# Integration tests against the packaged JAR + Mosquitto Testcontainer (~2 min)
./mvnw verify -Pintegration

# Native binary IT — packaged native executable + Mosquitto Testcontainer (~10 min)
./mvnw verify -Pnative,integration
```

> **Note:** if you keep a `.env` in the project root, move it aside before
> running tests — Quarkus auto-loads it and its `LOXONE_*` keys override the
> test fixtures, so one `LoxoneConfigInjectionTest` assertion fails (it is not
> a real failure; CI has no `.env`). Quick form:
> `mv .env .env.disabled && ./mvnw test ; mv .env.disabled .env`. Details in
> [TESTS.md](./TESTS.md) (TL;DR).

The native IT requires Docker to be running (the Mandrel builder image
is pulled on first run). See `NATIVE.md` for the full native build
notes.

## Code conventions

The repository follows a consistent set of code conventions. The
short form:

- **Allman braces** with spaces inside parens
  (`if ( foo )`, `f( x )`). Non-standard but consistent across the
  repo — Spotless is lint-only, no full reformat.
- **CDI** idiomatic: `@Inject` directly; in particular
  `@Inject Event<ExceptionEvent> exceptionEvent` then
  `exceptionEvent.fire(...)`.
- **Loggers**: `Logger.getLogger( MyClass.class )` (JBoss
  LogManager), not `getSimpleName()`, no utility `Log` class.
- **Hex**: `java.util.HexFormat` via a `private static final
  HexFormat HEX = HexFormat.of();` constant.
- **Scheduling**: `ScheduledExecutorService` (no `Timer`, no
  `Thread.sleep` loop).
- **Charset**: always explicit (`StandardCharsets.UTF_8`).
- **Records** for immutable DTOs.
- **No `System.gc()`**, no lazy non-thread-safe singletons.
- **Native image** discipline: any class manually serialized via
  Jackson outside a typed REST return must be listed in
  `NativeReflectionConfig.@RegisterForReflection.targets` and
  exercised by an IT against the native binary. See the "golden
  rule" section in `NATIVE.md`.

For touching the Loxone protocol layer (`miniserver/`,
`miniserver/message/`), cite the precise section of the Loxone V17.0 spec
PDFs (vendor-copyrighted, not redistributed in this repository) in the
commit message. Never invent field names, commands, header identifiers,
or permission bits: if a value is not in the spec PDFs or the existing
code, say so rather than guessing.

## Editing UI translations (i18n)

All dashboard strings live in **one file**:
`src/main/resources/META-INF/resources/js/i18n.js`, in the `I18N` dictionary
(`fr` / `en` / `de` blocks, dot-notation keys). There is **no external
locale file** (`.properties` / `.json`).

- **Change a label** — edit its value in the relevant language block.
- **Add a string** — add the key to **all three** blocks, then reference it
  with `data-i18n="key"` in the Qute template (or `t('key')` in JS).
- **Add a language** — add its code to `SUPPORTED`, a full `<code>: { … }`
  block, and a `lang.<code>` label; the header switcher is built from
  `SUPPORTED`.

**Picking up the change:**

- **Dev mode** (`./mvnw quarkus:dev`) — live: just **refresh the browser**
  (hard refresh, Ctrl/Cmd-Shift-R, to bust the cache). No app restart.
- **Packaged (fast-jar or native)** — the file is baked into the artifact at
  build time, so **rebuild + redeploy** (a plain service restart will *not*
  pick it up), then hard-refresh the browser.

Scope: only the web UI is translated — Miniserver runtime data and
server-side output (logs, REST, errors) stay as-is (see the README).

## Pull request workflow

1. **Fork** the repository and create a feature branch off `main`:
   ```bash
   git checkout -b feat/short-description main
   ```
2. **Keep the PR focused** — one logical change per PR. Split unrelated
   refactors into separate PRs.
3. **Add or extend tests** for any new behaviour. New native-facing
   code must include a native IT (see `TESTS.md`).
4. **Mark `WIP:`** at the start of the PR title while the change is in
   progress; drop it when ready for review.
5. **Run the full test suite locally** before requesting review:
   ```bash
   ./mvnw verify -Pintegration
   ```
6. The PR template at `.github/PULL_REQUEST_TEMPLATE.md` will guide
   the description — fill in the summary, type of change, checklist,
   and related issue.

## Reference documents

- **`ARCHITECTURE.md`** — design rationale, documented decisions, and
  consolidated pitfalls.
- **`TESTS.md`** — test strategy: unit / IT / native parity.
- **`SECURITY.md`** — vulnerability disclosure process and CVE audit
  methodology.
- **`NATIVE.md`** — native GraalVM Mandrel build details.
- **`RUNBOOK.md`** — operator diagnosis by symptom.

## Code of conduct

This project adheres to the
[Contributor Covenant v2.1](CODE_OF_CONDUCT.md). By participating you
agree to uphold its terms. Report unacceptable behaviour to
`dev.quaddan@gmail.com`.

## License

By contributing you agree that your contributions will be licensed
under the [Apache License 2.0](LICENSE).
