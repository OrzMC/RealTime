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
| PR11 | Admin-only disabled-device retirement command | In progress | PR10 |

## Milestones

- M1: deterministic timezone-aware core service.
- M2: commands, configuration, permissions, Paper/Folia scheduling.
- M3: persistent region-safe redstone devices.
- M4: optional integrations, compatibility matrix, release automation.
- M5: safe device administration and operational diagnostics.
- M6: crash-safe deferred cleanup and safe device retirement.

Do not implement future milestones in the scaffold PR. Split work into small PRs with explicit acceptance criteria.
