# Hangar and Modrinth Publishing

> 状态：**待启用**（截至 `v0.4.0` 仅发布到 GitHub Releases；工作流、校验脚本与演练通道已就绪）
> 最后更新：2026-11-25

本文是把 RealTime 同时发布到 **Hangar**（PaperMC 官方插件平台）和 **Modrinth** 的操作手册。
发布流程本身见 [`process.md`](process.md)，日常迭代流程见
[`../development/workflow.md`](../development/workflow.md)。

## 0. 当前状态

| 平台 | 工作流支持 | 凭据 | 结果 |
|---|---|---|---|
| GitHub Releases | 已启用（`RELEASE_PUBLISH_ENABLED=true`，`release` 环境审批） | `github.token` | ✅ 已发布 `v0.1.0`–`v0.4.0` |
| Hangar | 代码就绪，`HANGAR_PROJECT_ID` 未配置 → 跳过 | 缺 `HANGAR_PROJECT_ID` + `HANGAR_API_TOKEN` | ⏸ 未发布 |
| Modrinth | 代码就绪，`MODRINTH_PROJECT_ID` 未配置 → 跳过 | 缺 `MODRINTH_PROJECT_ID` + `MODRINTH_TOKEN` | ⏸ 未发布 |

任何一项未配置时，工作流**跳过而不是失败**（日志里会写明 `skipping ... upload`），所以启用过程可以分批进行：
先配 Hangar，再配 Modrinth，各自独立验证。

## 1. 一次性准备清单

| 名称 | 类型 | 作用域 | 值 |
|---|---|---|---|
| `HANGAR_PROJECT_ID` | repository **variable** | Release 工作流 | Hangar 项目 **slug**（不是显示名） |
| `HANGAR_API_TOKEN` | **environment secret** | `release` 环境 | Hangar API key，只勾 `create_version` |
| `MODRINTH_PROJECT_ID` | repository **variable** | Release 工作流 | Modrinth 项目 **slug 或 ID** |
| `MODRINTH_TOKEN` | **environment secret** | `release` 环境 | Modrinth PAT，只勾 `CREATE_VERSION` |

设置位置：仓库 Settings → Secrets and variables → Actions；环境密钥在 `release` 环境内设置。
**不要**把它们写进仓库文件、Gradle 属性、`.env` 或日志。

> 工作流读取方式：项目 id 来自 `vars.*`，令牌来自 `secrets.*`，且只在受保护的 `release` job 内可见。
> 因此 PR 工作流永远拿不到发布凭据。

## 2. 平台元数据（两端共用事实）

| 项 | 值 |
|---|---|
| 插件名 | `RealTime` |
| 入口 | `cn.orzmc.realtime.paper.RealTimePlugin` |
| API 线 | `26.3` |
| 服务端 | Paper 26.3+，声明 `folia-supported: true` |
| 依赖 | 无（PlaceholderAPI 为可选软依赖） |
| 命令 / 权限 | `/rtime`（含 `/rtime update`），根权限 `realtime.admin`，更新权限 `realtime.update` |
| 版本类型 | 稳定 tag → release；带 `-` 的 tag → 预发布 |
| 同一个 jar | GitHub Release、Hangar、Modrinth 三处只能是同一份（校验和一致） |

## 3. Hangar

### 3.1 创建项目

> Hangar 的公开 API **没有创建项目的端点**（`POST /api/v1/projects` 返回 405），项目只能在网页建。
> 同理 Modrinth 也需要先用有效 PAT 才能调 `POST /v2/project`，因此首次都建议在网页完成。

1. 用维护者账号登录 <https://hangar.papermc.io>，进入 Author dashboard → **New project**。
2. 名称写 `RealTime`；slug 建议 `RealTime`（即 `hangar.papermc.io/<owner>/RealTime`）。
   **slug 就是 `HANGAR_PROJECT_ID` 的值**，显示名改了不影响。
3. Category 选 `management`（工具/自动化类）；Platform 选 **Paper**。
4. 简介/长描述/图标/源码链接（`https://github.com/OrzMC/RealTime`）一次性填好；
   描述里注明"同时支持 Folia"，并把 [`docs/usage.md`](../usage.md) 的核心内容概括两三句。
