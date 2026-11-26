# RealTime 使用指南

面向服主与建筑/红石作者的完整用法说明。技术实现（数据结构、调度、编码公式）见
[redstone devices](development/redstone-devices.md)，产品需求见 [requirements](product/requirements.md)。

> 版本口径：本文对应 `0.5.0-SNAPSHOT`，适用于 `v0.4.0` 及之后的版本。命令、权限与行为都在 CI 与真机验收中覆盖过。

## 1. 这个插件能做什么

- **真实世界时间**：可查询、可占位符化，时区显式配置（默认 `Asia/Shanghai`），跟随配置的 `ZoneId` 而不是服务器机器时区。
- **把时间变成红石信号**：一台"时钟设备"把 HHmm / HHmmss 拆成数字输出到世界里，驱动灯阵、七段管、机械结构。
- **两种输出编码**（同一台设备二选一）：

| 编码 | 一个数字 = | `minute`（HHmm）槽位 | `second`（HHmmss）槽位 | 你怎么读 |
|---|---|---|---|---|
| `bcd`（默认） | 4 个槽位（二进制位） | 16 | 24 | 每个槽位 = 1 根线/1 盏灯，亮=1 |
| `analog` | 1 个槽位（讲台） | 4 | 6 | 比较器读**模拟强度**，强度 = 数字 + 1 |

BCD 稳、无需方块实体；analog 把 24 条线压成 6 条，代价是要用比较器译码、且讲台所在区块需要在 ticking（有人在场）。

## 2. 安装

| 项 | 要求 |
|---|---|
| Java | 25 |
| 服务端 | Paper 或 Folia **26.3+**（`api-version: 26.3`） |
| 安装 | 把 `RealTime-<版本>.jar` 放进 `plugins/` 后启动 |

首次启动会生成 `plugins/RealTime/`：`config.yml`、`messages.yml`、`modules/command.yml`、`modules/redstone.yml`、
`data/devices.yml`。**红石模块默认开启**（`enabled: true`）。没有设备时它不产生任何写入和开销；不想让插件碰世界就改成 `false`，
此时 `/rtime now`、`/rtime info` 与占位符仍然可用。

> ⚠️ 模块关闭时**命令全部照常工作，但世界里什么都不会出现**：`device create` / `device demo` 会成功返回，
> 槽位不写入、demo 的灯/讲台/比较器也不会放置。这是**新装服务器的默认状态**，看起来就像"插件不工作"。
> 三条自查：
>
> 1. `/rtime info` 的最后一行 `红石输出模块：` 必须是 `true`
> 2. 创建/启用设备后若模块是关的，命令会额外提示一行"红石输出模块未启用"
> 3. `/rtime device status` 里 `输出变更`/`示例方块放置` 一直是 0，而 `配置设备` 正常增长

```yaml
# modules/redstone.yml
enabled: true
limits:
  max-devices: 50            # 设备数量上限
  edit-cooldown-seconds: 3   # 每人执行建设备命令的冷却
  allowed-areas: []          # 空 = 允许全图放置；填了就只允许这些矩形区域
  # - { world: world, min-x: 39000, max-x: 41000, min-z: -200, max-z: 200 }
```

`allowed-areas` 为空是**合法配置**（全图放置），启动日志里会以 `info` 说明一次；填了区域则只允许在范围内建/改设备。

## 3. 命令与权限

一条命令就能建出一台能看的钟：**站在位置、面向要延伸的方向**，然后

```text
/rtime device create clock bcd      # 建在准星所指的方块（或你脚下），自动排好源并放好灯
/rtime device demo analog           # 同上，只是名字是 demo-analog，默认按秒刷新
```

`Tab` 会补全子命令、设备名、编码/分辨率、槽位号；`/rtime help` 随时查看这一页的精简版，`/rtime help create|place|cleanup` 看细节。

