# Running loxmq with Docker

This guide is for **users who just want to run loxmq** — you do **not** need
Java, Maven or GraalVM. You pull a pre-built image from Docker Hub and run it
with Docker Compose.

> Building the image yourself (from source) is a separate, maintainer-oriented
> path documented at the top of [`Dockerfile.dev`](Dockerfile.dev) and
> [`../docker-compose.yml`](../docker-compose.yml). Most users want the
> published image below.

---

## 1. Published images

Images live on Docker Hub at
**[`quaddan/loxmq`](https://hub.docker.com/r/quaddan/loxmq)**:

| Tag                    | Contents                          | Architectures            | Use when                                            |
|------------------------|-----------------------------------|--------------------------|-----------------------------------------------------|
| `:native`              | GraalVM native binary (~175 MB)   | `linux/amd64` only       | x86-64 server / NAS — smallest, instant start       |
| `:jvm`                 | Temurin 25 fast-jar (~360 MB)     | `linux/amd64`, `arm64`   | ARM hosts (Raspberry Pi, ARM NAS, Apple silicon)    |
| `:latest`              | same as `:jvm` (multi-arch)       | `linux/amd64`, `arm64`   | "just give me something that runs anywhere"         |
| `:1.0.1-jvm` / `-native` | a specific release              | as above                 | pinning a version                                   |

> Multi-arch (`arm64`) manifests are produced by the release workflow
> (`.github/workflows/release.yml`); an image pushed manually from a
> maintainer's x86-64 machine is `amd64`-only until the workflow has run.

> **On ARM, use `:jvm`.** A native `:native` image is amd64-only and will not
> start on ARM.

---

## 2. First run

```bash
# 1. Get the compose file + env template (clone the repo, or download just
#    these two files):
curl -O https://raw.githubusercontent.com/quaddan/loxmq/main/docker-compose.published.yml
curl -O https://raw.githubusercontent.com/quaddan/loxmq/main/.env.example
cp .env.example .env

# 2. CONFIGURE — edit .env: Miniserver host, MQTT broker host, credentials
#    (Base64-encoded), and your own LOXONE_MINISERVER_APP_ID (run `uuidgen`).
$EDITOR .env

# 3. CERTS — create the host folders and drop in YOUR TLS cert + key.
mkdir -p certs logs cache config
cp /path/to/fullchain.pem certs/
cp /path/to/privkey.pem   certs/
sudo chown -R 185:185 logs cache       # the container runs as uid/gid 185

# 4. PULL + RUN
docker compose -f docker-compose.published.yml pull
docker compose -f docker-compose.published.yml up -d
```

Open **`https://<your-host>:8443/`** — the dashboard.

To run the JVM image instead of the native one, set `LOXMQ_TAG=jvm` in `.env`.

---

## 3. The three things you manage

### Configure

Two layers, combine as you like:

1. **`.env`** — the common knobs: hosts, ports, credentials, `app id`,
   profile. Start from [`.env.example`](../.env.example); every variable is
   documented there. This covers almost everyone.
2. **`./config/application.yaml`** (optional, advanced) — mounted read-only at
   `/work/config`. Quarkus reads it at startup and it overrides any **runtime**
   key (log levels, timeouts, per-package logging, broker QoS…). Example:

   ```yaml
   # ./config/application.yaml — overrides on top of the baked prod profile
   quarkus:
     log:
       category:
         "com.quaddan":
           level: DEBUG
   ```

   > **Caveat:** *build-time* keys (TLS wiring, the active profile's HTTP
   > redirect/Swagger toggles) are compiled into the image and **cannot** be
   > changed via this file — they require a different image/profile.

> **Plain-TCP broker (no TLS)?** The baked `prod` profile expects a TLS
> WebSocket broker (`wss://…:8084/mqtt`). For a plain LAN Mosquitto on
> `1883`, override the transport at runtime — add to `.env`:
>
> ```properties
> LOXONE_TRANSPORT_CONNECTION_PROTOCOL=tcp
> LOXONE_TRANSPORT_CONNECTION_SECURE=false
> MQTT_BROKER_PORT=1883
> ```
>
> Accepted protocols: `tcp` `ssl` `tls` `mqtts` `ws` `wss`. Both keys are
> **runtime** config — they work on the published image as-is, native included.

### Certificates

The `prod` profile serves HTTPS, so it needs a cert. Put two PEM files in
`./certs`:

- `fullchain.pem` — certificate chain
- `privkey.pem` — private key

They are mounted **read-only**. On renewal, just replace the files on the host:
Quarkus polls their mtime every hour and rebuilds the TLS context **live — no
restart, no redeploy**.

> **Permissions:** the container runs as **uid/gid 185**, so the PEM files must
> be readable by it — otherwise the binding aborts at boot with
> `AccessDeniedException … fullchain.pem`. The simplest fix:
> ```bash
> chmod 0644 certs/fullchain.pem && chmod 0640 certs/privkey.pem
> sudo chown 0:185 certs/privkey.pem        # key stays non-world-readable
> ```

### Logs

Three ways to see what's happening:

```bash
# Live console (formatted, colourised)
docker compose -f docker-compose.published.yml logs -f

# Rotated files on the host (plain text, grep-friendly)
tail -f logs/application.log     # everything INFO+
tail -f logs/error.log          # ERROR only, with stack traces
tail -f logs/warn.log           # WARN only (recovered transients)
tail -f logs/commands.log       # inbound MQTT commands audit
tail -f logs/audit.log          # Miniserver user mutations audit
```

…or the built-in **`/logs`** page in the dashboard.

---

## 4. Day-2 operations

### Container restarting in a loop?

That is the **fail-fast boot** working as designed: if the MQTT broker or the
Miniserver is unreachable at startup, the bridge exits with code 1
(`loxone.boot.halt-on-failure=true`) and Docker's `restart: unless-stopped`
retries with backoff. Run `docker compose logs` — the last `Boot N/3 ✗` line
names the failing leg (1 = MQTT broker, 2 = Miniserver bootstrap, 3 =
Miniserver session). Fix the `.env` value it points at.

To keep the container up anyway (e.g. to reach the dashboard and recover via
the REST endpoints it suggests), set `LOXONE_BOOT_HALT_ON_FAILURE=false` in
`.env`.

### Update / stop / health

```bash
# Update to the latest image
docker compose -f docker-compose.published.yml pull
docker compose -f docker-compose.published.yml up -d

# Stop / start / restart
docker compose -f docker-compose.published.yml down
docker compose -f docker-compose.published.yml up -d

# Health
curl -k https://localhost:8443/q/health
```

Host folders you can relocate (set in `.env`): `LOXMQ_CERTS_DIR`,
`LOXMQ_LOGS_DIR`, `LOXMQ_CACHE_DIR`, `LOXMQ_CONFIG_DIR`.
