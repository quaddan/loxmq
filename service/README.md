# systemd deployment — `loxmq-{jvm,native}.service`

Two mutually exclusive unit files (reciprocal `Conflicts=`):

| Unit | Packaging | When to use it |
|---|---|---|
| `loxmq-jvm.service`    | fast-jar JVM (`/opt/loxmq/quarkus-app/quarkus-run.jar`) | First production deployment, active debugging (JFR always-on, heap tunable via `-Xms/-Xmx` in `ExecStart`). |
| `loxmq-native.service` | native GraalVM binary (`/opt/loxmq/loxmq`) | Final cutover. ~50 ms startup, ~50–80 MB RSS, standalone. **Recommended for prod.** |

This doc covers initial installation; the operational runbook
(recurring release, rollback, validations) lives in `service/DEPLOY.md`.

> ⚠️ **Never enable both units at the same time** — both
> bind the same HTTP port (`8443`) and publish the same LWT
> `…/status` retained. The reciprocal `Conflicts=` guarantees that
> `systemctl start <unit>` stops the other automatically, but it's
> still better to only enable the one you use, to avoid surprises at
> LXC boot.

---

## 1. Machine prerequisites

| Item | Detail |
|---|---|
| OS  | Any modern Linux with **systemd ≥ 254** (floor set by `RestartMode=direct` in the unit files). Debian 13+ "Trixie", Ubuntu 24.04 LTS+, Fedora 40+ … all qualify — no specific distro required. |
| User | `loxmq:loxmq` (created via `adduser --system --group --no-create-home loxmq`) |
| Java | If JVM packaging: OpenJDK 25+ at `/usr/bin/java`. Native: none. |
| TLS cert | Let's Encrypt on `*.example.com`, **Option B layout by default**: `/opt/loxmq/certs/{fullchain,privkey}.pem`. No `certbot` in the LoxMQ LXC — a centralized certbot LXC pushes certs via rsync. |
| Network | Loxone Miniserver (Gen1 or Gen2) reachable; MQTT broker reachable over `ws` / `wss` (MQTT-over-WebSocket). |

### Resource footprint

Steady-state figures (one Miniserver + one broker). On-disk sizes were
measured on the 1.0.0 artifacts; provision with headroom for GC spikes, the
JFR window, log rotation and OS page cache.

| Resource | Native binary | JVM fast-jar |
|---|---|---|
| **RAM — process (RSS)** | ~50–80 MB | ~250–320 MB — bounded by the unit's `-Xmx128m` heap + ~96 MB metaspace + 64 MB code-cache + 32 MB direct memory |
| **RAM — to allocate** (LXC/VM) | 128 MB min · **256 MB** recommended | 384 MB min · **512 MB** recommended |
| **Disk — app / binary** | ~88 MB — single ELF under `/opt/loxmq/` | ~45 MB — `/opt/loxmq/quarkus-app/` (mostly `lib/`, 42 MB) |
| **Disk — Java runtime** | none (self-contained) | a JDK/JRE 25 on the host: ~340–380 MB (full JDK) or ~50–150 MB (a `jlink` / headless runtime) |
| **Disk — runtime data** (`/var/lib/loxmq`) | `cache/` (`LoxAPP3.json`, a few KB–MB) + rotated logs | same, **plus** a JFR rolling window capped at 200 MB (`maxsize=200M` in the unit) |
| **Disk — to provision** | **≈ 256 MB** | **≈ 700 MB – 1 GB** (incl. the JDK and the 200 MB JFR window) |

CPU is negligible at steady state (event-driven I/O) — **1 vCPU** is plenty
for both modes.

---

## 2. Filesystem layout

```
/opt/loxmq/
├── quarkus-app/                       (if JVM fast-jar packaging)
│   ├── quarkus-run.jar
│   ├── app/
│   ├── lib/
│   └── quarkus/
├── loxmq                              (if native packaging — runner executable)
└── (LoxAPP3.json cache will be created under /var/lib/loxmq)

/var/lib/loxmq/      owner loxmq:loxmq, mode 0750
├── cache/                              (LoxApp3Cache writes here)
└── jfr/                                (Flight Recorder rotation)

/etc/loxmq/
└── env                                 owner root:loxmq, mode 0640
```

