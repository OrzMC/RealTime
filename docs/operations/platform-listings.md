# Platform listings (Hangar / Modrinth)

> 状态：生效中｜最后更新：2026-11-26
>
> 本文保存 **Hangar** 与 **Modrinth** 项目页的权威文案与元数据。改文案先改这里，再同步到平台页面，
> 避免两个平台各写一版、与 `docs/usage.md` 漂移。发布流程见 [`../release/process.md`](../release/process.md)，
> 平台凭据与排错见 [`../release/distribution.md`](../release/distribution.md)。

## 两个平台的元数据（应保持一致）

| 项 | 值 |
|---|---|
| 名称 | `RealTime` |
| 一句话简介 | Real-world wall-clock time as redstone: one lectern per digit, comparator level = digit + 1, on Paper and Folia. |
| 分类 | Hangar：`gameplay`（Hangar 没有 "management"，见下方分类枚举）；Modrinth：`management` + `technology` + `utility`（最多 3 个） |
| 关键词（Hangar）| `redstone, time, clock, utility, folia` |
| 许可证 | MIT |
| 源码 | <https://github.com/OrzMC/RealTime> |
| 问题追踪 | <https://github.com/OrzMC/RealTime/issues> |
| 文档 | <https://github.com/OrzMC/RealTime/blob/main/docs/usage.md> |
| 更新方式 | `/rtime update` 检查新版本，`/rtime update download` 下载到 `plugins/update/`，重启生效（校验 sha256/sha512，不热替换、不自动重启）|
| 服务端 | Paper 26.3+ / Folia 26.3+，`folia-supported: true` |
| 依赖 | 无（PlaceholderAPI 可选） |
| 发布时自动同步 | `scripts/hangar-page.sh`（Hangar 主页）+ `scripts/modrinth-page.sh`（Modrinth 一句话简介 + 长描述） |
| 只能网页改 | Hangar：名称、简介、分类、关键词、链接；Modrinth：名称、slug、图标、画廊、审核状态 |

> **同步机制**：粗体长描述与一句话简介都以本文件为准，`release.yml` 在每次正式发布后自动推送
> （`continue-on-error`，失败不影响发版；本地可手动执行两个脚本）。Hangar 的 `PATCH /api/v1/pages/editmain/{project}`
> 需要 `edit_page`；Modrinth 的 `PATCH /v2/project/{id}` 需要 `WRITE_PROJECT`，并且**字段名是 `description`**
> （项目模型里的一句话简介），`summary` 会被静默忽略。
>
> Hangar 的项目信息（名称/简介/分类/关键词/链接）没有 API：`PATCH/PUT /api/v1/projects/{slug}` 都是 405，
> `/projects/{slug}/settings` 也不存在（404），只能网页改。

### 网页字段的核对记录

2026-11-26 全部核对完成（`scripts/platform-audit.sh` 全部 `ok`）：

| 平台 | 字段 | 结果 |
|---|---|---|
| Hangar | Description / Category / Keywords | 已在网页更新：讲台版一句话、`Gameplay`、`redstone, time, clock, utility, folia` |
| Hangar | 主页（长描述）| `scripts/hangar-page.sh` 推送，与本文逐字一致 |
| Modrinth | `description` / `body` | `scripts/modrinth-page.sh` 推送，读回验证一致（112 / 3569 字符）|
| Modrinth | 版本列表 | 只剩 `0.4.0` / `0.5.0` / `0.6.0`，重复上传的版本已删除 |

`scripts/platform-audit.sh` 现在会核对 Hangar 分类与**关键词**、主页长度、每个版本的频道/游戏版本/sha256，
以及 Modrinth 的简介、长描述、分类、许可证、链接、游戏版本与 loader；发版后跑一次即可确认两端没有漂移。

## 长描述（两个平台共用，Markdown）

