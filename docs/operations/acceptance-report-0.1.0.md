# RealTime 0.1.0 acceptance report

> Status: **BLOCKED on the 0.1.0 artifact `379b2a7d…`; both blockers are fixed in PR13 and re-verified on the same servers (§7).**
> Companion checklist: [production-acceptance-0.1.0.md](production-acceptance-0.1.0.md).
> Only observed evidence is recorded here. RCON passwords, world data and player identities are omitted.

## 0. Scope

- Release under test: `0.1.0` (PR1–PR12, minute/second redstone resolution).
- Environment: local **test** instances (owner-approved, no backup required), Paper **and** Folia.
- Date: 2026-11-24, 14:04–14:40 (Asia/Shanghai).

## 1. Artifact under test

| Item | Value |
|---|---|
| Repository / commit | `OrzMC/RealTime` @ `b2fb5ddd42982a328d2dfe4e34b690bb4cdbf089` |
| Working tree at build time | clean |
| Artifact | `RealTime-0.1.0.jar`, 2,049,432 bytes |
| SHA-256 | `379b2a7d41c74b3cb98f3b433d95c3729d983aa4e01e0e9a09e4e718749a9fb1` |
| Verified inside both containers | `sha256sum /server/plugins/RealTime-0.1.0.jar` → identical to the local build |
| Build JDK / tool | Temurin 25.0.5.1+2 / Gradle 9.2.1 / Kotlin 2.4.21 |
| Descriptor | `version: '0.1.0'`, `api-version: '26.3'`, `folia-supported: true` |
| Reproducibility | Two independent clean builds produced the same SHA-256 |

## 2. Local automated gates (before the server run)

| Gate | Result | Evidence |
|---|---|---|
| `./scripts/verify.sh` | PASS | build + unit tests + spotless + release-script checks |
| Unit tests | PASS | 99 tests, 0 failures |
| CI on the merged commit | PASS | PR #26: 8/8 jobs green |
| Runtime smoke (Paper/Folia, CI and local) | PASS | device/cleanup/PAPI smokes — **but see §5 F2: they never assert that a powered output stays powered** |

## 3. Server preflight

| Fact | Value |
|---|---|
| Platform / build | Paper `26.3-258-main@9ee1fac`; Folia `26.3-26-ver/26.3.x@eca40f9` |
| Minecraft | 26.3 (API 26.3) → compatibility gate **PASS** |
| Java | Temurin `25.0.4+7-LTS` (both instances) |
| RealTime before test | not installed |
| PlaceholderAPI | 2.12.3 (both) |
| Environment | local test instances under MCSManager 4.x (Docker), owner-approved, no backup |
| Test area | `world`, 24 outputs at `x=39985..40008, y=79, z=-32` (second resolution), chunk-forceloaded |

## 4. Check matrix

| Check | Result | Evidence |
|---|---|---|
| Compatibility gate | PASS | Paper 26.3-258 / Folia 26.3-26 on Java 25, plugin loads on both |
| First boot and files | PASS | `config.yml`, `messages.yml`, `modules/{command,redstone}.yml`, `data/devices.yml`, `backups/` created on both; `timezone=Asia/Shanghai`, `locale=zh_CN`, `redstone.enabled=false` |
| Timezone and commands | PASS | `/rtime now` = host clock within the same second on both; `/rtime info` shows `0.1.0` / `Asia/Shanghai` / `zh-CN` |
| PlaceholderAPI | PASS | `%realtime_time%`, `%realtime_time_24h%`, `%realtime_timezone%`, `%realtime_bcd%` (`141030`), `%realtime_bcd_bits%` (24 bits, matching the BCD digits) on both |
| `/rtime reload` | PASS | success message; bad config (`timezone: Not/AZone`) rejected with `旧配置继续生效` and `/rtime info` still reported `Asia/Shanghai` |
| BCD output mapping (`second`) | **FAIL** | all 24 wires read power 0 while `%realtime_bcd%` reported `142725`; see §5 F2 |
| Device enable / disable | **FAIL** | `/rtime device enable|disable <uuid>` on a 24-output device returns `A minute-resolution redstone device must define exactly 16 outputs (HHmm)`; see §5 F1 |
| Disable and retire | PARTIAL | `/rtime device remove` correctly refuses an enabled device; after setting `enabled: false` in YAML + reload, retirement worked and returned a job UUID |
| Cleanup, `NEEDS_ATTENTION`, retry | PASS (Folia) | 24-output job: 23 × `CLEARED`, 1 × `NEEDS_ATTENTION` (stone target), queue showed `需人工处理：1`; after restoring the wire, `cleanup retry` drained the queue and the store returned to empty |
| Unloaded chunk deferral / chunk-load retry | PASS (CI smoke) | covered by `cleanup-smoke` on both platforms; not re-run manually this round |
| Restart recovery | PARTIAL | jobs survived plugin restart in the CI smoke; the manual run only restarted between installs (device store reloaded correctly) |
| Schema-v1 migration | PASS | v1 store with a 16-output device → rewritten as schema v3 with `resolution: minute`, device preserved, `backups/devices.yml.bak` kept the v1 original |
| Reload failure safety | PASS | see `/rtime reload` row |
| Plugin shutdown | PASS | `Disabling RealTime v0.1.0` / `RealTime disabled.` with no scheduler or exception noise |
| Performance / coexistence | PASS | TPS 20.0 / 19.8 / 19.8; no RealTime warnings or thread-ownership errors in either log |

