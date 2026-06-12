# loxmq — Loxone Miniserver ⇄ MQTT v5 bridge

**Production-grade bridge between a [Loxone](https://www.loxone.com) Miniserver
and an MQTT v5 broker.** Decodes Miniserver states in real time, republishes
them over MQTT v5 (Home Assistant-friendly), routes inbound MQTT commands back
to the Miniserver, and ships a web admin UI (live states, schedules, Loxone
users/groups, logs).

- **Source & full documentation:** <https://github.com/quaddan/loxmq>
- **Docker guide (configuration, certs, day-2):**
  <https://github.com/quaddan/loxmq/blob/main/docker/README.md>
- **License:** Apache 2.0

## Tags

| Tag | Contents | Architectures |
|-----|----------|---------------|
| `native` | GraalVM native binary, ~175 MB, instant start | `linux/amd64` |
| `jvm` | Temurin 25 fast-jar, ~360 MB | `linux/amd64`, `linux/arm64` |
| `latest` | same as `jvm` | `linux/amd64`, `linux/arm64` |
| `<version>-native` / `<version>-jvm` | pinned release | as above |

> **On ARM (Raspberry Pi, ARM NAS, Apple silicon) use `jvm`** — the `native`
> image is amd64-only and will not start there.

## Quick start (Docker Compose)

```bash
# Compose file + env template:
curl -O https://raw.githubusercontent.com/quaddan/loxmq/main/docker-compose.published.yml
curl -O https://raw.githubusercontent.com/quaddan/loxmq/main/.env.example
cp .env.example .env

# 1. CONFIGURE — edit .env: Miniserver host, MQTT broker host, credentials
#    (Base64-encoded), and your own LOXONE_MINISERVER_APP_ID (run `uuidgen`).
# 2. CERTS — the prod profile serves HTTPS; drop YOUR cert + key in ./certs:
mkdir -p certs logs cache config
cp /path/to/fullchain.pem /path/to/privkey.pem certs/
chmod 0644 certs/fullchain.pem && chmod 0640 certs/privkey.pem
sudo chown 0:185 certs/privkey.pem          # container runs as uid/gid 185
sudo chown -R 185:185 logs cache

# 3. RUN
docker compose -f docker-compose.published.yml up -d
```

Dashboard: **`https://<your-host>:8443/`** — health:
`curl -sk https://localhost:8443/q/health`.

## Configuration

Everything is driven by environment variables (see
[`.env.example`](https://github.com/quaddan/loxmq/blob/main/.env.example) —
every variable is documented there). The core set:

| Variable | What |
|----------|------|
| `MINISERVER_HOST` / `MINISERVER_PORT` | your Loxone Miniserver |
| `MQTT_BROKER_HOST` / `MQTT_BROKER_PORT` | your MQTT v5 broker |
| `LOXONE_MINISERVER_AUTH_USER` / `…_PASSWORD` | Loxone account (Base64) |
| `LOXONE_TRANSPORT_SECURITY_CREDENTIALS_USER` / `…_PASSWORD` | broker account (Base64) |
| `LOXONE_MINISERVER_APP_ID` | a UUID **you** generate (`uuidgen`) |

**Plain-TCP broker (LAN Mosquitto on 1883)?** The default targets a TLS
WebSocket broker (`wss`). Add to `.env`:

```properties
LOXONE_TRANSPORT_CONNECTION_PROTOCOL=tcp
LOXONE_TRANSPORT_CONNECTION_SECURE=false
MQTT_BROKER_PORT=1883
```

## Volumes and ports

| Mount / port | Purpose |
|--------------|---------|
| `./certs → /opt/loxmq/certs` (ro) | `fullchain.pem` + `privkey.pem` — hot-reloaded on renewal |
| `./logs → /var/lib/loxmq/logs` | rotated logs (`application.log`, `error.log`, audit trails) |
| `./cache → /var/lib/loxmq/cache` | Loxone Structure-File cache |
| `8443` | HTTPS — dashboard + REST API |
| `8080` | HTTP (redirects to 8443) |

## Good to know

- **The container exits (code 1) and restarts if the broker or Miniserver is
  unreachable at boot** — fail-fast by design; `docker compose logs` names the
  failing leg (`Boot N/3 ✗`). Set `LOXONE_BOOT_HALT_ON_FAILURE=false` to keep
  it up and recover from the dashboard instead.
- Runs as non-root (uid/gid **185**) on both image flavours.
- Issues and feature requests: <https://github.com/quaddan/loxmq/issues>
