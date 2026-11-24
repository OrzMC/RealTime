# Redstone Time Devices

## Configuration

`modules/redstone.yml` controls the optional output module:

```yaml
enabled: false
limits:
  max-devices: 50
  edit-cooldown-seconds: 3
  allowed-areas: []
  # - { world: world, min-x: 39000, max-x: 41000, min-z: -200, max-z: 200 }
```

The module is disabled by default. `limits` only affects the in-game authoring commands (`device create` / `device set`): an empty `allowed-areas` list means "no area restriction" (the commands still require `realtime.device.edit`, default op), `max-devices` caps how many devices may exist, and `edit-cooldown-seconds` throttles each player's authoring actions.

Each device output slot is a **power-source slot**: a logical 1 places a `minecraft:redstone_block` in the slot, a logical 0 leaves the slot empty (`minecraft:air`). Signals are therefore full strength (15) and vanilla redstone reacts to every change, so the surrounding circuit actually switches.

> Why not forced dust power: writing `power` onto a `redstone_wire` cannot work on the 26.x API surface. With physics the wire recalculates from its inputs and drops back to 0; without physics the value sticks but no consumer ever notices, and `World.updateNeighborsAt` no longer exists. Pre-release builds accepted an `output-power` value (1–15); that key is now ignored because a block cannot emit partial strength.

`data/devices.yml` stores versioned device records. It is separate from feature configuration and is backed up before repository writes.

## Output encodings

Every device declares how it materialises a digit: `encoding: bcd` (default) or `encoding: analog`.

| Encoding | Slots per digit | `minute` slots | `second` slots | What a slot is |
|---|---|---|---|---|
| `bcd` | 4 (LSB → MSB) | 16 | 24 | `redstone_block` = 1, air = 0 |
| `analog` | 1 | 4 | 6 | a lectern whose page index is the digit |

BCD keeps the existing wiring: 24 lines for `HHmmss`. Analog collapses a digit into a single comparator-readable level, so a `HHmmss` device needs **6 lines instead of 24**.

### Analog slots (lecterns)

Each analog slot is a `minecraft:lectern` holding a 15-page written book created by the plugin. Paper 26.3 computes the lectern's comparator output as

```java
float f = pageCount > 1 ? page / (pageCount - 1) : 1.0F;
return floor(f * 14.0F) + (hasBook ? 1 : 0);
```

which for 15 pages collapses to `page + 1`:

| Digit | Page | Comparator level |
|---|---|---|
| 0 | 0 | 1 |
| 1 | 1 | 2 |
| … | … | … |
| 9 | 9 | 10 |

No rounding is involved: all ten digits map onto distinct levels. A device that is disabled (or globally off) has its book removed, which makes the lectern emit level 0, so "off" is distinguishable from digit 0.

Writing a digit is a single block-entity operation (`setPage`), far cheaper than the 4 block writes of a BCD digit:

- The plugin places the lectern itself when the slot is air and enable the device; you build only the comparator and decoding circuit.
- The book is the ownership marker: title `RealTime <first 8 chars of the device UUID>`, author `RealTime`. A lectern holding any other book is reported as an invalid output and never modified.
- Writing the book resets the page to 0, so the page is always written after book installation.
- `setPage` notifies neighbouring blocks, so the comparator behind the lectern resamples immediately; no manual block update is needed.
- Lecterns are block entities: they only tick while the chunk is entity-ticking (a player nearby). A comparator in an unloaded chunk catches up as soon as the chunk ticks again, so the display is correct on arrival rather than live while nobody is around.
- Players can open the lectern and turn pages. The 30 s full re-check rewrites the page, so tampering self-heals within at most 30 s.

**Comparator direction:** `facing` points *toward the input*. Place the comparator with `facing` set towards the lectern; the output is on the opposite side. `comparator[facing=west]` therefore reads the lectern to its west and drives dust to its east.

Retirement follows the same rule as BCD slots: the cleanup job removes a lectern only while it still holds this device's book; any other block is left in place and reported as `NEEDS_ATTENTION`.

## Device store schema v4

