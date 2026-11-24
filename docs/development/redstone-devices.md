# Redstone Time Devices

## Configuration

`modules/redstone.yml` controls the optional output module:

```yaml
enabled: false
output-power: 15
```

The module is disabled by default. `output-power` must be 1–15. A logical 1 is emitted at that power; a logical 0 is emitted at power 0.

`data/devices.yml` stores versioned device records. It is separate from feature configuration and is backed up before repository writes.

## Device store schema v2

The current `data/devices.yml` format uses schema version 2. It stores active devices and deferred cleanup jobs in the **same file**, so a retirement operation can persist both sides of the state transition with one atomic replacement. Existing schema v1 files are migrated automatically on load; device records are preserved and `cleanup-jobs` starts empty. The previous file is retained at `backups/devices.yml.bak`.

A cleanup job records the retired device identity, world name, optional world UUID, and all 16 output coordinates. Each output has one of three states:

- `PENDING`: not yet safely processed (including unloaded chunks or unavailable worlds).
- `CLEARED`: confirmed redstone wire is at power 0, or was already at power 0.
- `NEEDS_ATTENTION`: target is not redstone wire or another terminal condition requires operator inspection; it is not considered fully cleaned.

Example shape (abbreviated):

```yaml
schema-version: 2
devices: []
cleanup-jobs:
  - id: 6b3c20e4-77e6-4d28-92d5-6a0d1fd20a10
    device-id: 45b8f930-0a13-4f8c-9b90-8eac4a2b4a10
    device-name: lobby-clock
    world: world
    world-uuid: null
    outputs:
      - { bit-index: 0, x: 100, y: 65, z: 100, status: PENDING, detail: null }
```

The complete job contains all 16 outputs. Do not hand-edit or delete cleanup jobs. Coordinates reserved by a cleanup job cannot be reused by an active device until the job is fully resolved and removed by the cleanup lifecycle. PR10a adds the schema and migration; PR10b adds the crash-safe retirement service and region-owned cleanup processor. PR10c adds operator queue inspection/retry and device-specific Paper/Folia runtime recovery coverage. PR11 exposes the disabled-only admin retirement command through the existing crash-safe service.

## HHmm BCD output layout

Each device has exactly 16 unique output blocks. All output blocks must be `minecraft:redstone_wire`.

| Bit indexes | Digit | Bit order |
|---|---|---|
| 0–3 | Hour tens | LSB → MSB |
| 4–7 | Hour ones | LSB → MSB |
| 8–11 | Minute tens | LSB → MSB |
| 12–15 | Minute ones | LSB → MSB |

For example, 23:59 is encoded as digits `2, 3, 5, 9`; each digit occupies four bits, least-significant bit first.

## Device file example

The following shows the complete schema and 16 output records. Change the world name and coordinates to match the build:

```yaml
schema-version: 2
devices:
  - id: 6b3c20e4-77e6-4d28-92d5-6a0d1fd20a10
    name: lobby-clock
    world: world
    enabled: true
    outputs:
      - { bit-index: 0, x: 100, y: 65, z: 100 }
      - { bit-index: 1, x: 102, y: 65, z: 100 }
      - { bit-index: 2, x: 104, y: 65, z: 100 }
      - { bit-index: 3, x: 106, y: 65, z: 100 }
      - { bit-index: 4, x: 108, y: 65, z: 100 }
      - { bit-index: 5, x: 110, y: 65, z: 100 }
      - { bit-index: 6, x: 112, y: 65, z: 100 }
      - { bit-index: 7, x: 114, y: 65, z: 100 }
      - { bit-index: 8, x: 116, y: 65, z: 100 }
      - { bit-index: 9, x: 118, y: 65, z: 100 }
      - { bit-index: 10, x: 120, y: 65, z: 100 }
      - { bit-index: 11, x: 122, y: 65, z: 100 }
      - { bit-index: 12, x: 124, y: 65, z: 100 }
      - { bit-index: 13, x: 126, y: 65, z: 100 }
      - { bit-index: 14, x: 128, y: 65, z: 100 }
      - { bit-index: 15, x: 130, y: 65, z: 100 }
cleanup-jobs: []
```

Device IDs must be unique. Bit indexes must cover 0 through 15 exactly once. Output coordinates must be unique within a device and across all devices in the same world. World names are used for configuration convenience; changing a world's name requires updating this file.

## Runtime safety

- Time and BCD frames are calculated on the global scheduler.
- Every output block update is dispatched through the owning region scheduler.
- Before `getBlockAt`, the owning-region callback checks `World.isChunkLoaded` for the target chunk.
- Unloaded chunks are skipped and their outputs are refreshed on `ChunkLoadEvent`.
- The manager never calls `getChunkAt`, chunk load APIs, or world generation APIs.
- Output wire blocks should be physically isolated to prevent adjacent redstone wire networks from coupling.
- The current MVP is configured through YAML; in-game device creation/editing commands are not included.

After editing the files, use `/rtime reload` as an operator. A failed validation keeps the previous runtime configuration and device list active.

## In-game device administration

The device records remain YAML-defined so each of the 16 output positions is explicit and reviewable. After the plugin loads the file, operators can inspect and manage existing records in-game:

| Command | Permission | Behavior |
|---|---|---|
| `/rtime device list` | `realtime.command` | Lists configured devices, UUID, world, and enabled state |
| `/rtime device info <uuid>` | `realtime.command` | Shows metadata and all 16 bit-to-coordinate mappings |
| `/rtime device status` | `realtime.command` | Shows aggregate output health and refresh counters |
| `/rtime device cleanup list` | `realtime.command` | Lists durable cleanup jobs with pending and operator-attention counts |
| `/rtime device cleanup retry <job-uuid>` | `realtime.admin` | Requeues only `NEEDS_ATTENTION` outputs after an operator has corrected their blocks |
| `/rtime device remove <uuid>` | `realtime.admin` | Retires a disabled device and atomically creates its deferred cleanup job |
| `/rtime device enable <uuid>` | `realtime.admin` | Persists enabled state and schedules a redstone refresh |
| `/rtime device disable <uuid>` | `realtime.admin` | Persists disabled state and schedules a redstone refresh |

Use the full UUID shown by `device list`; device names are labels and are not identifiers. A failed persistence operation leaves the current in-memory state unchanged. Mutations and `/rtime reload` share a runtime-state lock so one operation cannot overwrite the other's device list.

These commands never inspect or mutate world blocks directly. The redstone manager receives a refresh request and performs all world/block access through the existing Paper/Folia scheduler ownership rules. Disabled devices remain in the device store so their outputs can be driven to zero when their chunks are loaded.

There is intentionally no in-game device remove command yet. PR10b now contains the crash-safe retirement service and deferred cleanup processor, but the service is not exposed as a player-facing command. Do not manually delete device records or cleanup jobs from YAML: retirement must atomically persist the active-device removal and all 16 cleanup coordinates. While PR10c is in progress, operators can inspect the durable queue and retry `NEEDS_ATTENTION` outputs after manually correcting the target block. The remove command remains blocked until device-specific Paper/Folia restart and chunk-load recovery tests pass.

Device creation and coordinate editing remain in `data/devices.yml`; no automatic chunk loading or block-selection workflow is provided.

## Output diagnostics

Use `/rtime device status` to inspect aggregate counters without querying world state from the command thread. The snapshot reports configured/enabled devices and output points, completed output checks, unloaded-chunk skips, stale scheduled updates, invalid output blocks, actual power changes, unchanged outputs, and caught write failures. Counters are process-lifetime totals; configured counts reflect the current runtime snapshot. Diagnostics never load chunks and do not change output behavior.
