# RealTime

**RealTime** is an independent Minecraft real-world time plugin for Paper and Folia, maintained by [OrzMC](https://github.com/OrzMC).

> **Status: `v0.4.0` released** (2026-11-25) — see the [release](https://github.com/OrzMC/RealTime/releases/tag/v0.4.0) and
> [CHANGELOG](CHANGELOG.md). `main` is now on `0.5.0-SNAPSHOT`.
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

The distributable shaded JAR is written to `realtime-paper/build/libs/RealTime-<version>.jar` (`0.5.0-SNAPSHOT` on
`main`). Release builds use the tag, e.g. `PROJECT_VERSION=0.4.0 ./scripts/package.sh`.

## AI-agent development

Read [AGENTS.md](AGENTS.md), then [tasks/roadmap.md](tasks/roadmap.md). Agents must select only ready tasks, use isolated worktrees, run the shared verification scripts, and report observed evidence rather than assumptions.

See [isolated Agent worktrees](docs/development/agent-worktrees.md) for the multi-agent workspace helpers.

## Features

- **Real-world time** with an explicit `ZoneId` (default `Asia/Shanghai`), `/rtime now|info|reload`, and optional
  PlaceholderAPI placeholders.
- **Redstone clocks in two encodings**: `bcd` (one power-source slot per bit — 16 for `HHmm`, 24 for `HHmmss`) and
  `analog` (one lectern per digit, comparator level = digit + 1 — six lines instead of twenty-four).
- **In-game authoring**: `device create`, `device set`, `device demo` (one-command teaching build), all bounded by
  `limits.allowed-areas` / `max-devices` / `edit-cooldown-seconds` and split permissions.
- **Durable lifecycle**: disable empties every slot, remove queues a crash-safe cleanup job, and a `NEEDS_ATTENTION`
  entry means "that block is not ours" — never overwritten, retry it or dismiss the job.
- **Diagnostics** behind `device status`; tick-level change detection with diff-only, chunk-batched writes.

Commands, permissions, wiring diagrams, the cleanup queue and FAQ: **[usage guide](docs/usage.md)**.
Implementation notes (store schema, encoding maths, scheduler safety): [redstone devices](docs/development/redstone-devices.md).

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
- [0.1.0 production acceptance handoff](docs/operations/production-acceptance-0.1.0.md) (historical)
- [Analog and layout real-server acceptance](docs/operations/analog-acceptance-2026-11-25.md)
- [Contributing](CONTRIBUTING.md)
- [Security](SECURITY.md)

## License

MIT. See [LICENSE](LICENSE).
