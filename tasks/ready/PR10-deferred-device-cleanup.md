# PR10 — Persistent Deferred Cleanup for Device Retirement

- Status: Ready
- Priority: P1
- Depends on: PR5, PR8, PR9

## Objective

Make device retirement safe when some output chunks are unloaded or worlds are temporarily unavailable. A device must never be forgotten while it may still leave powered redstone outputs behind.

## Context

Today, disabled device records remain in `data/devices.yml` so their outputs can be cleared when chunks load. Deleting a record before all 16 output positions are confirmed cleared would discard the only durable coordinates needed to clean unloaded chunks. PR10 introduces a durable cleanup queue as the prerequisite for any in-game remove command.

## Scope

- Add a versioned, separately persisted cleanup queue containing immutable cleanup jobs derived from a device's world UUID/name and all 16 output coordinates.
- Add a safe retirement operation that first durably records the cleanup job, then removes the device from the active device store. Never remove the active record before the cleanup intent is durable.
- Process cleanup only through the owning region scheduler. Check chunk-loaded state before any block lookup; never force-load chunks or generate worlds.
- Clear only blocks that are still redstone wire and set their power to zero. Do not overwrite unrelated block types.
- Retry remaining positions when their chunks load and across plugin restarts. Completed positions must be idempotent and must not be reintroduced.
- Keep cleanup processing serialized with device mutations and reload so stale snapshots cannot resurrect retired devices or drop cleanup jobs.
- Expose read-only queue counts/status through diagnostics or a dedicated subcommand only if it can be done without broadening command scope unnecessarily.

## Constraints

- Preserve Paper/Folia region ownership for all Bukkit world/chunk/block access.
- No synchronous file I/O from region callbacks. Persist detached immutable state through the existing safe persistence boundary.
- Crash consistency: durable cleanup intent must exist before the device record is removed.
- Unknown/missing worlds and unloaded chunks remain pending; do not silently mark them complete.
- A non-redstone block at an output coordinate is not overwritten. Record a terminal/skipped outcome with enough context for an operator to inspect.
- Queue growth must be bounded by completed-job compaction/retention policy; never keep player references.
- Do not enable a user-facing remove command until all safety acceptance criteria pass on both Paper and Folia.

## Acceptance

- Unit tests prove cleanup intent is persisted before device removal; simulated persistence failure leaves the device active.
- Restart tests prove pending jobs survive reload and are resumed.
- Unloaded chunks are skipped without block lookup and are retried after chunk load.
- Missing worlds remain pending and resume when the world becomes available.
- Cleanup only changes REDSTONE_WIRE to power 0; unrelated blocks are never modified.
- Repeated processing is idempotent; completed positions are not rewritten.
- Concurrent reload/enable/disable/retire operations cannot lose queue entries or resurrect retired devices.
- Paper and Folia runtime smoke tests cover loaded and unloaded output chunks, restart recovery, and chunk-load retry.
- `./scripts/verify.sh`, Gradle CI, shaded JAR inspection, and the Paper/Folia runtime matrix pass.
- Only after all above pass may a separately reviewed PR expose `/rtime device remove <uuid>`.

## Deliverables

- Versioned cleanup queue model and persistence.
- Crash-safe retirement service and region-safe cleanup processor.
- Retry/recovery lifecycle integration.
- Tests, runtime smoke coverage, and updated operator documentation.
- A separate explicit decision/PR for exposing the remove command.
