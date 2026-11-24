# PR13 — Real Power-Source Outputs and Resolution-Safe Device Mutation

- Status: Implemented and locally verified; real-server re-verification done on the local test instances
- Depends on: PR5 (device model), PR8/PR9 (administration/diagnostics), PR10/PR11 (cleanup/retirement), PR12 (second resolution)
- Trigger: 0.1.0 acceptance on the real test servers (`docs/operations/acceptance-report-0.1.0.md`) found two blockers.

## Defects fixed

1. **Device resolution dropped on enable/disable** — `DeviceAdministrationService.setEnabled()` rebuilt `RedstoneDevice` without `resolution`, so any 24-output device failed validation with `A minute-resolution redstone device must define exactly 16 outputs (HHmm)`. Fixed by carrying the resolution through and adding a regression test.
2. **Outputs could not hold or propagate a signal** — forcing `power` onto a `redstone_wire` is not viable on the 26.x API surface:
   - with physics the wire recalculates from its inputs and drops back to 0;
   - without physics the value sticks but no consumer reacts, and `World.updateNeighborsAt` no longer exists.

## New output contract

- An output slot is a **power source**: a logical 1 places `minecraft:redstone_block`, a logical 0 leaves `minecraft:air`.
- Writes use block physics, so vanilla redstone reacts and the connected circuit switches (verified with a lamp that follows a per-second bit).
- A slot holding anything else is counted as an invalid output, reported once in the log, and never overwritten.
- Cleanup clears `redstone_block` to air; any other block becomes `NEEDS_ATTENTION`.
- `output-power` is retired (a block cannot emit partial strength); the key is ignored when present.
- `processResources` now declares `project.version` as an input, so an incremental build cannot ship a `plugin.xml`-style stale descriptor (`plugin.yml` from another version).

## Acceptance

- Unit tests cover the new slot semantics, cleanup states and the enable/disable regression.
- `scripts/smoke-device-cleanup.sh` now keeps a live enabled second-resolution device, requires its slots to be redstone blocks while enabled, and requires every slot to be air after disabling (block probes where the platform answers them, plugin counters otherwise).
- Re-verified on the local test instances: Paper 26.3-258 slot types matched HHmm exactly and a lamp above the seconds-LSB slot followed it every second; Folia 26.3-26 wrote with `非红石线：0` and cleared the retired device.

## Known behaviour

A receiver built while a slot is already powered only picks the state up on that slot's next change (≤1 s for second devices; minute/hour bits change on their digit boundary). Operators should wire receivers before enabling the device, or nudge one update afterwards.
