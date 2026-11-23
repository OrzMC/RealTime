# RealTime

**RealTime** is an independent Minecraft real-world time plugin for Paper and Folia, maintained by [OrzMC](https://github.com/OrzMC).

> **Status: initial scaffold.** The repository currently contains the project skeleton, plugin bootstrap, and Agent Native project workflow. The time service, device features, and actual Paper/Folia runtime compatibility are not yet implemented or verified.

## Goals

- Provide one timezone-aware real-world time service; default timezone: `Asia/Shanghai`.
- Keep domain logic independent from Bukkit/Paper/Folia APIs.
- Support Paper and Folia through explicit platform adapters.
- Add command, optional PlaceholderAPI, and redstone-clock features incrementally.
- Remain independently buildable, testable, and releasable; no dependency on OrzMCPlugin or OrzMC/tools.

## Technical baseline

| Area | Baseline |
|---|---|
| Language / JDK | Kotlin / 25 |
| Build | Gradle Kotlin DSL |
| Minecraft API | Paper API 26.3 line |
| Platforms | Paper + Folia |
| Minimum API | 26.3 |
| Command | `/rtime` |
| Default timezone | `Asia/Shanghai` |
| License | MIT |

PaperMC documents `io.papermc.paper:paper-api:26.3.build.+` for the 26.3 API line. The resolved version must be locked and reviewed after dependency resolution. See [Paper project setup](https://docs.papermc.io/paper/dev/project-setup/).

## Layout

- `realtime-core/`: pure Kotlin/JVM domain logic.
- `realtime-paper/`: plugin lifecycle, commands, configuration, platform integration.
- `tasks/`: roadmap and executable task contracts.
- `.agents/`: roles, workflows, and safety policies.
- `docs/`: architecture, ADRs, development, testing, release.
- `scripts/`: common local/agent entry points.

## Quick start

Requirements: JDK 25 and Gradle 9.2.1 (or compatible Gradle 9.x).

```bash
./scripts/doctor.sh
./scripts/verify.sh
./scripts/package.sh
```

The initial scaffold intentionally does not contain a fabricated Gradle Wrapper JAR. Until official wrapper artifacts are generated, scripts use an installed Gradle executable; CI installs the pinned Gradle version. See [development setup](docs/development/setup.md).

The distributable shaded JAR is expected at `realtime-paper/build/libs/RealTime-<version>.jar`.

## AI-agent development

Read [AGENTS.md](AGENTS.md), then [tasks/roadmap.md](tasks/roadmap.md). Agents must select only ready tasks, use isolated worktrees, run the shared verification scripts, and report observed evidence rather than assumptions.

## Docs

- [Architecture](docs/architecture/overview.md)
- [Development setup](docs/development/setup.md)
- [Testing strategy](docs/testing/strategy.md)
- [Release process](docs/release/process.md)
- [Contributing](CONTRIBUTING.md)
- [Security](SECURITY.md)

## License

MIT. See [LICENSE](LICENSE).
