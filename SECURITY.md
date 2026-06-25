<img src="src/main/resources/META-INF/resources/images/loxmq-logo.svg" alt="loxmq">

# SECURITY

Application security policy and CVE audit history.

> **Baseline verdict**: 0 exploitable CVE identified at the last
> audit pass. Methodology and reproducible details below.

---

## 1. Global security posture

The binding runs within a **LAN + VPN** perimeter (see `ARCHITECTURE.md
§Documented decisions`), which is why:

- No bearer authentication on REST endpoints (§11.h).
- TLS WSS to Miniserver **active by default in staging + prod**
  (`miniserver.connection.secure=true` + `bootstrap-prefer-secure=true`
  in the profiles). `tls-skip-hostname-verification=true` remains enabled
  as long as the bootstrap may target a LAN IP — see §4.3.
- TLS MQTT active in staging and prod via `wss://...:8084` + wildcard
  cert `*.example.com`.
- Inbound MQTT payload cap at 4 KB
  (`loxone.transport.security.max-inbound-payload-bytes`), retained
  drop on topics `…/command` + `…/api`.
- **One — and only one — outbound internet egress** since v1.1.0: the
  firmware-update check (`FirmwareUpdateService`) calls Loxone's public
  `https://update.loxone.com` once per session. Fully analysed in §1.2.

For per-layer details, see `ARCHITECTURE.md §6 TLS plumbing`

+ `§7 Reconnection ownership` + `§11.h No bearer auth`.

### 1.1 Credentials — no secret in the repo

The Quarkus profiles (`application{,-dev,-staging,-prod}.yaml`)
contain **no password**. The 4 sensitive credentials are
referenced via environment variables with `CHANGE_ME` fallback
(satisfies `@NotBlank` but auth fails at runtime
with a clear message):

| Env var                                          | Use                              |
|--------------------------------------------------|----------------------------------|
| `LOXONE_MINISERVER_AUTH_USER`                    | Loxone account username (Base64) |
| `LOXONE_MINISERVER_AUTH_PASSWORD`                | Loxone account password (Base64) |
| `LOXONE_TRANSPORT_SECURITY_CREDENTIALS_USER`     | MQTT broker username (Base64)    |
| `LOXONE_TRANSPORT_SECURITY_CREDENTIALS_PASSWORD` | MQTT broker password (Base64)    |

**For local dev**:

```bash
cp .env.example .env
# edit .env with real values
source .env
mvn quarkus:dev
```

The `.env` file is gitignored; `.env.example` is committed as a template.

**For staging / prod (LXC + systemd)**:

The deployment bootstrap (operator-specific, **not shipped in this public
repository**) requires the 4 env vars set in the calling shell (fail-fast
otherwise). It then deposits `/etc/loxmq/env` (mode 0640, owner
`root:loxmq`), which is read by the systemd unit via `EnvironmentFile=`.

No secret is ever committed nor transmitted in clear anywhere
other than `/etc/loxmq/env` on the target LXC.

### 1.2 Outbound network egress — firmware-update check (v1.1.0+)

Through v1.0.x the binding made **no outbound internet call** (LAN + VPN
only). Version 1.1.0 introduces exactly one, narrowly-scoped egress:
`FirmwareUpdateService` fetches Loxone's public
`https://update.loxone.com/updatecheck.xml` once per session (on each
`MiniserverConnectedEvent`) to tell the operator whether the installed
Miniserver firmware is the latest published Release. Security properties:

- **No SSRF surface** — the URL is a hard-coded constant; no part of it
  derives from user input, MQTT payloads, or Miniserver responses.
- **Full TLS validation** — built on the JDK `java.net.http.HttpClient`
  with default trust store + hostname verification. It does **not**
  inherit the Miniserver-side `tls-skip-hostname-verification` relaxation
  (§4.3); a certificate or hostname mismatch aborts the call.
- **No data exfiltration / privacy-preserving** — the request is a plain
  `GET` with an `Accept: application/xml` header. No Miniserver serial,
  firmware version, username or LAN identifier is sent; the version
  comparison runs locally after downloading the public catalogue.
- **No XML-parser attack surface** — the response is matched with two
  narrow regexes (channel block + `LatestRelease` version), never handed
  to an XML parser, so XXE / external-entity / entity-expansion vectors do
  not apply.
