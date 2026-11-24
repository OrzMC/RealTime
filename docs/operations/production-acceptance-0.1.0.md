# RealTime 0.1.0 — production acceptance handoff

> Audience: the operator and the AI agent working on the actual OrzMC Minecraft server.
> Purpose: execute a controlled real-server acceptance pass, record evidence, and return findings to the RealTime repository.
> Status: **prepared for real-server testing; not yet production-accepted or released**.

## 1. Current release and test baseline

- Repository: [OrzMC/RealTime](https://github.com/OrzMC/RealTime)
- Release-preparation PR #24 is merged. Current main commit at handoff preparation: `3c36b269656f4f54d309d04641f5ede82695c920`.
- PR1–PR11 are merged. Automated Paper/Folia smoke tests cover normal operation, schema migration, restart recovery, unloaded-chunk deferral, chunk-load retry, non-wire protection, operator retry, and disabled-device retirement.
- The project currently compiles against **Paper API 26.3** and declares `api-version: '26.3'`; Java **25** is the expected runtime.
- The OrzMC server was previously recorded as Paper 26.2 / Java 25. **Re-check the live server version before doing anything.** Do not assume the current production server is already 26.3.

### Mandatory compatibility stop gate

If the actual server is Paper 26.2 (or otherwise below the plugin's declared 26.3 API baseline), **do not install this JAR on the production server**. First choose one of:

1. Use a disposable staging copy upgraded to Paper 26.3 and run acceptance there; or
2. Open a separate compatibility task to lower/validate the supported API baseline, then rebuild and rerun CI/runtime tests.

Do not bypass the plugin API-version gate or claim 26.2 compatibility based only on successful compilation.

## 2. Safety rules

1. Prefer a staging copy of the actual server (same Paper build, Java, plugins, configs, and representative world data). If testing on production is unavoidable, schedule a maintenance window and announce it.
2. Before installation, back up the complete server directory or at minimum:
   - all world folders;
   - `plugins/RealTime/` if it already exists;
   - `plugins/` and server configuration;
   - the exact plugin JAR currently deployed.
3. Keep the redstone module disabled for the first boot. Its default is `plugins/RealTime/modules/redstone.yml: enabled: false`.
4. Use a disposable test world / isolated test area for all 16 redstone outputs and all retirement/cleanup tests. Do not point test devices at existing player builds, clocks, farms, doors, or other circuitry.
5. Never manually delete `data/devices.yml` cleanup jobs or edit away pending coordinates. The queue is durable state.
6. Never force-load chunks to make cleanup pass. Unloaded outputs must remain pending until the chunk naturally loads.
7. Never let an AI agent run destructive commands, replace blocks, or remove a device unless the operator has confirmed the target UUID, coordinates, backup, and test area.
8. Keep secrets, RCON passwords, tokens, player data, and private IPs out of committed logs and reports.

## 3. Preflight — collect facts before installation

Record the following in the acceptance report:

- Date/time and timezone.
- Server software and exact build (console `version` / `/version`).
- Java runtime (`java -version` from the server runtime, not merely the shell default).
- Current Minecraft version and whether the target is Paper or Folia.
- Current RealTime JAR/version, if any; installed plugin list; PlaceholderAPI presence/version.
- Whether the target is production or a disposable staging clone.
- Current backup location and restore procedure.
- Whether the server is using MCSManager/Docker or a direct process; use the normal managed restart path.

**Stop** if the platform is below 26.3, Java is not 25, backup/restore is unverified, or the operator cannot identify an isolated test area.

## 4. Install and first-boot checks

1. Check out the exact merged RealTime main commit in a clean worktree with JDK 25. The ordinary CI workflow does **not** upload a distributable JAR artifact; do not assume one exists.
2. Run the full verification and build a versioned shaded JAR:
   ```bash
   ./scripts/verify.sh
   PROJECT_VERSION=0.1.0 ./scripts/package.sh
   sha256sum realtime-paper/build/libs/RealTime-0.1.0.jar \\
     > realtime-paper/build/libs/RealTime-0.1.0.jar.sha256
   sha256sum --check realtime-paper/build/libs/RealTime-0.1.0.jar.sha256
   ```
   Preserve the exact commit SHA and generated checksum in the report. Do not build from an uncommitted tree or use an artifact from a different commit.
3. Stop the staging server cleanly, place the JAR in `plugins/`, and start it through the normal server manager.
4. Confirm the console reports RealTime enabled and no linkage, scheduler, configuration, or persistence errors.
5. Confirm the plugin creates its independent files under `plugins/RealTime/`:
   - `config.yml` — global enablement, timezone, locale, logging;
   - `modules/command.yml` — command feature and format;
   - `modules/redstone.yml` — redstone module, disabled by default;
   - `data/devices.yml` — versioned device store.
6. Confirm initial values are sensible:
   - `time.timezone: Asia/Shanghai`;
   - `time.locale: zh_CN`;
   - `modules/redstone.yml` has `enabled: false`.
7. Run the read-only commands as an operator:
   ```
   /rtime now
   /rtime info
   /rtime device list
   /rtime device status
   ```
   Confirm displayed time/zone/version and that an empty device store is handled cleanly.
8. Run `/rtime reload` once and confirm a successful reload. Do not edit files during the reload.
9. Check that no existing plugin command or permission behavior was changed. RealTime uses `/rtime`, `realtime.command` (default true), and `realtime.admin` (default op).

## 5. Timezone and command checks

- Confirm the configured timezone is explicit and is not inherited from the host JVM.
- Compare `/rtime now` against a trusted Asia/Shanghai clock at the same moment.
- Confirm the configured output format and offset are correct.
- Restart once and repeat the comparison.
- If PlaceholderAPI is installed, verify the RealTime expansion registers and returns values; if it is absent, confirm the plugin still starts normally.
- Do not claim DST coverage from a single live check; DST and formatting edge cases are already covered by deterministic automated tests.

Record console excerpts with timestamps, but redact server addresses and player identities where unnecessary.

## 6. Redstone device acceptance — isolated test area only

The redstone module is disabled by default. Enable it only after the first-boot and time checks pass, and only in the disposable test area.

### 6.1 Prepare a test device

- Choose the device resolution: `minute` (HHmm, 16 outputs) or `second` (HHmmss, 24 outputs). Create exactly that many isolated `minecraft:redstone_wire` output positions. Keep them separated from unrelated redstone networks so adjacent wires cannot couple.
- Record the world name and all 16 coordinates.
- Configure one device in `plugins/RealTime/data/devices.yml` using schema version 3 and an explicit `resolution`. Each output must have one unique `bit-index` from 0 through 15 (`minute`) or 0 through 23 (`second`); coordinates must be unique.
- Back up `devices.yml` before editing, then use `/rtime reload`.
- Confirm `/rtime device list` and `/rtime device info <uuid>` show the intended device and exact bit-to-coordinate mapping.
- Enable the device only after verifying every coordinate belongs to the disposable test area:
  `/rtime device enable <uuid>`.

### 6.2 Verify output mapping

The output is BCD in hour → minute → second order, with least-significant bit first inside each digit. `minute` devices expose the first four digits; `second` devices expose all six:

| Bit indexes | Digit |
|---|---|
| 0–3 | Hour tens |
| 4–7 | Hour ones |
| 8–11 | Minute tens |
| 12–15 | Minute ones |
| 16–19 | Second tens (`second` only) |
| 20–23 | Second ones (`second` only) |

For each digit, bit value 1 is emitted at configured `output-power` (1–15); bit value 0 is power 0. Observe the actual server-local time and independently calculate its BCD digits. Verify every wire strength. For a `minute` device, observe at least one minute boundary and confirm only the expected bits change; for a `second` device, confirm the second digits advance once per second and that only the changed bits are rewritten (adjacent bits must not flicker). Do not infer correctness from the plugin status counters alone.

Check `/rtime device status` before and after. Record output checks, unloaded-chunk skips, invalid outputs, power changes, unchanged outputs, and write failures. Investigate any invalid output or write failure before proceeding.

## 7. Device disable and safe retirement

Use only the disposable test device.

1. Capture the full device UUID and all 16 coordinates from `/rtime device info <uuid>`.
2. Run `/rtime device disable <uuid>`; verify the device is disabled and its loaded output wires are driven to power 0.
3. Confirm a non-admin cannot execute `/rtime device remove <uuid>`; an admin can.
4. As admin, run `/rtime device remove <uuid>` only after confirming the UUID and test coordinates. The command is disabled-device-only.
5. Record the returned cleanup job UUID. Confirm the active device disappears from `/rtime device list` and the job appears in `/rtime device cleanup list`.
6. Confirm the cleanup job is durable in `data/devices.yml` with the same `resolution` and every output coordinate (16 for `minute`, 24 for `second`).
7. Confirm cleanup only clears `REDSTONE_WIRE` outputs to power 0. It must not overwrite a different block type.
8. Wait for the job to finish; record the final queue state. Do not manually remove the job from YAML.

## 8. Deferred cleanup edge cases

Run these only in a disposable staging world. Each case must have its own test device/job.

### A. Unloaded chunk

- Arrange for one or more output chunks to be unloaded without force-loading them.
- Disable and retire the test device.
- Confirm those outputs remain pending and the plugin does not load the chunks.
- Load the chunk through normal player/server activity.
- Confirm the chunk-load path retries the pending outputs and the queue eventually resolves.

### B. Non-wire target / NEEDS_ATTENTION

- In a disposable test device, replace exactly one configured output with a non-wire block before retirement.
- Disable and retire the device.
- Confirm the processor does not overwrite the non-wire block and marks that output `NEEDS_ATTENTION`.
- Record the job UUID and output bit/coordinates.
- Correct the test block manually to the expected safe target (`redstone_wire`), then run:
  `/rtime device cleanup retry <job-uuid>`.
- Confirm the output is processed and the job is removed only after all outputs are resolved.

### C. Restart recovery

- Create a job with at least one pending output (e.g. an unloaded test chunk).
- Stop the server cleanly while the job remains pending.
- Restart and confirm the job is restored from `data/devices.yml`.
- Load the relevant chunk naturally; confirm processing resumes without duplicate jobs or lost coordinates.

## 9. Persistence, migration, and shutdown

- Test schema-v1 migration on a **copy** of a legacy `devices.yml`; never downgrade or replace the live file to manufacture this test.
- Confirm migration produces schema v2, preserves existing devices, initializes cleanup jobs, and creates `backups/devices.yml.bak`.
- Restart with both active devices and a pending cleanup job; confirm both are restored.
- Run `/rtime reload` with valid config; confirm state remains consistent.
- In staging, test invalid config and confirm failed reload preserves the previously active runtime state.
- Stop the server cleanly; confirm no scheduler/task exceptions occur during plugin disable.

## 10. Acceptance report — required output

The AI agent must create a report (Markdown). The prepared skeleton lives in [acceptance-report-0.1.0.md](acceptance-report-0.1.0.md); fill it with observed evidence and do not overwrite the preflight section. It must contain:

- Server platform/build, Minecraft version, Java version, RealTime artifact version and SHA-256.
- Production vs staging designation and backup/restore evidence.
- Exact commands executed and operator permissions used.
- Test device UUID, world name, coordinates, and cleanup job UUIDs (sanitize world/player details before sharing publicly).
- Pass/fail/not-run for every section above.
- Relevant console logs and screenshots, with secrets and personal data redacted.
- Any observed mismatch between expected and actual behavior; do not silently “fix” the evidence.
- Whether a failure is reproducible, steps to reproduce, and whether it risks player builds or existing redstone.
- Explicit recommendation: safe to continue staging / safe for production rollout / blocked.

A test is **not passed** merely because a command returned success. Record the resulting file state, block state, scheduler behavior, and restart behavior where applicable.

## 11. Stop conditions and escalation

Stop the test and preserve logs/backups if any of these occurs:

- Platform/API baseline mismatch or plugin load/linkage error.
- Unexpected world/chunk load or any write outside the disposable test area.
- A non-wire block is overwritten.
- A cleanup job disappears while outputs remain unresolved.
- A pending job or device record is lost across restart.
- A reload partially applies invalid configuration.
- Paper/Folia scheduler ownership or thread violation appears.
- Server TPS/MSPT degrades materially or other plugins begin failing.

Do not create `v0.1.0`, GitHub Release, Hangar release, or Modrinth version as part of acceptance. Return the report and logs first; release approval is a separate human decision.

## 12. Handoff instructions for the next AI agent

1. Start from a clean checkout of `OrzMC/RealTime` main and read `AGENTS.md`, this document, `docs/development/redstone-devices.md`, and `docs/release/process.md`.
2. Inspect the actual server version and Java runtime before preparing the JAR. Resolve the 26.2-vs-26.3 compatibility gate explicitly.
3. Do not assume access to this chat, prior local worktrees, credentials, or server paths. Ask the operator for the server manager/workdir only if it is not available in the local environment.
4. Follow the server's established MCSManager/Docker restart workflow; do not improvise container restarts.
5. Execute the checklist in order, keep production writes disabled until staging checks pass, and maintain a timestamped evidence log.
6. Commit the acceptance report and any reproducible issue/task to the RealTime repository through a dedicated branch and PR. Never commit raw credentials, RCON passwords, private IPs, or unredacted player data.
7. Do not publish or tag. Report findings and wait for explicit human release approval.

## 13. Result template

```markdown
# RealTime 0.1.0 acceptance report

- Date:
- Server / build:
- Platform: Paper / Folia
- Java:
- Environment: staging / production
- Artifact SHA-256:
- Backup and restore verified: yes / no

| Check | Result (PASS / FAIL / NOT RUN) | Evidence / notes |
|---|---|---|
| Compatibility gate | | |
| First boot and files | | |
| Timezone and commands | | |
| PlaceholderAPI | | |
| BCD output mapping | | |
| Disable and retire | | |
| Unloaded chunk deferral | | |
| Non-wire NEEDS_ATTENTION | | |
| Cleanup retry | | |
| Restart recovery | | |
| Schema-v1 migration | | |
| Reload failure safety | | |
| Plugin shutdown | | |
| Performance / coexistence | | |

## Findings
- 

## Recommendation
- BLOCKED / STAGING ACCEPTED / PRODUCTION ACCEPTED
```
