# Testing Strategy

## Layers

1. **Core unit tests** (`realtime-core`): clock, timezone/DST conversion, locale formatting, snapshots, clock-digit
   encoders, `AnalogScale`, `DeviceLayout`, `DeviceDemo` geometry, and device-model validation. Pure JVM, no server.
2. **Configuration tests**: defaults, parsing, migrations, invalid values, per-resolution slot counts, command parsing and completion (no server required). The updater's `modules/updates.yml` is covered the same way, including rejected typos in `allowed-hosts`.
3. **Paper unit tests** (`realtime-paper`): command parsing/completion/dispatch and permissions, device/cleanup services,
   YAML repository schema round-trips and migrations (including legacy BCD entries being skipped), diagnostics, and the
   region-aware managers driven through fake Bukkit proxies (no server needed). The updater is covered without network
   access: HTTP against a loopback `com.sun.net.httpserver` (allowlist, redirect escape, size caps), platform payload
   parsing from fixtures, and the installer against a real temporary directory.
4. **Release-script tests** (`scripts/test-release-scripts.sh`, `validate-release.sh`): tag/changelog rules, artifact
   inspection, checksums.
5. **Runtime smoke jobs** (CI, `scripts/smoke-*.sh`, real Paper and Folia servers):
   - `smoke-server.sh` — boot, `/rtime` overview, name addressing, `status`, `on`/`off`, `reload`
   - `smoke-device-cleanup.sh` — restart recovery, unloaded-chunk deferral, chunk-load retry, `NEEDS_ATTENTION`
     preservation (a player block is never overwritten), operator retry, retirement by name, lectern cleanup
   - placeholder smoke — PlaceholderAPI expansion output
   - `smoke-update.sh` — update check against a local fixture "Hangar", verified download into `plugins/update/`,
     Paper's swap on the next boot, and refusal of a checksum mismatch
6. **Real-server acceptance** (`docs/operations/`): the current report is
   [acceptance-report-0.6.0.md](../operations/acceptance-report-0.6.0.md); the 0.1.0 and 2026-11-25 documents are kept as
   history (they describe BCD-era builds), plus the per-PR evidence in `tasks/completed/`.

## Rules

- Time-dependent tests inject a fixed `java.time.Clock`; no sleeps and no assertions against the host clock.
- A successful compile is not proof of Paper/Folia behaviour. Runtime evidence must name the exact server build and the
  features exercised.
- Vanilla RCON block probes are advisory: Folia does not echo plugin commands run through `execute`, and a probe can
  answer with an empty body or an "unloaded" error. Assertions that must fail a pipeline use the plugin's own durable
  state (`data/devices.yml`, `/rtime cleanup`), not block probes.
- Run `./scripts/verify.sh` before pushing; it also fails on CRLF contamination in tracked text files
  (see [setup](../development/setup.md)).
