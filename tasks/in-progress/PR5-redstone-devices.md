# PR5 — Redstone Time Devices

- Status: In Progress
- Priority: P1
- Depends on: PR2, PR4

## Scope

- Add exhaustive HHmm BCD encoder and immutable 16-bit output frame to realtime-core.
- Add a validated persistent device model with 16 unique output positions.
- Store device records in versioned data/devices.yml and back up the previous file before atomic replacement.
- Add separate modules/redstone.yml configuration; default disabled and output power 15.
- Update only REDSTONE_WIRE outputs, mapping logical 0/1 to power 0/configured power.
- Use GlobalRegionScheduler for time/frame calculation and RegionScheduler for block operations.
- Refresh loaded outputs on minute changes and on ChunkLoadEvent.
- Check World.isChunkLoaded inside the owning region before accessing blocks; never call chunk-load/generation APIs.

## Acceptance

- Exhaustive 24 x 60 BCD encoder test coverage and explicit LSB-first bit ordering.
- Device validation rejects missing/duplicate bit indexes and duplicate block positions.
- YAML persistence round-trips device records, versions schema, and creates a backup before replacing existing data.
- Invalid timezone/config/device data fails before runtime state replacement.
- Region callbacks do not access blocks until the target chunk is confirmed loaded.
- Unloaded chunks are skipped and refreshed when ChunkLoadEvent fires; no forced or async chunk loads.
- Paper 26.3 and Folia 26.3 runtime smoke starts cleanly with the module disabled by default.