5. 保持默认频道 `Release` / `Snapshot` 存在 —— 工作流把稳定版发到 `Release`，预发布不发 Hangar。

### 3.2 关于平台与版本号

- `hangar-publish-plugin` 0.1.4 只暴露 `PAPER` / `WATERFALL` / `VELOCITY` 三个平台；
  **Folia 不是独立平台**，它的支持通过 `plugin.yml` 的 `folia-supported: true` 表达（本项目已声明）。
- 平台版本必须写 Hangar 认识的标签：`26.3`（2026-11-25 已通过 API 确认存在于 Hangar 的 PAPER 版本列表）。
  升级 API 线时同时改 `realtime-paper/build.gradle.kts` 的默认 `hangarPaperVersions`、
  工作流里的 `-PhangarPaperVersions`、以及 `scripts/distribution-check.sh` 的 `API_VERSION`。
- Hangar 会读取 jar 内 `plugin.yml` 校验 `api-version`；不匹配会在上传阶段直接报错。

### 3.3 创建令牌

1. <https://hangar.papermc.io/auth/settings/api-keys> → **Create API key**。
2. 名称写 `RealTime release (GitHub Actions)`；权限只勾**该项目**的 `create_version`
   （不要给 `edit_project`、`edit_version`、`delete_version` 等）。
3. 复制一次性的 key，写入 `release` 环境的 `HANGAR_API_TOKEN`，并同时设置变量 `HANGAR_PROJECT_ID`。

## 4. Modrinth

### 4.1 创建项目

1. 登录 <https://modrinth.com> → 头像 → **Creator dashboard** → **New project**（创建后才可以用 `POST /v2/project` 之类的 API 维护）。
2. Type: **Plugin**；Loaders: 勾 **Paper** 与 **Folia**；Game versions: 勾 **26.3**。
3. Slug（URL 末段）就是 `MODRINTH_PROJECT_ID`；用项目 ID 也可以。
4. 描述、图标、链接（源码 / 文档）填好；License 与仓库 `LICENSE` 保持一致。

工作流上传的 payload 固定为：

```json
{
  "game_versions": ["26.3"],
  "loaders": ["paper", "folia"],
  "version_type": "release | beta",
  "featured": false,
  "status": "listed",
  "file_parts": ["plugin"],
  "primary_file": "plugin",
  "dependencies": []
}
```

> `dependencies` **是必填**（哪怕为空数组）。2026-11-26 的 `v0.5.0` 发布就栽在这里：
> `400 invalid_input — missing field 'dependencies'`，GitHub Release 与 Hangar 都已成功、只有 Modrinth 失败。
> `scripts/distribution-check.sh` 现在会断言 payload 里有这个字段。

即：**一个 jar 同时标注 Paper 与 Folia**；稳定 tag → `release`，`-rc`/`-beta` 之类 → `beta`。
changelog 直接使用 `CHANGELOG.md` 对应版本的段落（与 GitHub Release 完全一致）。

### 4.2 创建令牌

1. <https://modrinth.com/settings/pats> → **Create a PAT**。
2. 权限最小集：

| 权限 | 上传是否需要 | 作用 |
|---|---|---|
| `CREATE_VERSION` | **必须** | `POST /v2/version` 的唯一要求 |
| `READ_PROJECT` | 否（**但强烈建议**） | 让发布前的预检能读到项目、区分「draft / id 写错 / 令牌坏了」 |
| `WRITE_PROJECT` | 否 | 仅当你还要用 API 改项目资料 |
| `USER_READ` | 否 | 发布流程不需要；给了它 `GET /v2/user` 才是 200 |

   > 拿掉 `READ_PROJECT` 后，`scripts/distribution-check.sh` 只能报「无法验证」而不是确定结论 —— 上传照样能成功，
   > 但发布前就没有任何"令牌/项目对不对"的检查了。这是唯一需要权衡的一项。

3. 写入 `release` 环境的 `MODRINTH_TOKEN`，变量 `MODRINTH_PROJECT_ID` 填 slug 或项目 ID。