| 命令 | 权限 | 默认 | 作用 |
|---|---|---|---|
| `/rtime`、`/rtime now` | `realtime.command` | 所有人 | 当前时间 |
| `/rtime info` | `realtime.command` | 所有人 | 版本、时区、模块开关（含红石模块） |
| `/rtime help [create\|place\|cleanup]` | `realtime.command` | 所有人 | 用法与示例 |
| `/rtime reload` | `realtime.admin` | OP | 重载配置 |
| `/rtime device list` | `realtime.device.view` | 所有人 | 设备列表与开关状态 |
| `/rtime device info <设备>` | `realtime.device.view` | 所有人 | 分辨率、编码、每个槽位坐标 |
| `/rtime device status` | `realtime.device.view` | 所有人 | 输出诊断计数器 |
| `/rtime device create <名字> <bcd\|analog> [second\|minute] [nodisplay\|manual]` | `realtime.device.edit` | OP | **推荐**：在玩家位置建钟，自动排源 + 放展示方块，立即启用 |
| `/rtime device create <名字> <minute\|second> <世界> <x> <y> <z> <轴> [间距] [bcd\|analog] [nodisplay]` | `realtime.device.edit` | OP | 显式坐标（脚本/远程用） |
| `/rtime device demo <bcd\|analog> [second\|minute]` | `realtime.device.edit` | OP | 与 create 相同，名字固定为 `demo-<编码>`，默认按秒 |
| `/rtime device place <设备> <槽位> [x y z]` | `realtime.device.edit` | OP | 把某个源放到准星位置（或显式坐标）；**设备需先禁用** |
| `/rtime device enable\|disable <设备>` | `realtime.device.toggle` | OP | 开/关设备 |
| `/rtime device remove <设备>` | `realtime.device.retire` | OP | 退役（**需先禁用**）→ 入清理队列 |
| `/rtime device cleanup list` | `realtime.device.view` | 所有人 | 待清理队列 |
| `/rtime device cleanup retry <任务ID>` | `realtime.device.retire` | OP | 修正方块后重排 `NEEDS_ATTENTION` 项 |
| `/rtime device cleanup dismiss <任务ID>` | `realtime.device.retire` | OP | 丢弃任务、方块保持原样（仅当没有待处理项） |

- **设备可以用名字也可以用 ID**：列表里两个都有，命令里写哪个都行；同名设备存在多台时会提示改用 ID。
- `realtime.device.edit` 是**唯一会按你给的坐标写方块**的权限：只给建筑/红石组，并配合 `limits.allowed-areas` 与 `max-devices`。
- `realtime.admin` 是父节点，包含以上全部。只给过 OP 的服不需要改任何东西。
- 创建后**默认立即启用**（`manual` 除外），所以 `device place` 前要先 `disable`。

## 4. 五分钟上手（BCD）

```text
/rtime device create lobby-clock bcd second      # 站在位置、面向延伸方向；自动排好 24 个源并放灯
/rtime device info lobby-clock                   # 确认编码/分辨率/所有坐标（名字或 ID 都行）
/rtime device enable <uuid>                                        # 开始驱动
```

- 槽位必须先是**空气**（或红石方块）。每个槽位就是一个电源：1 = 放 `redstone_block`、0 = 空气。
- 在槽位**正上方**放一盏红石灯，就能直接看到每一位：灯亮=1。这是最省事的目视方案。
- 想做真正的数字显示，就在每个槽位旁边接你的译码电路（红石线 → 七段/机械）。

改一个坐标：`/rtime device disable <设备>` → 对着目标位置执行 `/rtime device place <设备> <槽位>` → `/rtime device enable <设备>`。
**必须先禁用**，否则运行中的设备会继续写旧槽位，留下一个永远通电的红石块。

## 5. analog（讲台）编码

```text
/rtime device create wall-clock second world 39985 79 -24 x+ analog   # 6 个讲台，间隔 2 格
/rtime device enable <uuid>
```

每个数字一个讲台，插件会自动放置讲台并塞入一本插件生成的 15 页书；**页码 = 数字**，比较器读到的强度 = 页码 + 1（1–10）。