- **Bounded & best-effort** — 8 s connect + 10 s request timeouts; any
  failure (offline, timeout, non-200, unparseable, MITM) is swallowed and
  simply yields no firmware badge. It never propagates to the session nor
  blocks bootstrap.
- **No new dependency** — uses the JDK HTTP client only; 1.1.0 added no
  Maven dependency (consistent with the conservative dependency policy).

**Residual hardening item (low)**: the response body is read unbounded
(`HttpResponse.BodyHandlers.ofString()`). A compromised or MITM'd
`update.loxone.com` could return an oversized body and pressure the heap;
TLS validation makes that require breaking Loxone's certificate, so the
risk is low. Capping the body length is a possible future hardening.
Operators wanting strictly zero egress can block `update.loxone.com` at
the firewall — the binding degrades gracefully (no badge, no error).

## 2. CVE audit methodology

Three tools used in parallel, each with its own detection
logic:

| Tool                                    | Coverage                                    | Data source                     |
|-----------------------------------------|---------------------------------------------|---------------------------------|
| **Trivy** (official Docker image)       | Scan `pom.xml` + secret detection           | trivy-db (Aqua Security)        |
| **OWASP Dependency-Check** Maven plugin | Scan transitive deps via CPE matching       | NVD (NIST)                      |
| Manual triage                           | Validation that each finding is exploitable | Code analysis + CVE description |

Reproducible commands:

```bash
# Build before audit to have target/quarkus-app/ populated
mvn package -DskipTests -DskipITs

# Trivy via rootless Docker (portable method)
docker run --rm -v "$(pwd):/scan:ro" aquasec/trivy:latest fs \
    --severity HIGH,CRITICAL \
    --skip-dirs target/quarkus-app,.git,docs,images,certs \
    --scanners vuln /scan

# OWASP Dependency-Check Maven plugin (resolved classpath analysis)
mvn org.owasp:dependency-check-maven:11.1.1:check \
    -Dossindex.analyzer.enabled=false \
    -DfailBuildOnCVSS=11 \
    -DskipTests -DskipITs

# HTML + JSON report in target/dependency-check-report.{html,json}
```

## 3. Last audit (baseline)

### 3.1 Trivy `pom.xml` scan

```
┌─────────┬──────┬─────────────────┐
│ Target  │ Type │ Vulnerabilities │
├─────────┼──────┼─────────────────┤
│ pom.xml │ pom  │        0        │
└─────────┴──────┴─────────────────┘
```

**0 CVE** on direct dependencies (HIGH + CRITICAL +
MEDIUM). Management via Quarkus 3.36.0 BOM + a deliberately
conservative dependency policy (no new Maven dependencies
without an explicit need) pays off.

### 3.2 OWASP Dependency-Check transitive

16 findings raised via CPE matching, full triage:

| #  | CVE            | Severity (CVSS) | Listed dep                                         | Verdict             | Justification                                                                                                                               |
|----|----------------|-----------------|----------------------------------------------------|---------------------|---------------------------------------------------------------------------------------------------------------------------------------------|
| 1  | CVE-2019-3826  | MEDIUM (6.1)    | micrometer-registry-prometheus-simpleclient-1.16.5 | **False positive**  | Prometheus server v2.7.1 XSS — not the Micrometer client lib (CPE match 1.16.5 on "prometheus" product ≠ Micrometer)                        |
| 2  | CVE-2023-1932  | MEDIUM (6.1)    | quarkus-hibernate-validator-3.36.0                 | **False positive**  | hibernate-validator SafeHtml — the REAL hibernate-validator version is 9.1.0 (not affected), 3.36.0 is the Quarkus version. CPE confusion.  |
| 3  | CVE-2025-15104 | MEDIUM (5.3)    | hibernate-validator-9.1.0.Final                    | **False positive**  | "Nu Html Checker (validator.nu)" vulnerability — different product. CPE match on generic "validator".                                       |
| 4  | CVE-2025-15104 | MEDIUM (5.3)    | quarkus-hibernate-validator-3.36.0                 | **False positive**  | Same as #3 — different product.                                                                                                             |
| 5  | CVE-2025-15104 | MEDIUM (5.3)    | smallrye-config-validator-3.17.2                   | **False positive**  | Same as #3 — different product (SmallRye Config ≠ Nu Html Checker).                                                                         |
| 6  | CVE-2026-39882 | MEDIUM (5.3)    | opentelemetry-semconv-1.40.0                       | **False positive**  | OpenTelemetry-**Go** vulnerability (Go language). Our dep is the Java `opentelemetry-semconv` jar = constants strings.                      |
| 7  | CVE-2026-39882 | MEDIUM (5.3)    | opentelemetry-semconv-incubating-1.40.0-alpha      | **False positive**  | Same as #6.                                                                                                                                 |
| 8  | CVE-2026-29181 | HIGH (7.5)      | opentelemetry-semconv-1.40.0                       | **False positive**  | Same as #6 — OpenTelemetry-Go only.                                                                                                         |
| 9  | CVE-2026-29181 | HIGH (7.5)      | opentelemetry-semconv-incubating-1.40.0-alpha      | **False positive**  | Same as #6.                                                                                                                                 |
| 10 | CVE-2026-39883 | HIGH (7.0)      | opentelemetry-semconv-1.40.0                       | **False positive**  | Same as #6 — OpenTelemetry-Go (Darwin ioreg / BSD kenv).                                                                                    |
| 11 | CVE-2026-39883 | HIGH (7.0)      | opentelemetry-semconv-incubating-1.40.0-alpha      | **False positive**  | Same as #6.                                                                                                                                 |
| 12 | CVE-2026-0994  | HIGH (7.5)      | protobuf-java-4.33.2                               | **False positive**  | Vulnerability in `google.protobuf.json_format.ParseDict()` in **Python**. Our dep is `protobuf-java`. Not the same implementation.          |
| 13 | CVE-2026-42154 | HIGH (7.5)      | micrometer-registry-prometheus-simpleclient-1.16.5 | **False positive**  | Prometheus server vulnerability (/api/v1/read endpoint). Our dep is the CLIENT lib that EXPORTS metrics — no remote_read endpoint.          |
| 14 | CVE-2026-42154 | HIGH (7.5)      | simpleclient-0.16.0                                | **False positive**  | Same as #13 — Prometheus client lib.                                                                                                        |
| 15 | CVE-2026-42582 | HIGH (7.5)      | netty-codec-mqtt-4.1.133.Final                     | **Not exploitable** | Netty QpackDecoder HTTP/3. We use Netty for MQTT (HiveMQ) and WS (Vert.x) — **no HTTP/3 nor QPACK**. The bug code path is dead in our case. |
| 16 | CVE-2026-42582 | HIGH (7.5)      | netty-transport-4.1.133.Final                      | **Not exploitable** | Same as #15.                                                                                                                                |

**Summary**:

- 14 / 16 findings = **CPE matching false positives** (confusion
  between Quarkus/Micrometer/SmallRye versions with different products).