> 新建项目后还需要在项目页点 **Submit for review**：只上传版本、不提交审核时，项目会一直停留在 draft，对外仍然 404。

### 4.1.1 审核阶段（draft → Under Review → approved）

| 阶段 | 项目页显示 | 公开 API | 说明 |
|---|---|---|---|
| draft | Draft | 404（对团队也只在登录后可见） | 还没提交审核 |
| 提交后 | **Under Review**（API `status: processing`） | 404 | Modrinth 版主审核中，通常几分钟到一天 |
| 通过 | Approved | **200** | 项目公开，出现在搜索/列表里 |
| 被拒 | Rejected（附审核意见） | 404 | 按意见改完可重新提交 |

- **审核期间可以继续上传版本**（发布工作流照常工作），新版本会跟随项目一起进入审核流程，不会报错。
- 项目公开与否**不影响**发布工作流：上传只要求 `CREATE_VERSION` 与项目 id 正确，状态由平台处理。
- 被拒时不要重发同一个版本号（Modrinth 会返回版本号冲突）：按意见改完项目信息，用下一个 patch 版本发。
- 判定"是否已公开"只需一条匿名请求，不需要令牌：

  ```bash
  curl -s -o /dev/null -w '%{http_code}\n' https://api.modrinth.com/v2/project/<项目ID>
  # 200 = 已公开；404 = 仍是 draft / 审核中 / 被拒
  ```

## 4.3 把凭据配进 GitHub（两种方式）

### 方式 A：网页

| 名称 | 位置 |
|---|---|
| `HANGAR_PROJECT_ID`、`MODRINTH_PROJECT_ID` | Settings → Secrets and variables → Actions → **Variables** 标签 → New repository variable |
| `HANGAR_API_TOKEN`、`MODRINTH_TOKEN` | Settings → **Environments** → `release` → Environment secrets → Add secret |

> 项目 id 放**仓库级变量**（不是环境变量）：演练走的是 `release-dry-run` 环境，只有仓库级 `vars.*` 在演练里也能读到。
> 令牌放**`release` 环境密钥**：演练环境永远看不到它们，PR 工作流也拿不到。

### 方式 B：命令行（推荐，令牌不落 shell 历史）

```bash
cd /mnt/e/dev/OrzMC/tools/RealTime

# 项目 id → 仓库级变量
gh variable set HANGAR_PROJECT_ID --body "RealTime"      # 换成你的 slug
gh variable set MODRINTH_PROJECT_ID --body "realtime"    # 换成你的 slug 或项目 ID

# 令牌 → release 环境密钥（从文件读，避免出现在历史/参数里）
gh secret set HANGAR_API_TOKEN  --env release < ~/hangar.token
gh secret set MODRINTH_TOKEN   --env release < ~/modrinth.token
shred -u ~/hangar.token ~/modrinth.token 2>/dev/null || rm -f ~/hangar.token ~/modrinth.token

# 核对
gh variable list
gh secret list --env release
```

配完再跑一次演练，摘要里两个平台应从"没有 project id"变成实际项目名。

### 令牌校验（配之前或配之后都可以）

```bash
# 本地直接验（不打印令牌）：
HANGAR_API_TOKEN="$(cat ~/hangar.token)" \
MODRINTH_TOKEN="$(cat ~/modrinth.token)" \
HANGAR_PROJECT_ID=RealTime MODRINTH_PROJECT_ID=realtime \
  scripts/distribution-check.sh --remote --check-tokens

# 等价的手工检查（Hangar 用 400 表示 key 无效，不是 401）：
curl -s -X POST -o /dev/null -w '%{http_code}\n' -H "Authorization: $HANGAR_API_TOKEN" \
  https://hangar.papermc.io/api/v1/authenticate      # 200 = 有效
curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: $MODRINTH_TOKEN" \
  https://api.modrinth.com/v2/user                     # 200 = 有效
```

## 4.4 项目页自动同步（发布时）

项目页的一句话简介与长描述都以 [`docs/operations/platform-listings.md`](../operations/platform-listings.md) 为唯一源，
`release.yml` 在正式发布后自动推送（`continue-on-error: true`，失败不影响发版）：

