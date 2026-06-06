<img src="src/main/resources/META-INF/resources/images/loxmq-logo.svg" alt="loxmq">

# RUNBOOK

Operator guide **"something is off in prod, where do I start"**.
Complementary to:

- `FAQ.md` — "how to do X" (build, test, TLS, MQTT format)
- `ARCHITECTURE.md` — internal design / "why"

This document = **diagnosis + remediation** only. No setup procedures, no design.

---

## Environments

| Env         | Dashboard URL                             | LXC IP       | systemd service        |
|-------------|-------------------------------------------|--------------|------------------------|
| **prod**    | `https://loxmq.example.com:8443/`         | `192.0.2.10` | `loxmq-native.service` |
| **staging** | `https://loxmq-staging.example.com:8443/` | `192.0.2.20` | `loxmq-native.service` |

Backends both speak to:

- Miniserver: `miniserver.example.com:443` (UUID `d021b283-…`, fw 17.0.3.31, GEN2)
- MQTT broker: `broker.example.com:8084` (wss `/mqtt`, MQTT v5, retained on `/states/*`)

---

## 1. Diagnosis in 30 seconds

To be run **in this order** as soon as something is off. Each command answers
"yes it's good" or points to the next step.

```bash
# On the relevant LXC:
ssh root@192.0.2.10     # or 192.0.2.20

# 1. systemd service up + recent (not in a crash loop)?
systemctl status loxmq-native --no-pager

# 2. HTTP readiness — does the app consider itself OK?
curl -fsS http://localhost:8080/q/health/ready | jq

# 3. Full snapshot of the application state —
#    session.state should be "RUNNING", broker.connected = true.
curl -fsS http://localhost:8080/api/v1/state | jq '{session, broker, bootstrap}'

# 4. MQTT publishes counter —
#    should grow linearly (~5 events/s in steady state).
curl -fsS http://localhost:8080/q/metrics | grep '^binding_mqtt_publishes_total'
sleep 5
curl -fsS http://localhost:8080/q/metrics | grep '^binding_mqtt_publishes_total'
```

If all 4 are green → the app is fine, the problem is probably
elsewhere (DNS, MQTT broker, downstream Home Assistant). If one is red →
corresponding section below.

---

## 2. Expected healthy state — invariants

What you should see in `GET /api/v1/state` when all is well:

```json
{
    "session": {
        "state": "RUNNING",
        "token": {
            "expired": false,
            "tokenRights": 1668
        }
    },
    "broker": {
        "connected": true,
        "clientId": "<miniserver-uuid>"
    },
    "bootstrap": {
        "status": "SUCCESS",
        "completedAt": "<recent timestamp>"
    }
}
```

On normal startup, the log sequence should show:

```
Boot 1/3 ✓ MQTT connected
Boot 2/3 ✓ Miniserver bootstrap complete
Boot 3/3 ✓ Miniserver session RUNNING — binding fully up
```

in < 2 seconds (~0.8 s for the native binary).

---

## 3. Scenarios by symptom

### 3.1 `session.state != RUNNING` for more than 30 s

Symptoms: `/api/v1/state` shows `CONNECTING`, `AWAITING_*_REPLY` or
`DISCONNECTED` that doesn't progress.

**Step 1** — where does the state machine stop?

```bash
journalctl -u loxmq-native -n 100 --no-pager | \
    grep -E 'SessionTracker|SessionOrchestrator|BootstrapOrchestrator'
```

The last log `Session state: X → Y` tells you what state it's
stuck in:

| Stuck in…                          | Probable cause                                                                    | Procedure                                                                   |
|------------------------------------|-----------------------------------------------------------------------------------|-----------------------------------------------------------------------------|
| `CONNECTING`                       | TCP connection to the Miniserver impossible                                       | `getent hosts miniserver.example.com` + `nc -zv miniserver.example.com 443` |
| `AWAITING_KEY_EXCHANGE_REPLY`      | The Miniserver doesn't reply to `keyexchange` — often firmware update in progress | Wait, or restart the Miniserver                                             |
| `AWAITING_TOKEN_REPLY`             | Invalid credentials, or insufficient JWT rights                                   | Check `loxone.miniserver.security.credentials.*` in the env file            |
| `AWAITING_STRUCTURE_VERSION_REPLY` | Miniserver replies, but slowly or with an error                                   | Miniserver logs on the Loxone Config side                                   |
| `AWAITING_STATUS_UPDATE_REPLY`     | `enablebinstatusupdate` rejected — insufficient JWT rights                        | See 3.4 (token rights)                                                      |
| `OUT_OF_SERVICE`                   | Official Miniserver OOS (firmware update, Loxone reboot)                          | Wait, the binding reconnects on its own                                     |

