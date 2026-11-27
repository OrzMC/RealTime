# RealTime 使用指南

面向服主与建筑/红石作者的完整用法说明。技术实现（数据结构、调度、比较器读数公式）见
[redstone devices](development/redstone-devices.md)，产品需求见 [requirements](product/requirements.md)。

> 版本口径：本文对应 `0.9.0`（`main` 在 `0.10.0-SNAPSHOT`）。命令、权限与行为都在 CI 与真机验收中覆盖过。

## 1. 这个插件能做什么

- **真实世界时间**：可查询、可占位符化，时区显式配置（默认 `Asia/Shanghai`），跟随配置的 `ZoneId` 而不是服务器机器时区。
- **把时间变成红石信号**：一台"钟"把 `HHmm` / `HHmmss` 拆成数字，每个数字用一个**讲台**表示：
  书页索引 = 数字，讲台后面的比较器读到的强度 = **数字 + 1**（0 读 1、9 读 10）。
- 一台钟要么显示 `HHmm`（4 个讲台），要么显示 `HHmmss`（6 个讲台）。**默认按秒**。

为什么只有讲台：`6` 根线就能表达 `HHmmss`，红石机器里省下大量空间；而且读数不需要任何译码电路——
比较器直接给出模拟强度。（早期版本的 BCD 位输出已移除：多位并联的译码器延迟不可控，显示效果无法保证。）

## 2. 安装

| 项 | 要求 |
|---|---|
| Java | 25 |
| 服务端 | Paper 或 Folia **26.3+**（`api-version: 26.3`） |
| 安装 | 把 `RealTime-<版本>.jar` 放进 `plugins/` 后启动 |

首次启动会生成 `plugins/RealTime/`：`config.yml`、`messages.yml`、`modules/command.yml`、
`modules/redstone.yml`、`modules/updates.yml`、`data/devices.yml`、`data/updates.yml`。**红石模块默认开启**（`enabled: true`）。没有钟时它不产生任何写入和开销；
不想让插件碰世界就改成 `false`，此时 `/rtime`、`/rtime status` 与占位符仍然可用。

> ⚠️ 模块关闭时**命令全部照常工作，但世界里什么都不会出现**：`/rtime create` 会成功返回，
> 讲台、比较器、灯都不会放置，看起来就像"插件不工作"。自查三处：
>
> 1. `/rtime status` 的 `红石输出模块：` 必须是 `true`
> 2. 建钟/摆位/开机时若模块是关的，命令会额外提示一行"红石输出模块未启用"
> 3. `/rtime status` 里 `变化`/`讲台：放置` 一直是 0，而 `钟：` 正常增长

```yaml
# modules/redstone.yml
enabled: true
limits:
  max-devices: 50            # 钟的数量上限
  edit-cooldown-seconds: 3   # 每人执行建钟/摆位命令的冷却
  allowed-areas: []          # 空 = 允许全图放置；填了就只允许这些矩形区域
  # - { world: world, min-x: 39000, max-x: 41000, min-z: -200, max-z: 200 }
```

`allowed-areas` 为空是**合法配置**（全图放置），启动日志里会以 `info` 说明一次；填了区域则只允许在范围内建/改钟。

### 自动更新（`modules/updates.yml`，默认只提示）

插件会每 6 小时问一次 Hangar（失败再问 GitHub Releases）有没有新版本，**只会把新 jar 放进
`plugins/update/`，不会热替换自己、也不会重启服务器**——重启由你决定：

```yaml
enabled: true
mode: notify        # notify = 只提示；download = 发现就自动下载到 plugins/update/
channel: release    # release = 只要正式版；any = 连 -SNAPSHOT 一起
```

- 看到提示后执行 `/rtime update download`（或直接把 `mode` 改成 `download` 让它自己下）。
- 下载会**先校验平台公布的 sha256/sha512**，再落盘成 `plugins/update/RealTime-<新版本>.jar`；
  校验不通过会删掉下载并说明原因，不会留下任何文件。
- 重启后 Paper 会把 `plugins/update/` 里的 jar 覆盖到当前 jar，并把文件名改成新版本（见
  [开发文档](development/updates.md)），所以更新后 `plugins/` 里只有一个 RealTime。
- 开发构建（`0.8.0-SNAPSHOT` 之类）默认不检查（`safety.skip-dev-builds: true`）。

## 3. 命令

**两读 + 五写 + 五个运维命令**（`status` / `cleanup` / `update` / `help` / `reload`），名字和槽位号都能 Tab 补全：

