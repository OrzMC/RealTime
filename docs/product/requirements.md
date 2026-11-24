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

Administrative device commands are planned separately after the device model exists.

### Optional PlaceholderAPI integration (P1)

Initial planned placeholders: date, time, datetime, weekday, timezone, and Unix timestamp. PlaceholderAPI must remain optional; the plugin must load when it is absent.

### Redstone clock devices (P1)

Each output slot is a power source: a logical 1 places a `minecraft:redstone_block`, a logical 0 leaves the slot empty, so connected vanilla redstone actually switches (forced dust power cannot hold or propagate on the 26.x API surface). Devices declare a `resolution` of `minute` or `second`. A `minute` device exposes HHmm encoded as four BCD digits (16 logical outputs); a `second` device exposes HHmmss encoded as six BCD digits (24 logical outputs). Each digit uses four logical bits, and digits are always ordered hour → minute → second for both resolutions. Device definitions and runtime data are stored separately from feature configuration. Block access runs on the owning Folia region. Unloaded chunks are skipped and refreshed on chunk load; device updates must never force-load chunks. Minute devices are rewritten when the minute changes; second devices are refreshed every second and only bits whose power actually changed are written.

### Future display extensions (P2)

Devices also declare an `encoding`:

- `bcd` (default): the layout above, one slot per bit.
- `analog`: one slot per digit. Each slot is a lectern holding a 15-page book the plugin installs; the page index is the digit, so Paper's `floor(page / (pageCount - 1) * 14) + 1` yields `digit + 1` (levels 1–10) and a `second` device needs six lines instead of twenty-four. Disabling the device removes the book (level 0). Publishing a digit is one block-entity page write, and the plugin only ever pages through lecterns that still hold its own book; anything else is reported and left untouched.

Potential follow-ups include date displays, seven-segment displays, multiple timezones, and extensible output encoders. These are not part of the initial scaffold.

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

## Explicit non-goals for PR1

PR1 creates the project and Agent Native foundation only. It does not implement the time service, placeholders, redstone devices, device persistence, Hangar/Modrinth publishing, or claim Paper/Folia runtime compatibility.
