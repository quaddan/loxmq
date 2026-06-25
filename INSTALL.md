<img src="src/main/resources/META-INF/resources/images/loxmq-logo.svg" alt="loxmq">

# Install & run

A single, linear walkthrough — from a fresh clone to a running bridge,
then to a supervised **production** deployment.

It covers **both** runtime forms (JVM fast-jar and native binary) and is
the **source of truth for installation and deployment**. For adjacent
topics it links out rather than repeats:

- **[NATIVE.md](./NATIVE.md)** — the native-image build in depth and the GraalVM pitfalls already solved.
- **[RUNBOOK.md](./RUNBOOK.md)** — day-2 operations and the incident playbook (once it is deployed and something is off).
- **[service/README.md](./service/README.md)** — the shipped systemd units, in reference detail.
- **[FAQ.md](./FAQ.md)** — short question-and-answer entries.

**Contents**

1. [Prerequisites](#1-prerequisites)
2. [Get the source](#2-get-the-source)
3. [Configure](#3-configure)
4. [Build and run — JVM fast-jar](#4-build-and-run--jvm-fast-jar)
5. [Build and run — native binary](#5-build-and-run--native-binary)
6. [Verify it is running](#6-verify-it-is-running)
7. [Deploy to production](#7-deploy-to-production)
8. [Deploy with Docker (published image)](#8-deploy-with-docker-published-image)
9. [First-run troubleshooting](#9-first-run-troubleshooting)

---

## 1. Prerequisites

| Tool       | Version    | Needed for                                                                                                            |
|------------|------------|-----------------------------------------------------------------------------------------------------------------------|
| **JDK**    | **25 LTS** | Build + JVM run. The build is pinned to `--release=25`; the host JDK may be newer.                                     |
| **Maven**  | **3.9+**   | Build, tests, packaging. (Use the bundled `./mvnw` wrapper — no separate install needed.)                              |
| **Docker** | any recent | *Optional* — only for the native build (delegated to a Mandrel container) and the integration tests. Rootless is fine. |

At **runtime** (not at build time) you also need:

- A reachable **Loxone Miniserver** and a user account on it — with the
  App + Op-Modes + User-Management permissions if you intend to use the
  admin surface.
- A reachable **MQTT v5 broker**. loxmq speaks MQTT **v5 only** (HiveMQ
  client, no downgrade to 3.1.1) — a v3-only broker will refuse or drop
  the connection. **[FlashMQ](https://www.flashmq.org/)** is the reference
  broker; Mosquitto, HiveMQ and EMQX also work with MQTT v5 enabled.

The build itself is **hermetic**: no environment variable, no network call
to your Miniserver or broker.

---

## 2. Get the source

```bash
git clone https://github.com/quaddan/loxmq.git
cd loxmq
```

---

## 3. Configure

### 3.1 Credentials and environment (`.env`)

```bash
cp .env.example .env
```

All four credentials are **Base64-encoded** and decoded once at startup
(at-rest obfuscation, **not** encryption). Encode each value with
`echo -n 'my_secret' | base64`, then fill `.env`:

| Variable                                         | Maps to                      | Meaning                                |
|--------------------------------------------------|------------------------------|----------------------------------------|
| `LOXONE_MINISERVER_AUTH_USER`                    | Miniserver user (Base64)     | Loxone account the bridge logs in with |
| `LOXONE_MINISERVER_AUTH_PASSWORD`                | Miniserver password (Base64) | password for the account above         |
| `LOXONE_TRANSPORT_SECURITY_CREDENTIALS_USER`     | MQTT user (Base64)           | broker username for PUBLISH/SUBSCRIBE  |
| `LOXONE_TRANSPORT_SECURITY_CREDENTIALS_PASSWORD` | MQTT password (Base64)       | broker password                        |
| `MINISERVER_HOST` / `MINISERVER_PORT`            | host / port                  | override the neutral YAML defaults     |
| `MQTT_BROKER_HOST` / `MQTT_BROKER_PORT`          | host / port                  | override the neutral YAML defaults     |
| `QUARKUS_PROFILE`                                | active profile               | `dev` / `staging` / `prod` (optional)  |

For a **dev-mode** run, load it into your shell first: `source .env`. In
production the same keys live in `/etc/loxmq/env` (see [§7](#7-deploy-to-production)).

> **Never commit `.env`** — it is gitignored. The committed `.env.example`
> is a template only and must contain no real value.

### 3.2 Profiles

| Profile   | Activation                      | Behaviour                                                          |
|-----------|---------------------------------|-------------------------------------------------------------------|
| `dev`     | `./mvnw quarkus:dev` (implicit) | plain HTTP on `:8080`, verbose logs, Swagger UI open, live-reload |
| `staging` | `-Dquarkus.profile=staging`     | HTTPS on `:8443` (wildcard cert), JSON logs, Swagger UI open       |
| `prod`    | `-Dquarkus.profile=prod`        | HTTPS only on `:8443`, HTTP→HTTPS redirect, JSON logs, Swagger UI open |
| `test`    | auto (`./mvnw test`)            | Mosquitto mock + fake Miniserver                                  |

> `staging` and `prod` expect TLS certificates under `/opt/loxmq/certs/`
> (see [§7.3](#73-tls-certificates)), which only exist on a deployment
> host. For any **local** run, use `-Dquarkus.profile=dev`.

### 3.3 Essential configuration knobs

Defaults live in `src/main/resources/application.yaml`. Any knob is
overridable by an environment variable (Quarkus/SmallRye `UPPER_SNAKE_CASE`)
or by `-Dkey=value` on the command line.

| Knob (`application.yaml`)               | Default                  | Override                     | Meaning                                                            |
|----------------------------------------|--------------------------|------------------------------|--------------------------------------------------------------------|
| `loxone.miniserver.connection.host`    | `miniserver.example.com` | `MINISERVER_HOST`            | Miniserver hostname / IP                                           |
| `loxone.miniserver.connection.port`    | `443`                    | `MINISERVER_PORT`            | Miniserver TCP port                                               |
| `loxone.miniserver.connection.secure`  | `true`                   | —                            | prefer wss/https (auto-downgrades from `httpsStatus`; see README) |
| `loxone.transport.connection.host`     | `broker.example.com`     | `MQTT_BROKER_HOST`           | MQTT broker hostname / IP                                         |
| `loxone.transport.connection.port`     | `8083` (prod: `8084` wss)| `MQTT_BROKER_PORT`           | MQTT broker TCP port                                              |
| `loxone.transport.connection.secure`   | `true`                   | —                            | TLS to the broker (ws → wss)                                      |
| `loxone.transport.mode`                | `SINGLE`                 | —                            | `SINGLE` (one publish per state) or `BATCH` (aggregated JSON)     |
| `loxone.transport.topics.root`         | `iot/loxmq`              | —                            | MQTT topic root (`<root>/<app.id>/…`)                            |
| `loxone.transport.topics.qos`          | `2`                      | —                            | MQTT QoS for published states                                    |
| `loxone.boot.auto-start`               | `true`                   | `LOXONE_BOOT_AUTO_START`     | chain bootstrap → connect → transport at boot                    |
| `quarkus.http.port` / `.ssl-port`      | `8080` / `8443`          | —                            | plain HTTP (dev) / HTTPS (staging, prod)                         |

See **[FAQ.md](./FAQ.md)** → *Configuration* for the full knob list.

---

## 4. Build and run — JVM fast-jar

### 4.1 Dev mode (fastest iteration)

```bash
source .env
./mvnw quarkus:dev
```

Dashboard at `http://localhost:8080/`, live-reload on, Swagger UI at `/q/swagger-ui`.

### 4.2 Package the fast-jar

```bash
./mvnw -DskipTests package
```

Artifact: `target/quarkus-app/quarkus-run.jar` (with the `app/`, `lib/`,
`quarkus/` folders beside it — keep them together).

### 4.3 Run the fast-jar

```bash
# Local smoke test (no TLS certs required):
java -Dquarkus.profile=dev -jar target/quarkus-app/quarkus-run.jar
```

Startup is ~2 s. The fast-jar is a convenient first production cutover
before switching to the native binary; in production it is launched by the
`loxmq-jvm.service` unit (see [§7](#7-deploy-to-production)).

---

## 5. Build and run — native binary

The native binary is the **reference production artifact**: ~50 ms startup,
~50–80 MB RSS, fully standalone (no JVM required).

### 5.1 Build the native image

```bash
# Built via a Mandrel container — no local GraalVM install needed.
# ~3 min on a warm cache, ~10 min cold.
./mvnw package -Pnative -Dquarkus.profile=dev \
            -Dquarkus.native.container-build=true -DskipTests
```

Binary: `target/loxmq-1.1.0-runner` (~88 MB). See **[NATIVE.md](./NATIVE.md)**
for the build internals and GraalVM caveats.

> For a **production** image, build with `-Dquarkus.profile=prod` so the
> HTTPS/TLS wiring is baked in (build-time config). The `dev` profile above
> bakes the HTTP-only, certless wiring — right for a local run, wrong for a
> prod host.

### 5.2 Run the native binary

```bash
# Launch directly (no JVM):
./target/loxmq-1.1.0-runner -Dquarkus.profile=dev

# Smoke test without contacting a real Miniserver (no auto bootstrap):
./target/loxmq-1.1.0-runner -Dquarkus.profile=dev -Dloxone.boot.auto-start=false
```

Environment variables (`source .env`) and `-D…` properties are honoured
exactly as for the JVM run.

---

## 6. Verify it is running

With `loxone.boot.auto-start=true` (the default) the bridge chains
bootstrap → Miniserver connect → MQTT connect on its own:

```bash
curl -s http://localhost:8080/q/health/live    # process up?
curl -s http://localhost:8080/q/health/ready    # UP only when session RUNNING AND broker connected
curl -s http://localhost:8080/api/v1/state      # session.state == "RUNNING", broker.connected == true
curl -s http://localhost:8080/q/metrics         # the publish counter grows once states flow
```

Open the **dashboard** at `http://localhost:8080/` (dev). If you started
with `auto-start=false`, drive the lifecycle from the dashboard buttons or
via `POST /api/v1/bootstrap` → `/api/v1/connect` → `/api/v1/transport/connect`.

In `staging`/`prod` the UI and endpoints are served over **HTTPS on `:8443`**
(plain `:8080` redirects there).

---

## 7. Deploy to production

In production loxmq runs as a **systemd service** on a Linux host (a Proxmox
LXC in the reference setup). The native binary is the recommended artifact;
the fast-jar is a valid alternative for the first cutover.

The two unit files **are shipped** in this repo under
[`service/`](./service/) — `loxmq-native.service` and `loxmq-jvm.service`.
They are **mutually exclusive** (reciprocal `Conflicts=`): run the native
unit *or* the JVM unit, never both. The deep systemd reference (hardening,
JVM flags) is in [service/README.md](./service/README.md); the end-to-end
procedure below is self-contained.

### 7.1 Filesystem layout

Everything the service reads or writes, and who owns it:

| Path                              | Holds                                             | Owner : mode     |
|-----------------------------------|---------------------------------------------------|------------------|
| `/opt/loxmq/loxmq`                | the native runner (renamed from `*-runner`)       | `loxmq:loxmq` `0755` |
| `/opt/loxmq/certs/fullchain.pem`  | TLS certificate **full chain**                    | `root:loxmq` `0640`  |
| `/opt/loxmq/certs/privkey.pem`    | TLS **private key**                               | `root:loxmq` `0640`  |
| `/etc/loxmq/env`                  | profile + secrets + host overrides (env file)     | `root:loxmq` `0640`  |
| `/var/lib/loxmq/logs/`            | rotated logs (`application.log`, `audit.log`, …)  | `loxmq:loxmq` `0750` |
| `/var/lib/loxmq/cache/`           | LoxAPP3 structure cache (`<app.id>/…`)            | `loxmq:loxmq` `0750` |

> **Why `/var/lib/loxmq` and not `/opt/loxmq` for data:** the unit runs
> under `ProtectSystem=strict` with `WorkingDirectory=/opt/loxmq` read-only.
> The only writable tree is `/var/lib/loxmq`, so the unit sets
> `LOG_DIR=/var/lib/loxmq/logs` and `CACHE_DIR=/var/lib/loxmq/cache`. Leave
> those as-is.

### 7.2 Create the service user and directories

```bash
# System user, no shell, no home — owns the binary, logs and cache.
sudo adduser --system --group --no-create-home loxmq

sudo install -d -o loxmq -g loxmq -m 0750 /opt/loxmq /var/lib/loxmq \
     /var/lib/loxmq/logs /var/lib/loxmq/cache
sudo install -d -o root  -g loxmq -m 0750 /opt/loxmq/certs /etc/loxmq
```

### 7.3 TLS certificates

Copy your full chain and private key into `/opt/loxmq/certs/` under the
exact names `application-prod.yaml` expects:

```bash
sudo install -o root -g loxmq -m 0640 fullchain.pem /opt/loxmq/certs/fullchain.pem
sudo install -o root -g loxmq -m 0640 privkey.pem   /opt/loxmq/certs/privkey.pem
```

The `prod` profile references them as
`${LOXONE_CERT_DIR:/opt/loxmq/certs}/fullchain.pem` and `…/privkey.pem`.
The `loxmq` user only needs **read** (group `loxmq`, mode `0640`) — it does
not need the `ssl-cert` group with this side-car layout.

> **Renewal is live.** Quarkus polls the cert mtime every hour
> (`reload-period: PT1H`) and rebuilds the TLS context **without a restart**.
> To rotate, drop the new PEMs in place (same names/owner/mode); a central
> certbot host can `rsync --chown=root:loxmq --chmod=0640` them on renewal.
> To use the classic certbot layout instead, set
> `LOXONE_CERT_DIR=/etc/letsencrypt/live/<domain>` and add `loxmq` to the
> `ssl-cert` group.

### 7.4 Secrets and profile (`/etc/loxmq/env`)

Write the environment file the unit loads (`EnvironmentFile=-/etc/loxmq/env`).
Credentials are **Base64** (all four); hosts are plain:

```bash
sudo tee /etc/loxmq/env >/dev/null <<'EOF'
QUARKUS_PROFILE=prod

# Where the gear lives (override the *.example.com YAML defaults):
MINISERVER_HOST=miniserver.example.com
MINISERVER_PORT=443
MQTT_BROKER_HOST=broker.example.com
MQTT_BROKER_PORT=8084

# Credentials — Base64 (echo -n 'value' | base64):
LOXONE_MINISERVER_AUTH_USER=...
LOXONE_MINISERVER_AUTH_PASSWORD=...
LOXONE_TRANSPORT_SECURITY_CREDENTIALS_USER=...
LOXONE_TRANSPORT_SECURITY_CREDENTIALS_PASSWORD=...
EOF
sudo chown root:loxmq /etc/loxmq/env && sudo chmod 0640 /etc/loxmq/env
```

> Set `MINISERVER_HOST` / `MQTT_BROKER_HOST` explicitly — the YAML ships
> neutral `*.example.com` placeholders, so without these the bridge would
> never reach your gear. Use `QUARKUS_PROFILE=prod` (never `production` —
> the literal must match the profile name).

### 7.5 Install the binary and the unit

```bash
# Native binary, built with the prod profile (§5.1 with -Dquarkus.profile=prod):
sudo install -o loxmq -g loxmq -m 0755 target/loxmq-1.1.0-runner /opt/loxmq/loxmq

# systemd unit (native shown; use loxmq-jvm.service for the fast-jar):
sudo cp service/loxmq-native.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now loxmq-native.service
```

The unit pins `ExecStart=/opt/loxmq/loxmq`, `User=loxmq`,
`Restart=on-failure` (`RestartSec=45s`) and a crash-loop guard
(`StartLimitBurst=3` / `StartLimitIntervalSec=300`). It binds `:8443`;
port `:8080` redirects to it. To bind privileged `:443` instead, uncomment
`AmbientCapabilities=CAP_NET_BIND_SERVICE` in the unit.

### 7.6 Verify the deployment

```bash
# Health over HTTPS (use your real FQDN; -k only if the chain is self-signed):
curl -s https://loxmq.example.com:8443/q/health/live
curl -s https://loxmq.example.com:8443/q/health/ready    # UP once Miniserver + broker are connected

# Live logs:
journalctl -u loxmq-native.service -f
```

`health/ready` flips to **UP** once `session.state` is `RUNNING` and the
broker is connected (~1 s on native). If it stays `DOWN`, jump to
**[RUNBOOK.md](./RUNBOOK.md)** — it is organised by symptom.

---

## 8. Deploy with Docker (published image)

§7 deploys the binary under `systemd` — the maintained production posture.
**This section is the alternative for users who just want to run loxmq with
Docker, with no Java/Maven/GraalVM toolchain.** Pre-built images are pulled
from Docker Hub; you only configure `.env`, drop your TLS cert in `./certs`,
and read the logs.

> Full reference — image tags, day-2 operations, advanced config override —
> in **[docker/README.md](./docker/README.md)**. This is the condensed path.

### 8.1 Choose an image

Images live on Docker Hub at
**[`quaddan/loxmq`](https://hub.docker.com/r/quaddan/loxmq)**:

| Tag        | Contents                       | Architectures          | Use when                                       |
|------------|--------------------------------|------------------------|------------------------------------------------|
| `:native`  | GraalVM native binary (~175 MB)| `linux/amd64` only     | x86-64 server / NAS — smallest, instant start  |
| `:jvm`     | Temurin 25 fast-jar (~360 MB)  | `linux/amd64`, `arm64` | ARM hosts (Raspberry Pi, ARM NAS, Apple silicon)|
| `:latest`  | same as `:jvm` (multi-arch)    | `linux/amd64`, `arm64` | "runs anywhere"                                |

> **On ARM, use `:jvm`** (set `LOXMQ_TAG=jvm` in `.env`). The amd64-only
> `:native` image will not start on ARM.

### 8.2 Configure, certs, run

```bash
# 1. CONFIGURE — same .env as §3 (hosts, Base64 creds, your own app id).
cp .env.example .env
$EDITOR .env                              # set LOXONE_MINISERVER_APP_ID (uuidgen), hosts, creds

# 2. CERTS — prod serves HTTPS, so provide your cert + key (see §7.3).
mkdir -p certs logs cache config
cp /path/to/fullchain.pem /path/to/privkey.pem certs/
# The container runs as uid/gid 185 — the PEM files must be readable by it:
chmod 0644 certs/fullchain.pem && chmod 0640 certs/privkey.pem
sudo chown 0:185 certs/privkey.pem
sudo chown -R 185:185 logs cache

# 3. PULL + RUN
docker compose -f docker-compose.published.yml pull
docker compose -f docker-compose.published.yml up -d
```

The mounts: `./certs` (read-only, hot-reloaded on renewal), `./logs`,
`./cache`, and an optional `./config/application.yaml` to override any
**runtime** key (build-time keys stay baked — see
[docker/README.md](./docker/README.md)).

### 8.3 Verify and read logs

```bash
# Health over HTTPS (-k only because of any self-signed chain):
curl -sk https://localhost:8443/q/health/ready

# Live console:
docker compose -f docker-compose.published.yml logs -f
# …or the rotated files on the host: logs/application.log, error.log, warn.log,
#    commands.log, audit.log — or the built-in /logs dashboard page.
```

`health/ready` flips to **UP** once the Miniserver session reaches `RUNNING`
and the broker is connected. If it stays `DOWN`, see §9 and
**[RUNBOOK.md](./RUNBOOK.md)**.

---

## 9. First-run troubleshooting

| Symptom                                                  | First thing to check                                                                          |
|----------------------------------------------------------|-----------------------------------------------------------------------------------------------|
| `Port 8080 seems to be in use by another process`        | another instance is running — find and stop the PID.                                          |
| `Bootstrap failed — HTTP connect timed out`              | is `MINISERVER_HOST` / `MINISERVER_PORT` reachable from the host?                             |
| `MQTT … CONNACK contained an Error Code: NOT_AUTHORIZED` | Base64 broker credentials correct, and the user exists on the broker side?                    |
| Readiness stuck `DOWN`                                   | inspect `/api/v1/state`: is `session.state` reaching `RUNNING` and `broker.connected` `true`? |
| `… profile 'prod' … different from runtime`              | native image built with the wrong profile — rebuild with `-Dquarkus.profile=prod`.            |

For anything deeper, see **[RUNBOOK.md](./RUNBOOK.md)** (incident playbook by
symptom) and **[FAQ.md](./FAQ.md)**.
