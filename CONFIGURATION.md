<img src="src/main/resources/META-INF/resources/images/loxmq-logo.svg" alt="loxmq">

# CONFIGURATION

Single, exhaustive reference for **every** loxmq configuration knob. The
authoritative sources are the typed mapping
[`config/LoxoneConfig.java`](./src/main/java/com/quaddan/iot/loxmq/config/LoxoneConfig.java)
(`@ConfigMapping(prefix = "loxone")`) and the commented defaults in
[`application.yaml`](./src/main/resources/application.yaml). This document
mirrors them; if the two ever disagree, the code wins.

> Related: the *design* of the config model + eager validation →
> [ARCHITECTURE.md §4](./ARCHITECTURE.md). Essential first-run knobs →
> [INSTALL.md §3.3](./INSTALL.md#33-essential-configuration-knobs). Secrets /
> env-var policy → [SECURITY.md](./SECURITY.md). TLS cert placement + renewal →
> [INSTALL.md §7.3](./INSTALL.md#73-tls-certificates).

---

## 1. How configuration is resolved

- **Defaults live in `application.yaml`.** Protocol-stable values (Loxone command
  stems, crypto algorithm names) are baked into `LoxoneConfig` via `@WithDefault`
  and do **not** appear in the YAML — only operator-tunable values do.
- **Profiles** overlay the base: `application-dev.yaml`, `application-staging.yaml`,
  `application-prod.yaml`. Activate with `-Dquarkus.profile=prod` (or
  `QUARKUS_PROFILE=prod`). `quarkus:dev` runs the `dev` profile implicitly.
- **Precedence** (highest first): env var / `-Dkey=value` → active profile YAML →
  base `application.yaml` → `@WithDefault` in code.
- **Env-var override** — SmallRye convention: take the property, uppercase it, and
  replace `.` and `-` with `_`. Example:
  `loxone.miniserver.connection.host` → `LOXONE_MINISERVER_CONNECTION_HOST`.
  A handful of knobs are wired to **short, explicit** env vars in the YAML
  (`${MINISERVER_HOST:…}` etc.) — those are listed in the *Env* column below; every
  other knob still accepts its long-form convention name.
- **Validation runs eagerly** at first injection (Bean Validation on the
  `@ConfigMapping` tree): a missing required field, an out-of-range number, or a bad
  enum value fails fast with `io.smallrye.config.ConfigValidationException` before
  any business code runs.
- **Time units** — durations use ISO-8601 `PnDTnHnMnS` (`PT3S`, `PT60S`, `PT24H`,
  `P7D`); times-of-day use ISO-8601 local time `HH:mm:ss` (`04:30:00`).

**Required (no default — must be set per deployment):**
`loxone.miniserver.app.id`, `loxone.miniserver.security.credentials.{user,password}`
(and the MQTT broker host/credentials for a real broker). The base YAML ships
`CHANGE_ME` / all-zeros / `*.example.com` sentinels that only let the binding boot.

---

## 2. `loxone.boot.*` — boot orchestration

| Property | Default | Env | Meaning |
|---|---|---|---|
| `loxone.boot.auto-start` | `true` | `LOXONE_BOOT_AUTO_START` | Run the full chain **MQTT → bootstrap → session** at startup, in that strict order. When `true`, the per-service `*.auto-connect` flags are **ignored** (`BootAutoStarter` is the single source of truth). |
| `loxone.boot.halt-on-failure` | `true` | `LOXONE_BOOT_HALT_ON_FAILURE` | Exit the JVM (code 1) if the boot chain fails on its **first** attempt (hands control to systemd `Restart=on-failure`). `false` = fail-soft: stay up, retry manually via `POST /api/v1/bootstrap` + `/connect`. Only applies pre-RUNNING. |

---

## 3. `loxone.management.*` — operator surface

| Property | Default | Env | Meaning |
|---|---|---|---|
| `loxone.management.public-host` | *(unset)* | `LOXONE_MANAGEMENT_PUBLIC_HOST` | FQDN shown in startup logs + dashboard self-links. Decouples the bind address (`quarkus.http.host`, often `0.0.0.0`) from the URL an operator pastes in a browser. When unset, the literal `quarkus.http.host` is shown. |

---

## 4. `loxone.miniserver.*`

### 4.1 Connection

| Property | Default | Env | Meaning |
|---|---|---|---|
| `loxone.miniserver.connection.host` | `miniserver.example.com` | `MINISERVER_HOST` | Miniserver hostname / IP. |
| `loxone.miniserver.connection.port` | `443` | `MINISERVER_PORT` | Miniserver TCP port. |
| `loxone.miniserver.connection.secure` | `true` | — | TLS **preference** (not absolute). `ConnectionModeResolver` combines it with the device's TLS readiness: `false` ⇒ always plain; `true` ⇒ https+wss on a Gen2 with a valid cert, auto-downgraded to plain on Gen1 / missing-expired cert (reason logged + shown). |
| `loxone.miniserver.connection.bootstrap-prefer-secure` | `false` | — | Also use TLS for the **first** `GET /jdev/cfg/apiKey`. Default `false` sends it plain then re-resolves. Set `true` when the configured port is TLS-only (else "header parser received no bytes"). |
| `loxone.miniserver.connection.tls-skip-hostname-verification` | `false` | — | Skip TLS **hostname** check (chain still validated against the JVM truststore). For a Loxone-issued cert whose SAN ≠ your hostname. Cleaner alternative: upload a matching cert to the Miniserver. |

**HTTP sub-block** (`loxone.miniserver.connection.http.*` — bootstrap + admin calls)

| Property | Default | Meaning |
|---|---|---|
| `…http.connect-timeout` | `PT3S` | TCP connect timeout for bootstrap HTTP calls. |
| `…http.request-timeout` | `PT3S` | Full request→response timeout for those calls. |
| `…http.admin-max-concurrent` | `4` | Cap on concurrent in-flight admin HTTP commands (V17 has ~6-8 HTTP slots; leaves room for WS event-tables + keepalive). |
| `…http.admin-wait-timeout` | `PT30S` | How long an admin command waits in the semaphore queue before failing fast. Keep > `request-timeout × 2`. |

**WS sub-block** (`loxone.miniserver.connection.ws.*` — the session socket)

| Property | Default | Env | Meaning |
|---|---|---|---|
| `…ws.path` | `/ws/rfc6455` | — | WebSocket endpoint path (RFC 6455); stable across firmware. |
| `…ws.keepalive-interval` | `PT60S` | — | How often the `keepalive` command is sent on the WS. |
| `…ws.keepalive-max-missed` | `3` | — | Declare the link dead after this many missed keepalive responses (watchdog trips at `interval × max-missed` ≈ 3 min). Catches a half-open TCP (e.g. a reboot with no RST). Min 1. |

### 4.2 App identity

| Property | Default | Env | Meaning |
|---|---|---|---|
| `loxone.miniserver.app.id` | `0000…0000` *(sentinel — **required**)* | `LOXONE_MINISERVER_APP_ID` | Per-instance hyphenated UUID (8-4-4-4-12). Fans out into **5** places: MQTT topic root, MQTT client-id, JWT client-UUID, cache sub-dir, health/management uuid. **Must be unique per instance** (`uuidgen`). |
| `loxone.miniserver.app.info` | `loxmq` | — | Human label for the requested token (shown in Loxone Config's token list + republished under `…/app_info`). |
| `loxone.miniserver.app.permission` | `4095` | — | **Bitmask** of requested JWT rights (not a single ID). `4095` = bits 0-11 incl. App `0x004` (long-lived), Op-Modes `0x080` (`/schedules`), User-Mgmt `0x800` (`/users`). Granted = `requested AND user-rights`. Bits ≥ `0x1000` (destructive) deliberately not requested. |
| `loxone.miniserver.states-to-decode` | `2,3,4` | — | Loxone state-table type IDs to decode: 2=Value, 3=Text, 4=DayTimer, 7=Weather. Add `7` to enable weather (also set `subscription.weather=true`). |

### 4.3 Security — credentials & token

| Property | Default | Env | Meaning |
|---|---|---|---|
| `loxone.miniserver.security.credentials.user` | `CHANGE_ME` *(**required**)* | `LOXONE_MINISERVER_AUTH_USER` | **Base64-encoded** Miniserver username, decoded once at startup. |
| `loxone.miniserver.security.credentials.password` | `CHANGE_ME` *(**required**)* | `LOXONE_MINISERVER_AUTH_PASSWORD` | **Base64-encoded** Miniserver password. Never commit — supply via env. See SECURITY.md. |
| `loxone.miniserver.security.token.refresh.delay-time` | `04:30:00` | — | Local time-of-day for the daily JWT refresh. |
| `loxone.miniserver.security.token.refresh.period` | `PT24H` | — | Interval between token refreshes. |

### 4.4 Reconnect (WS, post-RUNNING)

Exponential backoff with jitter for WS drops **after** the first RUNNING (the first
bootstrap is governed by `boot.halt-on-failure`).

| Property | Default | Meaning |
|---|---|---|
| `loxone.miniserver.reconnect.enable` | `true` | Master switch for automatic WS reconnection. |
| `loxone.miniserver.reconnect.initial-delay` | `PT1S` | Delay before the first reconnect attempt. |
| `loxone.miniserver.reconnect.max-delay` | `PT2H` | Ceiling the backoff grows toward. |
| `loxone.miniserver.reconnect.multiplier` | `2.0` | Each failed attempt multiplies the delay. |
| `loxone.miniserver.reconnect.jitter-factor` | `0.2` | ± randomness fraction (anti thundering-herd). |
| `loxone.miniserver.reconnect.out-of-service.initial-delay` | `PT30S` | Wait before the first retry after an OUT_OF_SERVICE (reboot / firmware update, close-code `4009`) — let it finish rebooting. |
| `loxone.miniserver.reconnect.out-of-service.retry-interval` | `PT15S` | Fixed gap between subsequent OUT_OF_SERVICE retries (no backoff, no jitter). |

### 4.5 Cache & subscription

| Property | Default | Env | Meaning |
|---|---|---|---|
| `loxone.miniserver.cache.directory` | `cache` | `CACHE_DIR` | On-disk LoxAPP3 cache dir (prod unit sets `/var/lib/loxmq/cache`; the relative path is read-only under `ProtectSystem=strict`). |
| `loxone.miniserver.cache.ttl` | `P7D` | — | TTL safety-net: re-download the structure file if the on-disk marker is older than this even when `lastModified` matches (guards against firmware upgrades that don't bump the timestamp). `PT0S` disables the TTL check. |
| `loxone.miniserver.subscription.weather` | `false` | — | Decode + publish Weather state-tables (type 7). Requires a paid Loxone weather subscription **and** adding `7` to `states-to-decode`. |

### 4.6 Protocol command stems — `loxone.miniserver.cmd.*` ⚠️ rarely overridden

Loxone V17 command stems, baked in via `@WithDefault`. **Do not change** unless the
Loxone protocol itself changes.

| Property | Default |
|---|---|
| `…cmd.get-cfg-api` | `jdev/cfg/apiKey` |
| `…cmd.get-public-key` | `jdev/sys/getPublicKey` |
| `…cmd.key-exchange` | `jdev/sys/keyexchange/` |
| `…cmd.get-key-and-salt` | `jdev/sys/getkey2` |
| `…cmd.get-key` | `jdev/sys/getkey` |
| `…cmd.request-token` | `jdev/sys/getjwt/` |
| `…cmd.refresh-token` | `jdev/sys/refreshjwt` |
| `…cmd.kill-token` | `jdev/sys/killtoken/` |
| `…cmd.request-status-update` | `jdev/sps/enablebinstatusupdate` |
| `…cmd.request-structure-file` | `data/LoxAPP3.json` |
| `…cmd.request-structure-file-version` | `jdev/sps/LoxAPPversion3` |
| `…cmd.encrypt` | `jdev/sys/enc/` |
| `…cmd.keepalive` | `keepalive` |
| `…cmd.prefix.root` | `jdev/sps/io/` |
| `…cmd.prefix.salt` | `salt/` |
| `…cmd.prefix.next-salt` | `nextSalt/` |

### 4.7 Crypto — `loxone.miniserver.crypto.*` ⚠️ rarely overridden

Algorithm names + parameters, validated against Gen2 firmware 14.x–17.x.

| Property | Default |
|---|---|
| `…crypto.encrypt-command.algo` | `AES` |
| `…crypto.encrypt-command.transformation` | `AES/CBC/PKCS5Padding` |
| `…crypto.encrypt-command.key-size` | `256` |
| `…crypto.encrypt-command.salt-length` | `16` |
| `…crypto.encrypt-key.algo` | `RSA` |
| `…crypto.encrypt-key.transformation` | `RSA/ECB/PKCS1Padding` |
| `…crypto.hash-password.algo` | `SHA-256` *(digest — required, set in YAML)* |
| `…crypto.hash-user-password.algo` | `HmacSHA256` *(MAC — required, set in YAML)* |
| `…crypto.salt.max-age` | `PT1H` |
| `…crypto.session-key.init-vector-length` | `16` |

---

## 5. `loxone.transport.*` (MQTT v5)

### 5.1 Mode & connection

| Property | Default | Env | Meaning |
|---|---|---|---|
| `loxone.transport.mode` | `SINGLE` | — | `SINGLE` = one retained publish per UUID on `{topic}/{uuid}` (Home-Assistant-friendly); `BATCH` = one JSON envelope on `{topic}`. |
| `loxone.transport.auto-connect` | `false` | `LOXONE_TRANSPORT_AUTO_CONNECT` | Connect to the broker at startup. **Ignored** when `boot.auto-start=true`. |
| `loxone.transport.connection.protocol` | `ws` | — | Transport family: `ws`/`wss`/`tcp`/`ssl`/`tls`/`mqtts` (legacy TLS values accepted + mapped by family). |
| `loxone.transport.connection.secure` | `true` | — | TLS preference on top of the family: `(ws,true)`→wss, `(tcp,true)`→ssl. **No** auto-downgrade (unlike the Miniserver side) — a TLS-less broker fails loudly. |
| `loxone.transport.connection.host` | `broker.example.com` | `MQTT_BROKER_HOST` | Broker hostname / IP. |
| `loxone.transport.connection.port` | `8083` *(prod `8084` wss)* | `MQTT_BROKER_PORT` | Broker TCP port. |
| `loxone.transport.connection.path` | `/mqtt` | — | URL path (only for `ws`/`wss`). |
| `loxone.transport.connection.client-id` | `${app.id}` | — | MQTT client-id (interpolated from `app.id` → unique per instance). |
| `loxone.transport.connection.connect-timeout` | `PT3S` | — | TCP connect timeout for the broker. |
| `loxone.transport.connection.keepalive-interval` | `PT60S` | — | MQTT PINGREQ interval negotiated with the broker. |
| `loxone.transport.connection.request-problem-information` | `true` | — | Ask the broker to include Reason String / User Properties in failures. |

### 5.2 Session

| Property | Default | Meaning |
|---|---|---|
| `loxone.transport.session.clean-start` | `true` | Fresh MQTT v5 session on every connect (no resumed subs / queued messages). |
| `loxone.transport.session.expiry-interval` | `PT0S` | How long the broker keeps session state after disconnect (`PT0S` = discard immediately; pairs with `clean-start`). |

### 5.3 Security

| Property | Default | Env | Meaning |
|---|---|---|---|
| `loxone.transport.security.credentials.enable` | `true` | — | Send MQTT username/password on connect (`false` only for an anonymous broker). |
| `loxone.transport.security.credentials.user` | *(unset)* | `LOXONE_TRANSPORT_SECURITY_CREDENTIALS_USER` | Broker username (supply via env). |
| `loxone.transport.security.credentials.password` | *(unset)* | `LOXONE_TRANSPORT_SECURITY_CREDENTIALS_PASSWORD` | Broker password (supply via env). |
| `loxone.transport.security.max-inbound-payload-bytes` | `4096` | — | Max accepted size of an inbound `…/command` / `…/api` payload; larger ⇒ dropped (WARN), no JSON parse. `0` disables (not recommended). |
| `loxone.transport.security.inbound-queue-capacity` | `256` | — | Bounded inbound-command dispatch queue (single FIFO worker, off the broker event loop). Overflow ⇒ `DROP-QUEUE-FULL` + `queue_full` metric. Min 1. |

### 5.4 Reconnection (broker)

| Property | Default | Meaning |
|---|---|---|
| `loxone.transport.reconnection.automatic` | `true` | HiveMQ auto-reconnect for transient broker drops (exp. backoff, bounded below). |
| `loxone.transport.reconnection.min-delay` | `PT3S` | First reconnect delay after a broker drop. |
| `loxone.transport.reconnection.max-delay` | `PT2M` | Backoff ceiling. |
| `loxone.transport.reconnection.out-of-service.initial-delay` | `PT30S` | After a Miniserver OutOfService: disconnect MQTT (LWT "offline"), wait this long before the first reconnect. |
| `loxone.transport.reconnection.out-of-service.retry-interval` | `PT15S` | Gap between subsequent OutOfService retries until success. |

### 5.5 Topics

Root + QoS, plus the Will and every publish/subscribe endpoint. All topic paths are
`{root}/{app.id}/…` by interpolation; QoS/retain reference the shared defaults.

| Property | Default | Meaning |
|---|---|---|
| `loxone.transport.topics.root` | `iot/loxmq` | Prefix for all topics (full root `{root}/{app.id}`). Change to re-namespace everything. |
| `loxone.transport.topics.qos` | `2` | Default QoS (exactly-once) referenced by every entry below. |
| `…topics.will.enable` | `true` | Enable Last-Will-Testament presence toggle. |
| `…topics.will.retain` | `true` | Retain the presence message. |
| `…topics.will.message-online` / `.message-offline` | `online` / `offline` | Presence payloads. |
| `…topics.will.topic` | `{root}/{app.id}/status` | Presence topic. |
| `…topics.will.qos` | `{topics.qos}` | Presence QoS. |
| `…topics.publish.app-info` | `{root}/{app.id}/app_info` (retain) | App-info publish. |
| `…topics.publish.command-response` | `{root}/{app.id}/command_response` | Command-response publish (not retained). |
| `…topics.publish.lox-app3` | `{root}/{app.id}/loxapp3` (retain) | Structure-file publish. |
| `…topics.publish.out-of-service` | `{root}/{app.id}/out_of_service` | OutOfService publish. |
| `…topics.publish.value-states` | `{root}/{app.id}/states/type_2` (retain) | Value states (type 2). |
| `…topics.publish.text-states` | `{root}/{app.id}/states/type_3` (retain) | Text states (type 3). |
| `…topics.publish.day-timer-states` | `{root}/{app.id}/states/type_4` (retain) | DayTimer states (type 4). |
| `…topics.publish.weather-states` | `{root}/{app.id}/states/type_7` (retain) | Weather states (type 7). |
| `…topics.subscribe.command` | `{root}/{app.id}/command` | Inbound command subscription. |
| `…topics.subscribe.api` | `{root}/{app.id}/api` | Inbound API-connector subscription. |

Each publish entry also has `.qos` (default `{topics.qos}`) and `.retain`; each
subscribe entry has `.qos`. Values shown are the base-YAML defaults.

---

## 6. Quarkus knobs (operator-relevant)

loxmq runs on Quarkus; most `quarkus.*` settings are internal, but these are the
ones an operator touches. Full defaults in `application.yaml` (§`quarkus`).

| Property | Default | Meaning |
|---|---|---|
| `quarkus.http.host` | `0.0.0.0` | Bind address. |
| `quarkus.http.port` | `8080` | Plain-HTTP port (dev). |
| `quarkus.http.ssl-port` | `8443` | HTTPS port (staging / prod). |
| `quarkus.http.insecure-requests` | `enabled` | Whether plain HTTP is accepted (`disabled`/`redirect` to force HTTPS). |
| `quarkus.http.limits.max-body-size` | `128K` | Max request body. |
| `quarkus.http.ssl.certificate.*` | *(profiles)* | Server TLS cert/key — set in staging/prod overlays; see [INSTALL.md §7.3](./INSTALL.md#73-tls-certificates). |
| `quarkus.log.level` | `INFO` | Root log level (`QUARKUS_LOG_LEVEL`). Per-package levels set in YAML. |
| `quarkus.profile` | *(dev in `quarkus:dev`)* | Active profile (`QUARKUS_PROFILE=staging\|prod`). |
| `quarkus.otel.sdk.disabled` | `true` | OpenTelemetry off by default; `QUARKUS_OTEL_SDK_DISABLED=false` + an OTLP collector to opt in. |
| `quarkus.native.resources.includes` | `…/system-commands.json` | Embeds the `/system` catalogue in the native binary (see NATIVE.md). |

Fixed management paths: health `/q/health` (`live` / `ready`), metrics `/q/metrics`,
OpenAPI `/q/openapi`, Swagger UI `/q/swagger-ui`.

---

## 7. Secrets

Never commit the Miniserver credentials, the broker password, or the keystore
password. Supply them via environment variables (`LOXONE_MINISERVER_AUTH_USER`,
`LOXONE_MINISERVER_AUTH_PASSWORD`, `LOXONE_TRANSPORT_SECURITY_CREDENTIALS_PASSWORD`,
…) — Miniserver credentials are **Base64-encoded**. Full policy + the prod
`/etc/loxmq/env` layout → [SECURITY.md](./SECURITY.md) and
[INSTALL.md](./INSTALL.md).
