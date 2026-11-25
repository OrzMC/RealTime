# Testing Strategy

## Layers

1. **Core unit tests** (`realtime-core`): clock, timezone/DST conversion, locale formatting, snapshots, BCD/analog
   encoders, `AnalogScale`, `DeviceLayout`, `DeviceDemo` geometry, and device-model validation. Pure JVM, no server.
2. **Configuration tests**: defaults, parsing, migrations, invalid values, per-encoding slot counts.
3. **Paper unit tests** (`realtime-paper`): command handling and permissions, device/cleanup services, YAML repository
   schema round-trips and migrations (v1–v5), diagnostics, and the region-aware managers driven through fake Bukkit
   proxies (no server needed).
4. **Release-script tests** (`scripts/test-release-scripts.sh`, `validate-release.sh`): tag/changelog rules, artifact
   inspection, checksums.
5. **Runtime smoke jobs** (CI, `scripts/smoke-*.sh`, real Paper and Folia servers):
   - `smoke-server.sh` — boot, `/rtime`, info, device list/info/status, enable/disable, reload
   - `smoke-device-cleanup.sh` — restart recovery, unloaded-chunk deferral, chunk-load retry, `NEEDS_ATTENTION`
     preservation, operator retry, retirement, and power-source writes
   - placeholder smoke — PlaceholderAPI expansion output
6. **Real-server acceptance** (`docs/operations/`): the 0.1.0 report, the 2026-11-25 analog/layout report, and the
   per-PR evidence recorded in `tasks/completed/`.

## Rules

- Time-dependent tests inject a fixed `java.time.Clock`; no sleeps and no assertions against the host clock.
- A successful compile is not proof of Paper/Folia behaviour. Runtime evidence must name the exact server build and the
  features exercised.
- Vanilla RCON block probes are advisory: Folia does not echo plugin commands run through `execute`, and a probe can
  answer with an empty body or an "unloaded" error. Assertions that must fail a pipeline use the plugin's own durable
  state (`data/devices.yml`, `device cleanup list`), not block probes.
- Run `./scripts/verify.sh` before pushing; it also fails on CRLF contamination in tracked text files
  (see [setup](../development/setup.md)).
