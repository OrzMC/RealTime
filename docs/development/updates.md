# 自动更新（检查新版本 + 下载到更新目录）

状态：随 `0.8.0` 引入 · 最后更新 2026-11-27

RealTime 会检查 Hangar / GitHub Releases 上是否有比当前更新的版本，并且可以**把新 jar 放进服务器的
更新目录**——但**永远不热替换自己、永远不重启服务器**。这不是偷懒，而是 Paper/Folia 的标准做法。

## 为什么不是"自己换掉自己"

- Bukkit/Paper API 没有热替换插件的能力：插件类由插件类加载器持有，运行中替换 jar 不会重新加载它。
- Paper 官方文档明确劝退：*"Don't replace any JAR in a running server"*（[Updating](https://docs.papermc.io/paper/updating)）。
- 换 jar 的正确时机只有一个：**开服时加载插件之前**。

所以"标准插件更新方式"就是 Paper 的 `update` 文件夹。

## Paper 如何处理 `plugins/update/`

`bukkit.yml`：

```yaml
settings:
  update-folder: update    # 相对 plugins/，默认为 update
```

开服加载插件时，Paper 的 `FileProviderSource#checkUpdate` 会对 `plugins/` 里的每个插件：

1. 读出该插件 jar 里 `plugin.yml` 的 `name`（本项目是 `RealTime`）；
2. 深度 1 扫描 `plugins/update/`，对每个候选 jar 也读出 `plugin.yml` 的 `name`，**按插件名匹配**（不看文件名）；
3. 命中后：用候选覆盖当前 jar → 把当前 jar 重命名为候选的文件名 → 删除更新目录里的候选；
4. 解析失败的候选（比如半截下载）会被跳过，不会阻止服务器启动。

由此得到本实现的三条硬性设计：

| 约束 | 原因 |
|---|---|
| 半成品必须叫 `RealTime-x.y.z.jar.part`，**不能** `.jar` | 否则中断的下载会被当作候选：要么被换上去，要么让插件加载失败 |
| 校验和必须在**改名之前**验证 | 改名就是"允许被换上"的那一刻 |
| 只写 `plugins/update/`，不碰 `plugins/` 本体 | 运行中的 jar 在 Windows 上被锁，而且改它就是"替换运行中的 jar" |

`update-folder: ''`（被主机商关掉）时，本插件降级为"只提示"，`/rtime update` 会说明原因。

## 更新源与校验

| 源 | 端点 | 校验和 | 说明 |
|---|---|---|---|
| Hangar（主） | `GET /api/v1/projects/RealTime/versions?limit=20` | `downloads.PAPER.fileInfo.sha256Hash` | 唯一在同一响应里给出 sha256 的平台；直接给 `hangarcdn.papermc.io` 下载地址 |
| GitHub Releases（备） | `GET /repos/OrzMC/RealTime/releases?per_page=20` | `assets[].digest` (`sha256:…`) | 平台审核中也能用；下载地址跳 `objects.githubusercontent.com` |
| Modrinth（可选，默认不从它取） | `GET /v2/project/pRGtDpaD/version` | `files[].hashes.sha512` | 项目审核中时匿名读不到版本，所以只能当候选 |

选择顺序由 `source: auto`（默认）决定：Hangar → GitHub，任一成功即用；`source:` 写死某一个时只用它。
`UpdatePolicy` 只做一件事：从所有候选里挑**版本号最大且大于当前版本**的那个（拒绝降级、拒绝同版本），并按
`channel` 过滤预发布。

安全边界（`UpdateHttp`）：

1. **只走 HTTPS**；纯 HTTP 仅允许 loopback 且必须显式打开 `safety.allow-insecure-http`（CI 用）。
2. `safety.allowed-hosts` 白名单在**请求时**和**每一次重定向**都校验（Hangar/GitHub/Modrinth 的下载都会跳 CDN），
   所以重定向无法把下载引到别的主机。
3. JSON 响应上限 1 MiB、jar 上限按 `safety.max-download-mb`（默认 16 MB，真实包约 2.1 MB）。
4. 连接 10 s / 请求 20 s 超时；`User-Agent: RealTime/<版本> (+https://github.com/OrzMC/RealTime)`。
5. 没有校验和的发布**不会**被安装：`/rtime update download` 直接拒绝并说明原因。

## 线程模型（Folia 安全）

- 所有网络与磁盘工作跑在 Paper 的 `AsyncScheduler`（`server.asyncScheduler`），它不属于任何 region，
  因此碰不到世界状态，也就不会违反 Folia 的线程归属规则。
- 命令回复从异步线程回来时，经 `CommandTarget.runOnOwner` 回到**该玩家自己的 region**（控制台走全局 region），
  这是 Folia 下唯一安全的回话方式。
- 检查是**单飞**的：并发调用 `/rtime update` 不会叠加请求，只会复用正在跑的那次。
- 定时检查用 `AsyncScheduler.runAtFixedRate`，默认每 6 小时一次，首次延迟 30 秒。

## 配置（`plugins/RealTime/modules/updates.yml`）

```yaml
config-version: 1
enabled: true
mode: notify              # notify = 只提示；download = 发现即下载
channel: release          # release = 只要正式版；any = 连 -SNAPSHOT/预发布
check-interval-hours: 6
source: auto              # auto | hangar | github | modrinth
notify:
  console: true
  players: true           # 只发给有 realtime.update 权限的玩家，每个版本一次
safety:
  max-download-mb: 16
  skip-dev-builds: true   # 0.8.0-SNAPSHOT 这类开发构建默认不检查
  allowed-hosts: [hangar.papermc.io, hangarcdn.papermc.io, api.github.com, github.com, objects.githubusercontent.com, api.modrinth.com, cdn.modrinth.com]
  allow-insecure-http: false   # CI/本地联调
  api-base-override: ''        # CI/本地联调
```

`skip-dev-builds` 存在的原因：`0.8.0-SNAPSHOT` 永远比它即将变成的 `0.8.0` 小，开发构建天天提示"有新版本"没有意义。

## 命令与权限

| 命令 | 权限 | 作用 |
|---|---|---|
| `/rtime update` | `realtime.update`（默认 OP） | 立刻检查，回报"已最新 / X 可用 / 失败原因" |
| `/rtime update download` | `realtime.update` | 把可用版本下载到更新目录（仍要重启才生效） |
| `/rtime status` | `realtime.device.view` | 多一行更新状态 |

`realtime.update` 是 `realtime.admin` 的子权限。

## 一次完整更新长什么样

1. 控制台（或 `/rtime status`）看到：`RealTime 0.8.0 is available (running 0.7.0, hangar)`；
2. 执行 `/rtime update download` → `已下载 0.8.0 到 plugins/update/RealTime-0.8.0.jar（校验通过）`；
3. 运维在合适的时机重启服务器；
4. 开服时 Paper 把候选覆盖到当前 jar、把 jar 改名为 `RealTime-0.8.0.jar`、删掉候选；
5. `/rtime status` 的版本变成 `0.8.0`。

## 验证

- **单元/集成**（无网络）：`UpdateHttpTest` 用 JDK 自带 `HttpServer` 覆盖白名单、重定向逃逸、体积上限；
  `UpdateSourcesTest` 覆盖三个平台的 JSON 形状；`UpdateInstallerTest` 覆盖校验和/大小不符、`.part` 清理、
  旧 pending 替换；`UpdateServiceTest` 覆盖渠道过滤、单飞、通知去重、只在 `download` 模式自动下载。
- **真机 smoke**：`scripts/smoke-update.sh paper|folia` 用 `scripts/update-fixture-server.py` 起一个本地
  "假 Hangar"，在真 Paper/Folia 26.3 上验证：下载并校验 → **重启后 Paper 完成替换**（旧 jar 消失、
  新文件名出现、更新目录清空）→ 篡改校验和的候选被拒绝且不留残file。CI 里跑 paper + folia 两套。
- **真机验收**：`docs/operations/acceptance-report-0.8.0.md`（MCSM Paper 测试服，用 0.6.9 构建对着线上
  真实的 0.7.0 走一遍）。

## 不做的事（明确）

- 不热替换、不 reload 自身；
- 不自动重启服务器（没有安全 API，而且会踢人）；
- 不信任没有校验和的发布；
- 不引入第三方依赖：HTTP 用 JDK 的 `java.net.http`，JSON 用 `core/update/Json.kt`（约 250 行 + 单测）。