The current `data/devices.yml` format uses schema version 4. Schema v4 adds `encoding` to device records and cleanup jobs; records written by earlier schemas are read as `bcd`, so an existing BCD clock keeps working after the upgrade. It stores active devices and deferred cleanup jobs in the **same file**, so a retirement operation can persist both sides of the state transition with one atomic replacement. Existing schema v1–v3 files are migrated automatically on load; device records are preserved, records without a `resolution` become `minute` devices, records without an `encoding` become `bcd` devices, and `cleanup-jobs` starts empty for v1. The previous file is retained at `backups/devices.yml.bak`.

Each device record declares `resolution: minute` or `resolution: second` and, since schema v4, `encoding: bcd` or `encoding: analog`. A `bcd` `minute` device has 16 outputs and a `bcd` `second` device 24 (HHmmss); an `analog` device has one output per digit, so 4 and 6 respectively. Cleanup jobs record the same resolution and encoding as the retired device, so every variant is cleaned completely.

A cleanup job records the retired device identity, world name, optional world UUID, resolution, and every output coordinate of that device. Each output has one of three states:

- `PENDING`: not yet safely processed (including unloaded chunks or unavailable worlds).
- `CLEARED`: the slot was empty, or a `redstone_block` was removed from it.
- `NEEDS_ATTENTION`: the slot holds something other than air or a redstone block (it is never overwritten), so an operator must inspect it.

Example shape (abbreviated):

```yaml
schema-version: 4
devices: []
cleanup-jobs:
  - id: 6b3c20e4-77e6-4d28-92d5-6a0d1fd20a10
    device-id: 45b8f930-0a13-4f8c-9b90-8eac4a2b4a10
    device-name: lobby-clock
    world: world
    world-uuid: null
    resolution: second
    encoding: bcd
    outputs:
      - { bit-index: 0, x: 100, y: 65, z: 100, status: PENDING, detail: null }
```

The complete job contains every output of the retired device (16/24 for a BCD `minute`/`second` device, 4/6 for an analog one). Do not hand-edit or delete cleanup jobs. Coordinates reserved by a cleanup job cannot be reused by an active device until the job is fully resolved and removed by the cleanup lifecycle. PR10a adds the schema and migration; PR10b adds the crash-safe retirement service and region-owned cleanup processor. PR10c adds operator queue inspection/retry and device-specific Paper/Folia runtime recovery coverage. PR11 exposes the disabled-only admin retirement command through the existing crash-safe service. PR12 adds second resolution. PR16 adds the optional analog (lectern) encoding and schema v4.

## BCD output layout

Digits are always ordered hour → minute → second, and each digit is emitted least-significant bit first. `minute` devices expose the first four digits; `second` devices expose all six.

| Bit indexes | Digit | Resolution | Bit order |
|---|---|---|---|
| 0–3 | Hour tens | both | LSB → MSB |
| 4–7 | Hour ones | both | LSB → MSB |
| 8–11 | Minute tens | both | LSB → MSB |
| 12–15 | Minute ones | both | LSB → MSB |
| 16–19 | Second tens | `second` only | LSB → MSB |
| 20–23 | Second ones | `second` only | LSB → MSB |

A `minute` device must define exactly 16 unique output slots and a `second` device exactly 24; bit indexes must cover `0` through `outputs - 1` exactly once. Slots must contain `minecraft:air` or `minecraft:redstone_block`; anything else is reported as an invalid output and never overwritten.

For example, 23:59:47 is encoded as digits `2, 3, 5, 9, 4, 7`; each digit occupies four bits, least-significant bit first.

## Device file example

The following shows a complete `minute` device (16 outputs, HHmm). A `second` device is identical except for `resolution: second` and 24 output records covering bit indexes 0 through 23. Change the world name and coordinates to match the build:

```yaml
schema-version: 4
devices:
  - id: 6b3c20e4-77e6-4d28-92d5-6a0d1fd20a10
    name: lobby-clock
    world: world
    enabled: true
    resolution: minute
    encoding: bcd
    outputs:
      - { bit-index: 0, x: 100, y: 65, z: 100 }
      - { bit-index: 1, x: 102, y: 65, z: 100 }
      - { bit-index: 2, x: 104, y: 65, z: 100 }
      - { bit-index: 3, x: 106, y: 65, z: 100 }
      - { bit-index: 4, x: 108, y: 65, z: 100 }
      - { bit-index: 5, x: 110, y: 65, z: 100 }
      - { bit-index: 6, x: 112, y: 65, z: 100 }
      - { bit-index: 7, x: 114, y: 65, z: 100 }
      - { bit-index: 8, x: 116, y: 65, z: 100 }
      - { bit-index: 9, x: 118, y: 65, z: 100 }
      - { bit-index: 10, x: 120, y: 65, z: 100 }
      - { bit-index: 11, x: 122, y: 65, z: 100 }
      - { bit-index: 12, x: 124, y: 65, z: 100 }
      - { bit-index: 13, x: 126, y: 65, z: 100 }
      - { bit-index: 14, x: 128, y: 65, z: 100 }
      - { bit-index: 15, x: 130, y: 65, z: 100 }
cleanup-jobs: []
```