你需要自己接的只有译码部分（每位一行）：

```
讲台  比较器(朝讲台)  红石线  灯
 [x]  →  [x]  →      →      [ ]
```

- 比较器的 `facing` **指向输入侧**：讲台在比较器北边就写 `facing=north`，输出在南边。
- 讲台是方块实体：只有该区块处于 entity-ticking（附近有人）时比较器才会持续刷新；无人时到现场会补上。
- 关闭设备会**清空槽位**（先移走书再拆讲台，不会掉落物品）；重新启用会重新放置。
- 插件只操作"书仍属于该设备"的讲台，别人放的书/方块只告警、不覆盖。

## 6. `device create` 与 `device demo`

两者是**同一套构建**：源按朝向等距排开（BCD 每 4 位一组），并自动放好展示方块（BCD：每槽正上方一盏灯；
analog：每位一排讲台/比较器/红石线/灯），建完**立即启用**。区别只有三个：

| | `create` | `demo` |
|---|---|---|
| 名字 | 你指定 | 固定 `demo-<编码>` |
| 默认分辨率 | `minute` | `second`（能立刻看到秒在跳） |
| 展示方块 | 默认放，`nodisplay` 关闭 | 默认放 |

```
/rtime device create clock bcd            # 站好位置面向一排放
/rtime device create clock analog second  # 讲台版，按秒
/rtime device demo analog                 # 给玩家演示用
```

`manual` 模式（只建空钟、不启用、不放展示方块）：

```
/rtime device create clock bcd manual     # 全部槽位先按默认布局占位，但设备是禁用的，不会写世界
/rtime device place clock 0               # 对准第 1 个源要放的位置执行；Tab 补全槽位号
/rtime device place clock 1 ...           # 逐位放；遇到被占用的格子就换个位置
/rtime device enable clock                # 全部就位后启用
```

这是给"红石机械里已经有别的方块占位、等距布局放不下"的场景准备的：位置完全由你决定，插件只负责按
BCD/analog 规则去驱动这些源。

⚠️ **建之前先清场**：`device demo` 只填空位。如果槽位或灯位被地形（树、草、水）占着，那些位置会报"非法方块"，
退役时会变成 `NEEDS_ATTENTION` 需要人工处理。选一块平地，或先清空那片区域。

示例的整体清除：`device disable <uuid>` → `device remove <uuid>`（槽位 + 展示方块一起清）。

## 7. 关闭、退役与清理队列

- `disable`：把槽位清零（BCD → 空气；analog → 移书 + 拆讲台），设备保留在列表里。
- `remove`（必须先 disable）：把设备移出列表，并为**每个槽位**（含 demo 展示方块）生成一个**持久化清理任务**，
  由插件在区块加载/周期扫描时清理。

清理任务里每个位置有三种状态：

| 状态 | 含义 | 你要做什么 |
|---|---|---|
| `待处理` | 还没处理（区块未加载等） | 等（或到现场让它加载） |
| `已清理` | 已清成空气 / 本来就空 | 无 |
| `需人工处理` | 该位置是**不是插件放的**东西（玩家的方块、地形、别人的书） | 看一眼：想清就清掉再 `cleanup retry`；想保留就 `cleanup dismiss` |

```
/rtime device cleanup list
/rtime device cleanup retry   <job-uuid>    # 我把方块清好了，再试一次
/rtime device cleanup dismiss <job-uuid>    # 这些方块就保持原样，任务丢掉（没有待处理项时才能用）
```

**为什么会出现"需人工处理"**：插件只清"自己放的东西"——红石块、自己的讲台、demo 的灯/比较器/红石线。
如果那个位置是地形或玩家建筑，它**绝不覆盖**，只报告。典型场景就是 demo 建在树林里。

## 8. 诊断

```
/rtime device status
```

