# Paper/Folia Scheduler Safety

RealTime targets Paper 26.3 and Folia 26.3. The scheduler choice is part of the operation's ownership model, not a performance preference.

## Scheduler selection

| Work | Scheduler | Rules |
|---|---|---|
| Server-wide state without a specific location | Global region | Do not use it to access arbitrary region-owned blocks/entities |
| Block or location work | Region | Schedule using the target location; do not use it for entity work |
| Entity work | Entity | Use the entity's scheduler so work follows it across teleports and region transfers |
| Pure computation or detached-data I/O | No Bukkit scheduler in this adapter | If later added, pass immutable/detached data only; never access Bukkit world/block/entity objects |

## Entity retirement

The entity scheduler can decline a task if the entity is already retired. When a scheduled entity task is retired before execution, its retired callback is invoked on the owning region. That callback must remain lightweight and must not:

- access the retired entity;
- access or mutate worlds, chunks, or blocks;
- load chunks or worlds;
- modify ticket levels;
- schedule work that assumes the entity still exists.

Callers must treat a null task handle as not scheduled and must not assume either callback will run when the scheduler was already retired.

## Lifecycle

- Every scheduled task is registered in `TaskRegistry`.
- One-shot task handles are released after execution or entity retirement.
- Repeating task handles remain tracked until cancelled or retired.
- `RealTimePlugin.onDisable` cancels all outstanding handles.
- Repeating tasks require an initial delay and period of at least one tick.
- Location arguments are cloned before scheduling to prevent caller mutation after submission.

## Verification

- Unit tests use fake scheduler interfaces to verify global/region/entity routing, entity retirement, handle cleanup, and cancellation.
- The runtime smoke workflow starts disposable Paper 26.3 and Folia 26.3 servers, waits for the global scheduler callback, and executes `/rtime` and `/rtime reload`, and asserts the clock overview.
- The runtime smoke does not claim to prove world/block/entity mutation safety. Each future device operation must add region/entity-specific integration tests.

## API references

- [GlobalRegionScheduler — Paper 26.3](https://jd.papermc.io/paper/26.3/io/papermc/paper/threadedregions/scheduler/GlobalRegionScheduler.html)
- [RegionScheduler — Paper 26.3](https://jd.papermc.io/paper/26.3/io/papermc/paper/threadedregions/scheduler/RegionScheduler.html)
- [EntityScheduler — Paper 26.3](https://jd.papermc.io/paper/26.3/io/papermc/paper/threadedregions/scheduler/EntityScheduler.html)