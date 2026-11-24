# RealTime

**RealTime** is an independent Minecraft real-world time plugin for Paper and Folia, maintained by [OrzMC](https://github.com/OrzMC).

> **Status: PR1–PR12 complete.** The plugin provides timezone-aware time, Paper/Folia scheduler-safe redstone outputs with minute (HHmm, 16 outputs) or second (HHmmss, 24 outputs) resolution, versioned device persistence, diagnostics, crash-safe deferred cleanup, Paper/Folia restart/chunk-load recovery, and cleanup queue operator controls.

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

- `realtime-core/`: pure Kotlin/JVM time service, BCD encoder, and validated device model.
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

The distributable shaded JAR is expected at `realtime-paper/build/libs/RealTime-0.1.0-SNAPSHOT.jar` for the initial development version.

## AI-agent development

Read [AGENTS.md](AGENTS.md), then [tasks/roadmap.md](tasks/roadmap.md). Agents must select only ready tasks, use isolated worktrees, run the shared verification scripts, and report observed evidence rather than assumptions.

See [isolated Agent worktrees](docs/development/agent-worktrees.md) for the multi-agent workspace helpers.

## Device administration

| Command | Permission | Purpose |
|---|---|---|
| `/rtime device list` | `realtime.command` | List configured device IDs and status |
| `/rtime device info <uuid>` | `realtime.command` | Inspect resolution and every output coordinate |
| `/rtime device status` | `realtime.command` | View aggregate redstone output diagnostics |
| `/rtime device cleanup list` | `realtime.command` | Inspect deferred cleanup queue |
| `/rtime device cleanup retry <job-uuid>` | `realtime.admin` | Retry manually corrected `NEEDS_ATTENTION` outputs |
| `/rtime device remove <uuid>` | `realtime.admin` | Retire a disabled device and enqueue crash-safe deferred cleanup |
| `/rtime device enable <uuid>` | `realtime.admin` | Enable a device and refresh outputs |
| `/rtime device disable <uuid>` | `realtime.admin` | Disable a device and clear loaded outputs |

Each device declares `resolution: minute` (HHmm, 16 outputs) or `resolution: second` (HHmmss, 24 outputs); minute clocks are rewritten when the minute changes, second clocks every second, and only slots whose state changed are written. Every output is a **power-source slot**: bit 1 places a `redstone_block`, bit 0 leaves the slot empty, so vanilla redstone switches the connected circuit. Device records remain YAML-defined. The cleanup queue is durable and inspectable; operators may retry manually corrected attention-required outputs. Device removal is admin-only, disabled-device-only, and delegates to the crash-safe retirement service; the cleanup queue remains the durable source of truth until every output is cleared.

## Docs

- [Product requirements](docs/product/requirements.md)
- [Architecture](docs/architecture/overview.md)
- [Development setup](docs/development/setup.md)
- [Testing strategy](docs/testing/strategy.md)
- [Paper/Folia scheduler safety](docs/development/scheduler-safety.md)
- [Redstone time devices](docs/development/redstone-devices.md)
- [PlaceholderAPI integration](docs/development/placeholderapi.md)
- [Release process](docs/release/process.md)
- [0.1.0 production acceptance handoff](docs/operations/production-acceptance-0.1.0.md)
- [Contributing](CONTRIBUTING.md)
- [Security](SECURITY.md)

## License

MIT. See [LICENSE](LICENSE).