| 平台 | 脚本 | 端点 | 字段 |
|---|---|---|---|
| Hangar | `scripts/hangar-page.sh` | `PATCH /api/v1/pages/editmain/{project}`（需 `edit_page`）| 主页（长描述）|
| Modrinth | `scripts/modrinth-page.sh` | `PATCH /v2/project/{id}`（需 `WRITE_PROJECT`）| `description`（一句话简介）+ `body`（长描述）|

两个坑：

- Modrinth 的**一句话简介字段名是 `description`**，传 `summary` 会被静默忽略（HTTP 仍返回 204，看起来成功了）。
  另外项目审核中时，平台偶尔会继续展示旧文案——脚本读回比对，不一致会 `warn` 而不是假装成功。
- Hangar 的项目信息（名称/简介/分类/关键词/链接）**没有** API：`PATCH/PUT /api/v1/projects/{slug}` 均 405，
  只能网页改；本文与 `platform-listings.md` 只会同步主页。

`scripts/distribution-check.sh` 会断言工作流里这两个同步步骤都在。

## 5. 校验与演练

### 5.1 本地/CI 都能跑的静态检查

```bash
scripts/distribution-check.sh                       # 离线：plugin.yml 元数据 + 工作流接线 + payload 预览
scripts/distribution-check.sh --remote              # 追加：Modrinth 的 26.3 / paper / folia，以及已配置项目的可见性
scripts/distribution-check.sh --remote --check-tokens   # 追加：校验刚创建的令牌是否真的被平台接受
scripts/distribution-check.sh --remote --version 0.5.0
```

`--check-tokens` 会读取环境里的 `HANGAR_API_TOKEN` / `MODRINTH_TOKEN` 打一次身份接口，**只看状态码，不读响应体、不打印令牌**：

| 平台 | 请求 | 有效 |
|---|---|---|
| Hangar | `POST /api/v1/authenticate?apiKey=<key>` | `200`（无效/缺失 = `400`） |
| Modrinth | `GET /v2/project/<MODRINTH_PROJECT_ID>` + `Authorization: <token>` | `200`（无效 = `401`，项目不属于该令牌 = `404`） |

> **不要用 `GET /v2/user` 验 Modrinth 令牌**：那条路由要求 `USER_READ` 权限，而发布用的 PAT 故意不含它，
> 于是一个**完全有效**的令牌也会返回 `401 invalid_credentials`（这条坑踩过：花了半个多小时）。项目路由只需要项目级权限，
> 对 **draft 项目**也返回 200（只要令牌所属账号在团队里），所以它同时验证了"令牌有效"和"有权访问该项目"。

> Hangar 的 `/authenticate` **只认 `apiKey` 查询参数**，不带 `Authorization` 头也能过；而且它的 200 响应体是一个
> **30 天有效的 JWT**——任何脚本都不要把响应体打到日志里。

它会在以下情况失败（这些都不是"配置没做"，而是真问题）：`plugin.yml` 丢了 `folia-supported`、
payload 少了 `folia` loader、版本线不一致、工作流把凭据写成 `vars.*` 泄漏到 PR 工作流等。
离线模式已并入 `./scripts/verify.sh`，所以这类漂移会在 PR CI 阶段被发现。

### 5.2 GitHub 侧演练（不发版、不上传）

```bash
gh workflow run release.yml -f ref=main -f version=0.5.0 -f dry_run=true
gh run watch
```

演练会完整执行：**版本解析 → 发布元数据检查 → 构建 + jar 检查 → 分布预检（含远程 API）→ 摘要**，
但**不**创建 GitHub Release、**不**上传 Hangar/Modrinth。摘要表会写明每一步是 skipped 还是 attempted。

> 演练的 `publish` job 跑在 **`release-dry-run`** 环境里（无审批、无密钥、无分支限制），因为 `release` 环境被锁在
> `v*` tag 上、并且持有发布令牌。这样演练既能执行预检，又拿不到任何发布凭据。

> `dry_run=true` 时跳过 tag/CHANGELOG 严格校验（因为版本还没写进 CHANGELOG），改用最新一段做 notes 预览；
> 真实 tag 运行时仍然强校验。