Creation:

```bash
sudo install -d -o loxmq -g loxmq -m 0750 /opt/loxmq
sudo install -d -o loxmq -g loxmq -m 0750 /var/lib/loxmq
sudo install -d -o loxmq -g loxmq -m 0750 /var/lib/loxmq/cache
sudo install -d -o loxmq -g loxmq -m 0750 /var/lib/loxmq/jfr
sudo install -d -o root -g loxmq -m 0750 /etc/loxmq
```

---

## 3. Env file

`/etc/loxmq/env` — secrets, never in git:

```env
QUARKUS_PROFILE=prod   # or staging depending on the LXC

# MQTT broker credentials (Base64 — encoding expected by the binding)
LOXONE_TRANSPORT_SECURITY_CREDENTIALS_USER=<base64-encoded-username>
LOXONE_TRANSPORT_SECURITY_CREDENTIALS_PASSWORD=<base64-encoded-password>

# Miniserver credentials (Loxone user account with web permission)
LOXONE_MINISERVER_AUTH_USER=<base64-encoded-username>
LOXONE_MINISERVER_AUTH_PASSWORD=<base64-encoded-password>
```

To encode a value in Base64:

```bash
echo -n 'my_password' | base64
```

See `.env.example` at the root of the repo for the full template, and
`SECURITY.md §1.1` for the recommended procedure.

Permissions:

```bash
sudo chown root:loxmq /etc/loxmq/env
sudo chmod 0640 /etc/loxmq/env
```

---

## 4. Access to Let's Encrypt certs

**Default layout**: Option B — certs side-car of the binary in
`/opt/loxmq/certs/`. The service runs as `loxmq`, the
certs are dropped there with `root:loxmq 0640` by the pipeline that pushes them
(centralized certbot LXC, CI build, or manual rsync).

### Setup on the LoxMQ LXC side (no certbot installed)

```bash
sudo install -d -o root -g loxmq -m 0750 /opt/loxmq/certs
```

That's it — the `loxmq` user doesn't need the `ssl-cert` group, the
push pipeline takes care of perms at drop time.

### Push pipeline from the certbot LXC (example)

```bash
#!/bin/sh
SRC="/etc/letsencrypt/live/example.com"
HOST="192.0.2.10"     # or LoxMQ LXC IP
DEST="/opt/loxmq/certs"

# Push with the right perms in a single command
rsync -av --chown=root:loxmq --chmod=0640 \
    "$SRC/fullchain.pem" "$SRC/privkey.pem" \
    "$HOST:$DEST/"

# No need to signal reload — Quarkus TLS Registry detects the
# mtime change on cert files and rebuilds the SSLContext
# automatically (see application-{staging,prod}.yml:
# quarkus.tls.server.reload-period = PT1H).
```

### For a different layout (override)

If you deploy outside an LXC with old-school local certbot (cert in
`/etc/letsencrypt/live/example.com/`), you can switch back the standard
path without modifying the profile config via env var:

```bash
# In /etc/loxmq/env
LOXONE_CERT_DIR=/etc/letsencrypt/live/example.com
```

The `${LOXONE_CERT_DIR:/opt/loxmq/certs}` in the profiles
takes the override. In this case, also add the user to the `ssl-cert` group:

```bash
sudo addgroup --system ssl-cert
sudo adduser loxmq ssl-cert
sudo chgrp -R ssl-cert /etc/letsencrypt/live/ /etc/letsencrypt/archive/
sudo chmod -R g+rX     /etc/letsencrypt/live/ /etc/letsencrypt/archive/
```

(And a certbot `--deploy-hook` to restore perms at renewal.)

---

## 5. Binary deployment

### Variant A — fast-jar (recommended for first production deployment)

```bash
# Local build (profile = prod | staging)
mvn -DskipTests -Dquarkus.profile=prod package

# Push to the server
rsync -av target/quarkus-app/ prod:/opt/loxmq/quarkus-app/
ssh prod 'sudo chown -R loxmq:loxmq /opt/loxmq/quarkus-app/'
```

The active `ExecStart` in the unit already points to `quarkus-app/quarkus-run.jar`.

### Variant B — native binary (final cutover after 24h staging)

