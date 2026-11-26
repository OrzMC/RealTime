# Platform listings (Hangar / Modrinth)

> 状态：生效中｜最后更新：2026-11-25
>
> 本文保存 **Hangar** 与 **Modrinth** 项目页的权威文案与元数据。改文案先改这里，再同步到平台页面，
> 避免两个平台各写一版、与 `docs/usage.md` 漂移。发布流程见 [`../release/process.md`](../release/process.md)，
> 平台凭据与排错见 [`../release/distribution.md`](../release/distribution.md)。

## 两个平台的元数据（应保持一致）

| 项 | 值 |
|---|---|
| 名称 | `RealTime` |
| 一句话简介 | Real-world wall-clock time as redstone: BCD or analog (lectern) outputs, minute or second resolution, on Paper and Folia. |
| 分类 | Hangar：`management`；Modrinth：`technical`（Modrinth 的服务器插件分类里没有 utility，`technical` 最接近） |
| 许可证 | MIT |
| 源码 | <https://github.com/OrzMC/RealTime> |
| 问题追踪 | <https://github.com/OrzMC/RealTime/issues> |
| 文档 | <https://github.com/OrzMC/RealTime/blob/main/docs/usage.md> |
| 服务端 | Paper 26.3+ / Folia 26.3+，`folia-supported: true` |
| 依赖 | 无（PlaceholderAPI 可选） |

> Hangar 只能通过网页编辑项目信息（公开 API 没有项目编辑端点：`PATCH/PUT /api/v1/projects/{slug}` 均返回 405）。
> Modrinth 可以用 `PATCH /v2/project/{id}`（需要 `WRITE_PROJECT` 权限），2026-11-25 已用脚本写入下表内容。

## 长描述（两个平台共用，Markdown）

````markdown
**RealTime** turns the real world clock into redstone. Give it a timezone and it keeps a set of blocks powered so that
your circuits know what time it is — no mob-clock, no daylight sensor drift, no manual resetting.

Built for **Paper and Folia** (Folia-safe: every block write goes through the region scheduler), written in Kotlin,
MIT licensed.

## Two encodings

| Encoding | What it drives | Slots for `HHmm` / `HHmmss` |
|---|---|---|
| `bcd` | One real power source per BCD bit (`redstone_block` / air) | 16 / 24 |
| `analog` | One lectern per digit, comparator level = digit + 1 | 4 / 6 |

The analog mode exists because a comparator reads a lectern's page in a single int write: `HHmmss` needs six lectern
lines instead of twenty-four redstone lines.

## Features

- Real-world time with an explicit `ZoneId` (default `Asia/Shanghai`); `/rtime now`, `/rtime info`, `/rtime reload`.
- Minute **or second** resolution, tick-level change detection, diff-only chunk-batched writes.
- In-game authoring: `device create`, `device set`, and `device demo` — a one-command teaching build that lays out the
  whole clock (lectern / comparator / dust / lamp rows for analog, a lamp per slot for BCD).
- Digit grouping so neighbouring power sources do not couple into each other's circuits.
- Durable lifecycle: `disable` empties every slot, `remove` queues a crash-safe cleanup job, `retry` and `dismiss`
  let an operator finish a cleanup without editing YAML.
- Never overwrites blocks it did not place: anything foreign is reported as `NEEDS_ATTENTION`, never silently stolen.
- Diagnostics behind `device status`; optional PlaceholderAPI placeholders.
- Folia supported (`folia-supported: true`), no dependencies except optional PlaceholderAPI.

## Quick start

```
/rtime info                     # version, timezone, current time
/rtime device demo bcd minecraft:overworld 100 70 -20 x+     # teaching build, always second resolution
/rtime device list
/rtime device disable <uuid>    # empties the slots
/rtime device remove <uuid>     # queues cleanup of the blocks it placed
/rtime device cleanup list|retry|dismiss
```