### 5.3 首次真实发布

1. 先按 [`process.md`](process.md) 完成 changelog（含**升级须知**）、本地构建核验、合并、打 tag。
2. tag 推送后工作流依次：GitHub Release → Hangar（稳定版）→ Modrinth（稳定版或 beta）。
3. 三个动作各自独立：Hangar 失败不会阻止 Modrinth（但会让整个 run 标红）。
4. 发布后核对三处资产的 sha256 一致（`RealTime-X.Y.Z.jar`），并记录到 `process.md` 的发布历史表。

## 6. 排错

| 现象 | 原因 | 处理 |
|---|---|---|
| `skipping Hangar upload` / `skipping Modrinth upload` | 项目 id 或令牌为空 | 检查 `vars.*` 与 **`release` 环境**的 secret（不是仓库级） |
| Hangar `401` | Hangar `400`（含身份接口） | 令牌无效/过期/带了引号或换行 | 重新创建 API key，只勾 `create_version`；`--check-tokens` 先本地验 |
| Hangar `403` / `project not found` | `HANGAR_PROJECT_ID` 填了显示名，或令牌没有该项目权限 | 改成**slug**；确认 key 的 scope 属于该项目 |
| Hangar 报平台版本不支持 | `-PhangarPaperVersions` 与 Hangar 标签不一致 | 用 Hangar 支持的 MC 版本标签（当前 `26.3`） |
| Modrinth `401` | PAT 缺 `CREATE_VERSION` 或已撤销 | 重建 PAT |
| Modrinth `404`（匿名请求） | 项目是 **draft**（还没上传任何版本） | 正常；第一次上传版本后即公开 |
| Modrinth `404`（带令牌） | 项目 id 写错，或令牌账号不在该项目团队里 | 用 dashboard 里的 ID/slug；确认账号 |
| 演练里 `404` 只报 info | 演练环境拿不到令牌，匿名看 draft 项目就是 404，无法区分"id 错"与"draft" | 正常；真实 tag 运行带令牌，会给出确定结论 |
| Modrinth `400` + `version_number` 冲突 | 该版本号已存在（重跑同一次发布） | 不要重跑上传步骤；补一个新 patch 版本，或先在平台删除草稿版本 |
| Modrinth 拒绝 loaders/game_versions | payload 与项目设置不一致 | 先在项目设置里允许 `folia` 与 `26.3` |
| 上传成功但平台上显示"Folia 不支持" | 只看 Hangar 平台标签 | 以 `plugin.yml` 的 `folia-supported` 为准；Hangar 侧在描述里写明 |
| run 在 `Publish protected release` 上 3 秒失败、**没有任何步骤日志** | 环境分支策略拒绝了这次部署（`release` 只允许 `v*` tag，而这是一次 `main` 上的手动运行） | 用 `dry_run=true`（走 `release-dry-run` 环境），或推送真正的 tag |

## 7. 安全约束（硬性）

- 令牌**只**存在于 GitHub `release` 环境保护的 secret 中；不得写入仓库、issue、PR、日志、截图。
- 上传步骤只允许出现在 `publish` job 内（`environment: release` + 必需审批）。
- PR 工作流、`pull_request_target`、任何 `contents: write` + secret 组合都不得引入平台令牌。
- 平台令牌最小权限：Hangar `create_version`；Modrinth `CREATE_VERSION`。
- 怀疑泄漏时：立刻在平台撤销令牌 → 重建 → 更新 secret → 检查 Actions 日志是否打印过。

## 8. 启用顺序（建议）

1. 跑一次 `scripts/distribution-check.sh --remote`（离线部分已由 CI 覆盖）。
2. 配 Hangar（项目 + 变量 + 密钥）→ `gh workflow run release.yml -f version=<下一个版本> -f dry_run=true`，
   确认预检里 Hangar 项目可见。
3. 配 Modrinth（同上）→ 再跑一次演练，确认两个平台都 `attempted`/可见。
4. 下一个真实版本（例如 `v0.5.0`）按正常流程发；发布后核对三处校验和。
5. 全部确认无误后，把本文顶部状态改为"已启用"，并在 `process.md` 的发布历史里标注平台。
