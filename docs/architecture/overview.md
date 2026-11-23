# Architecture Overview

RealTime is an independent Minecraft plugin. It provides timezone-aware real-world time and exposes it to server features. It must not depend on OrzMCPlugin or the parent repository `OrzMC/OrzMC` at build time or runtime.

## Modules

### realtime-core
Pure Kotlin/JVM domain logic: Clock-backed time source, timezone/locale handling, immutable snapshots, formatting, encoders, and platform-independent device models. It must not depend on Bukkit, Paper, Folia, PlaceholderAPI, or server runtime classes.

### realtime-paper
Plugin lifecycle, command registration, configuration and migration, persistence, Paper/Folia scheduler adapters, world/block/entity access, and optional integrations.

## Packaging

One distributable JAR is built by Shadow. Paper API remains compile-only; Kotlin runtime and project modules are bundled. Optional integrations must not prevent startup when absent.

## Time model

Use `Instant` for absolute time, explicit `ZoneId` for configured timezone, and `ZonedDateTime` for local calendar representation. Inject `Clock`; never rely on the JVM default timezone.

PR2 implements `RealTimeService`, `ClockRealTimeService`, and immutable `RealTimeSnapshot` in `realtime-core`. The service requires an explicit `Clock`, `ZoneId`, and `Locale`; the clock's own zone is ignored. A snapshot captures one instant and its zoned representation together. Formatting uses `DateTimeFormatter.ofPattern(pattern, locale)` and preserves the supplied snapshot's zone. Invalid patterns propagate as `IllegalArgumentException`; invalid zone identifiers are rejected by `ZoneId.of` with `DateTimeException`, which the Paper configuration adapter must turn into an actionable configuration error.

## Scheduler model

Use the Folia global scheduler for global state, region scheduler for block/location work, and entity scheduler for entity work. Never access Bukkit world/block/entity objects from arbitrary async threads. Multi-region devices fan out to region-local tasks and must not force-load chunks to refresh displays.

## Configuration

Root config owns global enabled state, timezone, locale, and logging. Optional features own separate files under `plugins/RealTime/modules/`; device records live under `data/` separately from editable settings.

## Current state

PR1–PR3 are merged. PR4 adds a Paper/Folia scheduler facade with global, region, and entity ownership, tracked task handles, entity-retirement handling, and cancellation on plugin disable. Region scheduling is never used for entity work; the adapter exposes no generic asynchronous Bukkit callback.


### Paper/Folia scheduler ownership

PR4 provides `PaperFoliaScheduler` over the Paper 26.3 scheduler APIs:

- Global work uses `GlobalRegionScheduler` only for server-wide state that does not belong to a world location.
- Position and block work uses `RegionScheduler` with a defensive copy of the target `Location`.
- Entity work uses `EntityScheduler`; it follows the entity across region/world transfers. Region scheduling must never be used as a substitute for entity scheduling.
- Entity retirement callbacks must not touch the retired entity, worlds, chunks, or blocks.
- All returned tasks are tracked by `TaskRegistry`; plugin shutdown cancels all outstanding tasks.
- Repeating schedules require positive initial delay and period. One-shot handles are removed when the callback completes.
- No generic asynchronous Bukkit callback is exposed. Any future asynchronous work must operate on detached data only and hand world/entity mutations back to the appropriate owning scheduler.

Official Paper 26.3 API references: [GlobalRegionScheduler](https://jd.papermc.io/paper/26.3/io/papermc/paper/threadedregions/scheduler/GlobalRegionScheduler.html), [RegionScheduler](https://jd.papermc.io/paper/26.3/io/papermc/paper/threadedregions/scheduler/RegionScheduler.html), [EntityScheduler](https://jd.papermc.io/paper/26.3/io/papermc/paper/threadedregions/scheduler/EntityScheduler.html).
