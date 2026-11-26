# Analog Encoding and Layout — Real-Server Acceptance (2026-11-25)

> **历史文档（已过时）**：本文记录的是 v0.2.0–v0.3.0 时代的实现与验收，包含已移除的 BCD 编码、`device <子命令>` 等旧行为。
> 当前版本请看 [usage guide](../usage.md) 与 [0.6.0 验收报告](acceptance-report-0.6.0.md)。

- **Status**: PASSED, after one defect was found and fixed
- **Artifact under test**: `RealTime-0.2.0-SNAPSHOT.jar`, sha256
  `e3310e82bd1c27926ed1886d17447692770300b9aae7d99399e513a9ede3a40f` (main `f2143b1`),
  re-verified after the fix with `ad592b5fda6977fc90301cdfd68280205c42eea75f3d178e4637bad1b7c8916c`
- **Server**: Paper 26.3 (MCSM instance `MCSM-716c2f`), JDK 25, 24 other plugins, `modules/redstone.yml: enabled: true`
- **Method**: commands over RCON (`/rtime`, `/setblock`, `/data`, `/execute if block … run <plugin command>` as an
  assertion primitive). Test area `/forceload`ed, **no player online** — which incidentally proved that plugin writes
  notify comparators without a player, because `setPage` calls `LecternBlock.signalPageChange`.

## What was verified

### 1. Lectern pages encode the digits

`/rtime device create analog-accept second world 200 79 -40 x+ analog` → 6 lecterns at
`x = 200, 202, 204, 206, 208, 210` (analog default stride 2, no digit grouping), then `device enable`.

| Wall clock | Lectern pages (H, h, M, m, S, s) | Comparator levels | Expected |
|---|---|---|---|
| 07:12:19 | 0, 7, 1, 2, 1, 9 | 1, 8, 2, 3, 2, 10 | page + 1 each |
| 07:12:42 | 0, 7, 1, 2, 4, 0 | 1, 8, 2, 3, 5, 1 | page + 1 each |

The second-resolution digits move every second, so the two-probe comparison above was repeated on a
**minute-resolution** analog device (4 lecterns, pages stable for a whole minute):

| Wall clock | Pages | Levels | Result |
|---|---|---|---|
| 07:22 | 0, 7, 2, 2 | 1, 8, 3, 3 | exact, no drift |

### 2. The seconds digit tracks the wall clock

Second-ones lectern page sampled while the clock ran: `07:12:56 → 6`, `07:12:59 → 9`, `07:13:01 → 1`,
`07:13:03 → 3`.

### 3. Disable blanks the slot

`device disable` removes the book: block state `lectern[has_book=false]`, `Book` NBT gone, comparator output
**0** — distinguishable from digit 0 (level 1).

### 4. Tampering self-heals

Page overwritten in-world (`data merge … {Page:3}`) while the clock showed 7: the 30 s full re-check rewrote the
page back to 7 within **10 s**.

### 5. Retirement

First attempt (disable → remove) reported `需人工处理：6` and left the lecterns in the world. **Defect**, fixed in
PR [#38](https://github.com/OrzMC/RealTime/pull/38): a blank lectern is this device's slot and is cleared too.
After the fix, retrying the same pending job cleared all six lecterns to air, the queue emptied, and **no book item
entities dropped** (`@e[type=item]` empty in the area).

### 6. Default layout (PR17)

`/rtime device create layout-accept minute world 200 79 -60 x+` produced

```
200 202 204 206   209 211 213 215   218 220 222 224   227 229 231 233
```

i.e. one empty block between two bits and two between two digits (offsets `index*2 + index/4`), a 34-block span for
`HHmm`, and the 52-block span for `HHmmss` documented in the design notes.

### 7. Message-catalog fallback

The server still had the pre-0.2.0 `messages.yml` (no `encoding` key). `device info` showed
`编码：analog` anyway, because the new keys come from the `OPTIONAL_MESSAGES` defaults — an existing install picks up
the new lines without editing the file.

## Not covered here

- Analog display circuits: the 0–9 → seven-segment decoder stays the operator's build; only the comparator level was
  measured.
- Folia: identical code paths, covered by the Folia runtime smoke job in CI; the analog write path itself was measured
  on Paper only.
- Bookmarks/tamper pressure from real players: the 30 s re-check was verified by hand, not under load.
