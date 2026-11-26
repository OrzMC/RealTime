# Redstone Time Devices — Implementation Notes

Configuration, data structures, slot details and runtime behaviour. **Operators and builders should read the
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
`/rtime create` while writing nothing. Disabling it makes the plugin read-only (`/rtime`, `/rtime status`, placeholders).
`limits` only affects the in-game authoring commands (`/rtime create` / `/rtime place`): an empty `allowed-areas` list
means "no area restriction" — a legal configuration, reported once at `info` level on start — the commands still
require `realtime.device.edit` (default op), `max-devices` caps how many devices may exist, and
`edit-cooldown-seconds` throttles each player's authoring actions.

Each device output slot is a **power-source slot**: a logical 1 places a `minecraft:redstone_block` in the slot, a logical 0 leaves the slot empty (`minecraft:air`). Signals are therefore full strength (15) and vanilla redstone reacts to every change, so the surrounding circuit actually switches.

> Why not forced dust power: writing `power` onto a `redstone_wire` cannot work on the 26.x API surface. With physics the wire recalculates from its inputs and drops back to 0; without physics the value sticks but no consumer ever notices, and `World.updateNeighborsAt` no longer exists. Pre-release builds accepted an `output-power` value (1–15); that key is now ignored because a block cannot emit partial strength.

`data/devices.yml` stores versioned device records. It is separate from feature configuration and is backed up before repository writes.

## Output slots

Every clock is a row of **lecterns**, one per digit. There is no encoding choice: a digit's slot holds a lectern with the
plugin's book at page `digit`, and a comparator reads `digit + 1`.

| Slot content | Reads as | Meaning |
|---|---|---|
| lectern + plugin book, page `0`–`9` | level `1`–`10` | the digit |
| lectern, no book | level `0` | clock is off (or the slot was just placed) |
| anything else | — | not ours: reported as `NEEDS_ATTENTION`, never overwritten |

`HHmm` therefore needs four lecterns and `HHmmss` six, which is the whole reason BCD was dropped: sixteen/twenty-four
parallel bit lines needed a decoder whose propagation delay made the display unreadable.

### Page → level mapping

Paper 26.3 `LecternBlockEntity#getRedstoneSignal()`:

```
float f = pageCount > 1 ? page / (pageCount - 1) : 1.0F;
return floor(f * 14.0F) + (hasBook ? 1 : 0);
```

The plugin installs a 15-page book, so `pageCount - 1 == 14` and the formula collapses to `page + 1`: every digit maps
onto a distinct level with no rounding. Levels 11–15 are unused, so downstream circuits can tell a clock digit from "out
of range".

Writing a digit is one block-entity operation (`setPage`), and `setPage` fires `LecternBlock.signalPageChange`, so the
comparator updates without the plugin touching neighbouring blocks.

### Off, placement and cleanup

- **Off**: the book is removed (level 0) and the lectern stays. The build is always visible, and a slot positioned by
  hand keeps its position.
- **`place`**: `DevicePlacement.placeSlotNow` builds the lectern immediately, facing the player, with the current digit
  (or blank when the clock is off), and clears the lectern the slot left behind — but only one that still holds this
  clock's book or nothing at all.
- **Retirement**: the cleanup job removes the lectern after clearing its book (so no item drops) whether it still holds
  this clock's book or is already blank. A lectern holding **someone else's** book, or any other block, is left in place
  and reported as `NEEDS_ATTENTION`.

## Slot order

Digits are always ordered hour → minute → second, one slot each:

| Slot index | Digit | Resolution |
|---|---|---|
| 0 | hour tens | both |
| 1 | hour ones | both |
| 2 | minute tens | both |
| 3 | minute ones | both |
| 4 | second tens | `second` only |
| 5 | second ones | `second` only |

A `minute` clock must define exactly 4 unique slots and a `second` clock exactly 6; slot indexes must cover
`0` through `digitCount - 1` exactly once. A slot with a position must contain `minecraft:air` or a lectern belonging to
this clock; anything else is reported as an invalid output and never overwritten.

