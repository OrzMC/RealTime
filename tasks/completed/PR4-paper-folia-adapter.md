# PR4 — Paper/Folia Platform Adapter

- Status: Complete
- Priority: P1
- Depends on: PR3

## Scope

- Implement ownership-aware global, region, and entity scheduler adapters using Paper 26.3 APIs shared by Paper and Folia.
- Track all returned task handles and cancel outstanding tasks during plugin shutdown.
- Release one-shot task handles after execution and entity task handles after retirement.
- Clone region target locations at scheduling time.
- Keep retired entity callbacks isolated from entity/world/chunk/block access.
- Do not expose a generic asynchronous Bukkit callback.

## Acceptance

- No arbitrary async Bukkit world/block/entity access.
- Global work is dispatched through GlobalRegionScheduler.
- Position/block work is dispatched through RegionScheduler.
- Entity work is dispatched through EntityScheduler; region scheduling is never used for entity work.
- Entity retirement is handled and a null task handle is treated as not scheduled.
- Task registry tests cover cleanup and cancellation.
- Paper 26.3 and Folia 26.3 disposable runtime tests load the plugin, execute the global scheduler callback, and pass `/rtime`, `/rtime info`, and `/rtime reload`.
- No production data or persistent server world is used.