| 行 | 含义 |
|---|---|
| 配置设备 / 启用 / 输出点 | 当前规模 |
| 输出检查 / 未加载区块跳过 / 过期配置更新 | 实际派发了多少槽位工作 |
| 输出变更 / 无变化 / 非法方块 / 写入失败 | 写入结果；`非法方块` 增长说明有槽位被别的东西占着 |
| 讲台放置 / 书页安装 | 仅 analog 设备 |
| 示例方块放置 / 清除 | 仅 `device demo` 设备 |

计数器是进程内累计值；配置数反映当前运行状态。

`输出变更` 与 `示例方块放置` 长时间为 0，而 `配置设备` 不为 0 → 先看 `/rtime info` 的 `红石输出模块` 是不是 `false`。

## 9. PlaceholderAPI（可选）

装 PlaceholderAPI 即可用，不装也能正常运行。常用：

| 占位符 | 含义 |
|---|---|
| `%realtime_time%` / `%realtime_datetime%` | 配置格式的本地时间 |
| `%realtime_date%`、`%realtime_time_24h%`、`%realtime_time_12h%` | 日期 / 24h / 12h |
| `%realtime_hour%`、`%realtime_minute%`、`%realtime_second%` | 时分秒 |
| `%realtime_timezone%`、`%realtime_offset%`、`%realtime_iso%` | 时区与偏移 |
| `%realtime_timestamp%`、`%realtime_timestamp_millis%` | Unix 时间戳 |
| `%realtime_bcd%`、`%realtime_bcd_bits%` | 六位十进制数字 / 24 位 BCD |
| `%realtime_redstone_enabled%`、`%realtime_device_count%` | 红石模块状态 |

完整清单见 [PlaceholderAPI 集成](development/placeholderapi.md)。

## 10. 常见问题

**Q：为什么 `device place`（旧名 `set`）要我先把设备禁用？**
运行中的设备每 tick 只写"当前槽位表"，旧槽位不会被它清掉——改了坐标就会留下一个永久通电的红石块或一个讲台。
禁用后槽位会被清零，这时改坐标才干净。

**Q：能改设备的编码或分辨率吗？**
不能。16 个 BCD 槽位和 4 个讲台是两套布局，改编码等于换设备：`remove` 旧的，`create` 新的。

**Q：为什么 analog 的灯在没人的时候不动？**
讲台是方块实体，比较器读数依赖区块 ticking。有人靠近时区块恢复 ticking，显示会立刻对齐（可能跳过中间几秒，这是正常的）。

**Q：玩家能打开讲台翻页，会不会把时钟弄坏？**
会短暂显示错，但插件每 30 秒做一次全量复查，会把页码写回去。

**Q：为什么我的 demo 退役后队列里有"需人工处理"？**
该位置被地形/玩家方块占着（例如 demo 建在树林里）。清掉那几格后 `cleanup retry`，或者确认要保留它们就 `cleanup dismiss`。

**Q：在新服务器上建了设备/demo，但世界里一个方块都没出现？**
九成是**红石模块没开**（新装默认 `modules/redstone.yml` → `enabled: false`）。这时命令会成功返回、设备也进了列表，
但插件从不写方块：`/rtime info` 会显示 `红石输出模块：false`，`device demo`/`create`/`set`/`enable` 会额外提示一行。
改成 `enabled: true` 后 `/rtime reload` 即可，设备会立刻开始输出（demo 的展示方块也会补上）。

**Q：`device place` 报"该坐标已被其他设备或清理任务占用"？**
那格属于另一台设备、另一台设备的清理任务，或者**同一台设备的另一个槽位**。换个位置，或先把占用的设备退役并等清理跑完。

**Q：设备坐标能写到任意地方吗？**
能，但建议配 `limits.allowed-areas` 限定范围，并只把 `realtime.device.edit` 给可信的人——这条权限本质上是"在指定坐标放方块"。

**Q：集群/多服要注意什么？**
设备按世界名归属；同一个世界不要被两个服务器同时写（同一份存档只能一个实例在线）。Folia 上设备更新走 region 调度，不需要额外配置。
