# Changelog

All notable changes to RealTime are documented here. Release automation requires a non-empty section matching the exact version tag.

## [Unreleased]

- Add an optional `analog` output encoding: each digit becomes one lectern holding a plugin-created 15-page book,
  so a comparator reads level `digit + 1` and `HHmmss` needs 6 lines instead of 24. `device create ... [stride] [bcd|analog]`,
  schema v4 (`encoding` on devices and cleanup jobs, earlier stores migrate to `bcd`), lectern/book counters in
  `device status`, and cleanup that only removes a lectern still holding this device's book.
- Add in-game device authoring: `/rtime device create <name> <minute|second> <world> <x> <y> <z> <axis> [stride]`
  and `/rtime device set <uuid> <bit> <x> <y> <z>`, with area whitelist, device cap, per-player cooldown and audit logging.
- Split permissions: `realtime.device.view` (default true), `realtime.device.toggle` / `realtime.device.edit` /
  `realtime.device.retire` (default op); `realtime.admin` still grants everything including `reload`.

## [0.1.0]

- Establish the independent Kotlin/JDK 25 Gradle project and Agent Native development workflow.
- Add the Clock-backed timezone-aware core service and deterministic time tests.
- Add the `/rtime` command, modular configuration, validation, and safe reload behavior.
- Add Paper/Folia scheduler adapters with explicit global, region, and entity task ownership and plugin-disable cancellation.
- Add the HHmm/HHmmss BCD encoder, validated redstone device model with `minute` (16 outputs) and `second` (24 outputs) resolution, schema-versioned YAML persistence, and region-safe redstone output.
- Add optional PlaceholderAPI integration with timezone-aware date/time, epoch, BCD, and device placeholders.
- Add device list/info/enable/disable/status commands and aggregate output diagnostics.
- Add schema-v2 deferred cleanup jobs and crash-safe retirement persistence; active-device removal and all cleanup coordinates are atomically persisted.
- Add Paper/Folia region-owned cleanup with startup/world/chunk-load retry, no forced chunk loading, redstone-block removal, and durable `NEEDS_ATTENTION` states for slots that hold anything else.
- Add cleanup queue inspection and admin retry commands, plus admin-only disabled-device retirement through `/rtime device remove <uuid>`.
- Add second-resolution devices: 24 BCD outputs (HHmmss) refreshed every second, schema v3 with `resolution: minute|second`, and automatic migration of earlier device stores.
- Output slots are now real power sources (`REDSTONE_BLOCK` / air) instead of forced `REDSTONE_WIRE` power, so connected redstone actually switches; the pre-release `output-power` key is ignored and `device enable|disable` keeps the device resolution.
- Detect frame changes every tick (≤50 ms) instead of on a 1 Hz task, dispatch only the slots whose state changed, group them per chunk (two region tasks instead of twenty-four per device) and re-check every slot every 30 s, so hundreds of devices stay cheap.
- Verify restart recovery, unloaded-chunk deferral, chunk-load retry, non-wire preservation, operator retry, and full retirement on both Paper and Folia.
- Add protected tag-based release automation, reproducible shaded JAR packaging, and SHA-256 checksums.

### 安装与升级须知（0.1.0 首个公开版本）

- **环境要求**：Java 25 + Paper 或 Folia **26.3** 及以上（插件声明 `api-version: 26.3`，低于此版本请勿安装）。
- **首次安装**：把 `RealTime-0.1.0.jar` 放进 `plugins/` 后启动，会自动生成 `plugins/RealTime/`
  （`config.yml`、`messages.yml`、`modules/command.yml`、`modules/redstone.yml`、`data/devices.yml`）。
- **红石模块默认关闭**（`modules/redstone.yml: enabled: false`）：确认测试区无误后再改成 `true`。
- **输出槽位必须是空位或红石方块**：设备里配置的每个坐标由插件放/撤 `redstone_block` 表示 1/0；
  槽位里若有其他方块，插件只告警、**不会覆盖**，需要先清空。
- **从开发快照升级**：`data/devices.yml` 会自动迁移到 schema 3（原文件备份到 `backups/devices.yml.bak`）；
  旧的 `output-power` 键会被忽略（输出固定满强度 15）。
- 设备/清理数据结构请勿手工编辑，用 `/rtime device ...` 命令操作。
