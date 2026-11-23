# PR11 — Guarded In-Game Device Retirement Command

- Status: In Progress
- Priority: P1
- Depends on: PR10a, PR10b, PR10c

## Objective

Expose the already-tested crash-safe retirement service through a narrow administrator command without allowing enabled devices or unresolved cleanup coordinates to be silently discarded.

## Command contract

- `/rtime device remove <uuid>` requires `realtime.admin`.
- The device must already be disabled; enabled devices are rejected without persistence or runtime mutation.
- On success, remove the device from the active list and persist a complete 16-output cleanup job in the same schema-v2 atomic store snapshot.
- The command returns the cleanup job UUID so operators can inspect it with `/rtime device cleanup list`.
- Tab completion lists only disabled device UUIDs for administrators.
- No command deletes cleanup jobs or bypasses `NEEDS_ATTENTION` handling.

## Acceptance

- Command handler unit tests cover permission denial, success response, and disabled-device-only tab completion.
- Retirement service tests continue to prove enabled-device rejection and persistence-before-publish ordering.
- Paper and Folia runtime smoke verify a disabled device can be retired, disappears from active devices, and creates a durable, inspectable cleanup job.
- Gradle CI, Agent Validation, shaded JAR inspection, Paper/Folia smoke, and PlaceholderAPI smoke pass.
- Operator docs and plugin usage metadata describe the command and its disabled-device requirement.