| 命令 | 权限 | 默认 | 作用 |
|---|---|---|---|
| `/rtime` | `realtime.command` | 所有人 | 现在几点 + 所有钟（状态、精度、槽位数、未放置数量） |
| `/rtime <钟名>` | `realtime.device.view` | 所有人 | 这台钟的详情：槽位坐标、未放置的槽位、下一步能做什么 |
| `/rtime create <名字> [选项…]` | `realtime.device.edit` | OP | 在你面前建一台钟（详见 §6） |
| `/rtime on <钟名>` / `/rtime off <钟名>` | `realtime.device.toggle` | OP | 开机 / 关机 |
| `/rtime place <钟名> <槽位> [x y z]` | `realtime.device.edit` | OP | 把一个槽位摆到你看着的位置，**讲台立刻立起来** |
| `/rtime remove <钟名>` | `realtime.device.retire` | OP | 拆掉并清理方块（要先 `off`） |
| `/rtime status` | `realtime.device.view` | 所有人 | 版本/时区/模块状态 + 输出诊断计数器 |
| `/rtime cleanup [retry\|dismiss <任务ID>]` | `view`（dismiss 要 `retire`） | OP | 拆钟后的清理队列 |
| `/rtime update [check\|download]` | `realtime.update` | OP | 检查新版本 / 下载到 `plugins/update/`（重启后生效） |
| `/rtime help` | `realtime.command` | 所有人 | 一页帮助 |
| `/rtime reload` | `realtime.admin` | OP | 重载配置 |

- `realtime.admin` 包含上面全部权限。
- 旧的 `/rtime info`、`/rtime list`、`/rtime device …`、`/rtime demo`、`/rtime set` **不再存在**，
  输入时会得到一行"已移除，改用 …"的提示。
- 站得远、够不到目标方块时用显式坐标：`/rtime place clock 3 100 64 -20`。

## 4. 五分钟上手

```
/rtime create lobby-clock        # 站在要建的位置，面向钟延伸的方向
```

你会得到：6 个讲台（间隔 1 格）＋ 每个讲台后面的比较器 + 红石线 + 红石灯，**已经开机并在走秒**。

想改精度或不要装饰：

```
/rtime create wall-clock minute        # 4 个讲台，只显示 HHmm
/rtime create wall-clock nodisplay     # 只放讲台，比较器/灯自己接
```

读表：比较器强度 = 数字 + 1（0 读 1 … 9 读 10）。比较器输出接到译码/驱动电路即可。

## 5. 关机、开机与拆除

```
/rtime off lobby-clock     # 书取走 → 比较器读数归 0；讲台留在原地，位置不丢
/rtime on lobby-clock      # 重新装书 → 立刻显示当前时间
/rtime remove lobby-clock  # 先 off，再 remove：讲台进清理队列，跑完自动消失
```

关机**保留讲台**（只把书取走），所以你能随时看到钟的布局；`remove` 才会真正拆掉它们。
`remove` 前必须 `off`，避免拆到一半还在写。

## 6. `/rtime create` 的选项

```
/rtime create <名字> [second|minute] [manual] [nodisplay] [at <x> <y> <z>] [facing <north|south|east|west>]
```

| 选项 | 作用 |
|---|---|
| `second` / `minute` | 精度，**默认 `second`**（6 个讲台 / 4 个讲台） |
| `manual` | **不铺任何讲台**，所有槽位处于"未放置"，建完是禁用状态；用 `place` 一个一个摆 |
| `nodisplay` | 铺讲台，但不放比较器/红石线/灯 |
| `at <x> <y> <z>` | 显式原点（默认用你准星所指的方块，没有目标就用脚下那格） |
| `facing <朝向>` | 钟延伸的方向（默认你面朝的方向；`north` = 朝 −z，`east` = 朝 +x …） |

选项顺序随意、互不冲突。钟名不能和命令动词重名（`create`、`place` …），也不能和已有的钟重名。

## 7. 逐位摆放（位置被占、或要装进现成的机器）

红色机械里通常没有 2 格一格的空位，所以：

```
/rtime create machine manual      # 0 个讲台；所有槽位"未放置"
/rtime machine                    # 每个槽位显示「未放置（/rtime place machine <槽位> 立讲台）」
/rtime place machine 0            # 对准第一个数字要放的位置执行 → 讲台立刻出现
/rtime place machine 1            # 依次摆，坐标完全由你定；旧位置的讲台会被清掉
...
/rtime machine                    # 检查：6 个槽位都有坐标了
/rtime on machine                 # 全部就位后才能开机
```

- **摆一个就立一个讲台**：能立刻看到位置对不对、正面朝向对不对（讲台朝向你），不用先开机再猜。
- 还有槽位没摆就 `on`，会被拒绝并告诉你还差几个。
- 关机状态下讲台是"空白"的（没有书、读数 0），开机后才开始走时。
- `place` 只允许在关机状态下执行（开着的钟会一直写旧槽位）。