## 5. Findings

### F1 — BLOCKER: `device enable/disable` drops the device resolution

`DeviceAdministrationService.setEnabled()` rebuilds `RedstoneDevice` without `resolution`, so a `second` device is validated as a 16-output `minute` device and the mutation fails:

```
/rtime device disable 66666666-7777-8888-9999-aaaaaaaaaaaa
→ 设备操作或刷新失败：A minute-resolution redstone device must define exactly 16 outputs (HHmm)
```

Reproduced on Paper and Folia. Fix: pass `resolution = currentDevice.resolution` and add a regression test (unit fixtures default to `minute`, which is why the suite missed it).

### F2 — BLOCKER: BCD output cannot hold or propagate redstone power

`RedstoneDeviceManager.applyOutput()` writes `block.setBlockData(wire, true)`. With physics applied, Minecraft immediately recomputes redstone dust power from its inputs, and an isolated wire has none, so the value reverts to 0 before it can be observed:

```
wire power probe on bit 0 (host time 14:27:24, %realtime_bcd% = 142725 → bit 0 must be 1): 0
manual /setblock 39985 79 -32 minecraft:redstone_wire[power=15]  → probe still reads 0
control: /setblock 39984 79 -32 minecraft:redstone_block → probe reads 15   (probe is valid)
```

An experiment build with `setBlockData(wire, false)` (no physics) shows the other half of the problem:

- the forced value now sticks (`bit 0` = power 15 persisted), but
- nothing downstream reacts until an unrelated block update happens: a redstone lamp placed next to the powered wire stayed dark, and only turned lit after a stone block was placed next to the lamp; a neighbouring dust stayed at 0.

So neither setting yields a usable clock: physics = value lost, no physics = value invisible to consumers. This is a design-level blocker, not a one-line fix. Options to evaluate:

1. drive a real power source (place/remove `redstone_block`, or toggle a lever/torch) instead of forcing dust power — changes the documented "output blocks must be `REDSTONE_WIRE`" contract;
2. keep forced dust power and accept "value only, no propagation", documenting that consumers must be re-evaluated by other means;
3. force dust power and explicitly notify neighbours, accepting that any dust neighbour will then recompute the source wire back to 0 (needs prototyping to confirm).

The current runtime smoke never checks that a powered output stays powered — it only checks that retired outputs are cleared to 0, which trivially passes when every wire is already 0. That gap is why CI stayed green.

### F3 — minor: the bundled default device store is schema v1

First boot writes `data/devices.yml` with `schema-version: 1` (from the bundled resource) and immediately migrates it to v3, leaving a `backups/devices.yml.bak`. Harmless but noisy; ship a v3 template.

### F4 — tooling notes (not product defects)

- MCSM 4.x: `docker restart MCSM-<uuid>` leaves the instance container removed (the daemon deletes it on exit) and the instance stays down. Use the daemon socket.io `instance/stop` → wait for removal → `instance/open`. Response events are echoed on the request event name with a `uuid` field and a `{uuid,status,event,data}` envelope.
- Folia's RCON drops output for region-owned commands (`setblock`, `fill`, `execute … run`), so block-state reads were only possible on Paper; Folia was verified through plugin commands, counters and the durable store. A visual/lamp check by an operator is still advisable after the fix.

## 6. Recommendation

- **BLOCKED** — do not tag `v0.1.0` or publish. Fix F1, decide and implement the F2 output mechanism, then extend the runtime smoke with an assertion that a powered output is actually powered, and re-run this checklist on Paper and Folia.

## 7. Re-verification after the fixes (PR13)

Artifact under test: `RealTime-0.1.0.jar` rebuilt from the PR13 branch,
SHA-256 `5a4113375ccfc3ce765c51594180ce135cab2554384f3e1198722ca7db8c64d8`
(`plugin.yml` reports `version: '0.1.0'`; the descriptor is now an input of `processResources`).

| Check | Result | Evidence |
|---|---|---|
| F1 regression: `/rtime device enable`/`disable` on a 24-output device | PASS | both platforms accepted the command and persisted `enabled` without a resolution error |
| F2 regression: output slots are real power sources | PASS (Paper) | slot types matched the HHmm digits of `%realtime_bcd%` exactly; a lamp placed above the seconds-LSB slot followed it every second (`LIT`/`dark` alternating) |
| F2 regression: Folia | PASS (counters) | 24 outputs, `功率变更` growing every second, `非红石线：0`; block probes are not answerable through Folia's RCON |
| Disable clears powered slots | PASS | after `device disable`, 24/24 slots were air on Paper, including previously powered ones |
| Retirement + cleanup | PASS | `device remove` returned a job UUID, the queue drained within 1 s, and the store returned to `devices: []`, `cleanup-jobs: []` on both platforms |
| Runtime smoke (CI script, local run) | PASS | Paper and Folia `scripts/smoke-device-cleanup.sh` green, including the new live-device guard (slots must be redstone blocks while enabled, air after disable) |

Known behaviour after the fix: a receiver built while a slot is already powered picks up
the state on that slot's next change (`≤1 s` for `second` devices; minute/hour digits on
their boundary), because dust/lamps only re-evaluate on a block update.

Remaining work before release: re-run the full §4 matrix once more on the final artifact and
let an operator confirm a downstream circuit visually (the block-level assertions above are
per-block; the lamp check covered one bit).
