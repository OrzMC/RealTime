# PR10 — Persistent Deferred Cleanup for Device Retirement

- Status: In Progress
- Active implementation: PR10a — schema v2 store model and v1 migration
- Priority: P1
- Depends on: PR5, PR8, PR9

## Objective

Make device retirement safe when some output chunks are unloaded or worlds are temporarily unavailable. A device must never be forgotten while it may still leave powered redstone outputs behind.

## Context

Today, disabled device records remain in `data/devices.yml` so their outputs can be cleared when chunks load. Deleting a record before all 16 output positions are confirmed cleared would discard the only durable coordinates needed to clean unloaded chunks. PR10 introduces a durable cleanup queue as the prerequisite for any in-game remove command.

## Implementation slices

- **PR10a (current):** introduce immutable cleanup-job/store models, schema v2 serialization, atomic v1-to-v2 migration, and store invariants.
- **PR10b:** add crash-safe retirement service and region-owned deferred cleanup/retry processor.
- **PR10c:** complete Paper/Folia restart/chunk-load runtime coverage and operator observability. Keep the user-facing remove command in a separate follow-up PR after all acceptance criteria pass.

## Scope

- Extend the existing versioned `data/devices.yml` document with cleanup jobs so the active device list and cleanup intent are committed by one atomic file replacement. Do not use two independent files for the device store and cleanup queue: there is no cross-file transaction. Migrate schema v1 to v2 with an empty cleanup-job list while preserving existing devices.
- Add a safe retirement operation that constructs one new store snapshot containing both the removed active device and its complete cleanup job, then persists that snapshot through the repository's temp-file + atomic-replace path before publishing it to runtime state. A failed write leaves both active state and durable state unchanged.
- Process cleanup only through the owning region scheduler. Check chunk-loaded state before any block lookup; never force-load chunks or generate worlds.
- Clear only blocks that are still redstone wire and set their power to zero. Do not overwrite unrelated block types.
- Retry remaining positions when their chunks load, when a world becomes available, and across plugin restarts. Completed positions must be idempotent and must not be reintroduced. A crash after clearing a wire but before persisting completion must safely retry by observing power 0.
- Keep cleanup processing serialized with device mutations and reload so stale snapshots cannot resurrect retired devices or drop cleanup jobs.
- Expose read-only queue counts/status through diagnostics or a dedicated subcommand only if it can be done without broadening command scope unnecessarily.

## Constraints

- Preserve Paper/Folia region ownership for all Bukkit world/chunk/block access.
- No synchronous file I/O from region callbacks. Region callbacks may only inspect/mutate the target block and report a detached result; persist that result later through the global/state-owner path.
- Crash consistency: the device list and cleanup intent must be persisted in the same atomic store replacement; do not rely on a two-file write ordering.
- Unknown/missing worlds and unloaded chunks remain pending; do not silently mark them complete. Capture world UUID when the world is resolvable at retirement; otherwise retain the configured world name as the pending resolution key. When a UUID is known, never fall back to a different world that reuses the old name.
- A non-redstone block at an output coordinate is not overwritten. Record a terminal/skipped outcome with enough context for an operator to inspect; it must remain visible as unresolved cleanup, not silently count as fully cleaned.
- Queue growth must be bounded by an explicit completed-job compaction/retention policy; compaction must itself be atomic and must never discard pending or operator-action-required positions. Never keep player references.
- Do not enable a user-facing remove command until all safety acceptance criteria pass on both Paper and Folia.

## Acceptance

- Unit tests prove cleanup intent is persisted before device removal; simulated persistence failure leaves the device active.
- Schema migration tests prove v1 device stores load as v2 without data loss; restart tests prove pending jobs survive reload and are resumed.
- Unloaded chunks are skipped without block lookup and are retried after chunk load.
- Missing worlds remain pending and resume when the world becomes available.
- Cleanup only changes REDSTONE_WIRE to power 0; unrelated blocks are never modified.
- Repeated processing is idempotent; completed positions are not rewritten.
- Concurrent reload/enable/disable/retire operations cannot lose queue entries or resurrect retired devices; all store mutations share the runtime-state lock.
- Paper and Folia runtime smoke tests cover loaded and unloaded output chunks, restart recovery, and chunk-load retry.
- `./scripts/verify.sh`, Gradle CI, shaded JAR inspection, and the Paper/Folia runtime matrix pass.
- Only after all above pass may a separately reviewed PR expose `/rtime device remove <uuid>`.

## Deliverables

- Versioned cleanup queue model and persistence.
- Crash-safe retirement service and region-safe cleanup processor.
- Retry/recovery lifecycle integration.
- Tests, runtime smoke coverage, and updated operator documentation.
- A separate explicit decision/PR for exposing the remove command.