Build your own with `device create` and place the target blocks with `device set` (the device must be disabled while
you edit its slots). Full command reference, permissions, wiring diagrams, the cleanup queue and an FAQ live in the
[usage guide](https://github.com/OrzMC/RealTime/blob/main/docs/usage.md).

## Requirements

- Paper **26.3+** or Folia 26.3+ (API line 26.3)
- Java 25
- Optional: PlaceholderAPI

## Links

- Source: [github.com/OrzMC/RealTime](https://github.com/OrzMC/RealTime)
- Documentation: [usage guide](https://github.com/OrzMC/RealTime/blob/main/docs/usage.md)
- Changelog: [CHANGELOG.md](https://github.com/OrzMC/RealTime/blob/main/CHANGELOG.md)
````

## 每个平台的填写位置

### Modrinth（`pRGtDpaD` / slug `realtimesignal`）

| 字段 | 值 | 状态 |
|---|---|---|
| Title | `RealTime` | ✅ 已通过 `PATCH /v2/project/{id}` 写入 |
| Summary | 上表的一句話簡介（121 字符） | ✅ |
| Description | 上面的长描述 | ✅ |
| Categories | `technical` | ✅ |
| Source / Issues / Wiki | 上表三个链接 | ✅ |
| License | MIT | 已有 |
| Project type | `plugin`（API v2 显示成 `mod` 属正常） | 已有 |
| Loaders / Game versions | `paper` + `folia` / `26.3` | 已有 |

> 注意：Modrinth 的新项目**上传第一个版本后必须点 `Submit for review`**，否则一直停在 draft、对外 404。
> 提交后状态为 **Under Review**（API `status: processing`），审核期间版本上传照常工作；通过后匿名 API 才返回 200。
> 2026-11-25 现状：`0.4.0` 已上传、项目已提交审核（Under Review）。

### Hangar（`OrzMC/RealTime`）

网页：<https://hangar.papermc.io/OrzMC/RealTime/settings>。公开 API 无编辑端点，只能手填：

| 字段 | 值 |
|---|---|
| Category | `management`（当前是 `misc`） |
| License | `MIT` |
| Description | `Real-world wall-clock time as redstone: BCD or analog (lectern) outputs, minute or second resolution, on Paper and Folia.` |
| Main page content | 上面的长描述 |
| Links | Source / Issue tracker / Wiki 填上表三个链接 |
| Tags | `redstone`、`time`、`utility`、`folia`（按平台可选清单挑最接近的） |
| Platform | `PAPER`，平台版本 `26.3`（已配置） |

> Hangar 只有 `PAPER` / `WATERFALL` / `VELOCITY` 三个平台标签，**Folia 不是独立平台**：体现在 `plugin.yml` 的
> `folia-supported: true` 与本页描述里那句 "Built for Paper and Folia"，不要在平台标签上找 Folia。

## 核对方法（每次发版后跑一次）

```bash
MODRINTH_TOKEN="$(cat ~/modrinth.token)" scripts/platform-audit.sh
```

它逐项比对"应该是的值"和平台上的实际值：

| 检查项 | Hangar | Modrinth |
|---|---|---|
| 名称 / id | ✅ 公开 API | 需要 `READ_PROJECT` |
| 分类 | `management` | 至少一个分类 |
| 许可证 | `MIT` | `mit` |
| 简介 / 长描述非空 | ✅ | ✅ |
| 链接 | ≥1 组 | `source_url` |
| 平台 / 游戏版本 | 声明 `26.3` | 含 `26.3` |
| loader | （Hangar 只有 PAPER） | 含 `paper` + `folia` |
| 每版本资产 | 频道、`26.3`、**sha256** | — |

Hangar 部分不需要任何凭据（公开 API 就能读）；Modrinth 的项目在 draft / 审核中时，令牌没有 `READ_PROJECT` 就读不到，
脚本会如实报 `cannot read the project` 而不是假装通过 —— 这时要么临时勾上 `READ_PROJECT` 再跑，要么以项目页截图为准。

**2026-11-25 审计结果**（Hangar `OrzMC/RealTime` + Modrinth `pRGtDpaD`）：

| 项 | Hangar 实际 | 结论 |
|---|---|---|
| 名称 / 许可证 / 简介 / 链接 / 平台 / 关键词 | `RealTime` / `MIT` / 120 字符 / Source+Issues+Wiki / `PAPER` / `redstone,time,clock,utility,folia` | ✅ |
| 分类 | `misc` | ❌ 应为 `management` |
| 长描述（`mainPageContent`） | 0 字符 | ❌ 待粘贴本文档的长描述 |
| `0.4.0` 资产 | 频道 `Release`、`PAPER 26.3`、2,136,461 B、sha256 `8531becc…`（与 GitHub Release 一致） | ✅ |
| `0.4.0` 版本描述 | `MVP Version` | ⚠️ 建议改成 v0.4.0 的 changelog 摘要 |

Modrinth 项目此时处于 Under Review，令牌无 `READ_PROJECT` → 无法自动核对，待项目公开或临时授权后补。

## 版本页文案

`0.4.0` 在 Hangar 上的 version description 目前是 `MVP Version`，可改成对应 `CHANGELOG` 段落的摘要；
**发布工作流不会更新已存在版本的描述**，只有新版本才会带上 `CHANGELOG.md` 对应段落。
