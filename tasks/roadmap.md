# RealTime Roadmap

This is a plan, not a claim of implementation. Update statuses only with evidence.

| ID | Scope | Status | Depends on |
|---|---|---|---|
| PR1 | Project scaffold and Agent Native foundation | Complete | — |
| PR2 | Clock-backed RealTime Core | Complete | PR1 |
| PR3 | Commands and modular configuration | Complete | PR2 |
| PR4 | Paper/Folia scheduler adapter and runtime tests | Complete | PR3 |
| PR5 | Device model, BCD encoder, redstone output | Complete | PR2, PR4 |
| PR6 | Optional PlaceholderAPI integration | In progress | PR2, PR3 |
| PR7 | Hangar/Modrinth release automation | Backlog | PR4, PR5, PR6 |

## Milestones

- M1: deterministic timezone-aware core service.
- M2: commands, configuration, permissions, Paper/Folia scheduling.
- M3: persistent region-safe redstone devices.
- M4: optional integrations, compatibility matrix, release automation.

Do not implement future milestones in the scaffold PR. Split work into small PRs with explicit acceptance criteria.
