# Admin surface — Users · Schedules · Logs

Beyond the **passive** bridge (outbound state events + inbound command I/O),
loxmq exposes an **active** admin surface to the Miniserver: REST endpoints
plus three management pages (`/users`, `/schedules`, `/logs`). This document
is the single home for that surface — both **how it is built** (design) and
**how to use it** (operator guide).

Deep protocol/CDI rationale lives in [ARCHITECTURE.md](./ARCHITECTURE.md);
incident diagnosis lives in [RUNBOOK.md](./RUNBOOK.md).

**Contents**

1. [What the surface exposes](#1-what-the-surface-exposes)
2. [Design](#2-design)
3. [Operator guide — the pages](#3-operator-guide--the-pages)
4. [Transport reliability notes](#4-transport-reliability-notes)

---

## 1. What the surface exposes

| Endpoint group                                        | Verb        | Purpose                                                              | Spec                          |
|-------------------------------------------------------|-------------|----------------------------------------------------------------------|-------------------------------|
| `/api/v1/schedules`                                   | full CRUD   | operating calendar (seasonal modes, holidays, off-season) without Loxone Config | *OperatingModeSchedule* V14.4 |
| `/api/v1/users` + `/api/v1/groups`                    | read-only   | visual audit of Miniserver users and groups                          | *Usermanagement* V17          |
| `/api/v1/users/{uuid}/disable`                        | POST        | disable a user (refuses if the target is an admin)                   | *Usermanagement* V17          |
| `/api/v1/users/{uuid}/groups/{gid}`                   | POST/DELETE | assign / remove group membership                                     | *Usermanagement* V17          |

Each group is backed by a Qute page (`/schedules`, `/users`, `/logs`) — a
minimal HTML shell + vanilla JS (~150–700 lines) that calls the REST
endpoints via `fetch`. No external JS dependency, and the page renders even
if the session is `DISCONNECTED` (no server-side serialization of entries).

---

## 2. Design

### Synchronous request/reply correlation

`MiniserverAdminCommandClient` adds a synchronous request/reply layer on top
of the encrypted WebSocket pipeline. The Loxone protocol carries no
per-request correlation ID, but each encrypted response echoes verbatim the
encrypted `control` that was sent. The client decrypts that echo with the
session AES key (held by `LoxoneCryptoService`) and uses it as the matching
key against a `ConcurrentMap` registry of pending `CompletableFuture`s.

Two callers racing on the same path share one future via `putIfAbsent`
(idempotent for reads; writes are naturally serialised by the form-post).
This lives side by side with the `MiniserverCommandEvent` pipeline that
republishes everything on MQTT — both observers coexist.

### Dedicated audit log

User mutations are written to a separate `audit.log` (category `"audit"`,
routed out of the main log by a dedicated handler in `application.yml`,
30-day rotation). The format is ISO-8601 + `DISABLE` / `REFUSE` / `ASSIGN` /
`REMOVE`, so an operator can produce a one-off report with `grep`.

### No bearer auth (deliberate)

There is intentionally **no per-endpoint auth** on the admin surface. As
long as the global posture stays LAN+VPN, ad-hoc auth on one endpoint would
be premature; if the surface is ever exposed, the right move is a uniform
session-aware filter on **all** routes (reads and writes), not a one-off.
Belt and braces meanwhile: the audit log, the admin-protection guard on
`disable`, and standardised HTTP error mapping. (Rationale in the
`UserMutationService` javadoc.)

### V17 protocol quirks handled

The Users surface absorbs several firmware/spec inconsistencies:

| Quirk                                    | Handling                                                              | Effect           |
|------------------------------------------|----------------------------------------------------------------------|------------------|
| `addedituser` endpoint 404              | V17 p.7-8 → the real command is `addoredituser`                       | Create/Edit work |
| `UserDetail.userGroups` always empty     | V17 p.5-6 returns `usergroups` (lowercase); the binding read camelCase | Membership shows |
| 400 "Check calMode" on user/group        | `hint400(cmd, value)` is context-aware (schedule vs user vs idempotent) | Clear message    |
| Date fields swallowed                    | `UserDetail` extended with `validUntil` / `validFrom` / `expirationAction` | Edit prefill OK  |

`extractStringListTolerant(root, names…)` tries several spellings (V17
canonical first, fallbacks next) to absorb cases where one payload mixes
`usergroups` (lowercase) with `nfcTags` (camelCase).

### UI patterns

**Single status source — `computeUserStatus(detail)`.** One function
(`users.html`) interprets `userState` + `validFrom`/`validUntil` against the
current time (UTC-safe: Loxone epoch is seconds since 2009-01-01, offset
1230768000 to Unix). It returns `{kind, text}` and feeds **three** surfaces:
the View tab, the State chip colour, and the Members chips in the Groups
table. One place to change if the semantics evolve.

**Lazy enrichment — N+1 amortised.** `getuserlist2` (V17 p.4) returns only
name/uuid/isAdmin/userState. To get `validUntil`/`validFrom`/`userGroups`
you must call `getuser/{uuid}` per user. Blocking the render on N calls would
be unacceptable, so `enrichAllUsers(users)` fires them in parallel (bounded
by an admin semaphore + the browser's ~6 parallel fetches), and each
resolution broadcasts to the Validity cell, the State chip, and the group
Member chips. After `Promise.allSettled`, `finalizeEmptyGroupCells` turns
still-empty containers into a "no members" placeholder and sorts the chips
alphabetically (accent-insensitive `localeCompare(sensitivity:'base')`).

**Refresh — 30 s with pause-on-modal.** `/users` and `/schedules` auto-refresh
every 30 s; on `/users` the tick is skipped while any modal is open so the
operator's form is not wiped. The cadence is a compromise (a full
enrichment round is ~1–2 s for 20 users, semaphore-bounded). State is
session-scoped (no `localStorage`), so the toggle does not leak between
operators.

**Palette — one semantic mapping across pages:**

| Colour                  | Chip            | Button                            |
|-------------------------|-----------------|-----------------------------------|
| green (`--accent-up`)   | Active          | —                                 |
| amber (`--accent-warn`) | Pending         | —                                 |
| gray (`--fg-dim`)       | Disabled        | Disable (`.btn-muted`)            |
| red (`--accent-down`)   | Expired/Unknown | Delete (`.btn-danger`)            |
| blue (`--accent`)       | —               | + New user/group (`.btn-primary`) |

Chip text is black on every colour (white on red lacked contrast in dim light).

---

## 3. Operator guide — the pages

### Users — `State` chip vs `Validity` column

- The **`State` chip** shows the *configured mode*: `Active (no time limit)`,
  `Disabled`, `Enabled until`, `Enabled from`, `Timespan` — i.e. `userState`
  on the Loxone side (V17 Usermanagement p.14).
- The **`Validity` column** shows the *effective window*: `no limit`,
  `until 2026-06-30 18:00`, `from 2026-06-01 08:00`, a `from → to` range, or
  `—` for states without dates. Source: `getuser/{uuid}` `validUntil` /
  `validFrom`.

The chip answers "how it works" (mode); the column answers "when" (dates).

### Users — what the chip colour means

The chip **text** stays the mode; the **colour** reflects the live status
from `computeUserStatus`:

| Colour | Status   | Tooltip example                               |
|--------|----------|-----------------------------------------------|
| green  | active   | `Active — expires 2026-06-30 18:00`           |
| amber  | pending  | `Pending — starts 2026-06-01 08:00`           |
| red    | expired  | `Expired since 2025-12-31 23:59`              |
| red    | unknown  | `Missing validFrom or validUntil for state=4` |
| gray   | disabled | `Disabled`                                    |

`unknown` (red "Missing"): a `Timespan` user (state 4) **must** carry both
`validFrom` and `validUntil` per spec; if one is `0`/`null` (a partial edit
in Loxone Config), the chip flags the inconsistent Miniserver-side config.

### Users — group membership and the `Members` cell

A `Members` cell that shows `loading…` then `no members` is normal for an
empty group. The flow: `users-snapshot` renders the tables immediately with
empty cells → `enrichAllUsers` fires one `getuser/{uuid}` per user → each
response appends a chip to the groups that user belongs to → after all
settle, still-empty cells get the `no members` placeholder. A cell that
shows a red `?` means enrichment failed (timeout/403/network) — hover for
the message, and check the `/logs` panel (`error.log`) for an
`AdminCommandException`.

If a user you just assigned does **not** appear: that was a historical bug
(camelCase `userGroups` vs spec `usergroups`), now handled tolerantly. If it
persists despite a successful assign, check `error.log` for a
`getuser/{uuid}` failure. Chip order is alphabetical and stable
(accent-insensitive sort).

### Users / Schedules — auto-refresh

A 30 s `setInterval` calls `refresh()`; toggle it with the `☑ auto 30s`
checkbox. On `/users`, the tick pauses while a modal (View / Edit / Create /
Group) is open. Force an immediate refresh with the `↻ Refresh` button. The
preference is session-scoped (it does not leak to the next operator).

### Logs — filter and auto-scroll

- **`Min level: WARN+`** is a severity-≥ filter: `WARN+` shows WARN **and**
  ERROR (everything at least as severe). Stack-trace continuation lines
  inherit their parent line's level, so filtering `ERROR` keeps the whole
  multi-line block, not just the header.
- **Smart auto-scroll**: the viewer chases the bottom only if you were
  already at the bottom before the refresh (50 px tolerance); otherwise your
  scroll position is preserved so you can read history without being yanked
  on each tick.

---

## 4. Transport reliability notes

Two transport-side fixes matter here because they used to surface as admin
symptoms — a "dead-looking" binding and a dropped second command:

- **Disconnect log routing.** Disconnects are now logged at `ERROR` only
  when unintentional (broker drop `SERVER`, keepalive timeout `CLIENT`, WS
  handshake/4xxx/network), and at `INFO` when operator-initiated (`USER`,
  clean WS close). With the dedicated `error.log`, an unexpected disconnect
  is immediately visible instead of being buried in `application.log`.
- **No duplicate callbacks after reconnect.** `CommandSubscriber.onMqttConnected`
  now **unsubscribes before re-subscribing** on every CONNACK. The HiveMQ
  async API *adds* a callback per `subscribe` call rather than replacing it,
  so without this each reconnect stacked another callback — corrupting QoS-1
  flow control and silently swallowing the second command after a reconnect.
  Exactly one live callback per topic now. (Covered by
  `CommandSubscriberTest` reconnect-order tests.)

When either surfaces in production, see [RUNBOOK.md](./RUNBOOK.md) (§ broker
connectivity, § no events after bootup).
