# PR12 — Second-Resolution Redstone Output (HHmmss)

- Status: Implemented, pending verification and PR
- Depends on: PR5 (BCD encoder and device model), PR8/PR9 (administration and diagnostics), PR10/PR11 (persistent cleanup and retirement)
- Scope: make redstone clocks able to display seconds without breaking Paper/Folia thread ownership, the durable device store, or the crash-safe retirement/cleanup path.

## User outcome

An operator can define a redstone clock with second precision: 24 output wire positions carry the six BCD digits HHmmss, refreshed once per second, and the host timezone still decides the displayed time. A cheaper 16-wire `minute` clock stays available.

## Requirements

- Digit order is always hour → minute → second, each digit least-significant bit first. `minute` devices expose bits 0–15 (HHmm), `second` devices expose bits 0–23 (HHmmss).
- Every device record declares `resolution: minute|second`; the output count must match (16 or 24) and bit indexes must cover `0..count-1` exactly once.
- The device store becomes schema v3. Schema v1/v2 files migrate automatically; devices without `resolution` become `minute` devices, and cleanup jobs keep the resolution of the retired device.
- Frames are calculated on the global scheduler. Minute devices refresh only when the minute changes; second devices refresh every second, and only bits whose power actually changed are written.
- All existing Paper/Folia rules stay in force: region-owned block access, `isChunkLoaded` guard before block lookup, no forced chunk loading, no `World`/`Chunk`/`Block` access from the command thread.
- Retirement and deferred cleanup cover all outputs of the device (16 or 24) with the same atomic persist-before-publish ordering.
- `/rtime device info` reports the device resolution; `%realtime_bcd%` / `%realtime_bcd_bits%` expose the full six-digit HHmmss frame.

## Acceptance

- `realtime-core` tests cover six-digit frames, 24-bit ordering, and the resolution-specific prefix.
- `realtime-paper` tests cover device/store/cleanup-job validation for both resolutions, v1/v2 → v3 migration, second-resolution retirement, per-second refresh scheduling, and that minute devices are not rewritten while only the second changes.
- `./scripts/verify.sh` passes (build, tests, spotless, release-script checks).
- Paper and Folia runtime smoke exercise a `second` device with 24 real wire positions through retirement and cleanup.

## Notes

- Not released yet: second resolution ships inside `0.1.0`, so no compatibility shim for the pre-release 16-bit-only layout is required.
- Physical wiring cost doubles for `second` devices; outputs must stay physically isolated to avoid adjacent wire coupling.