**Step 2** — force a new cycle:

```bash
# Force reconnect (proper close + new handshake).
curl -fsS -X POST http://localhost:8080/api/v1/reconnect | jq

# Stronger: invalidates the JWT on the Miniserver side AND reconnects.
# To be used if a corrupt JWT is suspected (rare, but has happened after
# Miniserver firmware downgrade).
curl -fsS -X POST http://localhost:8080/api/v1/token/kill | jq
```

### 3.2 `broker.connected = false`

Symptoms: the `binding_mqtt_publishes_total` counter doesn't move anymore,
the log shows an MQTT disconnection.

**Important note**: the binding **keeps the Miniserver session active
even if MQTT falls** (both legs are independent by design). So you
can have `session.state = RUNNING` AND `broker.connected = false` —
it's coherent.

```bash
# 1. Does the broker respond on TCP?
nc -zv broker.example.com 8084

# 2. Does the WS-TLS handshake pass?
curl -fsS --include https://broker.example.com:8084/mqtt 2>&1 | head -5
# → we expect a cleanly-rejected upgrade (the broker does not accept plain
#   HTTP), not a timeout or a cert error.

# 3. Force reconnect on the binding side (without touching the Miniserver session).
curl -fsS -X POST http://localhost:8080/api/v1/transport/connect | jq
```

If the reconnection fails immediately → broker TLS cert problem (see
3.7) or broker down. If it reconnects but drops again after a few
seconds → MQTT keepalive KO on the broker side or will-message triggers a
disconnect (rare).

### 3.3 No events after bootup

Symptoms: everything is green (session RUNNING, broker connected), but
`binding_mqtt_publishes_total` stays at 0 or very low.

**Typical cause**: the `enablebinstatusupdate` command was not
sent (should be sent systematically at the transition
`AWAITING_STRUCTURE_VERSION_REPLY → AWAITING_STATUS_UPDATE_REPLY`).
Verify:

```bash
journalctl -u loxmq-native --since "10 min ago" --no-pager | \
    grep -i 'enablebin\|RUNNING'
```

You should see a transition to `RUNNING` after the `enablebinstatusupdate`.
If the transition didn't happen → the Miniserver may have rejected
the command (insufficient JWT rights, see 3.4).

If everything is in order but no event comes out → check on the Miniserver
side that objects are actually active (shutters moving, probes
changing value, etc.). A "quiet" Miniserver sends few
events.

### 3.4 Insufficient JWT token rights

Symptoms: log `tokenRights` < 1024 in the `/api/v1/state` payload,
or 401/403 error on some commands.

```bash
curl -fsS http://localhost:8080/api/v1/state | jq '.session.token'
```

The binding requests `permission=4` (Admin) on the token. The exact
rights counter returned by the Miniserver is a Loxone bitmask (1668 = sum
of several bits). As long as it's > 1024 (= Admin bit), we're fine.

If low → check the user `loxone.miniserver.security.credentials.user`
in `/etc/loxmq/env` on the LXC, and its level of
rights in Loxone Config (Users & Permissions).

### 3.5 `/states` page: empty dropdowns

Symptoms: the `/states` page loads, but the Room / Category / Control
dropdowns are empty. The binding runs without visible error.

**This is the bug** — `Topology` not registered for
native reflection → `writeValueAsString(topology)` fails → fallback
empty JSON. Visible **only in native**, not in JVM.

Diagnosis:

```bash
journalctl -u loxmq-native --since "1 hour ago" --no-pager | \
    grep 'Failed to serialise topology'
```

If the error appears → `NativeReflectionConfig` doesn't expose
`Topology.class` / `ControlInfo.class` to native reflection. Check
the class and rebuild the native binary after fix.

Immediate remediation (before fix): use the main dashboard
`/` which doesn't have this bug.

Prevention: `LiveStatesPageIT` catches exactly this case in native CI
via `./mvnw verify -Pnative,integration`.

