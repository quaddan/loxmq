<img src="src/main/resources/META-INF/resources/images/loxmq-logo.svg" alt="loxmq">

# FAQ

Operational answers to questions that come up when hacking or
deploying this binding. Organized by task — use `Ctrl+F` on the
section title.

Related docs (to consult first before advanced sections):

- `README.md` — overview + phase status
- `ARCHITECTURE.md` — non-obvious-choice rationale + **§12 cutover plan**
- `CHANGELOG.md` — release notes (Keep a Changelog format)
- `NATIVE.md` — practical native-image guide
- `ADMIN.md` — admin surface (users · schedules · logs)
- `TESTS.md` — test layout + commands

---

## 🚀 Build & run

> Full build/run walkthrough (dev mode, fast-jar, native, profile switching)
> → **[INSTALL.md](./INSTALL.md)**. Native-image internals + GraalVM pitfalls
> → **[NATIVE.md](./NATIVE.md)**. Running the test suite → **[TESTS.md](./TESTS.md)**.

## 🔐 TLS / Certificates

> **loxmq's own** server cert (where it goes on a prod host + live renewal)
> → **[INSTALL.md §7.3](./INSTALL.md#73-tls-certificates)**. TLS design
> (server + outbound flows) → **[ARCHITECTURE.md](./ARCHITECTURE.md)** §6. A
> rejected/expired cert in production → **[RUNBOOK.md](./RUNBOOK.md)**
> (*TLS certificate rejected*). The entries below cover the **Miniserver
> side** — its cert, and the HTTPS handshake the binding makes to it.

### How to upload my wildcard cert `*.example.com` on the Miniserver?

**Loxone Config GUI** (recommended):

1. Open Loxone Config → **Settings > Network > Certificates**
2. "Upload Certificate" button
3. Provide `fullchain.pem` (cert + intermediate) + `privkey.pem`
4. Save → the Miniserver restarts its TLS listener

Manual to redo at each Let's Encrypt renewal (90 days). Not cleanly
scriptable (the admin HTTP API exists but is undocumented). **FTP does NOT
work** — the cert is not a filesystem file, it's managed internally by the
Loxone firmware. See [ARCHITECTURE.md](./ARCHITECTURE.md) §6 for context.

### Why doesn't the binding connect in HTTPS to the Miniserver?

Three classic pitfalls (check in order):

**1. HTTP vs HTTPS port on the Miniserver Gen2.** The Miniserver exposes
**two separate listeners**: `:80` (plain HTTP) and `:443` (HTTPS TLS). If you
set `port=443` but the first bootstrap call goes in HTTP, it crashes with
"no bytes" (the TLS listener closes the socket on plain HTTP). Fix:
`loxone.miniserver.connection.bootstrap-prefer-secure = true` — forces HTTPS
from the very first call.

**2. Hostname mismatch.** The cert presented by the Miniserver is typically
`*.<serial>.dyndns.loxonecloud.com` (Loxone CloudDNS). If you connect via
`miniserver.example.com` (your internal DNS), the JDK hostname-verifier
refuses with `SSLHandshakeException: No subject alternative DNS name
matching`. Temporary fix (LAN):
`loxone.miniserver.connection.tls-skip-hostname-verification = true` — the
chain stays validated against the JVM truststore, only the hostname is
skipped. Clean fix: upload your `*.example.com` cert to the Miniserver (see
above), then set the knob back to `false`.

**3. DNS pointing to the binding instead of the Miniserver.** If
`miniserver.example.com` resolves to the binding machine (instead of the
Miniserver at e.g. `192.0.2.10`), the binding talks to itself. Check with
`getent hosts miniserver.example.com`.

### Why isn't the Let's Encrypt cert considered safe in the browser?

You're probably accessing by **IP** instead of the **hostname** covered by
the cert. Let's Encrypt doesn't issue certs for IPs. The browser does strict
hostname verification → mismatch → "Not secure". Fix: use a subdomain
covered by the wildcard `*.example.com` (e.g. `binding-staging.example.com`)
with internal DNS / `/etc/hosts` pointing to the binding machine. Then set
`loxone.management.public-host = binding-staging.example.com` so the startup
log shows the right URL.

### How to check which cert the Miniserver presents?

From your binding machine:

```bash
echo | openssl s_client -connect <host>:443 -servername <host> 2>/dev/null \
    | openssl x509 -noout -subject -issuer -ext subjectAltName
```

You'll see `subject: CN=…` (the cert's common name), `issuer: …` (the CA),
and `SAN: DNS:…` (the covered hostnames). Typical Loxone Gen2 case (no custom
cert uploaded):