```bash
# Native build (Mandrel container)
mvn -DskipTests -Pnative -Dquarkus.profile=prod -Dquarkus.native.container-build=true package

# Push
scp target/loxmq-1.0.0-runner \
    prod:/opt/loxmq/loxmq
ssh prod 'sudo chown loxmq:loxmq /opt/loxmq/loxmq && \
          sudo chmod 0755 /opt/loxmq/loxmq'
```

Two separate units (jvm + native) coexist. Once variant B is
installed, switch by disabling `-jvm` and enabling `-native`
(see §6 below).

---

## 6. Unit installation

### 6.1 Installing the JVM (fast-jar) variant

```bash
sudo cp service/loxmq-jvm.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable loxmq-jvm.service     # active at boot
# DO NOT START right away — the actual start is driven by the release
# (see service/DEPLOY.md §A.5 or §B.1 depending on the scenario).
```

### 6.2 Installing the native variant

```bash
sudo cp service/loxmq-native.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable loxmq-native.service     # active at boot
# Same: start = release driven.
```

### 6.3 Switch JVM → native (and vice versa)

The reciprocal `Conflicts=` in both units means that `systemctl start`
on one stops the other automatically. But `enable` remains independent
— to switch cleanly at boot:

```bash
# Switch to native
sudo systemctl disable loxmq-jvm.service
sudo systemctl enable  loxmq-native.service
sudo systemctl restart loxmq-native.service    # stop -jvm + start -native
```

### Verify the parse

```bash
systemctl cat loxmq-jvm.service
systemctl cat loxmq-native.service
systemd-analyze verify /etc/systemd/system/loxmq-{jvm,native}.service
# → exit 0 expected for each
```

### 6.4 Environment variables set by the unit files

Both unit files (jvm + native) set two crucial `Environment="…"` entries
to redirect read-write paths to `/var/lib/loxmq/`
rather than `/opt/...` (which is readonly via `ProtectSystem=strict`):

| Variable | Points to | Used by |
|---|---|---|
| `LOG_DIR`   | `/var/lib/loxmq/logs`  | `quarkus.log.file.path = ${LOG_DIR:logs}/application.log` and `commands.log` |
| `CACHE_DIR` | `/var/lib/loxmq/cache` | `loxone.miniserver.cache.directory = ${CACHE_DIR:cache}` |

Without these variables — i.e. if you write a homemade unit file or modify
ours — LoxMQ tries to write to `WorkingDirectory/logs/` and
`WorkingDirectory/cache/` which are READONLY → file handler silently
KO + LoxAPP3 cache re-downloads at every restart. Both folders are
created by `scripts/bootstrap/{staging,prod}_bootstrap.sh` with the right perms (`loxmq:loxmq 0750`).

---

## 7. Post-install checks

```bash
# The user exists and is in the ssl-cert group
id loxmq
# uid=… gid=… groups=…,ssl-cert

# The env file is readable by loxmq but not by anyone
sudo -u loxmq cat /etc/loxmq/env > /dev/null && echo OK
sudo -u nobody  cat /etc/loxmq/env 2>&1 | grep -q denied && echo "perms OK"

# The cache is writable
sudo -u loxmq touch /var/lib/loxmq/cache/.write-probe && \
    sudo -u loxmq rm  /var/lib/loxmq/cache/.write-probe && echo OK

# The binary is executable by loxmq (native variant)
sudo -u loxmq /opt/loxmq/loxmq --help 2>&1 | head -1
```

If all 4 checks pass: the machine is ready for the `systemctl start`
driven by the release (see `service/DEPLOY.md §A.5`).

---

## 8. Uninstall / rollback on the unit side

```bash
# Stop + disable both (if present — doesn't fail if one isn't installed)
sudo systemctl stop    loxmq-jvm.service    loxmq-native.service    2>/dev/null
sudo systemctl disable loxmq-jvm.service    loxmq-native.service    2>/dev/null

# Remove the unit files
sudo rm -f /etc/systemd/system/loxmq-jvm.service \
           /etc/systemd/system/loxmq-native.service

sudo systemctl daemon-reload
```

The binary and cache stay in place — delete manually if you want to
clean up entirely (`/opt/loxmq/`, `/var/lib/loxmq/`,
`/etc/loxmq/`).
