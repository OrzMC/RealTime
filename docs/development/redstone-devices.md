# Redstone Time Devices — Implementation Notes

Configuration, data structures, encoding details and runtime behaviour. **Operators and builders should read the
[usage guide](../usage.md)** for commands, permissions, wiring and the cleanup queue.

## Configuration

`modules/redstone.yml` controls the optional output module:

```yaml
enabled: true
limits:
  max-devices: 50
  edit-cooldown-seconds: 3
  allowed-areas: []
  # - { world: world, min-x: 39000, max-x: 41000, min-z: -200, max-z: 200 }
```

The module is **enabled by default**: with no device configured it costs nothing, and a disabled module would accept
`/rtime create` while writing nothing. Disabling it makes the plugin read-only (`/rtime`, `/rtime info`, placeholders).
`limits` only affects the in-game authoring commands (`/rtime create` / `/rtime place`): an empty `allowed-areas` list
means "no area restriction" — a legal configuration, reported once at `info` level on start — the commands still
require `realtime.device.edit` (default op), `max-devices` caps how many devices may exist, and
`edit-cooldown-seconds` throttles each player's authoring actions.

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

No rounding is involved: all ten digits map onto distinct levels. A device that is disabled (or globally off) has its slot **emptied**: the book is removed first (so it does not drop) and then the lectern, exactly like a BCD slot goes back to air. Level 0 therefore comes from air, "off" stays distinguishable from digit 0 (which reads level 1), and a slot that was moved while the device was off cannot leave an orphaned lectern behind.

Writing a digit is a single block-entity operation (`setPage`), far cheaper than the 4 block writes of a BCD digit:

- The plugin places the lectern itself when the slot is air and enable the device; you build only the comparator and decoding circuit.
- The book is the ownership marker: title `RealTime <first 8 chars of the device UUID>`, author `RealTime`. A lectern holding any other book is reported as an invalid output and never modified.
- Writing the book resets the page to 0, so the page is always written after book installation.
- `setPage` notifies neighbouring blocks, so the comparator behind the lectern resamples immediately; no manual block update is needed.
- Lecterns are block entities: they only tick while the chunk is entity-ticking (a player nearby). A comparator in an unloaded chunk catches up as soon as the chunk ticks again, so the display is correct on arrival rather than live while nobody is around.
- Players can open the lectern and turn pages. The 30 s full re-check rewrites the page, so tampering self-heals within at most 30 s. Disabling a device removes the lecterns, and enabling it again places them and their books back.

**Comparator direction:** `facing` points *toward the input*. Place the comparator with `facing` set towards the lectern; the output is on the opposite side. `comparator[facing=west]` therefore reads the lectern to its west and drives dust to its east.

Real-server evidence for this section (page/level table, disable, tamper self-heal, retirement) is recorded in
[analog acceptance 2026-11-25](../operations/analog-acceptance-2026-11-25.md).

Retirement follows the same rule as BCD slots: the cleanup job removes the lectern (after clearing its book, so no item drops) whether it still holds this device's book or is already blank, because the slot belongs to the device — disabling a device only removes the book. A lectern that holds **someone else's** book, or any other block, is left in place and reported as `NEEDS_ATTENTION`.

## Device store schema v5

The current `data/devices.yml` format uses schema version 5. Schema v4 added `encoding` to device records and cleanup jobs; schema v5 adds the `demo:` display-block list of a `create`/`demo` build; schema v6 adds the per-output `unplaced` flag of `manual` builds and the matching `display:` list on cleanup jobs. Records written by earlier schemas are read as `bcd` with no demo blocks, so an existing BCD clock keeps working after the upgrade. It stores active devices and deferred cleanup jobs in the **same file**, so a retirement operation can persist both sides of the state transition with one atomic replacement. Existing schema v1–v5 files are migrated automatically on load (a v5 output without `unplaced` is placed); device records are preserved, records without a `resolution` become `minute` devices, records without an `encoding` become `bcd` devices, and `cleanup-jobs` starts empty for v1. The previous file is retained at `backups/devices.yml.bak`.

