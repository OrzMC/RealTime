# PR15 — In-Game Device Authoring and Permission Split

- Status: Implemented and locally verified
- Depends on: PR13 (power-source outputs), PR14 (diff scheduling)

## Why

Devices could only be created by hand-editing `data/devices.yml` with 16 or 24 coordinate records, which is
error-prone, and every device action shared a single `realtime.admin` node, so the only alternative to "op" was
"no access". Requested after the 0.1.0 release.

## Commands

```
/rtime device create <name> <minute|second> <world> <x> <y> <z> <x+|x-|z+|z-> [stride]
/rtime device set <uuid> <bit-index> <x> <y> <z>
```

- `create` lays the slots out along one axis (default stride 1), creates the device **disabled**, and persists it
  through the same atomic store + publish path as the other mutations.
- `set` moves a single slot of an existing device and keeps the device's resolution.

## Validation (no world access, so it stays Folia-safe)

- resolution / axis / stride sanity; bit index within the device's resolution
- every coordinate inside `limits.allowed-areas` when that list is non-empty
- `limits.max-devices` cap
- no overlap with another device or with a pending cleanup job
- `limits.edit-cooldown-seconds` per player (console exempt)
- audit log line per create/set with actor, device UUID and coordinates

Blocks are deliberately **not** inspected by the command: slots holding something else are already reported as
invalid outputs and never overwritten.

## Permissions

| Node | Default | Commands |
|---|---|---|
| `realtime.command` | true | now, info |
| `realtime.device.view` | true | device list/info/status, cleanup list |
| `realtime.device.toggle` | op | device enable/disable |
| `realtime.device.edit` | op | device create/set |
| `realtime.device.retire` | op | device remove, cleanup retry |
| `realtime.admin` | op | reload + all of the above (children) |

## Acceptance

- Handler tests: permission denial per node, axis/stride layout, unknown resolution, limit/area/conflict messages,
  `set` success and out-of-range bit index, per-player cooldown.
- Service tests: limit, area, overlap with device and cleanup job, persist-before-publish ordering, resolution kept on
  `set`, conflict rejection.
- Config loader tests: limits parsed, defaults applied, negative limits and inverted areas rejected.
- `./scripts/verify.sh`, Paper/Folia smoke, and an in-game create/set on the test instances.
