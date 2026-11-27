# Changelog

All notable changes to RealTime are documented here. Release automation requires a non-empty section matching the exact version tag.

## [Unreleased]

### 新增

- **自动更新（检查 + 下载到更新目录）**：`/rtime update` 向 Hangar 查询新版本（失败时回退 GitHub Releases），
  `/rtime update download` 把校验过的 jar 放进服务器的 `plugins/update/`，**重启后**由 Paper 完成替换。
  不热替换插件、不重启服务器。
  - 新增 `modules/updates.yml`：`mode`（notify/download）、`channel`（release/any）、检查间隔、通知开关、
    白名单与体积上限；开发构建（`-SNAPSHOT`）默认不检查。
  - 新增权限 `realtime.update`（默认 OP，`realtime.admin` 的子权限）与 `/rtime status` 的更新状态行。
  - 安全：只走 HTTPS（loopback 除外，且需显式开启）、主机白名单在**每次重定向**都校验、JSON 1 MiB / jar 体积上限、
    必须带平台校验和（sha256/sha512），不符即删除下载且不留残file。
  - 线程模型：全部网络/磁盘工作在 Paper `AsyncScheduler`，回复经 `CommandTarget.runOnOwner` 回到玩家所在 region
    （Folia 安全）；检查单飞，不会叠加请求。
- 新增 `scripts/smoke-update.sh` + `scripts/update-fixture-server.py`：在真 Paper/Folia 上验证"下载 → 重启替换 →
  篡改校验和被拒"，已接进 `runtime-smoke` 的 paper/folia matrix。

### 文档

- 新增 [`docs/development/updates.md`](docs/development/updates.md)：Paper 的 `plugins/update` 到底怎么工作
  （按插件名匹配、开服时替换、坏文件被跳过）、三个平台各给哪种校验和、安全边界、为什么不做热替换/自动重启。
- `docs/usage.md` 增补自动更新一节、命令表与诊断字段；README、架构总览、需求、测试策略、平台文案同步。
- 新增 [`docs/operations/acceptance-report-0.8.0.md`](docs/operations/acceptance-report-0.8.0.md)：真机（真实 Hangar +
  真实 CDN）把 `0.6.9` 通过插件自己的更新流程升到已发布的 `0.7.0`，并核对下载文件 sha256 与发布资产一致。

## [0.7.0]

> 本版**没有**游戏内行为变化：只动文档与发布自动化。替换 jar 即可，数据仍是 schema v7，无需迁移。

### 工程

- **发布时自动同步平台项目页**：新增 `scripts/modrinth-page.sh`（`PATCH /v2/project`，写 `description` + `body`），
  与已有的 `scripts/hangar-page.sh` 一起接进 `release.yml`，发版后两端项目页不再需要手工粘贴；
  `scripts/distribution-check.sh` 断言这两个步骤都在，删掉就 CI 红。
  一句话简介/长描述的唯一源仍是 `docs/operations/platform-listings.md`。
  注意 Modrinth 的一句话简介字段叫 `description`，传 `summary` 会被**静默忽略**（仍返回 204）。
- **发布 job 可重跑**：`Create GitHub Release` 遇到已存在的 Release 时改为刷新资产（`--clobber`）与说明（`gh release edit`）；
  Hangar 上传失败自动重试一次。`v0.6.0` 就是卡在 Maven Central 403 上、只能手工补传的。
- **平台审计补齐**：`scripts/platform-audit.sh` 增加 Hangar **关键词**核对（分类、主页长度、每版本频道 / 游戏版本 / sha256
  原本就有），现在两端的项目信息都能一条命令审计。

### 文档

- `docs/` 与 0.6.0 对齐：清掉残留的 `/rtime info`、`/rtime list`、BCD/analog 双编码描述与 "per-encoding slot counts"。
- 旧验收报告（0.1.0、analog-2026-11-25）顶部加「历史文档（已过时）」横幅，指向当前报告。
- README 文档索引补上 [0.6.0 验收报告](docs/operations/acceptance-report-0.6.0.md) 与
  [平台文案源文件](docs/operations/platform-listings.md)。
- [`docs/release/distribution.md`](docs/release/distribution.md) 新增「项目页自动同步」一节：端点、权限、两个字段名坑。

### 安装与升级须知（0.7.0）

