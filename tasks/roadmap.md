# RealTime Roadmap

This is a plan, not a claim of implementation. Update statuses only with evidence.

| ID | Scope | Status | Depends on |
|---|---|---|---|
| PR1 | Project scaffold and Agent Native foundation | Complete | — |
| PR2 | Clock-backed RealTime Core | Complete | PR1 |
| PR3 | Commands and modular configuration | Complete | PR2 |
| PR4 | Paper/Folia scheduler adapter and runtime tests | Complete | PR3 |
| PR5 | Device model, BCD encoder, redstone output | Complete | PR2, PR4 |
| PR6 | Optional PlaceholderAPI integration | Complete | PR2, PR3 |
| PR7 | Hangar/Modrinth release automation | Complete | PR4, PR5, PR6 |
| PR8 | Device administration commands and safe lifecycle | Complete | PR5, PR7 |
| PR9 | Redstone device diagnostics and output observability | Complete | PR5, PR8 |
| PR10 | Persistent deferred cleanup for safely retiring devices (PR10a store, PR10b processor, PR10c runtime/ops) | Complete — PR10a/PR10b/PR10c merged | PR5, PR8, PR9 |
| PR11 | Admin-only disabled-device retirement command | Complete — PR #23 merged and Paper/Folia runtime smoke passed | PR10 |
| PR12 | Second-resolution output (HHmmss, 24 BCD outputs) and schema v3 | Complete — PR #26 (`b2fb5dd`) | PR5, PR10, PR11 |
| PR13 | Real power-source outputs (`redstone_block`/air) + resolution-safe enable/disable | Complete — PR #28 (`f31e7ce`) | PR12 |
| PR14 | Tick-level detection, diff-only and chunk-batched output writes | Complete — PR #30 (`dd844f4`) | PR13 |
| PR15 | In-game device authoring (`create`/`set`) + permission split | Complete — PR #33 (`1c81060`) | PR14 |
| PR16 | Analog (lectern) encoding, one digit per slot, schema v4 | Complete — PR #36 (`71c0355`) | PR15 |
| PR17 | Default layout: one-block gaps and four-bit digit groups | Complete — PR #35 (`f2143b1`) | PR16 |
| PR18 | `device demo` teaching builds (schema v5) and safe slot edits | Complete — PR #41 (`ebeee56`), #40 (`27ecc01`) | PR17 |
| PR19 | Redstone module on by default, stated module/limit state in logs, miss-proof warnings | Complete — PR #68 (`8bb951c`), #69 (`bf1558a`) | PR18 |
| PR20 | Command surface v2: flat verbs, one command per clock, per-slot placement, tab completion and help | Complete — #70 (`5e91ab8`), #67 (`024c8c1`), #65 (`26f1cca`) | PR19 |

## Milestones

- M1: deterministic timezone-aware core service.
- M2: commands, configuration, permissions, Paper/Folia scheduling.
- M3: persistent region-safe redstone devices.
- M4: optional integrations, compatibility matrix, release automation.
- M5: safe device administration and operational diagnostics.
- M6: crash-safe deferred cleanup and safe device retirement.
- M7: two encodings (BCD and analog lecterns), in-game authoring with split permissions, teaching builds.

Every stage above is merged and included in `v0.2.0`, `v0.3.0`, `v0.4.0` and `v0.5.0` (released 2026-11-26); `main` is on `0.6.0-SNAPSHOT`.
Split new work into small PRs with explicit acceptance criteria.
