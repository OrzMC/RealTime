# RealTime Product Requirements

## Product positioning

RealTime is an independent Minecraft server plugin that provides real-world time as a reusable server capability. It is not a feature inside OrzMCPlugin. Its canonical source is `OrzMC/RealTime`; the parent repository `OrzMC/OrzMC` pins it as a Git Submodule at `tools/RealTime`.

## Initial compatibility target

- Minecraft/Paper API line: 26.3.
- Server implementations: Paper and Folia.
- Runtime: JDK 25.
- Default timezone: `Asia/Shanghai`.
- Command namespace: `/rtime`, avoiding collision with vanilla `/time`.

## Functional requirements

### Real-world time service (P0)

- Return absolute time as `Instant`.
- Return local time using an explicitly configured `ZoneId`.
- Provide immutable snapshots and consistent formatting.
- Support deterministic testing through injected `Clock`.
- Never infer timezone from the host JVM or server machine.

### Command interface (P0)

Implemented commands:

- `/rtime` or `/rtime now`: display current configured local date/time.
- `/rtime info`: display plugin version, configured timezone, and module status.
- `/rtime reload`: validate and reload configuration safely.

Device administration happens entirely in game — no hand-editing of the data file. The command surface, permission split
(`realtime.device.view` / `toggle` / `edit` / `retire`, with `realtime.admin` as parent), authoring limits and the
cleanup queue are documented once in the [usage guide](../usage.md); this document only fixes the requirements:

- Devices must be creatable, inspectable, movable, enable/disable-able and retirable without editing YAML.
- Slot edits require a disabled device; encoding/resolution are immutable (retire and create instead).
- Every write is gated by area whitelist, device cap, per-player cooldown, and audit logging.
- Retirement is deferred and durable; blocks the plugin does not own are never overwritten and can be retried or
  dismissed by an operator.

### Optional PlaceholderAPI integration (P1)

Initial planned placeholders: date, time, datetime, weekday, timezone, and Unix timestamp. PlaceholderAPI must remain optional; the plugin must load when it is absent.

### Redstone clock devices (P1)

Each device declares a `resolution` (`minute` = `HHmm`, `second` = `HHmmss`) and an `encoding`.

**`bcd` (default)** — four slots per digit, least-significant bit first; sixteen slots for `minute`, twenty-four for
`second`. A slot is a power source: a logical 1 places a `minecraft:redstone_block`, a logical 0 leaves the slot empty,
so connected vanilla redstone actually switches (forced dust power cannot hold or propagate on the 26.x API surface).

**`analog`** — one slot per digit: four slots for `minute`, six for `second`. A slot is a lectern holding a 15-page book
the plugin installs; the page index is the digit, so Paper's `floor(page / (pageCount - 1) * 14) + 1` yields
`digit + 1` (levels 1–10) and `HHmmss` needs six lines instead of twenty-four. Disabling the device empties the slot
(book cleared first so it does not drop, then the lectern). Publishing a digit is one block-entity page write, and the
plugin only ever pages through lecterns that still hold its own book; anything else is reported and left untouched.

Both encodings share the digit order hour → minute → second, are laid out with one empty block between slots (BCD adds
an extra gap between digits), and are bounded by `/rtime create`/`/rtime place` validation. Device definitions and
runtime data are stored separately from feature configuration (`data/devices.yml`, schema v5). Block access runs on the
owning Folia region; unloaded chunks are skipped and refreshed on chunk load, and device updates never force-load
chunks. Minute devices are rewritten when the minute changes, second devices every second, and only slots whose state
actually changed are written. A 30 s full re-check catches player edits and invalid blocks, and nothing the plugin does
not own is ever overwritten.

`/rtime create <name> [bcd|analog] [second|minute]` builds one complete, immediately visible example at the player's
aim (lamps above every BCD slot; lectern → comparator → dust → lamp per analog digit) whose display blocks belong to the
device and are removed with it, so a newcomer can learn an encoding without wiring anything by hand. `manual` creates a
disabled device without display blocks, and `/rtime place <device> <slot>` positions each source individually, which is
how a build whose default layout is blocked by other blocks is authored.

Retirement is deferred and durable: `/rtime remove` (disabled devices only) queues a cleanup job that clears every slot
and display block on the owning region, reports foreign content as `NEEDS_ATTENTION` instead of overwriting it, and can
be retried from `/rtime cleanup retry` once an operator fixes the block.

## Configuration proposal

The plugin data directory should evolve toward:

```text
plugins/RealTime/
├── config.yml
├── messages.yml
├── modules/
│   ├── command.yml
│   ├── placeholder.yml
│   └── redstone.yml
├── data/
│   └── devices.yml
├── metadata.yml
└── backups/
```

The root config owns global enablement, timezone, locale, and logging. Each feature module owns its settings and enablement. Device records and schema metadata must not be mixed with editable settings. Implement this layout incrementally; do not add unused files to the initial runtime.

Example root configuration:

```yaml
config-version: 1
general:
  enabled: true
time:
  timezone: Asia/Shanghai
  locale: zh_CN
logging:
  debug: false
```

## Non-functional requirements

- Core logic is platform independent and deterministic under test.
- Paper and Folia compatibility is verified on actual server builds.
- Configuration parsing errors are explicit and actionable.
- Reloading must not leak scheduled tasks or leave stale state.
- Persistence must be versioned and recoverable.
- Optional integrations must not become mandatory dependencies.
- Agent-generated changes are reviewed, tested, and delivered through PRs.

## Scope note

The initial scaffold (PR1) intentionally implemented none of the above; everything in this document now exists on
`main` and is covered by unit tests, Paper/Folia runtime smoke jobs, and real-server acceptance reports (see
[operations](../operations/)).