23:59:47 is therefore digits `2, 3, 5, 9, 4, 7`, one lectern page each.

## Device file example

The following shows a complete `second` clock (6 lecterns, HHmmss) in the default layout — `offset = index * 2`, so
slots are two blocks apart and there is exactly one empty block for the comparator reading each lectern. Change the
world name and coordinates to match the build:

```yaml
schema-version: 7
devices:
  - id: 6b3c20e4-77e6-4d28-92d5-6a0d1fd20a10
    name: lobby-clock
    world: world
    enabled: true
    resolution: second
    demo: []
    outputs:
      - { bit-index: 0, x: 100, y: 65, z: 100 }
      - { bit-index: 1, x: 102, y: 65, z: 100 }
      - { bit-index: 2, x: 104, y: 65, z: 100 }
      - { bit-index: 3, x: 106, y: 65, z: 100 }
      - { bit-index: 4, x: 108, y: 65, z: 100 }
      - { bit-index: 5, x: 110, y: 65, z: 100 }
cleanup-jobs: []
```

Device IDs must be unique. Slot indexes must cover `0` through `digitCount - 1` exactly once (0–3 for `minute`, 0–5 for `second`). Output coordinates must be unique within a device and across all devices in the same world. World names are used for configuration convenience; changing a world's name requires updating this file.

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
- Clocks can be created and edited in game (`/rtime create` fills the layout, `/rtime place` moves individual slots and
  builds the lectern on the spot); the YAML store remains the single source of truth and every slot coordinate stays
  reviewable.
- A slot can be **unplaced** (`unplaced: true`): `manual` builds start every slot that way. Unplaced slots are never
  written, never take part in cross-clock position checks, and block `enable` with the number still missing, so the
  operator can position each lectern at an arbitrary coordinate in any order. `setOutput` clears the flag and builds the
  lectern immediately.
- `setOutput` only changes the slot coordinate. The `demo:` display blocks stay where the layout put them, because moving
  a comparator row cannot know whether the destination is free; `manual` creates a clock without display blocks for
  hand-wired builds, and `place` prints that hint whenever the clock has display blocks.

After editing the files, use `/rtime reload` as an operator. A failed validation keeps the previous runtime configuration and device list active.

## Layout defaults and authoring pipeline

New clocks are laid out by `DeviceLayout`: `offset = index * stride`, with `stride = 2` — one empty block for the
comparator that reads each lectern. A `minute` clock spans 6 blocks, a `second` clock 10. The whole layout is capped at
512 blocks from the origin.

Commands are validated off the world thread (Folia-safe): resolution/axis and slot-index sanity, the
`limits.allowed-areas` box, `limits.max-devices`, unique clock names that do not shadow a verb, no overlap with another
clock, display block or pending cleanup job, and a per-player `limits.edit-cooldown-seconds` throttle (console exempt).

The command never reads or writes blocks directly. `create` stores the resolved layout and the manager builds it through
the owning region on the next forced refresh; `place` additionally asks `DevicePlacement.placeSlotNow` for that one slot,
so the lectern appears immediately instead of on the next tick of a running clock. Every create/toggle/place/remove is
logged with the actor, clock UUID and coordinates.

User-facing usage (commands, permissions, wiring, cleanup queue) lives in [the usage guide](../usage.md).

## Output diagnostics

Use `/rtime status` to inspect aggregate counters without querying world state from the command thread. The snapshot reports configured/enabled devices and output points, dispatched output checks, unloaded-chunk skips, stale scheduled updates, invalid output blocks, actual output changes (power / page), unchanged outputs, and caught write failures. Once an analog device has written something, the status output also reports lectern placements and book installs/removals, and a `create`/`demo` build reports the display blocks it placed (and removed again when disabled or retired). Counters are process-lifetime totals; configured counts reflect the current runtime snapshot. Because writes are diffed and batched, `输出检查` counts slots that were actually dispatched (changed slots plus the 30 s full re-check), not a per-second poll of every slot. Diagnostics never load chunks and do not change output behavior.
