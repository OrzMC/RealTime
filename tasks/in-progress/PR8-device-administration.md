# PR8 — Device Administration Commands

- Status: In Progress
- Priority: P1
- Depends on: PR5 and PR7 (merged)

## Objective

Make existing YAML-defined redstone time devices manageable in-game without weakening the Paper/Folia threading boundary or risking partial runtime/persistence updates.

## Scope

Add the following /rtime device subcommands:

- list: list configured devices with name, UUID, world, and enabled state.
- info <uuid>: show device metadata and its 16 output coordinates.
- enable <uuid> / disable <uuid>: persist the enabled state and refresh outputs.

Permissions:

- realtime.command: device list and device info.
- realtime.admin: enable and disable.

Persistence and state:

- Reuse the existing versioned YamlDeviceRepository and its backup/atomic-replace behavior.
- Serialize mutations with reload through one shared runtime-state lock.
- Persist the candidate device list before publishing the new immutable RuntimeState.
- If persistence fails, retain the previous runtime state and report the failure.
- Trigger the existing redstone manager refresh after a successful mutation.

User experience:

- Add localized message keys and update command usage in messages.yml and plugin.yml.
- Add permission-aware tab completion for subcommands and configured device UUIDs.
- Keep device creation and coordinate editing YAML-based; do not invent a block-selection or auto-layout workflow in this task.

## Constraints

- No Bukkit world/block/chunk access from command logic.
- Never load chunks or generate worlds.
- Preserve Paper/Folia scheduler ownership; refresh requests must go through the existing scheduler adapter.
- Do not add Bukkit/Paper dependencies to realtime-core.
- Device identifiers in mutation commands must be full UUIDs; do not rely on potentially ambiguous names.
- Device deletion remains YAML-based until persistent deferred cleanup is implemented.
- Do not expose in-game deletion until a persistent cleanup queue can safely clear outputs in unloaded chunks.

## Acceptance

- Read-only commands enforce realtime.command; mutation commands enforce realtime.admin.
- Unknown/malformed UUIDs and missing devices return actionable messages without mutation.
- Enable/disable persists and survives reload/restart; repeated requests are safe no-ops.
- Disabling a device schedules zero-power output updates for loaded chunks; future chunk-load events also clear outputs for disabled devices.
- Failed repository writes do not change the active RuntimeState.
- Successful mutations replace runtime state atomically and request a redstone refresh.
- A stale scheduled update must re-check the current device snapshot before writing, so a disabled or reconfigured device cannot be repowered by an older task.
- Device deletion remains a documented YAML operation after all output chunks have been loaded and cleared; in-game deletion is explicitly deferred.
- Tab completion never reveals mutation commands or device IDs to unauthorized senders.
- Unit tests cover command permissions, parsing, persistence, state replacement, failure rollback, and reload serialization.
- Paper and Folia runtime smoke confirms commands work without unsafe world access.
- ./scripts/verify.sh, CI, shaded JAR inspection, and Paper/Folia smoke pass.

## Deliverables

- Device administration service and command handling.
- Command/message/permission/tab-completion updates.
- Focused unit tests and Paper/Folia runtime smoke coverage.
- Updated redstone-device documentation and task/roadmap status.