### 3.6 SSE disconnects / reconnects in a loop

Symptoms: the `/states` or `/` page loses the live connection every
few seconds (counter "X received" plateaus, resets, plateaus…).

**Server side (before)**: `BroadcastProcessor`
invalidated the subscriber on backpressure → SSE drop → browser
reconnects. Check that the deployed version has:

```bash
curl -fsS http://localhost:8080/api/v1/state | jq -r '.app.version'
# → must be >= 3.0.x (Quarkus repo) OR >
```

**Server side (still possible)**: browser disconnects abruptly
→ cosmetic `StacklessClosedChannelException` (silenced via a configured
log filter). If we still see these stacktraces, check the log
config in `application.yml`.

**Browser side**: try another tab / another browser → if OK,
local problem; otherwise server problem.

### 3.7 TLS certificate rejected

Symptoms:

- Browser refuses `https://loxmq.example.com:8443/`
- or binding refuses to connect MQTT broker (`PKIX path building failed`)

```bash
# Check the wildcard cert expiration date.
echo | openssl s_client -servername broker.example.com -connect broker.example.com:8084 2>/dev/null | \
    openssl x509 -noout -dates

# On the binding LXC — see current certs.
ls -la /opt/loxmq/certs/
openssl x509 -in /opt/loxmq/certs/fullchain.pem -noout -dates
```

If expired → manual Let's Encrypt renewal or wait for the
renew-hook (see `FAQ.md §"How to renew the cert without manual intervention"`).

To push a new cert on an LXC, copy the renewed certificate into
`/opt/loxmq/certs/` (owner `loxmq:loxmq`, mode `0750`) and restart the
service.

### 3.8 Logs no longer rotate / disk full

Symptoms: `journalctl` shows `No space left on device` errors,
or the `/var/log/loxmq/*.log` files no longer write.

```bash
# Disk saturated?
df -h /var/lib/loxmq /var/log

# Log inventory (rotation = size-based, 30 backups per log).
ls -la /var/lib/loxmq/logs/
du -sh /var/lib/loxmq/logs/
```

If 30 backups isn't enough disk headroom (huge event volume), reduce
`quarkus.log.handler.file.*.rotation.max-file-size` /
`max-backup-index` in `application.yml` then redeploy. Or purge manually:

```bash
# Purge all but the last 7 days.
find /var/lib/loxmq/logs -name '*.log.*' -mtime +7 -delete
systemctl reload loxmq-native    # or restart if reload not wired
```

### 3.9 Service won't start — crash loop

Symptoms: `systemctl status` shows `activating (auto-restart)` in
a loop.

```bash
# See the full journal of the last crash.
journalctl -u loxmq-native -p err --since "10 min ago" --no-pager
```

Typical causes:

| Error                                                    | Cause                                                                         | Fix                                                                   |
|----------------------------------------------------------|-------------------------------------------------------------------------------|-----------------------------------------------------------------------|
| `Failed to start application` at boot, missing cert path | `quarkus.tls.*.key-store` or `trust-store` points to an absent file           | Check `/opt/loxmq/certs/` exists + chmod readable by the service user |
| `Address already in use` 8443 or 8080                    | A previous instance wasn't cleanly killed, or collision with another service  | `ss -tlnp                                                             | grep -E '8443|8080'` + kill the PID |
| Stale PID file in `/run/loxmq/`                          | The LXC restarted during a shutdown                                           | `rm /run/loxmq/*.pid` then `systemctl start`                          |
| Native binary segfault at startup                        | Binary corruption (incomplete download, glibc mismatch between build and LXC) | Rebuild and re-upload the binary from the build host                  |

### 3.10 Prometheus metrics missing

Symptoms: Grafana / Prometheus can't scrape anymore, or `/q/metrics`
returns an empty payload.

```bash
# Does the endpoint respond?
curl -fsS http://localhost:8080/q/metrics | head -20

# If empty: micrometer/prometheus config is baked into the artifact (native
# binary, or quarkus-app/ jar for the JVM build) — no standalone file on the
# LXC. Check for a runtime override in the env file (else see the source
# application.yml, quarkus.micrometer.* keys):
grep -iE 'micrometer|prometheus|metrics' /etc/loxmq/env
```

If the endpoint responds but remote Prometheus doesn't scrape → LXC
firewall, check that port 8080 is reachable from the
Prometheus machine.