````markdown
**RealTime** turns the real world clock into redstone. Give it a timezone and it keeps a set of blocks powered so that
your circuits know what time it is — no mob-clock, no daylight sensor drift, no manual resetting.

Built for **Paper and Folia** (Folia-safe: every block write goes through the region scheduler), written in Kotlin,
MIT licensed.

## One lectern per digit

| What it drives | Slots for `HHmm` / `HHmmss` |
|---|---|
| One lectern per digit, comparator level = digit + 1 | 4 / 6 |

A comparator reads a lectern's page in a single int write, so `HHmmss` needs six lectern lines — no bit decoding, no
decoder delay, and a display that actually matches the clock. (An earlier BCD mode with 16/24 parallel bit lines was
dropped for exactly that reason.)

## Features

- Real-world time with an explicit `ZoneId` (default `Asia/Shanghai`); `/rtime` and `/rtime reload`.
- Minute **or second** resolution (seconds by default), tick-level change detection, diff-only chunk-batched writes.
- Two commands to read: `/rtime` (time + every clock) and `/rtime <name>` (one clock in detail). Five to write:
  `create`, `on`, `off`, `place`, `remove` — with `status`, `cleanup` and `help` for everyone else.
- In-game authoring: `/rtime create clock` builds at the block you aim at, grows the slots in the direction you face,
  places the comparator / dust / lamp row per digit and starts enabled. `manual` skips the display and leaves every slot
  unpositioned; `/rtime place clock 3` then **puts the lectern there immediately**, facing you, so the layout is
  reviewable instead of a silent coordinate change.
- Durable lifecycle: turning a clock off keeps its lecterns and drops the book (comparator reads 0), `remove` queues a
  crash-safe cleanup job, `retry` and `dismiss` let an operator finish a cleanup without editing YAML.
- Never overwrites blocks it did not place: anything foreign is reported as `NEEDS_ATTENTION`, never silently stolen.
- Diagnostics behind `/rtime status`; optional PlaceholderAPI placeholders.
- Folia supported (`folia-supported: true`), no dependencies except optional PlaceholderAPI.

## Quick start

```
/rtime                                   # current time + every clock
/rtime create clock                      # stand where you want it, face the way it should extend
/rtime clock                             # that clock: slots, state, next steps
/rtime off clock                         # book removed, comparator reads 0, lecterns stay
/rtime create machine manual             # no lecterns yet; position every slot by hand
/rtime place machine 0                   # put the lectern of digit 0 where you look
/rtime on machine                        # only once every slot has a position
/rtime remove clock                      # queues cleanup of the blocks it placed (clock must be off)
/rtime cleanup                           # what is left to clean up
```

