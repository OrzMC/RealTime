# RealTime 0.1.0 acceptance report

> Status: **in progress — preflight and local build verified; server sections not run yet.**
> Companion checklist: [production-acceptance-0.1.0.md](production-acceptance-0.1.0.md).
> Fill this report only with observed evidence. Never paste RCON passwords, tokens, server addresses, or player data.

## 0. Scope

- Release under test: `0.1.0` (PR1–PR12 features, including minute/second redstone resolution).
- Environment for the sections below: **local build only**. No production or staging server has been touched.

## 1. Artifact under test

| Item | Value |
|---|---|
| Repository / commit | `OrzMC/RealTime` @ `b2fb5ddd42982a328d2dfe4e34b690bb4cdbf089` |
| Working tree at build time | clean (`git status --porcelain` empty) |
| Artifact | `realtime-paper/build/libs/RealTime-0.1.0.jar` |
| Size | 2,049,432 bytes |
| SHA-256 | `379b2a7d41c74b3cb98f3b433d95c3729d983aa4e01e0e9a09e4e718749a9fb1` |
| Build JDK | Temurin 25.0.5.1+2 (LTS, build 25.0.5.1+2-LTS) |
| Build tool | Gradle Wrapper 9.2.1, Kotlin 2.4.21 |
| Build command | `PROJECT_VERSION=0.1.0 ./scripts/package.sh` |
| Descriptor check | `plugin.yml`: `version: '0.1.0'`, `api-version: '26.3'`, `folia-supported: true`, `main: cn.orzmc.realtime.paper.RealTimePlugin` |
| `scripts/inspect-jar.sh` | Passed (entry point present, no bundled `org/bukkit` or `io/papermc` classes) |
| Reproducibility | Two independent clean builds produced the identical SHA-256 |

## 2. Local automated gates

| Gate | Result | Evidence |
|---|---|---|
| `./scripts/verify.sh` | PASS | `clean check spotlessCheck build` + release-script checks (tag/changelog validation for `v0.1.0`) |
| Unit tests | PASS | 99 tests, 0 failures, 0 skipped (`realtime-core` + `realtime-paper`) |
| CI on the merged commit | PASS | PR #26: verify, validate, smoke (Paper/Folia), cleanup-smoke (Paper/Folia), placeholderapi-smoke (Paper/Folia) — 8/8 green |
| Local runtime smoke, Paper 26.3 | PASS | `scripts/smoke-server.sh paper`, `scripts/smoke-device-cleanup.sh paper` (24-output `second` device), `WITH_PLACEHOLDERAPI=true scripts/smoke-server.sh paper` |
| Local runtime smoke, Folia 26.3 | PASS | same three scripts against Folia |

## 3. Server preflight (to be filled on the test server)

| Fact | Value |
|---|---|
| Date / time / timezone | |
| Server software and exact build (`version` / `/version`) | |
| Java runtime (`java -version` from the server process) | |
| Minecraft version, Paper or Folia | |
| RealTime JAR currently installed (if any) | |
| Other plugins / PlaceholderAPI version | |
| Staging clone or production | |
| Backup location and restore procedure | |
| Process manager (MCSManager / Docker / direct) | |
| Isolated test area (world + coordinates) | |

Stop if the platform is below API 26.3, Java is not 25, backup/restore is unverified, or no isolated test area exists.

## 4. Check matrix

| Check | Result (PASS / FAIL / NOT RUN) | Evidence / notes |
|---|---|---|
| Compatibility gate | NOT RUN | |
| First boot and files | NOT RUN | |
| Timezone and commands | NOT RUN | |
| PlaceholderAPI | NOT RUN | |
| BCD output mapping (`minute` device) | NOT RUN | |
| BCD output mapping (`second` device) | NOT RUN | |
| Disable and retire | NOT RUN | |
| Unloaded chunk deferral | NOT RUN | |
| Non-wire `NEEDS_ATTENTION` | NOT RUN | |
| Cleanup retry | NOT RUN | |
| Restart recovery | NOT RUN | |
| Schema-v1 migration | NOT RUN | |
| Reload failure safety | NOT RUN | |
| Plugin shutdown | NOT RUN | |
| Performance / coexistence | NOT RUN | |

## 5. Findings

- Preflight only. No server behaviour has been observed yet, so no compatibility claim is made.
- Fixed before this report: the runtime smoke scripts relied on `time query daytime` (removed from the 26.x command surface) and on `execute … run <plugin command>` output (empty on Folia); the non-wire assertion now reads the durable `Expected REDSTONE_WIRE, found STONE` detail instead.
- Follow-up candidate (not fixed, out of scope for this artifact): `processResources` does not depend on `project.version`, so an incremental build that reuses `build/resources/main/plugin.yml` can produce a jar whose descriptor says `0.1.0-SNAPSHOT` while the file name says `0.1.0`. Always build release artifacts from a clean tree and confirm `scripts/inspect-jar.sh` passes.

## 6. Recommendation

- **BLOCKED** — server acceptance has not been executed; do not tag `v0.1.0` or publish yet.
