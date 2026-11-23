# PR1 — Project Scaffold and Agent Native Foundation

- Status: In Progress
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
- Local verification scripts, CI, PR and issue templates.

## Acceptance criteria

- [x] Repository identity and module boundaries documented.
- [x] Agent instructions, workflows, and safety policies committed to the PR branch.
- [x] Paper API coordinate follows official Paper 26.3 documentation.
- [x] CI and local validation entry points defined.
- [ ] Official Gradle Wrapper artifacts added and verified.
- [ ] Gradle build and tests pass in CI.
- [ ] Paper server startup and command smoke test recorded.
- [ ] Folia server startup and command smoke test recorded.
- [ ] Shaded plugin JAR inspected.

## Known limitations

This task is not complete until the unchecked criteria are verified. The current PR branch is a scaffold and does not claim runtime compatibility.
