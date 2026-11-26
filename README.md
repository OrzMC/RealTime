# RealTime

<p align="center">
  <img src="assets/branding/icon.svg" alt="RealTime project icon" width="160">
</p>

**RealTime** is an independent Minecraft real-world time plugin for Paper and Folia, maintained by [OrzMC](https://github.com/OrzMC).

> **Status: `v0.6.0` released** (2026-11-26) — see the [release](https://github.com/OrzMC/RealTime/releases/tag/v0.6.0) and
> [CHANGELOG](CHANGELOG.md). `main` is now on `0.7.0-SNAPSHOT`. Also on
> [Hangar](https://hangar.papermc.io/OrzMC/RealTime) and [Modrinth](https://modrinth.com/project/pRGtDpaD).
> The plugin provides timezone-aware time and Paper/Folia scheduler-safe lectern clocks: **one lectern per digit**, so a
> comparator reads the digit as an analog level (`digit + 1`) and `HHmmss` needs only six slots. Build one with
> `/rtime create <name>`, position single slots with `/rtime place` (the lectern appears immediately), and manage it with
> a flat five-command surface. Versioned persistence (schema v7), diagnostics, crash-safe deferred cleanup and
> cleanup-queue operator controls included.

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

- `realtime-core/`: pure Kotlin/JVM time service, clock-digit encoder, lectern layout and display geometry, and the validated device model.
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

The distributable shaded JAR is written to `realtime-paper/build/libs/RealTime-<version>.jar` (`0.7.0-SNAPSHOT` on
`main`). Release builds use the tag, e.g. `PROJECT_VERSION=0.6.0 ./scripts/package.sh`.

## AI-agent development

Read [AGENTS.md](AGENTS.md), then [tasks/roadmap.md](tasks/roadmap.md). Agents must select only ready tasks, use isolated worktrees, run the shared verification scripts, and report observed evidence rather than assumptions.

See [isolated Agent worktrees](docs/development/agent-worktrees.md) for the multi-agent workspace helpers.

## Features

- **Real-world time** with an explicit `ZoneId` (default `Asia/Shanghai`), `/rtime`, and optional PlaceholderAPI
  placeholders.
- **Reading is two commands**: `/rtime` (time plus every clock) and `/rtime <name>` (one clock in detail). Writing is
  `create`, `on`/`off`, `place`, `remove`, with `status`/`cleanup`/`reload` for operators. Tab completion covers clock
  names, options and slot numbers; removed verbs (`info`, `list`, `device`, `demo`, `set`) answer with their
  replacement.
- **Lectern clocks**: one lectern per digit holding a plugin-authored 15-page book, so a comparator reads
  `digit + 1` with no decoding — six slots for `HHmmss`, four for `HHmm`. Seconds are the default resolution.
- **In-game authoring**: `/rtime create <name>` builds at the block you aim at, grows the slots in the direction you
  face, places the display blocks (comparator + dust + lamp per slot) and starts enabled. `manual` builds skip the
  display and leave every slot unpositioned, so `/rtime place <name> <slot>` can drop each lectern exactly where the
  machine has room — **the lectern appears immediately**, facing you. Authoring is bounded by
  `limits.allowed-areas` / `max-devices` / `edit-cooldown-seconds` and split permissions.
- **Durable lifecycle**: turning a clock off keeps its lecterns and drops the book (comparator reads 0); `remove`
  queues a crash-safe cleanup job, and a `NEEDS_ATTENTION` entry means "that block is not ours" — never overwritten,
  retry it or dismiss the job.
- **Diagnostics** behind `/rtime status`; tick-level change detection with diff-only, chunk-batched writes.

Commands, permissions, wiring diagrams, the cleanup queue and FAQ: **[usage guide](docs/usage.md)**.
Implementation notes (store schema, page/level maths, scheduler safety): [redstone devices](docs/development/redstone-devices.md).

## Docs

- [Product requirements](docs/product/requirements.md)
- [Architecture](docs/architecture/overview.md)
- [Development setup](docs/development/setup.md)
- [Testing strategy](docs/testing/strategy.md)
- [Paper/Folia scheduler safety](docs/development/scheduler-safety.md)
- [Usage guide](docs/usage.md)
- [Redstone devices (implementation notes)](docs/development/redstone-devices.md)
- [PlaceholderAPI integration](docs/development/placeholderapi.md)
- [Development workflow](docs/development/workflow.md) (branch names, gates, PR shape, definition of done)
- [Release process](docs/release/process.md)
- [Hangar and Modrinth publishing](docs/release/distribution.md)
- [0.6.0 real-server acceptance](docs/operations/acceptance-report-0.6.0.md) (current)
- [Hangar / Modrinth listing source of truth](docs/operations/platform-listings.md)
- [0.1.0 production acceptance handoff](docs/operations/production-acceptance-0.1.0.md) (historical)
- [Analog and layout real-server acceptance](docs/operations/analog-acceptance-2026-11-25.md) (historical)
- [Contributing](CONTRIBUTING.md)
- [Security](SECURITY.md)

## License

MIT. See [LICENSE](LICENSE).
