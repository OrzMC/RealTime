# RealTime

**RealTime** is an independent Minecraft real-world time plugin for Paper and Folia, maintained by [OrzMC](https://github.com/OrzMC).

> **Status: PR1–PR18 merged; `0.2.0-SNAPSHOT` is unreleased** (the last public build is [`v0.1.0`](https://github.com/OrzMC/RealTime/releases/tag/v0.1.0)).
> The plugin provides timezone-aware time, Paper/Folia scheduler-safe redstone clocks in two encodings — **BCD**
> (16/24 power-source slots for `HHmm`/`HHmmss`) and **analog** (one lectern per digit, so `HHmmss` needs six lines
> instead of twenty-four) — plus in-game device authoring, one-command teaching builds, versioned persistence
> (schema v5), diagnostics, crash-safe deferred cleanup, and cleanup-queue operator controls.

## Goals

- Provide one timezone-aware real-world time service; default timezone: `Asia/Shanghai`.
- Keep domain logic independent from Bukkit/Paper/Folia APIs.
- Support Paper and Folia through explicit platform adapters.
- Add command, optional PlaceholderAPI, and redstone-clock features incrementally.
- Remain independently buildable, testable, and releasable; no build-time or runtime dependency on the parent repository `OrzMC/OrzMC`.

## Technical baseline

| Area | Baseline |
|---|---|
| Language / JDK | Kotlin / 25 |
| Build | Gradle Kotlin DSL / Gradle Wrapper 9.2.1 |
| Minecraft API | Paper API 26.3 line |
| Platforms | Paper + Folia |
| Minimum API | 26.3 |
| Command | `/rtime` |
| Default timezone | `Asia/Shanghai` |
| License | MIT |

PaperMC documents `io.papermc.paper:paper-api:26.3.build.+` for the 26.3 API line. The resolved version must be locked and reviewed after dependency resolution. See [Paper project setup](https://docs.papermc.io/paper/dev/project-setup/).

## Layout

- `realtime-core/`: pure Kotlin/JVM time service, BCD/analog encoders, device layout and demo geometry, and the validated device model.
- `realtime-paper/`: plugin lifecycle, commands, modular configuration, Paper/Folia adapters, device persistence, and optional integrations.
- `tasks/`: roadmap and executable task contracts.
- `.agents/`: roles, workflows, and safety policies.
- `docs/`: product requirements, architecture, ADRs, development, testing, release.
- `scripts/`: common local/agent entry points.

## Quick start

Requirements: JDK 25.

```bash
./scripts/doctor.sh
./scripts/verify.sh
./scripts/package.sh
```

The Gradle Wrapper pins Gradle 9.2.1. The first build downloads the Gradle distribution and project dependencies.

The distributable shaded JAR is expected at `realtime-paper/build/libs/RealTime-0.2.0-SNAPSHOT.jar` for the initial development version.

## AI-agent development

Read [AGENTS.md](AGENTS.md), then [tasks/roadmap.md](tasks/roadmap.md). Agents must select only ready tasks, use isolated worktrees, run the shared verification scripts, and report observed evidence rather than assumptions.

See [isolated Agent worktrees](docs/development/agent-worktrees.md) for the multi-agent workspace helpers.

## Device administration

| Command | Permission | Purpose |
|---|---|---|
| `/rtime device create <name> <minute\|second> <world> <x> <y> <z> <axis> [stride] [bcd\|analog]` | `realtime.device.edit` | Create a device slot layout along an axis |
| `/rtime device demo <bcd\|analog> <world> <x> <y> <z> <axis>` | `realtime.device.edit` | Build one complete teaching clock (display blocks included) and enable it |
| `/rtime device set <uuid> <slot> <x> <y> <z>` | `realtime.device.edit` | Move one output slot (disabled devices only) |
| `/rtime device list` | `realtime.device.view` | List configured device IDs and status |
| `/rtime device info <uuid>` | `realtime.device.view` | Inspect resolution and every output coordinate |
| `/rtime device status` | `realtime.device.view` | View aggregate redstone output diagnostics |
| `/rtime device cleanup list` | `realtime.device.view` | Inspect deferred cleanup queue |
| `/rtime device cleanup retry <job-uuid>` | `realtime.device.retire` | Retry manually corrected `NEEDS_ATTENTION` outputs |
| `/rtime device remove <uuid>` | `realtime.device.retire` | Retire a disabled device and enqueue crash-safe deferred cleanup |
| `/rtime device enable <uuid>` | `realtime.device.toggle` | Enable a device and refresh outputs |
| `/rtime device disable <uuid>` | `realtime.device.toggle` | Disable a device and clear loaded outputs |

Each device declares `resolution: minute` (`HHmm`) or `second` (`HHmmss`) and an `encoding`:

- **`bcd`** (default): four slots per digit — 16 for `minute`, 24 for `second`. A slot is a power source: bit 1 places a `redstone_block`, bit 0 leaves it empty, so vanilla redstone switches the connected circuit.
- **`analog`**: one slot per digit — 4 for `minute`, 6 for `second`. A slot is a lectern holding a plugin-created 15-page book; the page index is the digit, so a comparator reads level `digit + 1`. Six lines replace twenty-four.

New devices are laid out with one empty block between slots and one extra block between the four bits of a digit (`stride` defaults to 2, BCD groups four slots per digit), so neighbouring slots cannot feed each other. Minute clocks are rewritten when the minute changes, second clocks every second, and only slots whose state changed are written. Authoring limits live in `modules/redstone.yml` (`max-devices`, `edit-cooldown-seconds`, `allowed-areas`).

`/rtime device demo` builds a complete example — lamps above every BCD slot, or a lectern/comparator/dust/lamp row per analog digit — and enables it, which is the fastest way to see an encoding working. Display blocks belong to that device and are removed by `disable`/`remove`; a normal device only ever touches its own slots.

The cleanup queue is durable and inspectable; operators may retry manually corrected attention-required outputs. Device removal is disabled-device-only and delegates to the crash-safe retirement service; the cleanup queue remains the durable source of truth until every output (and demo display block) is cleared.

## Docs

- [Product requirements](docs/product/requirements.md)
- [Architecture](docs/architecture/overview.md)
- [Development setup](docs/development/setup.md)
- [Testing strategy](docs/testing/strategy.md)
- [Paper/Folia scheduler safety](docs/development/scheduler-safety.md)
- [Redstone time devices](docs/development/redstone-devices.md)
- [PlaceholderAPI integration](docs/development/placeholderapi.md)
- [Release process](docs/release/process.md)
- [0.1.0 production acceptance handoff](docs/operations/production-acceptance-0.1.0.md) (historical)
- [Analog and layout real-server acceptance](docs/operations/analog-acceptance-2026-11-25.md)
- [Contributing](CONTRIBUTING.md)
- [Security](SECURITY.md)

## License

MIT. See [LICENSE](LICENSE).