Each device record declares `resolution: minute` or `resolution: second`, `encoding: bcd` or `encoding: analog`, and — for a teaching build — a `demo:` list of the display blocks it owns (`{x, y, z, data}`, where `data` is a block-data string such as `minecraft:comparator[facing=north]`). A `bcd` `minute` device has 16 outputs and a `bcd` `second` device 24 (HHmmss); an `analog` device has one output per digit, so 4 and 6 respectively. Cleanup jobs record the same resolution and encoding as the retired device, so every variant is cleaned completely.

A cleanup job records the retired device identity, world name, optional world UUID, resolution, encoding, every output coordinate of that device, and the display blocks of a demo build (`display:`). Each output has one of three states:

- `PENDING`: not yet safely processed (including unloaded chunks or unavailable worlds).
- `CLEARED`: the slot was already empty, or a `redstone_block` / the plugin's lectern / one of its demo blocks was removed from it.
- `NEEDS_ATTENTION`: the slot holds something the plugin does not own (it is never overwritten), so an operator must inspect it.

Example shape (abbreviated):

```yaml
schema-version: 6
devices: []
cleanup-jobs:
  - id: 6b3c20e4-77e6-4d28-92d5-6a0d1fd20a10
    device-id: 45b8f930-0a13-4f8c-9b90-8eac4a2b4a10
    device-name: lobby-clock
    world: world
    world-uuid: null
    resolution: second
    encoding: bcd
    display: []
    outputs:
      - { bit-index: 0, x: 100, y: 65, z: 100, status: PENDING, detail: null }
```

A job created by a demo device also carries a `display:` list, and both lists count towards the `pending` / `attention`
numbers printed by `/rtime cleanup`; `/rtime cleanup retry <job-uuid>` requeues every `NEEDS_ATTENTION` entry of
the job (slots **and** display blocks), because a job only disappears once all of them are cleared.

The complete job contains every output of the retired device (16/24 for a BCD `minute`/`second` device, 4/6 for an analog one). Do not hand-edit or delete cleanup jobs. Coordinates reserved by a cleanup job cannot be reused by an active device until the job is fully resolved and removed by the cleanup lifecycle. PR10a adds the schema and migration; PR10b adds the crash-safe retirement service and region-owned cleanup processor. PR10c adds operator queue inspection/retry and device-specific Paper/Folia runtime recovery coverage. PR11 exposes the disabled-only admin retirement command through the existing crash-safe service (now `/rtime remove`). PR12 adds second resolution. PR16 adds the optional analog (lectern) encoding and schema v4; PR17 changes the default layout to one-block gaps with four-bit digit groups; PR18 adds the `demo` teaching builds and schema v5.

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

Analog devices reuse the same record shape with **one entry per digit**: `bit-index` 0–3 (`minute`) or 0–5 (`second`) is the digit position, and the slot value is the digit itself rather than a bit. `/rtime info <device>` prints the same `槽 <index>` lines for both encodings.

For example, 23:59:47 is encoded as digits `2, 3, 5, 9, 4, 7`; each digit occupies four bits, least-significant bit first.

## Device file example

The following shows a complete `minute` device (16 outputs, HHmm) in the default layout — one empty block between slots, one extra between the four bits of a digit (`offset = index * 2 + index / 4`). A `second` device is identical except for `resolution: second` and 24 output records covering bit indexes 0 through 23 (`offset = index * 2 + index / 4`, spanning 52 blocks). Change the world name and coordinates to match the build:

