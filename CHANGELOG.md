# Changelog

All notable changes to RealTime are documented here. Release automation requires a non-empty section matching the exact version tag.

## [Unreleased]

### 新增

- **analog（讲台）编码**：一个数字 = 一个槽位。槽位是插件放置的讲台，里面放着插件生成的 15 页书，页码 = 数字；Paper 的
  `floor(page / (pageCount - 1) * 14) + 1` 在 15 页时化简为 `page + 1`，所以比较器读到的是**强度 1–10**，`HHmmss` 从
  24 条线降到 **6 条**。关闭设备会把槽位清空（先清书再拆讲台，避免掉落），且只操作"书仍属于该设备"的讲台，别人的书/方块只
  报 `NEEDS_ATTENTION`。`device create ... [stride] [bcd|analog]`，设备与清理任务持久化 `encoding`（schema v4，旧数据按
  `bcd` 迁移），`device status` 增加讲台放置/书页安装计数。
- **游戏内建设备**：`/rtime device create <名称> <minute|second> <世界> <x> <y> <z> <轴> [间距] [bcd|analog]`、
  `/rtime device set <uuid> <槽位> <x> <y> <z>`、`/rtime device demo <bcd|analog> <世界> <x> <y> <z> <轴>`，配合
  `limits`（`allowed-areas` / `max-devices` / `edit-cooldown-seconds`）与审计日志。
- **教学示例 `device demo`**：一条命令就地生成完整可读时钟并**立即启用**（analog：每位讲台 + 比较器/红石线/灯一行；BCD：
  每个槽位正上方一盏灯），让新人不用先学接线就能看懂两种编码。展示方块属于该设备（schema **v5**，设备 `demo:` / 清理任务
  `display:`），`disable` 拆、`enable` 重搭、`remove` 连展示一起清，只拆类型仍匹配的方块。
- **权限拆分**：`realtime.device.view`（默认 true）、`realtime.device.toggle` / `realtime.device.edit` /
  `realtime.device.retire`（默认 op）；`realtime.admin` 仍是父节点，包含以上全部 + `reload`。
- **默认布局间隔 + 分组**：新建设备 `stride` 默认 2（相邻槽位之间 1 格空气，避免信号互相干扰），BCD 每 4 位一组、数字之间
  再多 1 格空隙，方便目视区分。`minute` 设备占 34 格、`second` 占 52 格；**已存设备坐标不变**，显式 `[stride]` 仍可覆盖
  组内间距。

### 修复

- `device set` 现在要求设备处于**禁用**状态（补全也只推荐已禁用设备）：之前允许对启用中设备改槽位，而设备只写"当前槽位表"，
  旧槽位会留下一个永久通电的红石块（或一个讲台）。
- analog 设备 `disable` 现在把槽位整体清空（而不是只移走书留下空讲台），与 BCD "归 air" 一致，也让"改槽位不留残留"成立。
- 退役 analog 设备时，`disable` 留下的空白讲台现在也会被清理（先清空库存再拆方块，书不掉落），不再需要人工手拆 6 个讲台。

### 工程

- `./scripts/verify.sh` 增加 CRLF 门禁，并新增 `.gitattributes`（`* text=auto eol=lf`，仅 Windows 启动器保留 CRLF），治掉
  Windows 上"整文件显示已修改"的假 diff。
- Paper/Folia runtime smoke 加固：块探针改用 `run list` 并逐位置重试，关键判定改为**按持久化清理队列**（Folia 对 region
  命令的 RCON 应答会丢包/报未加载，之前因此偶发失败）。

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
