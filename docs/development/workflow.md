# Development Workflow

> 状态：生效中（自 `v0.4.0` 起）｜最后更新：2026-11-25
>
> 本文是 RealTime **每一次迭代**都要走的流程。规则来源是 `AGENTS.md`，本文把它展开成可执行的步骤、
> 检查清单与命名约定。发布相关细节见 [`docs/release/process.md`](../release/process.md)，
> 外部平台发布见 [`docs/release/distribution.md`](../release/distribution.md)。

## 0. 一图流

```
选任务 → 任务契约 → 分支 → 实现 + 本地门禁 → PR → CI 全绿 → 人工审查 → squash 合并
                                                              ↓
                                                   父仓库 bump 指针（OrzMC/OrzMC）
                                                              ↓
                                                    发版（tag → Release → 平台）
```

**没有任务的改动不开分支。** 一行拼写修正可以并入相邻任务；一个新的行为、命令、配置项、schema 变更都先写成
任务契约（`tasks/PR<N>-<slug>.md`，模板见 [`tasks/TEMPLATE.md`](../../tasks/TEMPLATE.md)），再动手。

## 1. 选任务

1. 读 [`tasks/roadmap.md`](../../tasks/roadmap.md) —— 它按顺序列出阶段，并注明每个阶段是否已合并。
2. 从**未完成**的阶段里取最小可交付的一块；不要重规划已有契约的工作。
3. 优先级判据（顺序固定）：
   1. 会让服务器出错或让运维卡住的问题（数据不一致、无法清理、无法启动）；
   2. 破坏用户既有装置的缺陷；
   3. 阻塞发布的事项；
   4. 新功能；
   5. 纯粹的重构/文档润色（可与前四类合并提交）。
4. 缺陷类任务必须能写成"复现步骤 + 期望行为"。写不出复现步骤的，先补诊断输出再动手。

## 2. 写任务契约

契约是**执行合同**，不是设计文档。它要在开工前就能回答"做完的样子是什么"：

- 目标（一句话）、背景（为什么要做）、**不在范围内**的相邻问题；
- 依赖（需要哪个 PR / 平台能力 / 上游数据）；
- 范围：涉及哪些模块与文件、是否改 schema、是否影响已发布版本；
- 约束：`realtime-core` 不许引入平台依赖、Folia 线程规则、绝不覆盖非自己方块等；
- 验收标准：可执行的用检查（命令、`execute if block` 探针、单测名、CI job 名）；
- 交付物：代码、测试、文档、CHANGELOG 条目。

契约里写的验收标准就是 PR 里要贴的证据。**不要**在合并后回填"已完成"却不留证据。

## 3. 分支与提交

| 分支前缀 | 用途 |
|---|---|
| `feat/<slug>` | 新功能 / 新命令 |
| `fix/<slug>` | 缺陷修复 |
| `docs/<slug>` | 仅文档 |
| `chore/<slug>` | 构建、CI、脚本、依赖、发版准备 |
| `release/<version>` | 只改 `CHANGELOG` + 版本号，合并后立刻打 tag |

- 一律从最新 `main` 切出，禁止直接提交 `main`，禁止 force-push 共享分支。
- 同名主题只保留一个分支；合并后分支必须删除（GitHub squash 合并会带 `--delete-branch`）。
- 提交信息：Conventional Commits，正文用中文说明**为什么**、附上验证过的命令与结果。
  多行信息写进文件再 `git commit -F <file>`（`-m` 里的反引号会被 shell 吃掉）。
- 一个提交一件事；格式化（`spotlessApply`）的噪声提交并入相邻功能提交。

## 4. 本地门禁（推送前必须跑）

```bash
export JAVA_HOME=~/jdk25 PATH=~/jdk25/bin:$PATH     # JDK 25
./scripts/verify.sh                                  # = clean check spotlessCheck build + release 脚本自检
bash scripts/validate-release.sh vX.Y.Z              # 只有发版时才需要
```

- **CRLF 门禁**：`verify.sh` 会拒绝工作区里的 CRLF 文件。`.gitattributes` 已固定：文本文件 LF，
  `*.bat/*.cmd/*.ps1` CRLF，压缩包/图片二进制。Windows 侧编辑器把它改回 CRLF 会导致 CI 失败。
- 涉及设备/红石/命令的改动，**推送前**至少跑一次对应冒烟脚本（本地 Docker 服务器）：
  ```bash
  scripts/smoke-server.sh paper          # 启动 + /rtime now + 命令面
  scripts/smoke-device-cleanup.sh paper  # 建/禁/退/清理队列
  scripts/smoke-device-cleanup.sh folia
  ```
- 达不到门禁时**不要**用 `SKIP_PREPUSH` 硬推；先在本机修好，或明确在 PR 里写"未运行的检查 + 原因"。

## 5. PR

PR 必须**自解释**，审查者不看聊天记录也能看懂。固定四段：

```markdown
## 为什么
（问题现象、复现步骤、影响面）

## 改了什么
（按模块列，标出 schema / 配置 / 命令 / 权限变化）

## 验证
（本地命令与结果、单测数、CI job、真机证据；没跑的要写原因）

## 风险与回滚
（兼容性、旧数据迁移、回滚方式）
```