---

## 4. Corrective procedures — the toolbox

### Clean service restart

```bash
# JVM (local debug, dev) — not used in prod.
systemctl restart loxmq-jvm

# Native (prod + staging).
systemctl restart loxmq-native

# Restart with real-time log watching.
systemctl restart loxmq-native && \
    journalctl -u loxmq-native -f
```

### Force-reconnect Miniserver without touching MQTT

```bash
curl -fsS -X POST http://localhost:8080/api/v1/reconnect | jq
```

### Invalidate JWT on Miniserver side + reconnect

To be used as a last resort (rare, but useful if a broken JWT is suspected
after Miniserver downgrade):

```bash
curl -fsS -X POST http://localhost:8080/api/v1/token/kill | jq
```

The binding does a `killtoken` on the Miniserver side, removes its cached
JWT, then re-handshakes from scratch.

### Reset MQTT broker (without touching Miniserver)

```bash
curl -fsS -X POST http://localhost:8080/api/v1/transport/disconnect | jq
sleep 2
curl -fsS -X POST http://localhost:8080/api/v1/transport/connect    | jq
```

### Purge LoxAPP3 cache

The LoxAPP3 cache lives in `/var/lib/loxmq/cache/<miniserver-uuid>/`.
To purge if a corrupt or stale LoxAPP3 is suspected:

```bash
systemctl stop loxmq-native
rm -rf /var/lib/loxmq/cache/*
systemctl start loxmq-native
```

At the next handshake, the binding re-downloads the fresh LoxAPP3
(additional latency ~50-100 ms on the boot side).

### View retained on the broker (audit)

```bash
mosquitto_sub -h broker.example.com -p 8084 --ws-protocol mqtt \
    -t 'iot/loxmq/+/+/#' -v -C 100
```

(Without `-r --remove-retained` which would delete — **NEVER touch
prod retained without explicit user validation**.)

---

## 5. Diagnostics by useful command

### Journal filtered by category

```bash
# Critical events only.
journalctl -u loxmq-native -p warning --since "1 hour ago"

# Only the session state machine.
journalctl -u loxmq-native --since "1 hour ago" | \
    grep -E 'SessionTracker|SessionOrchestrator'

# Only MQTT publishes (but it's A LOT).
journalctl -u loxmq-native --since "5 min ago" | \
    grep 'StatesPublisher'

# All but the DEBUG noise (useful for reports).
journalctl -u loxmq-native --since "1 hour ago" | \
    grep -v -E 'DEBUG|TRACE'
```

### Structured log files (alternatives to journalctl)

```bash
# Application (all but commands).
tail -F /var/lib/loxmq/logs/application.log

# Inbound MQTT commands only (inbound topics).
tail -F /var/lib/loxmq/logs/commands.log
```

### Relevant Micrometer counters

```bash
curl -fsS http://localhost:8080/q/metrics | grep -E '^binding_(mqtt|miniserver)_'
```

| Metric                                                  | Meaning                                                          |
|---------------------------------------------------------|------------------------------------------------------------------|
| `binding_mqtt_publishes_total{kind="state"}`            | Events published to `/states/*`                                  |
| `binding_mqtt_publishes_total{kind="command_response"}` | Replies to inbound commands                                      |
| `binding_mqtt_inbound_dropped_total`                    | Dropped events (overflow / broker disconnected) — must stay at 0 |
| `binding_miniserver_keepalive_rtt_seconds`              | Miniserver WS RTT (timer) — p95 < 200 ms on healthy LAN          |
| `binding_session_state_ordinal`                         | Gauge at 4 when `RUNNING` (0-4 depending on transition)          |
| `binding_broker_connected`                              | Gauge at 1 when broker UP, 0 otherwise                           |

---

## 6. When to escalate (out of operator scope)

These situations are NOT runtime diagnosis — they require an upstream
code or config change, a new patch:

- **Bump Quarkus version / critical dependency** → dedicated patch, build,
  test, release.
- **New serialized record / DTO** → add to `NativeReflectionConfig`
    + native IT, otherwise silent type-107 bug.
- **MQTT schema change** (`type_N`, topics) → downstream impact (Home
  Assistant), explicit user validation mandatory.
