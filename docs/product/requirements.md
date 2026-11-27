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

- `/rtime`: the current configured local date/time plus one line per clock; `/rtime <name>`: one clock in detail.
- `/rtime status`: plugin version, timezone, module state, output counters and the updater state.
- `/rtime update [check|download]`: ask the platforms for a newer build and stage it in the server update folder.
- `/rtime reload`: validate and reload configuration safely.

Device administration happens entirely in game — no hand-editing of the data file. The command surface, permission split
(`realtime.device.view` / `toggle` / `edit` / `retire` / `update`, with `realtime.admin` as parent), authoring limits and the
cleanup queue are documented once in the [usage guide](../usage.md); this document only fixes the requirements:

- Devices must be creatable, inspectable, movable, enable/disable-able and retirable without editing YAML.
- Slot edits require a disabled clock; the resolution is immutable (retire and create instead).
- Every write is gated by area whitelist, device cap, per-player cooldown, and audit logging.
- Retirement is deferred and durable; blocks the plugin does not own are never overwritten and can be retried or
  dismissed by an operator.

### Self-update (P1)

- Check for a newer published version on Hangar, falling back to GitHub Releases, at most every
  `check-interval-hours` (default 6).
- Report it on the console, to holders of `realtime.update` when they join, and in `/rtime status`.
- Never hot-swap the running jar and never restart the server: the only supported application point is
  the server's own update folder (`plugins/update/`), which Paper applies at plugin load.
- Download only what can be verified: HTTPS (loopback HTTP only for CI), host allowlist re-validated on
  every redirect, size cap, and a checksum published by the platform. A publication without a checksum is
  reported but never installed.
- Never offer a downgrade, and filter pre-releases unless `channel: any`.

### Optional PlaceholderAPI integration (P1)

Initial planned placeholders: date, time, datetime, weekday, timezone, and Unix timestamp. PlaceholderAPI must remain optional; the plugin must load when it is absent.

### Redstone clock devices (P1)

Each clock declares a `resolution` (`minute` = `HHmm`, `second` = `HHmmss`; **`second` is the default**). There is a
single output form: **one lectern per digit** — four slots for `minute`, six for `second`. A slot is a lectern holding a
15-page book the plugin installs; the page index is the digit, so Paper's
`floor(page / (pageCount - 1) * 14) + 1` yields `digit + 1` (levels 1–10). Publishing a digit is one block-entity page
write, and the plugin only ever pages through lecterns that still hold its own book; anything else is reported and left
untouched.

> The earlier BCD encoding (four parallel bit slots per digit) was removed after real-server acceptance: its decoder
> propagation delay made the displayed value unreadable, so it could not deliver a usable clock.

Slots are laid out with one empty block between them (room for the reading comparator), are ordered hour → minute →
second, and are bounded by `/rtime create`/`/rtime place` validation. Device definitions and runtime data are stored
separately from feature configuration (`data/devices.yml`, schema v7). Block access runs on the owning Folia region;
unloaded chunks are skipped and refreshed on chunk load, and device updates never force-load chunks. Second clocks are
rewritten every second, minute clocks when the minute changes, and only slots whose state actually changed are written.
A 30 s full re-check catches player edits and invalid blocks, and nothing the plugin does not own is ever overwritten.

Turning a clock off removes the book (level 0) but keeps the lectern, so a build stays visible and hand-positioned slots
keep their coordinates. `/rtime create <name> [minute] [manual] [nodisplay] [at <x y z>] [facing <north|south|east|west>]`
builds one complete, immediately visible example at the player's aim (lectern → comparator → dust → lamp per digit)
whose display blocks belong to the clock and are removed with it. `manual` skips the display blocks and leaves every slot
unpositioned; `/rtime place <clock> <slot>` then puts each lectern exactly where the build has room and **builds it on the
spot**, so the operator can see the position and facing before switching the clock on.

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
