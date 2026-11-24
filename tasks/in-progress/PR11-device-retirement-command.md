# PR11 — Safe Device Retirement Command

- Status: Complete — merged PR #23 (`6f87d712b6f1451aa525ea55da0e6c58196b305b`)
- Depends on: PR10 (complete)
- Scope: expose the existing crash-safe retirement service through an admin-only command without weakening Paper/Folia thread ownership or cleanup invariants.

## User outcome

An operator can retire a disabled redstone clock with `/rtime device remove <uuid>`. The active device record is removed only after one schema-v2 atomic store replacement also records all 16 output coordinates as a durable cleanup job. The command response includes the cleanup job UUID.

## Requirements

- Require `realtime.admin`.
- Reject enabled devices; operators must run `/rtime device disable <uuid>` first.
- Reuse `DeviceRetirementService`; do not implement a second persistence path.
- Do not inspect `World`, `Chunk`, or `Block` from the command thread. Retire with an unresolved world UUID and let the global cleanup processor bind the configured world name to the loaded world's UUID.
- If persistence fails, leave runtime state unchanged and do not request cleanup.
- On success, report both the retired device ID and cleanup job ID; do not claim all blocks have already been cleared. The job ID must remain usable with `device cleanup list` and `device cleanup retry`.
- Preserve cleanup reservation rules: no coordinate reuse until every output is `CLEARED`.
- Keep `device cleanup list` and `device cleanup retry` as the operator follow-up path.
- Never expose a command that directly deletes YAML records or discards cleanup jobs.

## Acceptance

- Unit tests cover permission denial, enabled-device rejection, missing device, successful service invocation, and command completion.
- Existing retirement service tests continue to prove disabled-only validation, persist-before-publish ordering, and persistence-failure atomicity.
- Paper and Folia runtime smoke invokes the actual `/rtime device remove <uuid>` command and verifies a cleanup job is durably enqueued and processed without force-loading unloaded chunks. Passed in runtime smoke run #94.
- Gradle CI, shaded JAR inspection, Agent Project Validation, generic Paper/Folia smoke, and the cleanup runtime matrix pass.

## Result

- PR #23 merged successfully. Gradle CI #136, Agent Project Validation #103, Paper/Folia + PlaceholderAPI smoke, and Paper/Folia cleanup + device removal smoke #94 passed.
