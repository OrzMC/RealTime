# Redstone Time Devices

## Configuration

`modules/redstone.yml` controls the optional output module:

```yaml
enabled: false
output-power: 15
```

The module is disabled by default. `output-power` must be 1–15. A logical 1 is emitted at that power; a logical 0 is emitted at power 0.

`data/devices.yml` stores versioned device records. It is separate from feature configuration and is backed up before repository writes.

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
schema-version: 1
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
| `/rtime device enable <uuid>` | `realtime.admin` | Persists enabled state and schedules a redstone refresh |
| `/rtime device disable <uuid>` | `realtime.admin` | Persists disabled state and schedules a redstone refresh |

Use the full UUID shown by `device list`; device names are labels and are not identifiers. A failed persistence operation leaves the current in-memory state unchanged. Mutations and `/rtime reload` share a runtime-state lock so one operation cannot overwrite the other's device list.

These commands never inspect or mutate world blocks directly. The redstone manager receives a refresh request and performs all world/block access through the existing Paper/Folia scheduler ownership rules. Disabled devices remain in the device store so their outputs can be driven to zero when their chunks are loaded.

There is intentionally no in-game remove command yet. Removing a record while one of its output chunks is unloaded could leave a powered wire behind with no record available to clear it on the next chunk load. To retire a device safely, disable it, allow every output chunk to load so the manager can clear the wires, then remove its YAML record. PR10 is the next planned milestone: add a persistent deferred-cleanup queue before safe in-game deletion can be offered. Until that work is merged and verified, do not remove device records through runtime commands or by any automated process that could lose unloaded output coordinates.

Device creation and coordinate editing remain in `data/devices.yml`; no automatic chunk loading or block-selection workflow is provided.

## Output diagnostics

Use `/rtime device status` to inspect aggregate counters without querying world state from the command thread. The snapshot reports configured/enabled devices and output points, completed output checks, unloaded-chunk skips, stale scheduled updates, invalid output blocks, actual power changes, unchanged outputs, and caught write failures. Counters are process-lifetime totals; configured counts reflect the current runtime snapshot. Diagnostics never load chunks and do not change output behavior.