- 标题同提交信息（Conventional Commits，中文描述）。
- CI 必须全绿才能合并：`verify`（构建 + 单测 + spotless + 链接/模板检查）、`validate`（发布元数据）、
  `smoke`/`cleanup-smoke`/`placeholderapi-smoke`（Paper + Folia 矩阵）。
- 合并方式固定 **squash**，并在合并前确认 diff 里没有无关改动、没有 secrets、没有父仓库的子模块指针。
- **不自行合并**：合并需要维护者明确同意。跨模块或架构级改动建议再找另一家厂商的 agent 交叉审查。

## 6. 完成定义（DoD）

一个任务只有满足以下全部条件才能移入 `tasks/completed/`：

1. 验收标准逐条有证据（命令输出、单测名、CI job、真机记录）；
2. `./scripts/verify.sh` 全绿，PR CI 全绿；
3. 文档同步：用户可见行为改 `docs/usage.md`；实现细节改 `docs/development/*`；
   schema/配置变化同时更新 `docs/development/redstone-devices.md` 的迁移段；
4. `CHANGELOG.md` 的 `## [Unreleased]` 有对应条目；**需要用户手动处理的写"升级须知"**；
5. PR 已合并、分支已删除、任务契约移入 `tasks/completed/` 并把证据写进契约；
6. 父仓库指针已 bump（`OrzMC/OrzMC`，一次提交只 bump 本子模块）。

证据状态词必须准确：`planned` → `implemented` → `locally verified` → `CI verified` → `merged` → `released`。
"代码写完"不等于"已经能用"。

## 7. 发布节奏

- 版本号 SemVer：`0.x` 期间，新功能 → minor，缺陷修复 → patch，破坏性变更 → minor + 升级须知。
  日常 `main` 恒为下一个版本的 `-SNAPSHOT`。
- **小步快发**：一个可用的用户价值（一条命令、一个编码、一次缺陷修复）就是一个版本；不要攒到"里程碑"。
- 完整步骤（changelog 段 → 本地构建核验 → tag → 保护环境审批 → 产物复核 → 收尾 PR → 父指针）见
  [`docs/release/process.md`](../release/process.md)。
- 平台发布（Hangar / Modrinth）目前**未启用**，启用与演练步骤见
  [`docs/release/distribution.md`](../release/distribution.md)。

### 热修复

线上版本出现严重问题时：

1. 从**该版本的 tag**切 `fix/<slug>`（不是 `main`，`main` 可能已含未发布改动）；
2. 只修这一个问题，最小 diff，附回归测试；
3. 合并到 `main`，然后在 `main` 上发 patch 版本（`vX.Y.Z+1`），确保发布内容与 `main` 一致；
4. 若 `main` 已含其他未发布改动，先在 PR 里说明"本 patch 是否包含它们"，不要偷偷夹带。

## 8. 真机与冒烟

- CI 的 `smoke` 矩阵（Paper + Folia）是**下限**：它验证命令存在、时钟推进、清理队列收敛。
- 涉及红石行为的改动（编码、布局、demo、比较器）必须在真机/开发服上按
  [`docs/development/redstone-devices.md`](../development/redstone-devices.md) 的探针口径验证，
  并把关键坐标与输出记进 `docs/operations/`（一次性验收写 `<主题>-acceptance-<日期>.md`）。
- 判定以**持久化状态**为准（`/rtime list`、`/rtime cleanup`、`data/devices.yml`），方块探针只作旁证：
  Folia 上 `execute ... run <插件命令>` 不回显，统一用 `run list` 之类原生命令探针。

## 9. 文档分层（避免重复）

| 文件 | 只写什么 |
|---|---|
| `docs/usage.md` | **唯一**面向使用者的文档：安装、命令与权限、接线、示例、清理队列、诊断、FAQ |
| `docs/development/redstone-devices.md` | SQL/schema/YAML 结构、编码公式、布局算法、运行时安全、诊断字段 |
| `docs/development/workflow.md`（本文） | 流程、门禁、命名、DoD |
| `docs/release/*` | 发版与外部平台 |
| `README.md` | 定位、5 条 Features、构建与文档索引，不复述命令细节 |
| `AGENTS.md` | 给 AI 的硬规则与必读清单 |

规则：**一个新事实只写一处**，其他地方链接过去。用户向文档不再罗列实现细节，实现文档不再教怎么用命令。

## 10. 与父仓库的边界

- 代码改动**只**发生在 `OrzMC/RealTime`；父仓库 `OrzMC/OrzMC` 只记录 commit 指针。
- 合并后回父仓库：`git add tools/RealTime` → `chore(submodule): bump RealTime 指针到 <说明>` → PR → 合并。
- 一次父仓库提交只 bump 本子模块；`git add -A` 会把别的子模块指针一起带进去（历史上踩过）。
- 父仓库的 `HANDOFF.md` §1.5 / §5 要写清"这一版做了什么、下一步是什么"，否则换设备/换会话就丢上下文。
