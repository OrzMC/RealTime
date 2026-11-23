# PR9 — Redstone Device Diagnostics and Output Observability

- Status: In Progress
- Priority: P1
- Depends on: PR5, PR8

## Objective

Give operators a reliable view of redstone output health and recent refresh behavior without performing Bukkit world/block/chunk access from command logic or violating Paper/Folia ownership.

## Scope

Add `/rtime device status` as a read-only diagnostic command. It reports:
- configured device count and enabled device count;
- configured output count;
- cumulative region-output attempts;
- outputs skipped because their chunks were not loaded;
- outputs whose target block was not redstone wire;
- successful power changes and unchanged/no-op outputs;
- stale scheduled writes skipped because the current device/output configuration changed;
- write failures, if safely catchable and attributable.

Expose an immutable diagnostics snapshot from the Paper device layer. Use thread-safe counters because command execution and region callbacks may run on different owning threads. Keep diagnostic state out of `realtime-core`.

## Constraints

- The command must not access Bukkit worlds, chunks, blocks, or block data.
- Never load chunks or generate worlds to improve diagnostics.
- Every block read/write remains inside the owning region scheduler callback.
- Diagnostics must not alter device output behavior or suppress existing safety checks.
- Counters must have explicit semantics (attempt vs completion); avoid double-counting chunk-load refreshes and normal refreshes.
- Do not retain unbounded per-device history or player references.
- Preserve optional PlaceholderAPI behavior and existing command permissions.

## Acceptance

- `/rtime device status` requires `realtime.command`; unauthorized callers receive the normal no-permission response.
- Status output is available on both Paper and Folia.
- Snapshot reads are safe from command/global/region threads and immutable to callers.
- Tests cover counter increments for successful writes, unchanged outputs, unloaded chunks, invalid output blocks, stale configuration, and disabled-device cleanup.
- Existing region ownership and no-force-load tests remain green.
- Runtime smoke verifies the command output on Paper and Folia.
- `./scripts/verify.sh`, Gradle CI, shaded JAR inspection, and Paper/Folia plus optional PlaceholderAPI smoke pass.

## Deliverables

- Immutable diagnostics model and thread-safe metrics in the Paper device layer.
- `/rtime device status` command, localized messages, permission and tab-completion behavior.
- Unit tests and Paper/Folia runtime smoke coverage.
- Updated redstone-device docs, roadmap, and task status.
