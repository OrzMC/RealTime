# PR17 — Default Device Layout: Gaps and Digit Groups

- Status: Implemented, locally verified, and accepted on the Paper test instance (2026-11-25)
- Depends on: PR15 (`device create`), PR16 (`DeviceLayout`)

## Why

`device create` used `stride = 1`, so a 24-output BCD clock was a solid row of 24 power sources. Two problems in
practice:

1. **Signals couple.** Two adjacent power-source slots feed the same receiving dust line, so one bit sets its
   neighbour. A bit can only be read safely when at least one empty block separates it from the next slot.
2. **The row is unreadable.** Nothing in the world shows where one digit ends and the next begins, which makes a
   24-slot build hard to wire and harder to debug.

Requested after building the 0.1.0 acceptance clock by hand.

## Change

`DeviceLayout.default(OutputEncoding.BCD)` now returns `stride = 2, groupSize = 4, groupGap = 1`.

- offsets are `index * stride + (index / groupSize) * groupGap`
- one empty block between two bits, two empty blocks between two digits
- `minute` devices span 34 blocks (offsets 0–33), `second` devices 52 blocks (offsets 0–51)
- analog devices are unchanged: one slot per digit, `stride = 2`
- an explicit `[stride]` replaces the intra-group spacing but keeps the digit boundaries
- the whole layout is capped at 512 blocks from the origin block (`LayoutAxis` + `DeviceLayout.MAX_SPAN`), so a bad
  origin cannot walk across the world

## Impact

- Only **new** devices are affected: a stored device keeps its explicit coordinate list, so existing clocks
  (including the 0.1.0 acceptance clock) do not move.
- `device create` therefore rejects fewer positions per axis than before, but the operator now needs a bigger wall.
  A `second` device needs 52 blocks along one axis; build it on a free wall or split it into two devices.
- Power-source slots are still air/`redstone_block` only; the layout does not change the write semantics.

## Acceptance

- `DeviceLayoutTest`: default offsets (1 empty block inside a digit, 2 between digits), 34/52-block span, explicit
  stride keeps grouping, span limit rejected.
- `RealTimeCommandHandlerTest`: default `create` produces the grouped layout, explicit stride produces
  `index * stride + digit gap`, unknown resolution/encoding still rejected.
- `./scripts/verify.sh` green (119 tests).
- In-game: created a `minute` device on the test instance — slots at `200 202 204 206 209 211 213 215 …`, i.e. the
documented gaps; see [analog acceptance](../operations/analog-acceptance-2026-11-25.md).
