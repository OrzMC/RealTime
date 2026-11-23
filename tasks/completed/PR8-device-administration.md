# PR8 — Device Administration Commands

- Status: Complete
- Priority: P1
- Depends on: PR5 and PR7 (merged)
- Merged: PR #12 — https://github.com/OrzMC/RealTime/pull/12
- Merge commit: `c4888a7857711f7d84e9c1cb2695e7c4931ce0f1`

## Delivered

- `/rtime device list`: list configured devices with name, UUID, world, and enabled state.
- `/rtime device info <uuid>`: show metadata and all 16 output coordinates.
- `/rtime device enable <uuid>` / `disable <uuid>`: persist enabled state and refresh outputs.
- `realtime.command` for read-only commands; `realtime.admin` for mutations.
- Reuses versioned `YamlDeviceRepository`; serializes mutations with reload through a shared runtime-state lock.
- Persists candidate device list before publishing the new immutable RuntimeState.
- Failed persistence leaves the active runtime state unchanged.
- Disabled outputs are cleared through region-safe refresh and chunk-load handling.
- In-game deletion remains deferred until a persistent cleanup queue can safely handle unloaded chunks.

## Verified acceptance

- Permission checks, malformed/missing UUIDs, idempotent mutations, persistence failure, reload serialization, and tab completion are covered by tests.
- Paper and Folia smoke tests exercise list/info/enable/disable and reload.
- Gradle CI, shaded JAR inspection, Agent Project Validation, Paper/Folia and PlaceholderAPI smoke matrix passed after fixing the localized output assertion.