Device IDs must be unique. Bit indexes must cover `0` through `output count - 1` exactly once (0–15 for `minute`, 0–23 for `second`). Output coordinates must be unique within a device and across all devices in the same world. World names are used for configuration convenience; changing a world's name requires updating this file.

## Runtime safety

- The global task runs **every tick** and only compares the wall-clock second with the last one it acted on, so a frame change is picked up on the first tick after it happens (≤ 50 ms at 20 TPS) instead of at an arbitrary phase of a one-second task. The extra runs cost one clock read each; they never iterate devices unless a second actually changed.
- Frame work is diffed per device: only slots whose desired state changed are dispatched, and the changed slots are grouped **per chunk** (one region task per chunk instead of one per slot — a 24-output device costs two region tasks instead of twenty-four). Minute devices therefore still only touch the world when their minute changes.
- Every 30 s a full re-check walks all slots of every device so player edits, invalid blocks and missed writes are still detected, without paying that cost every second.
- Block writes use physics, which is what notifies and powers the surrounding redstone network. Downstream redstone (repeaters, pistons, mechanical displays) adds its own tick delays, so a physical display always lags the plugin by a few ticks.
- A slot containing anything other than air or a redstone block is left untouched and counted as an invalid output, so player builds are never overwritten. Because writes are diffed, such a slot is reported on the next change of its bit or on the 30 s full re-check.
- Measured on the test server (Paper 26.3, 24 plugins, 240 outputs across 10 devices): tick impact is below measurement noise, and the display follows a wall-clock second within 60–80 ms end to end.
- Before `getBlockAt`, the owning-region callback checks `World.isChunkLoaded` for the target chunk.
- Unloaded chunks are skipped and their outputs are refreshed on `ChunkLoadEvent`.
- The manager never calls `getChunkAt`, chunk load APIs, or world generation APIs.
- Output slots should be physically isolated from unrelated redstone so two devices cannot couple; connect the receiving circuit next to the slot.
- Devices can be created and edited in game (`/rtime device create|set`); the YAML store remains the single source of truth and every slot coordinate stays reviewable.

After editing the files, use `/rtime reload` as an operator. A failed validation keeps the previous runtime configuration and device list active.

## In-game device administration

The device records remain YAML-defined so each output position is explicit and reviewable; a slot is a power source, not a display wire. After the plugin loads the file, operators can inspect and manage existing records in-game:

| Command | Permission | Behavior |
|---|---|---|
| `/rtime device create <name> <minute\|second> <world> <x> <y> <z> <x+\|x-\|z+\|z-> [stride] [bcd\|analog]` | `realtime.device.edit` | Creates a disabled device whose slots run from the start position along the axis (default stride 1 for `bcd`, 2 for `analog`) |
| `/rtime device set <uuid> <bit-index> <x> <y> <z>` | `realtime.device.edit` | Moves one output slot of an existing device |
| `/rtime device list` | `realtime.device.view` | Lists configured devices, UUID, world, and enabled state |
| `/rtime device info <uuid>` | `realtime.device.view` | Shows metadata, resolution, and every bit-to-coordinate mapping |
| `/rtime device status` | `realtime.device.view` | Shows aggregate output health and refresh counters |
| `/rtime device cleanup list` | `realtime.device.view` | Lists durable cleanup jobs with pending and operator-attention counts |
| `/rtime device cleanup retry <job-uuid>` | `realtime.device.retire` | Requeues only `NEEDS_ATTENTION` outputs after an operator has corrected their blocks |
| `/rtime device remove <uuid>` | `realtime.device.retire` | Retires a disabled device and atomically creates its deferred cleanup job |
| `/rtime device enable <uuid>` | `realtime.device.toggle` | Persists enabled state and schedules a redstone refresh |
| `/rtime device disable <uuid>` | `realtime.device.toggle` | Persists disabled state and schedules a redstone refresh |