`/rtime help` prints the whole surface in one page; `/rtime place clock 3` repositions a single slot (the clock must be
off). Full command reference, permissions, wiring diagrams, the cleanup queue and an
FAQ live in the [usage guide](https://github.com/OrzMC/RealTime/blob/main/docs/usage.md).

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
| Categories | `management` `technology` `utility` | ✅ |
| Source / Issues / Wiki | 上表三个链接 | ✅ |
| License | MIT | 已有 |
| Project type | `plugin`（API v2 显示成 `mod` 属正常） | 已有 |
| Loaders / Game versions | `paper` + `folia` / `26.3` | 已有 |

> 注意：Modrinth 的新项目**上传第一个版本后必须点 `Submit for review`**，否则一直停在 draft、对外 404。
> 提交后状态为 **Under Review**（API `status: processing`），审核期间版本上传照常工作；通过后匿名 API 才返回 200。
> 2026-11-25 现状：`0.4.0` 已上传、项目已提交审核（Under Review）。

### Hangar（`OrzMC/RealTime`）

网页：<https://hangar.papermc.io/OrzMC/RealTime/settings>。

**分类与许可证没有 API**（v1 的 `/projects` 只有 GET，`PATCH`/`PUT` 返回 405），只能在网页改。分类是固定枚举，
没有 "management"：

`Admin Tools` · `Chat` · `Developer Tools` · `Economy` · `Gameplay` · `Games` · `Protection` · `Role Playing` · `World Management` · `Miscellaneous`

RealTime 是红石/技术类工具而不是管理后台，**选 `Gameplay`**（`misc` 只是默认值）。

**主页（长描述）可以通过 API 设置**：`PATCH /api/v1/pages/editmain/{project}`，body `{"content": "<markdown>"}`，
需要项目级 `edit_page` 权限；`GET /api/v1/pages/main/{project}` 可回读核对。仓库里用脚本执行：

```bash
HANGAR_API_TOKEN=… scripts/hangar-page.sh      # 从本文档的 markdown 块推送并核对
```

发布工作流在 Hangar 发布之后会自动跑这一步（失败不影响发版），所以主页内容始终与本仓库一致。

| 字段 | 值 |
|---|---|
| Category | `gameplay`（当前是 `misc`，需网页改） |
| License | `MIT` |
| Description | `Real-world wall-clock time as redstone: one lectern per digit, comparator level = digit + 1, on Paper and Folia.` |
| Main page content | 上面的长描述 —— **可用 `scripts/hangar-page.sh` 自动写入**（已写入，2,798 字符） |
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

Modrinth（`status: processing`，`in_review: true`，`queued` 2026-11-25T14:21Z）：

| 项 | 实际 | 结论 |
|---|---|---|
| id / slug / 标题 | `pRGtDpaD` / `realtimesignal` / `RealTime` | ✅ |
| 项目类型 | `plugin`（v2 显示 `mod` 属正常） | ✅ |
| 简介 / 长描述 | 121 字符 / 2,799 字符 | ✅ |
| 分类 | `management`、`technology`、`utility` | ✅ |
| 许可证 | `MIT` | ✅ |
| loader / 游戏版本 | `paper` + `folia` / `26.3` | ✅ |
| 链接 | source / issues / wiki | ✅ |
| 图标 | 已上传（96px webp） | ✅ |
| 截图 `gallery` | `[]` | ⚠️ 可选，建议后续补 1–2 张 |
| 版本 `0.4.0` | 存在（版本 id `kKzydngL`），但**审核中版本接口返回 404** | ⏳ 审核通过后才能核对文件 sha256 |

> 审核期间 Modrinth 把版本对象也藏起来（`/v2/version/<id>` 与 `/v2/project/<id>/version` 都是 404，即使令牌有
> `READ_PROJECT`）。所以"上传的 jar 是否与 GitHub Release 一致"要等公开后再跑一次审计 —— Hangar 那份已经确认
> sha256 `8531becc…` 逐字节一致。

Hangar：

| 项 | Hangar 实际 | 结论 |
|---|---|---|
| 名称 / 许可证 / 简介 / 链接 / 平台 / 关键词 | `RealTime` / `MIT` / 120 字符 / Source+Issues+Wiki / `PAPER` / `redstone,time,clock,utility,folia` | ✅ |
| 分类 | `misc` | ❌ 应为 `gameplay` |
| 长描述（`mainPageContent`） | 2,798 字符（`scripts/hangar-page.sh` 写入并已核对） | ✅ |
| `0.4.0` 资产 | 频道 `Release`、`PAPER 26.3`、2,136,461 B、sha256 `8531becc…`（与 GitHub Release 一致） | ✅ |
| `0.4.0` 版本描述 | `MVP Version` | ⚠️ 建议改成 v0.4.0 的 changelog 摘要 |

Modrinth 项目此时处于 Under Review，令牌无 `READ_PROJECT` → 无法自动核对，待项目公开或临时授权后补。

## 版本页文案

`0.4.0` 在 Hangar 上的 version description 目前是 `MVP Version`，可改成对应 `CHANGELOG` 段落的摘要；
**发布工作流不会更新已存在版本的描述**，只有新版本才会带上 `CHANGELOG.md` 对应段落。
