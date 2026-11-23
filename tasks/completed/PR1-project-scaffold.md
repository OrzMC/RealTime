# PR1 — Project Scaffold and Agent Native Foundation

- Status: Complete
- Priority: P0
- Depends on: empty repository bootstrap

## Objective

Create an independently buildable Kotlin/Paper plugin skeleton and establish the project-level AI Agent development system.

## Scope

- Two Gradle modules: pure Kotlin core and Paper/Folia adapter.
- Minimal plugin lifecycle, default config, and `/rtime` status command.
- Project identity, architecture, ADRs, development/testing/release docs.
- AGENTS.md, Claude Code entry point, roles, workflows, permission/isolation policies.
- Roadmap and task contracts for PR2–PR7.
- Official Gradle Wrapper, local verification scripts, CI, PR and issue templates.

## Acceptance criteria

- [x] Repository identity and module boundaries documented.
- [x] Agent instructions, workflows, and safety policies committed to the PR branch.
- [x] Paper API coordinate follows official Paper 26.3 documentation.
- [x] CI and local validation entry points defined.
- [x] Official Gradle Wrapper 9.2.1 artifacts included from the Gradle distribution repository.
- [x] Gradle build and tests pass in CI.
- [x] Paper server startup and command smoke test recorded.
- [x] Folia server startup and command smoke test recorded.
- [x] Shaded plugin JAR inspected.

## Known limitations

Verified in PR #1 CI and subsequent Paper/Folia 26.3 runtime smoke runs. The scaffold was the base; Core, commands, scheduler, and device features were delivered by subsequent focused PRs.