Permission defaults: `realtime.command` and `realtime.device.view` are `true` for everyone, `realtime.device.toggle` / `realtime.device.edit` / `realtime.device.retire` are `op`, and `realtime.admin` (op) grants reload plus all of the above. Grant `realtime.device.edit` only to people who may place redstone blocks at chosen coordinates; combine it with `limits.allowed-areas` on public servers.

Use the full UUID shown by `device list`; device names are labels and are not identifiers. A failed persistence operation leaves the current in-memory state unchanged. Mutations and `/rtime reload` share a runtime-state lock so one operation cannot overwrite the other's device list.

These commands never inspect or mutate world blocks directly. The redstone manager receives a refresh request and performs all world/block access through the existing Paper/Folia scheduler ownership rules. Disabled devices remain in the device store so their outputs can be driven to zero when their chunks are loaded.

Device retirement is available through the admin-only `/rtime device remove <uuid>` command, but only after the device has been disabled. The command atomically removes the active record and persists every output coordinate (16 or 24, matching the device resolution) as a durable cleanup job. Do not manually delete device records or cleanup jobs from YAML. Operators can inspect the durable queue and retry `NEEDS_ATTENTION` outputs after correcting the affected target block. Paper/Folia restart, unloaded-chunk, chunk-load, and retirement behavior has automated runtime coverage; production deployment still requires the real-server acceptance checklist in [Production acceptance 0.1.0](../operations/production-acceptance-0.1.0.md).

Device records are persisted in `data/devices.yml`; the plugin never loads a chunk to create or edit a device.

## In-game authoring

`device create` fills the layout for you; the physical slot positions are still yours to prepare:

```
/rtime device create lobby-clock second world 39985 79 -32 x+            # 24 BCD slots at x=39985..40008
/rtime device create lobby-clock minute world 40985 79 -32 z+ 2          # 16 BCD slots, 2 blocks apart
/rtime device create wall-clock second world 39985 79 -32 x+ analog     # 6 analog lecterns, 2 blocks apart
/rtime device create wall-clock second world 39985 79 -32 x+ 3 analog   # same, 3 blocks apart
/rtime device set 77777777-8888-9999-aaaa-bbbbbbbbbbbb 5 41000 79 -30
```

Both optional arguments may be given in either order; `bcd` (alias `binary`) is the default encoding.

What the commands validate (all without touching the world, so they are Folia-safe):

- resolution must be `minute` or `second`, encoding `bcd` (`binary`) or `analog`, stride 1–16, axis one of `x+ x- z+ z-`
- every coordinate must fall inside `limits.allowed-areas` (when configured)
- the new device must stay under `limits.max-devices`
- no coordinate may overlap another device or a pending cleanup job
- `device set` must use a slot index that exists for that device's resolution and encoding (0–15 / 0–23 for BCD, 0–3 / 0–5 for analog)
- each player is throttled by `limits.edit-cooldown-seconds`; console commands are not throttled

They do **not** inspect blocks. A slot that already contains something else is reported as an invalid output and left alone — clear it first. Analog slots are the exception to "prepare it yourself": when the slot is air the plugin places the lectern and its book on the next refresh, because the lectern *is* the output and has to carry the plugin's book. Each create/set is written to the plugin log with the acting player, device UUID and coordinates.

New devices start disabled: review `/rtime device info <uuid>`, then `/rtime device enable <uuid>`.

## Output diagnostics

Use `/rtime device status` to inspect aggregate counters without querying world state from the command thread. The snapshot reports configured/enabled devices and output points, dispatched output checks, unloaded-chunk skips, stale scheduled updates, invalid output blocks, actual output changes (power / page), unchanged outputs, and caught write failures. Once an analog device has written something, the status output also reports lectern placements and book installs/removals. Counters are process-lifetime totals; configured counts reflect the current runtime snapshot. Because writes are diffed and batched, `输出检查` counts slots that were actually dispatched (changed slots plus the 30 s full re-check), not a per-second poll of every slot. Diagnostics never load chunks and do not change output behavior.
