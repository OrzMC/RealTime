# Changelog

All notable changes to RealTime are documented here. Release automation requires a non-empty section matching the exact version tag.

## [Unreleased]

### 工程

- 迭代流程标准化：[`docs/development/workflow.md`](docs/development/workflow.md)（选任务 → 任务契约 → 分支命名 →
  本地门禁 → PR 四段式 → CI → squash 合并 → 父仓库 bump → 发版 → DoD / 证据状态）与
  [`tasks/TEMPLATE.md`](tasks/TEMPLATE.md) 任务契约模板；`AGENTS.md`、`tasks/README.md`、README 索引同步指向。
- Hangar / Modrinth 发布准备：`scripts/distribution-check.sh`（离线校验 `plugin.yml`、工作流接线、Modrinth payload；
  `--remote` 追加公共 API 校验，已并入 `verify.sh`）、[`docs/release/distribution.md`](docs/release/distribution.md)
  （项目创建、令牌最小权限、演练、排错、安全约束）。
- Release 工作流支持 `workflow_dispatch`（`ref` / `version` / `dry_run`）：可完整跑版本解析、构建、jar 检查与
  分布预检而不创建 Release、不上传；演练的 `publish` job 走独立的 `release-dry-run` 环境（无审批、无密钥），
  因此 `release` 环境仍然只允许 `v*` tag；Modrinth payload 的 loaders 补上 `folia`。

## [0.4.0]

### 新增

- `/rtime device cleanup dismiss <job-uuid>`（`realtime.device.retire`）：丢弃一个只剩 `需人工处理` 的清理任务，
  相关方块保持原样。用于"这些方块本来就不该清"的场景（典型：`device demo` 建在草地上，支撑位是原始地形），
  以前只能手改 `data/devices.yml`。仍有 `待处理` 项时会拒绝。
- `device demo` 现在会单独提示"建示例前请确认该区域是空地"（独立消息键 `command.device.demo.hint`，老服的
  `messages.yml` 缺这个键时会自动使用内置文案），减少地形占位导致的非法方块。
- 新增面向使用者的 [usage guide](docs/usage.md)：安装、命令与权限、两种编码的接线、教学示例、清理队列、
  诊断、占位符与 FAQ；README、需求文档与实现说明改为引用它，去掉重复的命令表。

### 安装与升级须知（0.4.0）

- **无强制操作**：直接替换 jar 重启即可；`data/devices.yml` 仍是 schema **v5**，配置与数据结构均无变化。
- **新增可选命令** `/rtime device cleanup dismiss <job-uuid>`（`realtime.device.retire`）：当清理任务只剩
  `需人工处理`、而那些方块本来就不该由插件清理（地形、玩家建筑）时，用它确认并丢弃任务。以前只能手改
  `data/devices.yml`（文档禁止）。
- **会看到一条新提示**：`device demo` 之后多输出一行"建示例前请确认该区域是空地"。这是独立消息键
  `command.device.demo.hint`，不想保留就把它从 `messages.yml` 删掉（缺失时会退回内置文案，如需彻底隐藏请设成空字符串）。
- **命令与用法以 [usage guide](docs/usage.md) 为准**：README 与需求文档里的重复命令表已移除，历史文档
  （`docs/operations/*0.1.0*`）标注为历史快照。

## [0.3.0]

### 修复

- `device cleanup list` 现在把 demo 展示方块也计入 `待处理`/`需人工处理`，`device cleanup retry` 也会把
  `NEEDS_ATTENTION` 的展示方块重新排队 —— 之前只看槽位，导致 demo 设备退役后队列显示"需人工处理：1"，但实际还有
  多个展示方块卡着，且 retry 无法让任务完成。
- 周期扫描里 demo 展示方块的下标改为按存储列表计算（之前用了过滤后的下标），修掉"队列卡在 `待处理：1` 且永远不动"
  的问题 —— 只影响退役中的 demo 设备，普通 BCD/analog 设备不涉及。

### 安装与升级须知（0.3.0）

- **谁受影响**：只有**在 0.2.0 上用 `device demo` 建过教学示例**的服务器。普通 `device create` 建的 BCD/analog 设备
  的清理任务没有 `display:` 列表，不受这两个缺陷影响。
- **症状**（升级前）：退役 demo 设备后 `device cleanup list` 的 `需人工处理` 数字偏小（少算了灯/比较器/红石线），
  对任务执行 `cleanup retry` 也无法让它消失；或者数字正确但队列卡在 `待处理：1` 一直不动。
- **升级后怎么办**：把 0.3.0 的 jar 换上并重启即可 —— 卡住的清理任务会在区块加载时按正确路径完成；若仍有
  `需人工处理`，说明对应位置被非插件方块占着（例如 demo 建在树林里，树干/树叶占位），清掉那几格后再执行一次
  `/rtime device cleanup retry <job-uuid>` 即可。
- 提醒：`device demo` 只填空位，建示例前先把场地清空，否则槽位/灯位会被地形占住并报 `NEEDS_ATTENTION`。
- 本版**没有**配置或数据结构变更：`data/devices.yml` 仍是 schema **v5**，0.2.0 的存储可以直接用。

## [0.2.0]

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

### 安装与升级须知（0.2.0）

- **环境要求不变**：Java 25 + Paper 或 Folia **26.3** 及以上（`api-version: 26.3`）。
- **直接从 0.1.0 覆盖升级**：把 `RealTime-0.2.0.jar` 放进 `plugins/` 替换旧 jar 后重启。
  `data/devices.yml` 会从 schema v3 自动迁移到 **v5**，迁移前自动备份到 `backups/devices.yml.bak`。
- **已有设备坐标不会移动**：新的默认布局（间隔 + 分组）只影响之后用 `device create` 新建的设备。
- **行为变化（请注意）**：
  - `device set`（移动槽位）现在要求设备处于**禁用**状态：`device disable` → `device set` → `device enable`。
    之前允许对启用中设备改槽位，会在世界里留下一个永久通电的红石块/讲台。
  - `analog` 设备 `disable` 现在会**清空槽位**（先移走书再拆讲台），不再留下空讲台；`enable` 会重新放回。
  - 新建 BCD 设备默认 **每格间隔 1 空气、每 4 位一组**（`minute` 占 34 格、`second` 占 52 格）。墙不够宽可以显式传
    间距（`... x+ 1`），或用 `device set` 微调。
- **权限**：新增 `realtime.device.view`（默认所有人）/ `toggle` / `edit` / `retire`（默认 op），
  `realtime.admin` 仍是父节点。**只给过 OP 的服无需改动**；若曾把 `realtime.admin` 发给非 OP，建议换成更细的节点。
- **新命令**：`device create ... [间距] [bcd|analog]`、`device set`、`device demo <bcd|analog> ...`。
  `device demo` 一条命令生成完整可看的时钟（含灯/比较器/红石线），适合教学与自测，`device remove` 可整体清除。
- **已知限制**：analog 的讲台是方块实体，比较器读数需要该区块处于 entity-ticking（有人在附近）；无人时到达现场会补齐。
  玩家可以打开讲台翻页，插件每 30 秒的全量复查会把页码写回。
- Hangar / Modrinth 仍未接入（未配置 `HANGAR_PROJECT_ID` / `MODRINTH_PROJECT_ID`），本次仍只发 GitHub Release。

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