```
subject: CN=*.504f94aabbcc.dyndns.loxonecloud.com
issuer:  C=US, O=Let's Encrypt, CN=R13
SAN:     DNS:*.504f94aabbcc.dyndns.loxonecloud.com
```

→ The Loxone serial (`504f94aabbcc`) is in the SAN. If you access via
`miniserver.example.com` (not in the SAN), the JDK refuses → use
`tls-skip-hostname-verification=true` or upload your own cert.

## 📦 Deployment

> Deploying to a prod host (filesystem layout, certs, `/etc/loxmq/env`, the
> systemd units) → **[INSTALL.md §7](./INSTALL.md#7-deploy-to-production)**.
> Day-2 operations + the incident playbook → **[RUNBOOK.md](./RUNBOOK.md)**.
> Quick operational answers below.

### What is `loxone.app.id` and must I change it?

`loxone.miniserver.app.id` is a **per-instance hyphenated UUID**
(`8-4-4-4-12`). One value fans out into five things, which is why it must
be **unique per running instance**:

1. **MQTT topic root** — every topic the binding publishes/subscribes is
   `iot/loxmq/<app.id>/…` (the `<root>/<app.id>` prefix).
2. **MQTT client-id** — `loxone.transport.connection.client-id` is
   interpolated from it.
3. **JWT client-UUID** — sent to the Miniserver as the `{uuid}` of
   `jdev/sys/getjwt/{hash}/{user}/{permission}/{uuid}/{info}`, so the token
   is bound to this client identity.
4. **On-disk LoxAPP3 cache** — files live under `cache/<app.id>/`.
5. **Health / management `uuid`** — surfaced on the readiness probe and the
   `/api/v1` management surface.

**Yes, change it before first deployment**, and use a distinct value per
instance. Two instances sharing an `app.id` would collide on the same
retained MQTT topics *and* fight over the same Loxone token slot. Generate
one with:

```bash
uuidgen        # or: python -c 'import uuid; print(uuid.uuid4())'
```

The base `application.yaml` ships the sentinel `00000000-0000-0000-0000-000000000000`
(an obvious "not configured yet" marker); the `dev`/`staging`/`prod`
profiles ship distinct random placeholders. Set your own per profile, or
override at deploy time with the `LOXONE_MINISERVER_APP_ID` environment
variable.

### How to view the binding's retained MQTT?

Tools:

```bash
# CLI — mosquitto_sub
mosquitto_sub -h <broker> -p 1883 \
    -t 'iot/loxmq/<app-id>/#' \
    -W 5 --retained-only

# GUI — MQTT Explorer (https://mqtt-explorer.com/)
#   Connect to the broker, navigate the tree. Nodes (with
#   gray timestamp) are retained.
```

Retained topics published by the binding:

- `…/status` — `online` / `offline` (via LWT on ungraceful disconnect)
- `…/app_info` — JSON metadata binding + miniserver identity
- `…/loxapp3` — verbatim LoxAPP3.json
- `…/states/type_2/<uuid>` — each Value state retained (SINGLE mode)
- `…/states/type_3/<uuid>` — Text states retained
- `…/states/type_4/<uuid>` — DayTimer states retained

`<app-id>` matches `loxone.miniserver.app.id` of the active profile. The
values shipped in the repo are **random placeholders you are meant to
replace** (see "What is `loxone.app.id`…" above):

- dev: `7b66ce4a-c00a-453c-8da3-314e971db14d`
- staging: `de6e88bb-18fd-455e-815b-ba6585418018`
- prod: `d021b283-4f50-4822-91c6-91a9a1c83dd2`

⚠️ **DO NOT touch the prod retained MQTT without operator agreement.**
The retained namespace is consumed by Home Assistant and other
downstream tools; any hot wipe breaks downstream automations.
Two instances must never publish in parallel on the same
topic root.

### How to force a re-handshake without restarting the binding?

```bash
# Solution A — kill the token + auto reconnect
curl -X POST -k https://localhost:8443/api/v1/token/kill

# Solution B — disconnect / reconnect full session
curl -X POST -k https://localhost:8443/api/v1/disconnect
sleep 2
curl -X POST -k https://localhost:8443/api/v1/connect
```

Solution A is less invasive — keeps the WebSocket but forces a
new JWT. Solution B cuts and rebuilds everything (keyexchange → getjwt
→ LoxAPP3 → enablebinstatusupdate).

## 🛠️ Configuration

> Where the knobs are defined + the `@ConfigMapping` validation →
> **[ARCHITECTURE.md](./ARCHITECTURE.md)** §4. Which secrets never to commit +
> the env-var policy → **[SECURITY.md](./SECURITY.md)**. One recurring how-to:

### How to override a knob via env var?

SmallRye Config convention: uppercase + dashes/dots → underscores.

| Knob property                       | Env var                             |
|-------------------------------------|-------------------------------------|
| `loxone.miniserver.connection.host` | `LOXONE_MINISERVER_CONNECTION_HOST` |
| `loxone.boot.halt-on-failure`       | `LOXONE_BOOT_HALT_ON_FAILURE`       |
| `quarkus.profile`                   | `QUARKUS_PROFILE`                   |
| `quarkus.log.level`                 | `QUARKUS_LOG_LEVEL`                 |

Tests: `./mvnw quarkus:dev -Dloxone.miniserver.connection.host=192.0.2.10`

---

## 🛰️ Dashboard & SSE

### How to view the Server-Sent Events?

The endpoint: `GET /api/v1/state/stream` (text/event-stream).

```bash
# Terminal — the simplest
curl -N -k https://localhost:8443/api/v1/state/stream

# Browser DevTools → Network → /state/stream → EventStream sub-tab
# JS console (on the dashboard):
new EventSource('/api/v1/state/stream').onmessage = e => console.log(JSON.parse(e.data));
```

Three types of event:

- `session` (SessionState transitions: DISCONNECTED → CONNECTING → ... → RUNNING)
- `mqtt-connected` (broker CONNACK received)
- `mqtt-disconnected` (broker drop)

See + the javadoc of `StateStreamResource` for details.

To provoke events for testing:

```bash
curl -X POST -k https://localhost:8443/api/v1/disconnect && sleep 2
curl -X POST -k https://localhost:8443/api/v1/connect
```

### How to add an icon to a dashboard section?

Pattern. HTML layout:

```html
<h2 class="with-icon">
    <img src="/images/my-icon.png" alt="alt-text">
    <span>My title</span>
</h2>
```

The CSS `.panel h2.with-icon` (already in `dashboard.html`) handles
flex + vertical alignment + gap.

To add a new image:

1. Put the PNG in `src/main/resources/META-INF/resources/images/`
2. Quarkus exposes it automatically at `/images/my-icon.png`
3. For native-image: no hint required, the
   `META-INF/resources/` folder is auto-included

### The dashboard doesn't refresh after an action

The SSE auto-refresh assumes the browser supports
`EventSource` (all modern browsers). Check in the
JS console:

```javascript
typeof window.EventSource
// → "function"  ← OK
// → "undefined" ← browser too old
```

If OK, check that the SSE connection is open (DevTools → Network →
`/state/stream` should be in `pending`).

If blocked: the auto-refresh is suppressed if focus is in an
`<input>` or `<textarea>` — precaution against typing yank.
Click elsewhere and provoke an event.

---

## 🏠 Dashboard

### What exactly does the "Connect to Miniserver" button do?

It chains **two steps in a single action**:

1. **HTTP Bootstrap**: `jdev/cfg/apiKey` + `jdev/sys/getPublicKey`
   to fetch the Miniserver's identity and load its RSA key
   into the crypto service.
2. **Connect WebSocket + handshake**: opens the WS, performs
   keyexchange + getkey2 + getjwt, transitions to RUNNING.

If bootstrap fails, the connect step is **not** attempted — the
response indicates `step="bootstrap"`. If bootstrap succeeds but
connect fails, you see `step="connect"` + `bootstrap.status=success`
to know where the chain broke.

Endpoint: `POST /api/v1/connect-with-bootstrap`.

### Why is the "Refresh token" button grayed out?

Because there's no local token to refresh. Typical cases:

- You just booted — not connected yet → no token.
- You clicked "Disconnect Miniserver" or "Kill token" — token
  invalidated locally.
- The session is in FAILED after a failed handshake.

Click **Connect to Miniserver** first. Once the session is
RUNNING with an active token, the Refresh and Kill buttons
activate automatically (the Token panel also shows
`expiresAt` + a `VALID`/`EXPIRED` badge).

### What exactly does "Refresh token" do?

Triggers the same refresh as the automatic 24h scheduler, but
on demand. Sends `jdev/sys/refreshjwt/{hash}/{user}` (encrypted)
over the WS and waits for the async reply that updates
`expiresAt` of the token on the binding side.

Useful for:

- Force a refresh before a critical operation to ensure
  a token valid for several minutes.
- Test that the refresh works correctly after a period of
  uncertainty (post-incident debug).

Endpoint: `POST /api/v1/token/refresh`. Immediate 200 reply
("refreshjwt sent") — the new `expiresAt` appears
in the Token section after a few hundred ms (poll
`/api/v1/state` or wait for the auto re-render via SSE).

### Why 2 different REST endpoints for Kill vs Refresh?

Because they perform 2 **fundamentally different** operations:

- **Refresh** (`refreshjwt`): extends the lifetime of an existing
  token. The token stays valid during the call + the new
  `expiresAt` is further in the future. **No re-handshake**.
- **Kill** (`killtoken`): invalidates the token on the Miniserver side +
  closes the WS on the binding side. The next reconnection redoes
  the entire handshake from scratch (key-exchange + getkey2 + getjwt).

Refresh = I want to continue my session. Kill = I want to start
from zero (useful after a Miniserver password change, for
example).

### How to view the Bootstrap details?

The Bootstrap status block is **collapsed by default** in the
"Miniserver — Config" panel, indicated by an arrow `▸` next to the badge
status (SUCCESS / FAILED / IN PROGRESS / NOT STARTED).

Click on the line to expand — you see:

- `Started`: local timestamp
- `Completed`: local timestamp
- `Duration`: duration in ms
- `Last error`: error message if FAILED (red)

Re-click to collapse. It's a native HTML5 `<details>`, so
state browser-side (not synced between tabs).

### Has the dashboard changed endpoints?

**Read** side: no. All `/states`, `/schedules`,
`/users`, `/logs` pages are unchanged. The dashboard `/` itself
keeps refreshing via SSE `/api/v1/state/stream`.

**Action** side: 2 new endpoints that you
can use in CLI / scripts:

```
POST /api/v1/token/refresh
POST /api/v1/connect-with-bootstrap
```

The old ones remain in place for fine-grain needs:
`/api/v1/bootstrap`, `/api/v1/connect`, `/api/v1/disconnect`,
`/api/v1/reconnect`, `/api/v1/token/kill`,
`/api/v1/transport/{connect,disconnect}`.

---

## 👥 Admin UI — Users · Schedules · Logs

> Reading the `State`/`Validity` chips, group membership, the `/logs` level
> filter + smart auto-scroll, and auto-refresh now have a dedicated guide →
> **[ADMIN.md §3](./ADMIN.md#3-operator-guide--the-pages)**.

## 🐞 Troubleshooting

### `Port 8080 seems to be in use by another process`

If you just Ctrl+C'd a `mvn quarkus:dev`: that's dev-mode JVM
idle after the application stopped. The
process still holds the port.

```bash
# Identify the PID
sudo ss -tlnp | grep :8080
# Or
lsof -i :8080

# Kill it
kill <PID>
```

### `Boot 2/3 ✗ Bootstrap failed — HTTP call failed — HTTP connect timed out`

The Miniserver IP is unreachable. Test:

```bash
curl -sv http://<host>:<port>/jdev/cfg/apiKey | head -5
```

If also timeout → network problem / wrong IP / firewall. With
`halt-on-failure=true` (default), the binding exits with
code 1 → systemd `Restart=on-failure` 3 times then gives up.

### `Bootstrap failed — header parser received no bytes`

You're doing plain HTTP on a TLS-only listener. Either:

- Port mismatch (set `port=80` instead of `443` for plain HTTP)
- Or `bootstrap-prefer-secure=true` to force HTTPS

See "Why doesn't the binding connect in HTTPS" question
above.

### `MQTT disconnected ... CONNACK contained an Error Code: NOT_AUTHORIZED`

The broker rejects the creds. Diagnose:

```bash
# 1. Decode what the binding sends
echo "<base64-from-config>" | base64 -d

# 2. Check the user exists on the broker side
ssh broker 'sudo grep -E "^<user>:" /etc/mosquitto/passwd'

# 3. Direct mosquitto_pub test
mosquitto_pub -h <broker> -p 8083 -L "ws://<broker>:8083/mqtt" \
    -u "<user>" -P "<pass>" -t test -m hello -d
```

Three typical pitfalls:

- The `loxmq_dev` user (default config) doesn't exist on the prod broker
- The clientId conflicts with another MQTT client connected on the same app-id
- Mosquitto ACL restricts by pattern

### MQTT won't connect / drops right after CONNACK — is the broker really MQTT v5?

loxmq connects with an **MQTT v5** client (HiveMQ) and does **not** fall
back to MQTT 3.1 / 3.1.1. Against a v3-only broker — or a v5 broker with v5
disabled or forced into v3 compatibility — the CONNECT is rejected or the
session is dropped right after CONNACK (often an
`UNSUPPORTED_PROTOCOL_VERSION` reason code, sometimes just an opaque drop).

Before chasing credentials or ACLs, confirm the broker speaks MQTT v5:

- **FlashMQ** — the reference broker loxmq is built and tested for; v5 on by default.
- **Mosquitto** ≥ 2.0 — supports v5; make sure you are **not** pinning
  `protocol_version mqttv311` in `mosquitto.conf`.
- **EMQX / HiveMQ** — MQTT v5 enabled by default.

Quick check with a v5-capable client:

```bash
# -V 5 forces MQTT v5. A v3-only broker errors out; a v5 broker accepts.
mosquitto_pub -V 5 -h <broker> -p 1883 -t test -m hello -d
```

If `-V 5` fails but `-V 311` works, the broker isn't serving MQTT v5 —
switch to FlashMQ or enable v5 on your current broker.

### `SSLHandshakeException: No subject alternative DNS name matching ...`

Hostname mismatch between the URL and the cert. See "Why doesn't the
binding connect in HTTPS to the Miniserver" question above.

### The binding exit(1)s but dev-mode JVM doesn't die

This is expected in dev. See — `Quarkus.asyncExit(int)` in
`LaunchMode.DEVELOPMENT` only kills the application, not the dev-mode JVM
(to allow hot-reload). The WARN instead of ERROR misleading message says so.

To test the real exit(1):

```bash
./mvnw -DskipTests package
java -Dquarkus.profile=dev -jar target/quarkus-app/quarkus-run.jar
echo $?
# → 1
```

### `Reindexing quarkus-hivemq-client-2.5.0.jar` at build

Upstream issue on the Quarkiverse side — the JAR embeds a Jandex v10
(Jandex 2.x) index instead of v11+ (Jandex 3.x). Cosmetic, auto
re-indexing at build (~150 ms). Documented in `pom.xml`.

Will disappear as soon as a re-indexed 2.6.0+ version is released on Maven Central.

### `Unrecognized configuration key "quarkus.dev-ui.always-include"`

Not in progress. The property was removed — no longer exists
in Quarkus 3.36.1. If you see it, it's an out-of-date
config file.

### The browser says "Not secure" despite a valid Let's Encrypt cert

You access by IP instead of a hostname covered by the cert. Let's
Encrypt doesn't issue certs for IPs. See dedicated question above.

---

## 🧪 Tests

> The two-tier strategy (unit + integration), the fake resources, and how to
> add a test → **[TESTS.md](./TESTS.md)**. The one-line commands are in
> **[INSTALL.md](./INSTALL.md)** and `CONTRIBUTING.md`.

## 📁 Repo layout

```
loxmq/        # repo dir on disk (Maven artifactId = loxmq, app = loxmq)
├── pom.xml                             — Maven build + plugins
├── ARCHITECTURE.md, CHANGELOG.md, …    — docs
├── docker/                             — Dockerfiles (JVM + native)
├── src/main/
│   ├── java/com/quaddan/iot/loxmq/
│   │   ├── boot/                       — Application, BootAutoStarter
│   │   ├── config/                     — LoxoneConfig (@ConfigMapping)
│   │   ├── health/                     — MicroProfile liveness + readiness checks
│   │   ├── management/                 — REST API + Qute pages + SSE
│   │   ├── miniserver/                 — connection + session + crypto + bootstrap
│   │   ├── transport/                  — MQTT (HiveMQ client + scheduler)
│   │   └── util/                       — logging, Qute templates
│   └── resources/
│       ├── application*.yml                 — base + dev/staging/prod
│       ├── META-INF/resources/         — static files (JS + images)
│       └── templates/<Resource>/*.html  — Qute pages (dashboard, states, schedules, users, logs)
└── src/test/                           — unit + IT
```

---

## 📞 Going further

- Bug or Loxone protocol question → consult the Loxone V17.0 spec PDFs
  (vendor-copyrighted, not redistributed here — obtain them from Loxone)
- Code bug → open a GitHub issue, SemVer tag `v<MAJOR>.<MINOR>.<RELEASE>`
- Ops/deployment question → **[RUNBOOK.md](./RUNBOOK.md)** (the systemd units
  ship under `service/`; the LXC bootstrap scripts stay operator-side)
- Future evolutions / feature ideas → open a GitHub issue

*Doc created. To enrich as recurring operator questions come up.*