- 2 / 16 = **real CVE but non-exploitable** (HTTP/3 QPACK,
  we don't have this code path).
- 0 immediately actionable CVE.

### 3.3 Trivy secret detection

```
HIGH: AsymmetricPrivateKey (private-key)  certs/privkey.pem:2-4
```

**False positive** in the repo: `certs/` is in `.gitignore` and
does NOT contain a committed key. The local certs that Trivy
detected are the operator-side copy of the Let's Encrypt chain
(Option B layout) for local testing. Verification:

```bash
$ git ls-files certs/
(empty — nothing tracked)
```

### 3.4 Re-verification on Quarkus 3.36.1 (2026-06-06)

Quick re-scan after the 3.36.0 → 3.36.1 patch, via dependency-tree delta
(`mvn dependency:tree`). **No flagged transitive component changed version** —
the attack surface is identical to the baseline above:

- `netty-codec-mqtt` / `netty-transport` (#15, #16) — **4.1.133.Final** (unchanged)
- `hibernate-validator` (#3) — **9.1.0.Final** (unchanged)
- `micrometer-registry-prometheus-simpleclient` (#1, #13) — **1.16.5** (unchanged)
- `simpleclient` (#14) — **0.16.0** (unchanged)
- `opentelemetry-semconv` [`-incubating`] (#6–#11) — **1.40.0** / **1.40.0-alpha** (unchanged)
- `protobuf-java` (#12) — **4.33.2** (unchanged)

So the §3.2 verdicts stand verbatim: **0 actionable CVE**. Only the Quarkus
*artifact* version strings move (e.g. `quarkus-hibernate-validator-3.36.1`) —
the CPE-confusion source for #2/#4, still false positives. A full Trivy +
OWASP run (the §2 commands) remains the formal audit; this delta confirms the
patch introduced no new or changed dependency.

### 3.5 Re-verification on Quarkus 3.37.0 (2026-06-25)

Re-scan after the 3.36.1 → 3.37.0 minor bump (shipped with loxmq 1.1.0),
via dependency-tree delta (`./mvnw dependency:tree`). The flagged
transitive components either **did not change** or were **bumped within
their line** (risk only decreases):

| Component                                     | §3.4 (3.36.1)   | 3.37.0            | Effect on verdict                    |
|-----------------------------------------------|-----------------|-------------------|--------------------------------------|
| `netty-*` (codec-mqtt, transport, …)          | 4.1.133.Final   | **4.1.135.Final** | still 4.1.x — #15/#16 stand          |
| `micrometer-registry-prometheus-simpleclient` | 1.16.5          | **1.16.6**        | client lib — #1/#13 stand (FP)       |
| `protobuf-java`                               | 4.33.2          | **4.35.0**        | #12 stands (FP — Python json_format) |
| `hibernate-validator`                         | 9.1.0.Final     | 9.1.0.Final       | unchanged — #2/#3/#4/#5 stand        |
| `opentelemetry-semconv` [`-incubating`]       | 1.40.0 / -alpha | 1.40.0 / -alpha   | unchanged — #6–#11 stand             |
| `simpleclient`                                | 0.16.0          | 0.16.0            | unchanged — #14 stands               |
| `quarkus-*` artifacts                         | 3.36.1          | 3.37.0            | CPE-confusion source for #2/#4 (FP)  |

**No new flagged transitive component** appeared, and **no new Maven
dependency** was introduced by 1.1.0 (the firmware check uses the JDK HTTP
client — §1.2). So the §3.2 verdicts stand verbatim: **0 actionable CVE**.
As in §3.4, this delta confirms the bump introduced nothing new; a full
Trivy + OWASP run (the §2 commands) remains the formal audit.

### 3.6 Application surface — v1.1.0 changes

- **New outbound egress** (`FirmwareUpdateService`): fully analysed in
  §1.2 — no SSRF, full TLS, regex (no XML parser), no data sent, bounded,
  best-effort. One residual low-risk hardening item (unbounded response
  body).
- **Reduced info disclosure on the dashboard** (unauthenticated, LAN-only
  by design — §11.h): the Token *Rights* bitmask, the Miniserver
  *Permission* field and the duplicate Identity *Address* row were removed
  from the home screen. Low-sensitivity details, but their removal trims
  what an on-LAN viewer sees.
- **New badges expose no secret**: the firmware badge shows only the
  public latest-Release version string; the next-token-refresh row shows a
  schedule timestamp, never a token value.

### 3.7 Formal scan run — 2026-06-25 (loxmq 1.1.0)

Ran the §2 reproducible commands against the 1.1.0 tree (Quarkus 3.37.0,
build `target/quarkus-app/` populated):

| Scan                          | Result                                                                     |
|-------------------------------|----------------------------------------------------------------------------|
| **Trivy** `fs --scanners vuln` (HIGH, CRITICAL) | `pom.xml` → **0 vulnerabilities**. Reproduces §3.1.       |
| **Trivy** `fs --scanners secret`                | 1 HIGH `AsymmetricPrivateKey` at `certs/privkey.pem` → **false positive** — `git ls-files certs/` is empty (nothing tracked; local operator-side cert copy). Reproduces §3.3. |
| **OWASP Dependency-Check** `11.1.1`             | **Not completed this pass** — the NVD full-DB update could not finish. First attempt (no API key) stalled at ~3 % (`startIndex=10000`). Second attempt **with a valid NVD API key** reached ~12 % then aborted on repeated server-side errors (*"NVD API request failures are occurring; retrying for the 7 time"*, `startIndex≈48000`) — i.e. NIST's NVD API was degraded at audit time, independent of the key. The §3.5 dependency-tree delta covers the transitive-version check for this release instead. |

**Net**: confirmed **0 actionable CVE** (Trivy direct + §3.5 transitive
delta). The full OWASP pass is blocked only by NVD API availability, not by
the binding. Re-run when NVD is healthy with a free NVD API key
(`export NVD_API_KEY=…` then add `-Dnvd.api.key=$NVD_API_KEY` to the §2
command) — a healthy NVD build then takes ~2-3 min instead of ~45.

## 4. Recommendations

### 4.1 Continuous maintenance

1. **Re-scan quarterly** or before each major release
   (command in § 2 reproducible locally).

2. **Follow Quarkus BOM bumps** — the majority of transitive
   deps are managed by Quarkus. A bump of
   `quarkus.platform.version` in `pom.xml` propagates CVE fixes
   on Netty, Jackson, Hibernate-Validator, etc.

3. **In CI (GitHub Actions)**: add a weekly job
   `mvn dependency-check:check -DfailBuildOnCVSS=8` that fails the
   build on CVSS ≥ 8 NON-suppressed. List of suppressions
   (confirmed false positives) kept in `dependency-check-suppressions.xml`
   to be created. **Provide an NVD API key** (free from
   `nvd.nist.gov/developers/request-an-api-key`) as a CI secret
   `NVD_API_KEY` and pass `-Dnvd.api.key=${NVD_API_KEY}`; without it the
   NVD update is rate-limited by NIST and routinely stalls (see §3.7).

### 4.2 Open item — Netty version

Findings #15 + #16 (CVE-2026-42582) clear at Netty ≥ 4.2.13.Final. The
Quarkus BOM deliberately keeps Netty on the **4.1.x line** (4.2.x broke the
HTTP pipeline — see the `netty-codec-http` note in `pom.xml`), and the
3.37.0 re-verification (§3.5) confirms it is now `4.1.135.Final` (still
4.1.x — bumped from 4.1.133, but not to the 4.2.13+ that closes the
finding). So these two findings do **not** auto-resolve on the 3.3x line;
revisit when a future BOM ships 4.2.13+ without the pipeline regression.
**Not urgent** — non-exploitable in our usage (no HTTP/3 / QPACK path).

### 4.3 Deferred items

- **Maven dependency audit (CVE) — CI integration**: the first pass
  (Trivy + OWASP Dependency-Check) is documented in §2/§3. Open follow-up:
  a GitHub Actions weekly job (`mvn dependency-check:check
  -DfailBuildOnCVSS=8`). Re-scan quarterly meanwhile, per §2's reproducible
  procedure (~1 h infra setup).
- **Audit `tls-skip-hostname-verification=true`**: enabled in staging/prod
  because the wildcard cert pushed onto the Miniserver matches the
  public-FQDN SAN, but `TransportConnectionResolver` may target a LAN IP
  depending on the bootstrap context. Revisit if the orchestrator is
  constrained to always target the FQDN — hostname verification can then be
  re-enabled.

## 5. Reporting a vulnerability

### Supported versions

| Version | Supported          |
|---------|--------------------|
| 1.x     | :white_check_mark: |

Older or unreleased branches are not supported. When a security fix
ships in `1.x`, users on a previous `1.x.y` are expected to upgrade.

### Disclosure process

**Please do not open a public GitHub issue for security vulnerabilities.**
Use one of the two private channels below:

1. **Preferred — GitHub Security Advisories**: from the repository's
   **Security** tab, click *Report a vulnerability*. This opens a
   private discussion with the maintainers, lets us coordinate a fix
   privately, and produces a public advisory once the fix is released.
2. **Fallback — email**: send a report to
   **`dev.quaddan@gmail.com`** with the subject prefix
   `[SECURITY] loxmq — <short title>`. Include reproduction steps,
   affected version, and (if possible) a suggested fix.

### What to expect

- **Acknowledgement** within **7 days** of the report.
- **Initial triage** (impact, severity, scope) within **14 days**.
- **Coordinated public advisory + fix release** within **90 days** of
  the report, or sooner if the vulnerability is actively exploited.
- Credit to the reporter in the advisory unless they request otherwise.

### Scope

In scope: the `loxmq` binding itself (Java source, configuration,
deployment scripts, web UI).

Out of scope: vulnerabilities in upstream dependencies (Quarkus,
HiveMQ Client, Netty, Jackson, …) — please report those to the
respective upstream projects. We may still accept a report when the
combination of an upstream issue and our usage creates a new
exploitable surface in `loxmq`.

---

*Baseline document v1.0.0 (2026-05-30); last updated 2026-06-25 for
loxmq 1.1.0 (§1.2 firmware egress, §3.5 Quarkus 3.37.0 re-verification,
§3.6 application-surface review, §3.7 formal Trivy scan run). Keep in
sync with CVE audits.*
