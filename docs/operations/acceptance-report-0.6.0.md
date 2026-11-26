# RealTime 0.6.0 真机验收报告

> 状态：进行中（Paper 已完成，Folia 由 PR CI 的 smoke job 覆盖）
> 最后更新：2026-11-26
> 被测构建：`RealTime-0.6.0-SNAPSHOT.jar`，sha256 `27463b32ca683392c1991595a55c4aaac81f16ba76ea82dba15244bb73cc4f01`

## 环境

| 项 | 值 |
|---|---|
| 服务端 | Paper 26.3（MCSManager 实例 `716c2fb712154c36ba5ab0f1480d3f87`）|
| Java | 25 |
| 插件 | `plugins/RealTime-0.6.0-SNAPSHOT.jar` |
| 数据 | `data/devices.yml` schema 7（由 0.5.0 的 v6 文件自动迁移，两台旧钟保留）|
| 测试区 | `world`，`forceload 40000 -64`，y = 86、z = -45 |

## 结果

| # | 用例 | 期望 | 实测 |
|---|---|---|---|
| 1 | `/rtime help` | 一页列出全部动词 | ✅ 10 行，`create` 行含全部选项 |
| 2 | `/rtime` | 时间 + 每台钟一行 | ✅ `钟（2）` / `禁用 mc · HHmm · 4 个讲台 · 4 个未放置` |
| 3 | `/rtime info`（已移除动词）| 一行"已移除，改用 …" | ✅ `info 这个写法已经移除，改用 /rtime 或 /rtime <钟名>。` |
| 4 | `/rtime demo`（已移除动词）| 同上 | ✅ `demo 这个写法已经移除，改用 /rtime create <名字>。` |
| 5 | 控制台 `/rtime create test` | 拒绝并说明要玩家视角 | ✅ `建钟要在游戏里执行：站到目标位置再输入命令…` |
| 6 | `/rtime place mc2 0 40060 86 -45` … 槽 0–5 | 每个槽位**立刻**立起讲台 | ✅ 6 条"讲台已立起"，随后 `execute if block … lectern` 六个位置全部命中 |
| 7 | `/rtime mc2` | 逐槽坐标 + 操作提示 | ✅ 6 行 `槽 N：x, y, z` + `/rtime on mc2 · off · place · remove` |
| 8 | `/rtime on mc2`（全部槽位已摆放）| 开机 | ✅ `mc2 已开机。`；`status`：`变化 13`、`讲台：放置 6 · 书页写入 6` |
| 9 | `/rtime off mc2` | 讲台保留、书移除、输出 0 | ✅ 提示"讲台保留，输出归零"，`execute if block … lectern` 仍命中，`书页写入` 6 → 12 |
| 10 | `/rtime place mc 0 …`（槽位还有 3 个未放置时）| `on` 被拒绝并报数量 | ✅ `还有 4 个槽位没有位置：用 /rtime place <名字> <槽位> 摆好再开机。` |
| 11 | `/rtime place mc 9 …` | 槽位越界 | ✅ `槽位超出范围（0–3）：9` |
| 12 | `/rtime nope` | 没有这台钟 | ✅ `没有这台钟：nope （/rtime 看全部）` |
| 13 | `/rtime remove mc2` | 入队，清理跑完 | ✅ `已拆除，清理任务入队：d55b1a68…`；数秒后 `清理队列是空的。` |
| 14 | 清理后世界状态 | 六个槽位回到空气 | ✅ `execute if block 40060/40064/40070 86 -45 air` 全部命中 |
| 15 | `/rtime status` | 版本/时区/模块 + 计数器 | ✅ `版本：0.6.0-SNAPSHOT`、`红石输出模块：true`、`输出检查/变化/无变化/非法方块/写入失败`、`讲台/书页` |
| 16 | 旧数据（v6，`encoding: analog`）| 正常加载，不影响 | ✅ 两台旧钟迁移后可用；`skippedLegacyEntries` 为空 |
| 17 | 手工塞入一条 `encoding: bcd` 旧钟 + `/rtime reload` | 跳过、日志、`status` 计数、不出现在 `/rtime` | ✅ `/rtime` 仍显示"还没有任何钟"；`status` 追加 `有 1 台旧版 BCD 钟已不再加载…：data/devices.yml: devices[0] (legacy-bcd)`；删掉该条目再 reload 后该行消失 |
| 18 | 数据文件被自动迁移 | 写回 schema 7 | ✅ `schema-version: 7`、设备/队列结构保留 |

## 未覆盖（交由玩家或 CI）

- **游戏内 `create`**：控制台没有世界/朝向，命令按设计拒绝；`create` 的三种模式（带展示方块 / `nodisplay` / `manual`）
  由单元测试覆盖（`RealTimeCommandHandlerTest`），需要玩家在游戏内复核手感。
- **Folia**：由 CI 的 `smoke (folia)` / `cleanup-smoke (folia)` / `placeholderapi-smoke (folia)` 覆盖（三者均通过）。
  本机 Folia 实例是**在用的服务端**（AuthMe / Essentials / 十余个插件），不部署测试构建。
- **Tab 补全**：`CommandCompletion` 有单元测试（动词、钟名、槽位号、`create` 选项），客户端实际观感需玩家确认。

## 收尾

- 测试区已 `forceload remove`，测试钟已全部拆除，`/rtime` 回到"还没有任何钟"。
- 测试服保留 `0.6.0-SNAPSHOT` 构建，便于继续验收。
