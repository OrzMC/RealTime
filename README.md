# RealTime

**RealTime** is an independent Minecraft real-world time plugin for Paper and Folia, maintained by [OrzMC](https://github.com/OrzMC).

> **Status: PR1–PR3 merged; PR4 in progress.** Paper/Folia 26.3 startup, global scheduler, `/rtime`, `info`, and `reload` smoke tests pass. The Paper/Folia scheduler facade now routes global, region, and entity work through their owning schedulers and tracks tasks for shutdown cancellation.

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

- `realtime-core/`: pure Kotlin/JVM domain logic, including the Clock-backed time service (PR2).
- `realtime-paper/`: plugin lifecycle, commands, configuration, platform integration.
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

## Docs

- [Product requirements](docs/product/requirements.md)
- [Architecture](docs/architecture/overview.md)
- [Development setup](docs/development/setup.md)
- [Testing strategy](docs/testing/strategy.md)
- [Release process](docs/release/process.md)
- [Contributing](CONTRIBUTING.md)
- [Security](SECURITY.md)

## License

MIT. See [LICENSE](LICENSE).