- **Cryptographic / auth layer change** → validation against the
  Loxone V17.0 spec PDFs (vendor-copyrighted, not redistributed here).
- **Touches prod retained** → **forbidden without explicit agreement**.
  The retained `…/states/*` is consumed by Home Assistant, modifying
  or hot-wiping breaks downstream automations.

---

## 7. Quick reference

### Operator pages

| Target            | URL / command                                                             |
|-------------------|---------------------------------------------------------------------------|
| Prod dashboard    | `https://loxmq.example.com:8443/`                                         |
| Staging dashboard | `https://loxmq-staging.example.com:8443/`                                 |
| Live states       | `…/states` (cascade filter room/cat/control + sparklines + persisted URL) |
| Schedules         | `…/schedules` (operating schedule CRUD)                                   |
| Users             | `…/users` (audit users + Miniserver groups —)                             |

### Platform

| Target  | URL / command                                              |
|---------|------------------------------------------------------------|
| Health  | `/q/health/ready` (readiness), `/q/health/live` (liveness) |
| Metrics | `/q/metrics` (Prometheus)                                  |
| OpenAPI | `/q/openapi`, `/q/swagger-ui`                              |

### REST diagnostic + ops

| Target           | URL / command                                 |
|------------------|-----------------------------------------------|
| State snapshot   | `GET /api/v1/state` (JSON config + runtime)   |
| SSE stream       | `GET /api/v1/state/stream` (live events)      |
| Force reconnect  | `POST /api/v1/reconnect`                      |
| Invalidate JWT   | `POST /api/v1/token/kill`                     |
| MQTT (re)connect | `POST /api/v1/transport/{connect,disconnect}` |

### REST admin Miniserver

| Target            | URL / command                                                                 |
|-------------------|-------------------------------------------------------------------------------|
| List schedules    | `GET /api/v1/schedules`                                                       |
| Create schedule   | `POST /api/v1/schedules` (body `{name, operatingMode, calMode, calModeAttr}`) |
| Delete schedule   | `DELETE /api/v1/schedules/{uuid}`                                             |
| List users        | `GET /api/v1/users`                                                           |
| User detail       | `GET /api/v1/users/{uuid}`                                                    |
| List groups       | `GET /api/v1/groups`                                                          |
| Disable user      | `POST /api/v1/users/{uuid}/disable` (refused if admin)                        |
| Add user→group    | `POST /api/v1/users/{u}/groups/{g}`                                           |
| Remove user→group | `DELETE /api/v1/users/{u}/groups/{g}`                                         |

### Logs

| File                                  | Content                                                                                                                                                                                                                                                                                                                                                                           |
|---------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `/var/lib/loxmq/logs/application.log` | Main application log (all INFO+ levels)                                                                                                                                                                                                                                                                                                                                           |
| `/var/lib/loxmq/logs/error.log`       | **ERROR-only** — exceptions + stacktraces of all categories. Empty in steady state, spikes during incidents. **First debug reflex**: `tail -F error.log`.                                                                                                                                                                                                                         |
| `/var/lib/loxmq/logs/warn.log`        | **WARN only** — recovered transients (retry-once), Semaphore contention, HMAC rotation, deprecation warnings. Strict WARN-only: ERROR/FATAL are excluded (they live in `error.log`), enforced by a programmatic filter attached at boot (`LoggingProducers.installStrictWarnFilter`). Workflow: `error.log` empty but weird behavior → check `warn.log`.          |
| `/var/lib/loxmq/logs/commands.log`    | Inbound MQTT commands (`/command`, `/api`)                                                                                                                                                                                                                                                                                                                                        |
| `/var/lib/loxmq/logs/audit.log`       | User + group mutations via `/api/v1/users/*` + `/api/v1/groups/*`. Verbs: `CREATE` / `EDIT` / `DELETE` / `DISABLE` / `ASSIGN` / `REMOVE` / `UPDATE_PWD` / `UPDATE_VISU_PWD` / `UPDATE_ACCESS_CODE` / `NFC_ADD` / `NFC_REMOVE` / `NFC_DISCOVER` / `CREATE_GROUP` / `EDIT_GROUP` / `DELETE_GROUP`; `*_ADMIN_OVERRIDE` (WARN) for admin ops with `?force=true`.                      |

---

*Doc created. Keep in sync with `application.yml`
(endpoints, port 8443/8080).*
