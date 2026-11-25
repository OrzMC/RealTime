# PR18 — Teaching Builds (`device demo`)

- Status: Implemented, locally verified, accepted on the Paper test instance (2026-11-25)
- Depends on: PR15 (`device create`), PR16 (analog), PR17 (layout)

## Why

The analog and BCD acceptance builds proved the encodings but were assembled by hand: the operator had to work out
where the lamps, comparators and dust go, and a mistake looks like "the plugin is broken" rather than "the wire is
off by one". A one-command example that appears next to the operator and disappears again removes that learning curve.

## Command

```
/rtime device demo <bcd|analog> <world> <x> <y> <z> <x+|x-|z+|z->
```

- always `second` resolution, so the display visibly moves
- created **enabled**: the display lights up within a tick or two
- same gates as `device create`: `realtime.device.edit`, `limits.allowed-areas`, `limits.max-devices`,
  `limits.edit-cooldown-seconds`

## What it builds

```
analog  lectern  comparator  dust  lamp      (per digit, perpendicular to the axis,
        (slot)   ← reads     → level → lit    on a stone support row at y-1)
bcd     slot with a lamp directly above it    lamp lit = 1, no dust needed
```

The comparator's `facing` always points at its lectern (`north` for an `x` axis, `west` for a `z` axis), which is the
direction convention the analog documentation found on the real server.

## Lifecycle

Display blocks are part of the device record (`demo:` in `data/devices.yml`, schema **v5**) and of its cleanup job
(`display:`), so:

- `device disable` removes the display blocks and empties the slots
- `device enable` rebuilds them
- `device remove` clears both the slots and the display blocks
- the plugin only removes a block whose type still matches; anything else is reported as `NEEDS_ATTENTION`

## Acceptance

- Core tests: lamp above every BCD slot, analog row geometry for both axes (comparator facing), uniqueness, no overlap
  with slots, material parsing.
- Paper tests: display placed when enabled, removed when disabled, settled demo not rewritten, player block never
  overwritten; repository v5 round-trip of `demo:`/`display:` and v4 migration; cleanup clears a display block and keeps
  a replaced one; command creates an enabled build with the expected block counts and rejects unknown encodings.
- `./scripts/verify.sh` (182 tests) green; real-server run: `/rtime device demo analog ...` next to the existing BCD
  acceptance clock on the Paper test instance.
