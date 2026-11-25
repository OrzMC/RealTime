# PR14 — Tick-level Detection and Diff/Chunk-Batched Output Writes

- Status: Implemented and locally verified
- Depends on: PR13 (power-source outputs)
- Trigger: a display built on the old 1 Hz task could lag by up to ~1 s (and skip seconds when TPS drops) because the periodic task's phase relative to wall-clock seconds is arbitrary; and every refresh dispatched one region task per output, which is wasteful for many devices.

## Changes

- The global task period is now **1 tick**. It only reads the clock and compares epoch seconds; it returns before touching any device unless a frame changed, so the cost is one clock read per tick.
- Writes are **diffed per device**: only slots whose desired state changed are dispatched (typically 1–3 per second for a `second` device), and the changed slots are **grouped per chunk** (one region task per chunk instead of one per slot).
- A **full re-check every 30 s** re-dispatches all slots so player edits, invalid blocks and writes skipped by unloaded chunks are still noticed.
- Device identity is part of the diff cache, so a reload/enable forces a full rewrite of that device.
- `/rtime device status` counters now describe dispatched slot work, not a per-second poll; the docs say so.

## Measured

| Scenario (Paper 26.3, 24 plugins) | MSPT avg |
|---|---|
| 0 devices, 1 Hz task | 17–28 ms |
| 10 devices × 24 outputs, 1 Hz task | 15.7–16.7 ms |
| 0 devices, 1 tick task | 9.9–10.4 ms |
| 10 devices × 24 outputs, 1 tick task | 8.1–9.4 ms |

End-to-end display latency measured against the host clock: 60–80 ms (one to two ticks, including the region dispatch).

## Acceptance

- Unit tests cover the 1 tick period, per-chunk batching, diff-only scheduling, unchanged-second no-ops, minute devices, the 30 s full re-check, unloaded chunks, chunk-load refresh, disabled-device clearing, stale outputs and write failures.
- `./scripts/verify.sh`, Paper/Folia cleanup smokes and a re-run on the local test instances must pass before merge.
