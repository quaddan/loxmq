---
name: Bug report
about: Report something that is not working as documented
title: "[BUG] "
labels: bug
assignees: ''
---

## Describe the bug

A clear and concise description of what the bug is.

## Steps to reproduce

1. Configure '...'
2. Run '...'
3. Observe '...'

## Expected behaviour

What you expected to happen instead.

## Logs

Paste the relevant snippet of `application.log` / `error.log` / `warn.log`
(or `journalctl -u loxmq-native.service -n 200 --no-pager` on a systemd
deployment). Redact any credentials.

```
<log snippet here>
```

## Environment

- loxmq version (see footer of the dashboard or
  `curl -s http://localhost:8080/q/info | jq .application.version`):
- Packaging (fast-jar / native binary / Docker):
- Java version (`java -version`):
- Operating system:
- Miniserver firmware version (visible in the dashboard under
  "Miniserver Identity"):
- MQTT broker brand + version (Mosquitto, EMQX, HiveMQ, …):

## Additional context

Anything else that may help: a `curl /api/v1/state | jq` snapshot, the
relevant `application*.yml` overrides, a diagram of the network topology
if non-trivial.
