# ADR 0001: Independent repository and Submodule integration

- Status: Accepted
- Date: 2026-11-23

## Context

RealTime is an OrzMC Minecraft plugin. It must be developed and released independently while remaining discoverable from the organization tools workspace.

## Decision

- Canonical source: `OrzMC/RealTime`.
- `OrzMC/tools` may include this repository as a Git Submodule at `RealTime/`.
- RealTime owns source, issues, PRs, CI, releases, docs, and Agent workflow.
- tools stores only the submodule URL and pinned commit; it does not copy source or become a build dependency.
- RealTime must not depend on tools at build or runtime.

## Consequences

Clone tools with `git clone --recurse-submodules` or run `git submodule update --init --recursive`. Commit changes in RealTime first, then update the tools pointer.
