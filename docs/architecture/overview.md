# Architecture Overview

RealTime is an independent Minecraft plugin. It provides timezone-aware real-world time and exposes it to server features. It must not depend on OrzMCPlugin or the parent repository `OrzMC/OrzMC` at build time or runtime.

## Modules

### realtime-core
Pure Kotlin/JVM domain logic: Clock-backed time source, timezone/locale handling, immutable snapshots, formatting, encoders, and platform-independent device models. It must not depend on Bukkit, Paper, Folia, PlaceholderAPI, or server runtime classes.

### realtime-paper
Plugin lifecycle, command registration, configuration and migration, persistence, Paper/Folia scheduler adapters, world/block/entity access, the update checker/downloader (`update/`), and optional integrations.

## Packaging

One distributable JAR is built by Shadow. Paper API remains compile-only; Kotlin runtime and project modules are bundled. Optional integrations must not prevent startup when absent.

## Time model

Use `Instant` for absolute time, explicit `ZoneId` for configured timezone, and `ZonedDateTime` for local calendar representation. Inject `Clock`; never rely on the JVM default timezone.

PR2 implements `RealTimeService`, `ClockRealTimeService`, and immutable `RealTimeSnapshot` in `realtime-core`. The service requires an explicit `Clock`, `ZoneId`, and `Locale`; the clock's own zone is ignored. A snapshot captures one instant and its zoned representation together. Formatting uses `DateTimeFormatter.ofPattern(pattern, locale)` and preserves the supplied snapshot's zone. Invalid patterns propagate as `IllegalArgumentException`; invalid zone identifiers are rejected by `ZoneId.of` with `DateTimeException`, which the Paper configuration adapter must turn into an actionable configuration error.

## Scheduler model

Use the Folia global scheduler for global state, region scheduler for block/location work, and entity scheduler for entity work. Never access Bukkit world/block/entity objects from arbitrary async threads. Multi-region devices fan out to region-local tasks and must not force-load chunks to refresh displays.

Blocking work (the update check's HTTP calls and the jar download) runs on Paper's `AsyncScheduler`, which owns no region and therefore cannot touch world state. Answers that must reach a player are bounced back through `CommandTarget.runOnOwner` (the player's own region, or the global region for the console).

## Configuration

Root config owns global enabled state, timezone, locale, and logging. Optional features own separate files under `plugins/RealTime/modules/` (`command.yml`, `redstone.yml`, `updates.yml`); device records and updater state live under `data/` separately from editable settings.

## Current state

All planned stages (PR1–PR20) are merged and `v0.8.0` is released; `main` is on `0.9.0-SNAPSHOT`.
Behaviour that exists today:

- **Time service**: `RealTimeService` / `ClockRealTimeService` with an injected `Clock`, explicit `ZoneId` and
  `Locale`, immutable `RealTimeSnapshot`, and locale-aware formatting.
- **Commands**: parsed by `CommandSyntax` (pure, unit-tested) and executed by a thin dispatcher. Reading is `/rtime`
  (time + every clock) and `/rtime <name>`; writing is `create` / `on` / `off` / `place` / `remove`; operators get
  `status` / `cleanup` / `reload` / `help`. Clock names are unique and may not shadow a verb, so `/rtime <name>` is
  unambiguous. Removed verbs answer with their replacement (see [usage guide](../usage.md#3-命令)).
- **Redstone devices**: lectern slots only — one per digit, page index = digit, so a comparator reads `digit + 1`.
  Tick-level detection with diff-only writes batched per chunk, a 30 s full re-check, immediate single-slot writes for
  `place`, and a durable deferred-cleanup queue.
- **Persistence**: `data/devices.yml` schema **v7** (devices + cleanup jobs + display blocks); entries written by a
  version that still used the removed BCD encoding are skipped and reported instead of being mis-loaded.
- **Diagnostics**: aggregate counters behind `/rtime status`; no world access from the command thread.
- **PlaceholderAPI**: optional internal `realtime` expansion, compile-only dependency.
- **Release automation**: protected tag workflow with changelog validation, reproducible artifacts, and optional
  Hangar/Modrinth publishing that stays disabled until the project IDs and tokens are configured.

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


### Redstone devices

The core maps local time onto digits in hour → minute → second order ([`TimeDigits`]) and gives every digit one slot:

- A slot is a lectern holding a plugin-created 15-page book. The page index is the digit and
  `LecternBlockEntity#getRedstoneSignal()` reduces to `page + 1`, so a comparator reads levels 1–10 without any bit
  decoding. Turning a clock off removes the book (level 0) but keeps the lectern, so the build stays visible.
- Layout is computed by `DeviceLayout` (`offset = index * stride`, default stride 2 → one empty block for the reading
  comparator). `DeviceDemo` derives the comparator/dust/lamp display from the same slot list, so a created clock is
  readable immediately.
- `place` writes a single slot through `DevicePlacement.placeSlotNow`, which builds the lectern with the requested
  facing and the current digit — positioning a slot is visible and reviewable instead of a silent coordinate change.

The Paper adapter persists validated device records in `data/devices.yml` (schema v7) and runs every block access on
the owning region: global work calculates the frame, region work checks `World.isChunkLoaded` before touching a block,
`ChunkLoadEvent` refreshes outputs when a chunk becomes available, and nothing ever force-loads a chunk. Writes are
diffed per device and grouped per chunk, with a 30 s full re-check for player edits and invalid blocks. Anything that
is not air (or a block/book the plugin placed) is reported as an invalid output and never overwritten. Retirement goes
through a durable cleanup job that clears slots and display blocks; slots with foreign content are reported as
`NEEDS_ATTENTION` for an operator. See [redstone device configuration](../development/redstone-devices.md).

### Optional PlaceholderAPI adapter

The optional Paper adapter registers an internal `realtime` expansion only when PlaceholderAPI is installed. Its API dependency is compile-only and is excluded from the shaded JAR. The bridge is isolated so the main plugin loads without PlaceholderAPI. Expansion requests read the current immutable runtime state and never depend on a player context. The expansion unregisters when RealTime disables.
