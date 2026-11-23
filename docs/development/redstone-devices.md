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