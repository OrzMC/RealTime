# PR16 — Analog (lectern) Output Encoding

- Status: Implemented and locally verified
- Depends on: PR13 (power-source outputs), PR14 (diff scheduling), PR15 (`device create`)

## Why

A BCD `HHmmss` device needs 24 output lines. Wiring 24 isolated redstone lines is the expensive part of building a
clock, and every bit costs a block write on change. A lectern's comparator output is a **0–15 level**, so one lectern
can carry a whole decimal digit: six lines instead of twenty-four.

The mapping is exact rather than approximate. Paper 26.3 `LecternBlockEntity#getRedstoneSignal()` is

```java
float f = pageCount > 1 ? page / (pageCount - 1) : 1.0F;
return floor(f * 14.0F) + (hasBook ? 1 : 0);
```

so a **15-page book** collapses the formula to `page + 1`: pages 0–9 give levels 1–10 with no rounding loss.

## Design

- `OutputEncoding` (`bcd` / `analog`, alias `binary`) decides the slot count per resolution: 16/24 for BCD, 4/6 for
  analog. `RedstoneDevice` and `DeviceCleanupJob` validate against `encoding.outputCount(resolution)`.
- `AnalogScale` owns the page/level mapping (`BOOK_PAGES = 15`, `NO_BOOK = -1`, `level(page, pageCount)`), so the
  formula is unit-tested without a server.
- `DeviceLayout` moved slot placement out of the command handler: offset = `index * stride + (index / groupSize) * groupGap`.
  Analog devices default to stride 2 (room for the reading comparator); BCD keeps stride 1 until PR17.
- Every analog slot is a lectern holding a plugin-created book: title `RealTime <first 8 chars of device UUID>`,
  author `RealTime`, 15 pages. The book **is** the ownership marker.
- Write path per slot: air → the plugin places the lectern; lectern → install the book if it is missing, then
  `setPage(digit)`. Writing the book resets the page, so the page is always written last. `setPage` calls
  `LecternBlock.signalPageChange`, which notifies neighbours and makes the comparator resample.
- Off state: the device switch removes the book (level 0) instead of paging to a magic value, so "disabled" stays
  distinguishable from digit 0 (which reads level 1).
- Anything else in the slot, or a lectern holding a foreign book, is reported as an invalid output and never
  overwritten — same rule as BCD slots.
- Cleanup removes a lectern only while it still holds the retired device's book; otherwise `NEEDS_ATTENTION`.
- Schema v4 persists `encoding` for devices and cleanup jobs; earlier stores load as `bcd`.
- Diagnostics gained `lecternPlacements` and `bookWrites`, rendered by `device status` once non-zero.

## Command

```
/rtime device create <name> <minute|second> <world> <x> <y> <z> <x+|x-|z+|z-> [stride] [bcd|analog]
```

The two optional arguments are order-independent; `bcd` remains the default.

## Acceptance

- Core tests: encoding slot counts and aliases, off values, `AnalogScale` (all 15 pages, short books, invalid input),
  `DeviceLayout` (offsets, axis direction, defaults, span limit), frame → value conversion for both encodings,
  `RedstoneDevice` analog validation.
- Paper tests: lecterns placed + book installed + page written for every slot; unchanged digits are not rewritten;
  disabling removes the books; foreign blocks and foreign books are reported and untouched; analog cleanup removes
  the lectern only for its own book; schema v4 round-trip, v3 migration to `bcd`, unsupported encoding rejected;
  `create` accepts `analog` and unknown encodings are rejected.
- `./scripts/verify.sh`, then a real-server run on the Paper test instance with an operator online (comparator reading
  the lectern) before merge.

## Known limitations

- Lecterns are block entities: they only tick while the chunk is entity-ticking, so a comparator in an idle chunk
  catches up when the chunk next ticks. BCD slots do not have this constraint.
- Players can open the lectern and turn pages; the 30 s full re-check restores the page.
- Analog decoding (0–9 → seven-segment) stays the operator's circuit; the plugin only publishes the level.
