# ADR 0001: Independent repository and Submodule integration

- Status: Accepted
- Date: 2026-11-23

## Context

RealTime is an OrzMC Minecraft plugin. It must be developed, built, tested, and released as an independent repository while remaining discoverable from the OrzMC product umbrella.

## Decision

- Canonical source: `OrzMC/RealTime`.
- Integration point: `OrzMC/OrzMC/tools/RealTime`.
- The parent repository `OrzMC/OrzMC` records RealTime as a Git submodule at `tools/RealTime`.
- RealTime owns source, issues, PRs, CI, releases, documentation, and Agent workflow.
- The parent repository stores only the submodule URL and pinned commit; it does not copy source or become a build dependency.
- RealTime must not depend on `OrzMC/OrzMC` at build time or runtime.

## Consequences

Clone the parent repository with `git clone --recurse-submodules` or initialize it with `git submodule update --init --recursive`. Commit changes in RealTime first, merge them into its canonical branch, then update the `tools/RealTime` pointer in the parent repository.
