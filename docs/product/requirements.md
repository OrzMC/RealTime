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

Planned commands:

- `/rtime` or `/rtime now`: display current configured local date/time.
- `/rtime info`: display plugin version, configured timezone, and module status.
- `/rtime reload`: validate and reload configuration safely.

Administrative device commands are planned separately after the device model exists.

### Optional PlaceholderAPI integration (P1)

Initial planned placeholders: date, time, datetime, weekday, timezone, and Unix timestamp. PlaceholderAPI must remain optional; the plugin must load when it is absent.

### Redstone clock devices (P1)

Initial output mode is HHmm encoded as four BCD digits. Each digit uses four logical bits, for 16 logical outputs. Device definitions and runtime data must be separate from feature configuration. Block access must run on the owning Folia region. Updating devices must not force-load chunks.

### Future display extensions (P2)

Potential follow-ups include HHmmss, date, seven-segment displays, multiple timezones, and extensible output encoders. These are not part of the initial scaffold.

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
