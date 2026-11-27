# 0.8.0 验收报告：自动更新（真机，真实 Hangar）

状态：完成 · 日期 2026-11-27 · 被测构建 `0.6.9`（插件）→ 更新到已发布的 `0.7.0`

## 目的

`0.8.0` 引入自动更新。单元测试与 CI smoke 用的是本地"假 Hangar"，本报告回答的是另一个问题：
**对着真实的 Hangar API + 真实 CDN + 真实 Paper 26.3，这套流程能不能把一个旧版本换成新版本。**

做法：故意把插件构建成 `0.6.9`（比线上最新版 `0.7.0` 旧），装到测试服，让插件自己去问 Hangar。

## 环境

| 项 | 值 |
|---|---|
| 服务端 | Paper 26.3（MCSM 实例 `MCSM-716c2f`）|
| 被测插件 | `RealTime-0.6.9.jar`（`./gradlew -PprojectVersion=0.6.9 :realtime-paper:shadowJar`，2,348,809 B）|
| 线上最新版 | `0.7.0`（Hangar Release 频道，2,183,111 B，sha256 `d4f3dae1…b8dc504`）|
| 配置 | `modules/updates.yml` 生成默认值：`mode: notify`、`channel: release`、`source: auto`、`check-interval-hours: 6` |

## 步骤与结果

| # | 操作 | 结果 |
|---|---|---|
| 1 | 启动 `0.6.9`，`/rtime status` | `版本：0.6.9`，`更新：还没有检查过（/rtime update 立刻检查）` ✅ |
| 2 | `/rtime update` | 控制台日志：`RealTime 0.7.0 is available (running 0.6.9, hangar). Use /rtime update download to fetch it, then restart the server.` ✅（真实 Hangar API）|
| 3 | `/rtime update download` | 日志：`RealTime 0.7.0 saved as update/RealTime-0.7.0.jar (2183111 bytes, sha256 verified). Restart the server to apply it.` ✅（真实 `hangarcdn.papermc.io`，跳转后仍在白名单内）|
| 4 | 校验落盘文件 | `plugins/update/RealTime-0.7.0.jar` 2,183,111 B，sha256 `d4f3dae1ae9abce9834ec32e09c1ff4ed4a9a17aa96e3773573873137b8dc504`，与 GitHub Release 资产一致 ✅ |
| 5 | 重启服务器 | `plugins/RealTime-0.6.9.jar` 消失，`plugins/RealTime-0.7.0.jar` 出现（Paper 按插件名匹配、覆盖并改名），`plugins/update/` 清空 ✅ |
| 6 | 重启后 `/rtime status` | `版本：0.7.0`，时钟/诊断计数正常 ✅ |

## 结论

- 真实平台链路可用：Hangar API → 校验和 → CDN 下载 → 落盘 → 重启时由 Paper 完成替换。
- 下载的产品包与已发布资产逐字节一致（sha256 相同），说明"平台公布的校验和"这一环是可信的。
- 更新目录在替换后被清空，`plugins/` 里不会出现两个 RealTime。

## 记录下来的行为细节

- **异步回复不进 RCON 响应体**：`/rtime update` 会先把"正在检查…"同步返回，真正的结果在检查完成后发出；
  对控制台/RCON 来说它进的是服务器日志（`logs/latest.log`），不在 RCON 那一行输出里。玩家执行时是正常聊天消息。
- `mode: notify` 下不会自动下载；只有 `/rtime update download`（或 `mode: download`）才会动更新目录。
- 开发构建（`-SNAPSHOT`）默认不检查（`safety.skip-dev-builds: true`），本次为了验证旧版本行为用的是 `0.6.9` 正式版本号。

## 收尾

- 测试服已换回 `0.8.0-SNAPSHOT` 构建（带自动更新），`/rtime status` 显示 `更新检查已关闭：running a development build`——这是开发构建的预期行为。
- CI 侧对 Folia 的等价验证见 `runtime-smoke.yml` 的 `update-smoke (folia)` 任务（`scripts/smoke-update.sh`）。