```yaml
schema-version: 6
devices:
  - id: 6b3c20e4-77e6-4d28-92d5-6a0d1fd20a10
    name: lobby-clock
    world: world
    enabled: true
    resolution: minute
    encoding: bcd
    demo: []
    outputs:
      - { bit-index: 0, x: 100, y: 65, z: 100 }
      - { bit-index: 1, x: 102, y: 65, z: 100 }
      - { bit-index: 2, x: 104, y: 65, z: 100 }
      - { bit-index: 3, x: 106, y: 65, z: 100 }
      - { bit-index: 4, x: 109, y: 65, z: 100 }
      - { bit-index: 5, x: 111, y: 65, z: 100 }
      - { bit-index: 6, x: 113, y: 65, z: 100 }
      - { bit-index: 7, x: 115, y: 65, z: 100 }
      - { bit-index: 8, x: 118, y: 65, z: 100 }
      - { bit-index: 9, x: 120, y: 65, z: 100 }
      - { bit-index: 10, x: 122, y: 65, z: 100 }
      - { bit-index: 11, x: 124, y: 65, z: 100 }
      - { bit-index: 12, x: 127, y: 65, z: 100 }
      - { bit-index: 13, x: 129, y: 65, z: 100 }
      - { bit-index: 14, x: 131, y: 65, z: 100 }
      - { bit-index: 15, x: 133, y: 65, z: 100 }
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
- Devices can be created and edited in game (`/rtime create` fills the layout, `/rtime place` moves individual slots); the YAML store remains the single source of truth and every slot coordinate stays reviewable.
- A slot can be **unplaced** (`unplaced: true`): `manual` builds start every slot that way. Unplaced slots are never
  written, never take part in cross-device position checks, and block `enable` with the number still missing, so the
  operator can position each source at an arbitrary coordinate in any order. `setOutput` clears the flag.
- `setOutput` only changes the slot coordinate. The `demo:` display blocks stay where the layout put them, because moving
  a comparator row cannot know whether the destination is free; `manual` creates a device without display blocks for
  hand-wired builds. This applies to both encodings (BCDs lamp, analog's comparator/dust/lamp row).

After editing the files, use `/rtime reload` as an operator. A failed validation keeps the previous runtime configuration and device list active.

## Layout defaults and authoring pipeline

New devices are laid out by `DeviceLayout`: `offset = index * stride + (index / groupSize) * groupGap`. Defaults are
`stride = 2` (one empty block between neighbours, so adjacent power sources cannot feed each other's circuit),
`groupSize = 4` with `groupGap = 1` for BCD (the four bits of a digit read as one visual block), and a plain
`stride = 2` row for analog (room for the reading comparator). A `minute` device spans 34 blocks, a `second` device 52.
An explicit `[stride]` replaces the intra-group spacing but keeps the digit boundaries; the whole layout is capped at
512 blocks from the origin.

Commands are validated off the world thread (Folia-safe): resolution/encoding/axis/stride and slot-index sanity, the
`limits.allowed-areas` box, `limits.max-devices`, no overlap with another device, demo display block or pending cleanup
job, and a per-player `limits.edit-cooldown-seconds` throttle (console exempt). The command never reads or writes blocks;
the manager performs all world access through the owning region on the next forced refresh. Every create/set/demo/remove
is written to the plugin log with the actor, device UUID and coordinates.

User-facing usage (commands, permissions, wiring, cleanup queue) lives in [the usage guide](../usage.md).

## Output diagnostics

Use `/rtime status` to inspect aggregate counters without querying world state from the command thread. The snapshot reports configured/enabled devices and output points, dispatched output checks, unloaded-chunk skips, stale scheduled updates, invalid output blocks, actual output changes (power / page), unchanged outputs, and caught write failures. Once an analog device has written something, the status output also reports lectern placements and book installs/removals, and a `create`/`demo` build reports the display blocks it placed (and removed again when disabled or retired). Counters are process-lifetime totals; configured counts reflect the current runtime snapshot. Because writes are diffed and batched, `输出检查` counts slots that were actually dispatched (changed slots plus the 30 s full re-check), not a per-second poll of every slot. Diagnostics never load chunks and do not change output behavior.