- **游戏内行为与 0.6.0 完全一致**：命令、配置、数据格式、占位符都没有变化，直接替换 jar 重启即可。
- 如果你是从 **0.5.0 或更早**升级，请先读 [0.6.0 的升级须知](#060)：BCD 钟不会再加载、命令面已重写。
- 本版新增的是**发布侧**能力：维护者发版后，Hangar 主页与 Modrinth 简介/长描述会自动从仓库文案同步，无需手工粘贴。

## [0.6.0]

> **这是一个破坏性版本**：BCD 编码被移除，命令面重写。升级前请读下面的「安装与升级须知」。

### 新增

- **命令面重写**：读两条 `/rtime`（时间 + 所有钟）与 `/rtime <钟名>`（一台钟的详情）；
  写 `create` / `on` / `off` / `place` / `remove`；运维 `status` / `cleanup` / `help` / `reload`。
  选项改成关键字（`minute`、`manual`、`nodisplay`、`at <x y z>`、`facing <朝向>`），顺序随意，写错会点名是哪个词。
- **钟名唯一、且不能与动词重名**：`/rtime <钟名>` 因此没有歧义；设备 UUID 不再需要手输（只出现在日志与 `status`）。
- **Tab 补全按语法重写**：一级为动词 + 钟名，`create` 补选项关键字，`place` 补钟名后再补槽位号。
- **`place` 立刻立讲台**：摆一个槽位就把讲台放到目标位置、正面朝向你、写上当前数字，
  同时清掉这个槽位原来那格讲台（仅限本钟的空讲台/带本书的讲台）。逐位摆放从"改个坐标"变成"看得见的施工"。
- `/rtime status` 增加「旧版 BCD 钟已忽略」计数，并显示模块开关、版本、时区。

### 变更（破坏性）

- **移除 BCD 编码，只保留讲台**（一个数字一个讲台，比较器读数 = 数字 + 1）：
  真机验收发现四位并联需要译码器，其延时不可控、显示对不上时间，故整条路径删除
  （`OutputEncoding`、BCD 位帧/编码器、`bcd`/`binary` 参数、按 4 位分组的布局参数、`%realtime_bcd%` 与 `%realtime_bcd_bits%` 占位符）。
- **默认精度改为 `second`**：`/rtime create <名字>` 建出来就是 6 个讲台、按秒跳；要 `HHmm` 才写 `minute`。
- **关机语义**：不再清空槽位，而是取走书（比较器读数归 0）并**保留讲台** —— 位置可见、可继续调整；`remove` 才真正拆。
- **持久化 schema v7**：设备记录不再写 `encoding`。v1–v6 文件里 `encoding: bcd`（或没有该字段，历史上等于 BCD）的钟
  会被**跳过**、写入一条 WARN 日志、并在 `/rtime status` 里列出，不会按讲台几何误加载。
- `demo` 概念并入 `create`；`device <子命令>` 兼容层删除（旧动词只回一行"已移除，改用 …"）。
- 退役（`remove`）仍然要求先 `off`：避免拆到一半还在写方块。

### 修复

- `place` 在设备开机的状态下仍然拒绝执行并给出提示，而不是静默改坐标后继续写旧槽位。
- 重名钟（只可能来自旧数据）会明确提示"有 N 台钟都叫这个名字"，而不是含糊的"未找到"。

### 工程

- 修 Modrinth 上传：payload 必须带 `dependencies`（空数组也要），`v0.5.0` 因此被拒（`400 missing field dependencies`）；
  已在工作流中补上，并由 `scripts/distribution-check.sh` 断言防回归。
- 命令解析抽成 `CommandSyntax`（纯 Kotlin、无 Bukkit 依赖）与 `CommandCompletion`，handler 变成薄分发器；
  单测从 166 增加到 118 项（删除 BCD 用例、新增解析/补全/名字规则/即时立讲台/关机保留讲台/schema v7 与旧数据跳过）。
- 两个 smoke 脚本改成讲台 + 扁平动词 + 名字寻址，CI 覆盖的就是文档里教的命令。

### 安装与升级须知（0.6.0）

**替换 jar 重启即可**，数据文件会自动迁移到 schema v7 —— 但本版有以下**必须知道**的变化：

1. **BCD 钟不再可用**：旧数据里 `encoding: bcd`（或老到没有 `encoding` 字段）的钟会**跳过加载**，
   启动日志有一条 WARN，`/rtime status` 会列出它们。这些钟的方块布局无法当作讲台钟复用，
   请用新命令重建（`/rtime create <名字>` 或 `manual` + `place`），然后从 `data/devices.yml` 里删掉旧条目。
   讲台钟（`encoding: analog`）**会自动迁移并继续工作**。
2. **命令全换了写法**（旧写法只提示、不执行）：

   | 旧 | 新 |
   |---|---|
   | `/rtime` | `/rtime`（现在同时列出所有钟） |
   | `/rtime info` / `/rtime list` | `/rtime` |
   | `/rtime info <设备>` | `/rtime <钟名>` |
   | `/rtime create <名字> <bcd\|analog> [second\|minute]` | `/rtime create <名字> [minute]`（讲台是唯一形式） |
   | `/rtime create … manual` | `/rtime create <名字> manual`（不变） |
   | `/rtime demo analog` | `/rtime create demo`（`demo` 不再特殊） |
   | `/rtime place <设备> <槽位>` / `set` | `/rtime place <钟名> <槽位>` |
   | `/rtime on\|off <设备>` | `/rtime on\|off <钟名>` |
   | `/rtime remove <设备>` | `/rtime remove <钟名>`（仍需先 `off`） |
   | `/rtime device <子命令>` | 直接写动词 |
   | `/rtime help all` | `/rtime help`（一页列全部） |

   设备名现在是**唯一**的：如果你有同名钟（只可能来自旧数据），先用 `/rtime` 看清并在 `devices.yml` 里改名。
3. **默认按秒**：不带 `minute` 的 `create` 会建 6 个讲台。想省空间就写 `minute`。
4. **`off` 不再拆方块**：关机只是取走书（比较器读数 0），讲台留在原地。要拆用 `remove`。
5. **`%realtime_bcd%` / `%realtime_bcd_bits%` 已删除**：用到它们的地方请改用 `%realtime_time%` 等（完整列表见 usage）。
6. **建议重新生成 `messages.yml`**：旧文件里的旧键（`command.device.…`）不会报错也不会生效，
   新版文案用的是新键。备份后删掉该文件再 `/rtime reload` 即可重新生成，只保留你自定义的 `prefix`。

## [0.5.0]

### 新增

- **一条命令建钟**：`/rtime create <名字> [bcd|analog] [second|minute]` 以玩家准星（或脚下）的方块为原点、
  朝向为轴线，自动排好所有源并放置展示方块（BCD：每槽正上方一盏灯；analog：每位一排讲台/比较器/红石线/灯），
  建完即启用。`/rtime demo <bcd|analog>` 是同义词（名字 `demo-<编码>`、默认按秒）。显式坐标形式保留
  （`/rtime create clock bcd second world 100 70 0 x+`），末尾可用 `nodisplay` 关掉展示方块。
- **逐位自由摆放**：`/rtime create <名字> bcd manual` 建一台**未启用、无展示方块**的钟，所有槽位处于「未放置」：
  不写世界、不参与坐标冲突。用 `/rtime place <设备> <槽位>` 按**任意顺序、任意坐标**定位（不给坐标时用准星位置），
  全部就位后 `/rtime on`。用于"红石机械里已经有别的方块占位、等距布局放不下"的场景。BCD 与 analog 完全一致
  （analog 摆出来的是讲台 + 插件生成的书，页码 = 数字）。
- **设备名寻址**：`info` / `on` / `off` / `remove` / `place` 都可以写设备名，同名冲突时提示改用 ID。
- **Tab 补全**：子命令、设备名与 ID、编码（`bcd`/`analog`）、分辨率（`minute`/`second`）、`manual`/`nodisplay`、
  槽位号、布局轴。
- **`/rtime help [create|place|cleanup|all]`**：默认只列 5 条日常命令，`all` 展开进阶项；`/rtime cleanup`
  不带参数即可查看队列。

### 变更

- **命令面收敛为 5 条日常命令**：`/rtime`、`/rtime create`、`/rtime info [设备]`、`/rtime on|off <设备>`、
  `/rtime remove <设备>`；`place` / `status` / `cleanup` / `reload` 收进 `/rtime help all`。`/rtime info` 无参数时
  同时列出所有设备（`list` 保留为独立命令）。**旧的 `/rtime device <子命令>` 仍然可用**，只是不再出现在帮助与补全里。
- **红石输出模块默认开启**（`modules/redstone.yml` → `enabled: true`）。没有设备时零开销；关闭后插件只读
  （`/rtime`、`/rtime info`、占位符仍可用）。
- `limits.allowed-areas` 为空视为**合法配置**（允许全图放置），启动日志以 `info` 说明一次，不再提示为警告。
- `/rtime create` **默认启用并放置展示方块**（此前创建为禁用、不放任何装饰方块）。想保持"自己接线"用 `nodisplay`，
  想先建后摆用 `manual`。
- 启动与每次 `/rtime reload` 各输出一行状态：红石模块是否开启、`allowed-areas` 是"全图"还是具体区域。
- 持久化 schema **v6**：每个输出新增 `unplaced` 标记（`manual` 建钟用）。v1–v5 自动迁移，旧槽位视为已放置。

### 修复

- **红石模块被关掉时给出明确提示**：以前 `/rtime create` 会成功返回但世界里什么都不出现，
  看起来像插件坏了。现在 `/rtime info` 显示 `红石输出模块：true/false`，建钟/改槽位/开机时若模块关闭会额外提示一行
  并说明怎么开。
- `place` 移动槽位后，若设备带展示方块，会提示「灯/比较器不会跟着移动」（展示方块属于默认布局），
  并建议手工接线或改用 `manual`。此前移动 analog 槽位会留下一个没人能解释的比较器/灯。

### 工程

- 迭代流程标准化：[`docs/development/workflow.md`](docs/development/workflow.md)（选任务 → 任务契约 → 分支命名 →
  本地门禁 → PR 四段式 → CI → squash 合并 → 父仓库 bump → 发版 → DoD / 证据状态）与
  [`tasks/TEMPLATE.md`](tasks/TEMPLATE.md) 任务契约模板；`AGENTS.md`、`tasks/README.md`、README 索引同步指向。
- Hangar / Modrinth 发布就绪：`scripts/distribution-check.sh`（离线校验 `plugin.yml`、工作流接线、Modrinth payload；
  `--remote` 追加公共 API 校验、`--check-tokens` 校验令牌，已并入 `verify.sh`）、
  [`docs/release/distribution.md`](docs/release/distribution.md)（项目创建、最小权限令牌、演练、排错、安全约束）。
- Release 工作流支持 `workflow_dispatch`（`ref` / `version` / `dry_run`）：完整跑版本解析、构建、jar 检查与分布预检
  而不创建 Release、不上传；演练走独立的 `release-dry-run` 环境，`release` 环境仍只允许 `v*` tag；
  Modrinth payload 的 loaders 补上 `folia`。
- `scripts/platform-audit.sh`：发版后核对 Hangar / Modrinth 项目页与本仓库文案（含每个 Hangar 版本的频道、`26.3`、sha256）。
- `scripts/hangar-page.sh`：把 `docs/operations/platform-listings.md` 的长描述推送到 Hangar 主页；发版工作流在 Hangar
  发布后自动同步（失败不影响发版）。
- smoke 脚本（`smoke-server.sh` / `smoke-device-cleanup.sh`）改用扁平命令，CI 覆盖的就是文档教的命令。

### 安装与升级须知（0.5.0）

**升级只需替换 jar 并重启**；`data/devices.yml` 会自动从 schema v5 迁移到 **v6**（旧设备、旧槽位坐标、清理队列
全部保留，已有槽位视为"已放置"，行为不变）。

三处**行为变化**需要你知道：

1. **`/rtime create` 现在默认启用并放置展示方块**（0.4.0 及以前是"创建为禁用、不放任何装饰方块"）。已有设备不受影响；
   想保持自己接线用 `nodisplay`，想先建后摆用 `manual`。
2. **红石模块默认开启**：只影响**全新安装**（已有的 `modules/redstone.yml` 不会被覆盖）。如果不希望插件碰世界，
   把它改成 `enabled: false` 再 `/rtime reload`。
3. **命令改成 5 条日常命令**（`create` / `info` / `on` / `off` / `remove`，进阶收进 `help all`）。
   旧的 `/rtime device <子命令>` **仍然可用**，现有脚本不用改；只是帮助和补全里不再出现。

Hangar 已有 `0.4.0`；本版发布后会自动上传 `0.5.0`（Release 频道）。Modrinth 项目仍在审核中，通过后同样自动上传。

## [0.4.0]

### 新增

- `/rtime cleanup dismiss <job-uuid>`（`realtime.device.retire`）：丢弃一个只剩 `需人工处理` 的清理任务，
  相关方块保持原样。用于"这些方块本来就不该清"的场景（典型：`device demo` 建在草地上，支撑位是原始地形），
  以前只能手改 `data/devices.yml`。仍有 `待处理` 项时会拒绝。
- `/rtime demo` 现在会单独提示"建示例前请确认该区域是空地"（独立消息键 `command.device.demo.hint`，老服的
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

### 变更（命令面收敛）

命令从「三层 + 18 条路径」收敛为**一条命令一台钟**，`/rtime help` 只列 5 条日常用法：

| 日常 | 命令 |
|---|---|
| 时间 | `/rtime` |
| 建钟 | `/rtime create <名字> [bcd\|analog] [second\|minute]` |
| 看状态/列表 | `/rtime info [设备]` |
| 开关 | `/rtime on\|off <设备>` |
| 拆除 | `/rtime remove <设备>` |

进阶项（`/rtime help all`）：`place`（逐位摆源）、`status`（诊断）、`cleanup`（清理队列）、`reload`。
`list` 保留为独立命令；`device <子命令>` 作为兼容别名继续可用，但不再出现在帮助与补全里。

### 新增

- **一条命令建钟**：`/rtime create <名字> [bcd|analog] [second|minute]` 直接以玩家位置与朝向布局，
  并自动放置展示方块（灯 / 讲台+比较器+红石线），建完即启用。`/rtime demo` 成为同义词（名字 `demo-<编码>`、默认按秒）。
  显式坐标形式保留，并同样会放展示方块（`nodisplay` 关闭）。
- **逐位精确放置**：`/rtime place <设备> <槽位> [x y z]`，不给坐标就用准星所指（或脚下）的方块；
  `manual` 模式建出未启用的空钟，配合 `place` 逐个定位，用于等距布局被其它方块占位的场景。`set` 保留为别名。
- **设备名寻址**：`info` / `on` / `off` / `remove` / `place` 都可以写设备名，同名冲突时提示改用 ID。
- **Tab 补全补齐**：子命令、设备名与 ID、编码、分辨率、`nodisplay`/`manual`、槽位号、轴。
- **`/rtime help [create|place|cleanup|all]`**：默认只列 5 条日常命令，`all` 展开进阶项；`plugin.yml` 的 usage 同步为多行。

### 新增

- **`manual` 建钟 = 逐位自由摆放**：所有槽位以「未放置」创建（不写世界、不参与坐标冲突），
  `/rtime place <设备> <槽位>` 按任意顺序、任意坐标定位，全部就位后才能 `/rtime on`（否则提示还差几个）。
  `info` 会把未放置的槽位标成「未放置」。BCD 与 analog 完全一致（analog 摆出来的是讲台 + 插件书）。
  持久化 schema **v6**（每个输出多一个 `unplaced` 标记，v1–v5 自动迁移，旧槽位视为已放置）。

### 变更

- `place` 移动槽位后若设备带展示方块，会提示「灯/比较器不会跟着移动」，建议手工接线或用 `manual`。
- **红石输出模块默认开启**（`modules/redstone.yml` → `enabled: true`）。全新安装不再出现"命令成功但世界里没有方块"的
  默认状态；没有设备时该模块零开销。已有服务器的 `modules/redstone.yml` 不会被覆盖，行为不变。
- `limits.allowed-areas` 为空视为**合法配置**（允许全图放置），启动日志以 `info` 说明一次，不再当作警告。
- `/rtime create` 现在**默认启用**并放置展示方块（此前创建为禁用、不放任何装饰方块）。已有设备不受影响；
  想保持"自己接线"的用法加 `nodisplay`，想先建后摆用 `manual`。
- 启动与 `/rtime reload` 时各输出一行模块状态：红石模块是否开启、`allowed-areas` 是"全图"还是具体区域。

### 修复

- `place` 移动槽位后，如果设备带展示方块，会额外提示「灯/比较器不会跟着移动」，并给出手工接线或 `manual` 的建议
  （此前移动 analog 槽位会留下一个没人能解释的比较器/灯）。

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

### 变更（命令面收敛）

命令从「三层 + 18 条路径」收敛为**一条命令一台钟**，`/rtime help` 只列 5 条日常用法：

| 日常 | 命令 |
|---|---|
| 时间 | `/rtime` |
| 建钟 | `/rtime create <名字> [bcd\|analog] [second\|minute]` |
| 看状态/列表 | `/rtime info [设备]` |
| 开关 | `/rtime on\|off <设备>` |
| 拆除 | `/rtime remove <设备>` |

进阶项（`/rtime help all`）：`place`（逐位摆源）、`status`（诊断）、`cleanup`（清理队列）、`reload`。
`list` 保留为独立命令；`device <子命令>` 作为兼容别名继续可用，但不再出现在帮助与补全里。

### 新增

- **一条命令建钟**：`/rtime create <名字> [bcd|analog] [second|minute]` 直接以玩家位置与朝向布局，
  并自动放置展示方块（灯 / 讲台+比较器+红石线），建完即启用。`/rtime demo` 成为同义词（名字 `demo-<编码>`、默认按秒）。
  显式坐标形式保留，并同样会放展示方块（`nodisplay` 关闭）。
- **逐位精确放置**：`/rtime place <设备> <槽位> [x y z]`，不给坐标就用准星所指（或脚下）的方块；
  `manual` 模式建出未启用的空钟，配合 `place` 逐个定位，用于等距布局被其它方块占位的场景。`set` 保留为别名。
- **设备名寻址**：`info` / `on` / `off` / `remove` / `place` 都可以写设备名，同名冲突时提示改用 ID。
- **Tab 补全补齐**：子命令、设备名与 ID、编码、分辨率、`nodisplay`/`manual`、槽位号、轴。
- **`/rtime help [create|place|cleanup|all]`**：默认只列 5 条日常命令，`all` 展开进阶项；`plugin.yml` 的 usage 同步为多行。

### 新增

- **`manual` 建钟 = 逐位自由摆放**：所有槽位以「未放置」创建（不写世界、不参与坐标冲突），
  `/rtime place <设备> <槽位>` 按任意顺序、任意坐标定位，全部就位后才能 `/rtime on`（否则提示还差几个）。
  `info` 会把未放置的槽位标成「未放置」。BCD 与 analog 完全一致（analog 摆出来的是讲台 + 插件书）。
  持久化 schema **v6**（每个输出多一个 `unplaced` 标记，v1–v5 自动迁移，旧槽位视为已放置）。

### 变更

- `place` 移动槽位后若设备带展示方块，会提示「灯/比较器不会跟着移动」，建议手工接线或用 `manual`。
- **红石输出模块默认开启**（`modules/redstone.yml` → `enabled: true`）。全新安装不再出现"命令成功但世界里没有方块"的
  默认状态；没有设备时该模块零开销。已有服务器的 `modules/redstone.yml` 不会被覆盖，行为不变。
- `limits.allowed-areas` 为空视为**合法配置**（允许全图放置），启动日志以 `info` 说明一次，不再当作警告。
- `/rtime create` 现在**默认启用**并放置展示方块（此前创建为禁用、不放任何装饰方块）。已有设备不受影响；
  想保持"自己接线"的用法加 `nodisplay`，想先建后摆用 `manual`。
- 启动与 `/rtime reload` 时各输出一行模块状态：红石模块是否开启、`allowed-areas` 是"全图"还是具体区域。

### 修复

- `place` 移动槽位后，如果设备带展示方块，会额外提示「灯/比较器不会跟着移动」，并给出手工接线或 `manual` 的建议
  （此前移动 analog 槽位会留下一个没人能解释的比较器/灯）。

- `device set` 现在要求设备处于**禁用**状态（补全也只推荐已禁用设备）：之前允许对启用中设备改槽位，而设备只写"当前槽位表"，
  旧槽位会留下一个永久通电的红石块（或一个讲台）。
- analog 设备 `disable` 现在把槽位整体清空（而不是只移走书留下空讲台），与 BCD "归 air" 一致，也让"改槽位不留残留"成立。
- 退役 analog 设备时，`disable` 留下的空白讲台现在也会被清理（先清空库存再拆方块，书不掉落），不再需要人工手拆 6 个讲台。

### 变更（命令面收敛）

命令从「三层 + 18 条路径」收敛为**一条命令一台钟**，`/rtime help` 只列 5 条日常用法：

| 日常 | 命令 |
|---|---|
| 时间 | `/rtime` |
| 建钟 | `/rtime create <名字> [bcd\|analog] [second\|minute]` |
| 看状态/列表 | `/rtime info [设备]` |
| 开关 | `/rtime on\|off <设备>` |
| 拆除 | `/rtime remove <设备>` |

进阶项（`/rtime help all`）：`place`（逐位摆源）、`status`（诊断）、`cleanup`（清理队列）、`reload`。
`list` 保留为独立命令；`device <子命令>` 作为兼容别名继续可用，但不再出现在帮助与补全里。

### 新增

- **一条命令建钟**：`/rtime create <名字> [bcd|analog] [second|minute]` 直接以玩家位置与朝向布局，
  并自动放置展示方块（灯 / 讲台+比较器+红石线），建完即启用。`/rtime demo` 成为同义词（名字 `demo-<编码>`、默认按秒）。
  显式坐标形式保留，并同样会放展示方块（`nodisplay` 关闭）。
- **逐位精确放置**：`/rtime place <设备> <槽位> [x y z]`，不给坐标就用准星所指（或脚下）的方块；
  `manual` 模式建出未启用的空钟，配合 `place` 逐个定位，用于等距布局被其它方块占位的场景。`set` 保留为别名。
- **设备名寻址**：`info` / `on` / `off` / `remove` / `place` 都可以写设备名，同名冲突时提示改用 ID。
- **Tab 补全补齐**：子命令、设备名与 ID、编码、分辨率、`nodisplay`/`manual`、槽位号、轴。
- **`/rtime help [create|place|cleanup|all]`**：默认只列 5 条日常命令，`all` 展开进阶项；`plugin.yml` 的 usage 同步为多行。

### 新增

- **`manual` 建钟 = 逐位自由摆放**：所有槽位以「未放置」创建（不写世界、不参与坐标冲突），
  `/rtime place <设备> <槽位>` 按任意顺序、任意坐标定位，全部就位后才能 `/rtime on`（否则提示还差几个）。
  `info` 会把未放置的槽位标成「未放置」。BCD 与 analog 完全一致（analog 摆出来的是讲台 + 插件书）。
  持久化 schema **v6**（每个输出多一个 `unplaced` 标记，v1–v5 自动迁移，旧槽位视为已放置）。

### 变更

- `place` 移动槽位后若设备带展示方块，会提示「灯/比较器不会跟着移动」，建议手工接线或用 `manual`。
- **红石输出模块默认开启**（`modules/redstone.yml` → `enabled: true`）。全新安装不再出现"命令成功但世界里没有方块"的
  默认状态；没有设备时该模块零开销。已有服务器的 `modules/redstone.yml` 不会被覆盖，行为不变。
- `limits.allowed-areas` 为空视为**合法配置**（允许全图放置），启动日志以 `info` 说明一次，不再当作警告。
- `/rtime create` 现在**默认启用**并放置展示方块（此前创建为禁用、不放任何装饰方块）。已有设备不受影响；
  想保持"自己接线"的用法加 `nodisplay`，想先建后摆用 `manual`。
- 启动与 `/rtime reload` 时各输出一行模块状态：红石模块是否开启、`allowed-areas` 是"全图"还是具体区域。

### 修复

- `place` 移动槽位后，如果设备带展示方块，会额外提示「灯/比较器不会跟着移动」，并给出手工接线或 `manual` 的建议
  （此前移动 analog 槽位会留下一个没人能解释的比较器/灯）。

- **红石模块未启用时给出明确提示**：`modules/redstone.yml` 被手动关掉时，`/rtime create` 会成功返回但世界里什么都不出现
  （槽位不写、展示方块不放置），
  看起来像插件坏了。现在 `/rtime info` 会显示 `红石输出模块：true/false`，
  建钟/改槽位/开机时若模块关闭会额外提示一行并说明怎么开。

### 工程

- Hangar 项目主页（长描述）改由 [`scripts/hangar-page.sh`](scripts/hangar-page.sh) 从
  `docs/operations/platform-listings.md` 推送（`PATCH /api/v1/pages/editmain/{project}`，需 `edit_page`）；
  发布工作流在 Hangar 发布后自动同步，失败不影响发版。分类与许可证没有 API，仍是网页操作。
- 新增 [`scripts/platform-audit.sh`](scripts/platform-audit.sh)：发版后核对 Hangar / Modrinth 项目页是否与本仓库文案一致
  （名称、分类、许可证、简介/长描述、链接、平台与游戏版本、loader，以及每个 Hangar 版本的频道、`26.3` 与 **sha256**）。
  Hangar 无需凭据；Modrinth 需要 `READ_PROJECT`，读不到时如实报「无法核对」而不是假装通过。

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