## 8. 诊断

```
/rtime status
```

| 字段 | 含义 |
|---|---|
| `钟：N 台（开着 M 台）· 槽位：K` | 配置了多少、其中多少在走 |
| `输出检查` | 本轮检查了多少个槽位 |
| `未加载区块跳过` | 槽位所在区块没加载 → 跳过（区块加载后自动补写） |
| `变化` / `无变化` | 真正写了几个 / 已经一致 |
| `非法方块` | 槽位里是别的方块（有人盖了东西）→ **不覆盖**，日志警告一次 |
| `写入失败` | 调度或写入异常 |
| `讲台：放置 / 书页写入` | 讲台放置数、书的安装/移除数 |
| `旧版 BCD 钟已忽略` | 数据里还有 BCD 编码的旧钟（本版本已移除 BCD），需要手工删掉 |
| `更新：…` | 更新检查状态：已是最新 / `X` 可用 / 已下载待重启 / 检查失败 / 开发构建不检查 |

`非法方块` 增长说明钟的位置被别的建筑占了：`/rtime machine` 看清是哪个槽位，`/rtime off` → `place` 换个位置，或 `remove` 后重建。

## 9. PlaceholderAPI（可选）

已安装 PlaceholderAPI 时自动注册：

| 占位符 | 含义 |
|---|---|
| `%realtime_time%` | 按 `modules/command.yml` 的 `format` 格式化 |
| `%realtime_date%` | `uuuu-MM-dd` |
| `%realtime_time_24h%` / `%realtime_time_12h%` | `HH:mm:ss` / `hh:mm:ss a` |
| `%realtime_hour%` `%realtime_minute%` `%realtime_second%` | 两位数字 |
| `%realtime_day%` `%realtime_month%` `%realtime_year%` | 日期分量 |
| `%realtime_weekday%` / `%realtime_weekday_short%` | 星期几 |
| `%realtime_timezone%` `%realtime_offset%` `%realtime_iso%` | 时区 / 偏移 / ISO 时间 |
| `%realtime_timestamp%` `%realtime_timestamp_millis%` | Unix 时间 |
| `%realtime_redstone_enabled%` | 红石模块是否开启 |
| `%realtime_device_count%` | 正在走时的钟数量 |
| `%realtime_update_available%` | `true`/`false`：是否有新版本（含已下载待重启）|
| `%realtime_latest_version%` | 重启后会装上的版本号，已是最新则为空字符串 |

旧版本的 `%realtime_bcd%` / `%realtime_bcd_bits%` 随 BCD 编码一起移除。

## 10. 常见问题

**建钟成功但世界里什么都没有？**
`/rtime status` 看 `红石输出模块：` 是否为 `true`；不是就把 `modules/redstone.yml` 改成 `enabled: true` 再 `/rtime reload`。

**讲台立起来了但比较器没反应？**
比较器要贴着讲台、并且 `facing` 指向讲台（比较器的 `facing` 是**输入端**）。用 `create` 自动生成的
比较器行已经接好；自己接线时注意这点。读数 = 数字 + 1，所以"0 点 0 分"读到的是 1 和 1，不是 0。

**开机被拒绝，说还有槽位没有位置？**
`manual` 建出来的钟要先用 `/rtime place <名字> <槽位>` 把每个槽位摆好（`/rtime <名字>` 能看到还差哪几个）。

**摆位时报"这个位置已经被占用"？**
同一个钟的两个槽位不能在同一格，也不能和清理队列里的方块重叠。换个位置即可。

**关机后讲台还在，是不是没关？**
这是预期行为：关机只取走书，比较器读数归 0，讲台保留方便你继续调整。要真正拆掉用 `/rtime remove`。

**`/rtime update` 说"没有下载：…"？**
最常见的是已经有更新的版本在等重启（`plugins/update/` 里那份）、或者已经是最新。真要看原因就把
`modules/updates.yml` 的 `enabled` 打开、检查服务器能否出网（Hangar/GitHub 的 443）。

**更新下载好了，但版本没变？**
更新目录里的 jar 要**重启**才生效，插件不会热替换自己。重启后 `plugins/` 里的文件名会变成新版本。

**能同时显示日期吗？**
不能。钟只表达时间；日期用占位符（`%realtime_date%`）放到告示牌/计分板上。

**钟能建多大？**
`second` 精度 6 个槽位、间隔 1 格 → 跨度 11 格；`minute` 4 个槽位 → 7 格。展示行在轴线的垂直方向再延伸 3 格。
