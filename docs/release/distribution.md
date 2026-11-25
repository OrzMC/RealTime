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
| 命令 / 权限 | `/rtime`，根权限 `realtime.admin` |
| 版本类型 | 稳定 tag → release；带 `-` 的 tag → 预发布 |
| 同一个 jar | GitHub Release、Hangar、Modrinth 三处只能是同一份（校验和一致） |

## 3. Hangar

### 3.1 创建项目

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

1. 登录 <https://modrinth.com> → 头像 → **Creator dashboard** → **New project**。
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
  "primary_file": "plugin"
}
```

即：**一个 jar 同时标注 Paper 与 Folia**；稳定 tag → `release`，`-rc`/`-beta` 之类 → `beta`。
changelog 直接使用 `CHANGELOG.md` 对应版本的段落（与 GitHub Release 完全一致）。

### 4.2 创建令牌

1. <https://modrinth.com/settings/pats> → **Create a PAT**。
2. 只勾 `CREATE_VERSION`（需要读取自身项目时再加 `READ_PROJECT`；不要给删除/编辑权限）。
3. 写入 `release` 环境的 `MODRINTH_TOKEN`，变量 `MODRINTH_PROJECT_ID` 填 slug 或项目 ID。

## 5. 校验与演练

### 5.1 本地/CI 都能跑的静态检查

```bash
scripts/distribution-check.sh                    # 离线：plugin.yml 元数据 + 工作流接线 + payload 预览
scripts/distribution-check.sh --remote           # 追加：Modrinth 的 26.3 / paper / folia，以及已配置项目的可见性
scripts/distribution-check.sh --remote --version 0.5.0
```

它会在以下情况失败（这些都不是"配置没做"，而是真问题）：`plugin.yml` 丢了 `folia-supported`、
payload 少了 `folia` loader、版本线不一致、工作流把凭据写成 `vars.*` 泄漏到 PR 工作流等。
离线模式已并入 `./scripts/verify.sh`，所以这类漂移会在 PR CI 阶段被发现。

### 5.2 GitHub 侧演练（不发版、不上传）

```bash
gh workflow run release.yml -f ref=main -f version=0.5.0 -f dry_run=true
gh run watch
```

演练会完整执行：**版本解析 → 发布元数据检查 → 构建 + jar 检查 → 受保护 job 的分布预检（含远程 API）→ 摘要**，
但**不**创建 GitHub Release、**不**上传 Hangar/Modrinth。摘要表会写明每一步是 skipped 还是 attempted。

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
| Hangar `401` | 令牌无效/过期 | 重新创建 API key，只勾 `create_version` |
| Hangar `403` / `project not found` | `HANGAR_PROJECT_ID` 填了显示名，或令牌没有该项目权限 | 改成**slug**；确认 key 的 scope 属于该项目 |
| Hangar 报平台版本不支持 | `-PhangarPaperVersions` 与 Hangar 标签不一致 | 用 Hangar 支持的 MC 版本标签（当前 `26.3`） |
| Modrinth `401` | PAT 缺 `CREATE_VERSION` 或已撤销 | 重建 PAT |
| Modrinth `404` | 项目 slug/ID 写错，或项目未发布 | 用 Creator dashboard 里的 slug |
| Modrinth `400` + `version_number` 冲突 | 该版本号已存在（重跑同一次发布） | 不要重跑上传步骤；补一个新 patch 版本，或先在平台删除草稿版本 |
| Modrinth 拒绝 loaders/game_versions | payload 与项目设置不一致 | 先在项目设置里允许 `folia` 与 `26.3` |
| 上传成功但平台上显示"Folia 不支持" | 只看 Hangar 平台标签 | 以 `plugin.yml` 的 `folia-supported` 为准；Hangar 侧在描述里写明 |

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
