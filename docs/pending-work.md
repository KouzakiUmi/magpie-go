# 进度与待办

> 本文只记尚未完成的事项。未完成需求的完整实现要点见 `docs/unfinished-work.md`；项目边界见 `AGENTS.md`，统一优先级见 `docs/requirements-backlog.md`，已执行验证见 `docs/verification.md`。

## 已完成并验证

- 配置导出分享与导入恢复。
- DSH Go 三协议一键 Provider 与重复更新。
- 启动器、网页角色、脱敏头像和横版演示资源替换。
- `.segs` 防截断、网关 API 3/2、路由策略 3/3/3、检查间隔全宽布局。
- 网关返回最小化、健康进程接回和配置备份流程。

## 待实现

### About、主题和工具栏（已完成）

- 浅色与深色主题；浅色使用角色的冰蓝和暖白，不使用纯白。
- 左侧菜单收纳导出日志，中间网络代理，右侧关于。
- 首页与 About 的派卡原画帧动画、作者头像、作者介绍、GitHub、contributors、感谢信息和完整开源许可均已实现并验证；Bun 下载与 `MAGPIE_BUN` 注入已实测。

### 当前供应商余额 / 用量通知

- 当前启用的每个供应商账户分别显示一条通知，不合并不同币种。
- 优先 `balance`，其次额度窗口；无余额时回退 token / 消费统计。
- 已实测 `GET /api/providers` 与 `GET /api/usage/quotas` 的数据结构。
- 刷新频率、后台生命周期、通知 ID 稳定性与凭据边界在实现前确定。

### APK 内更新 Magpie 内核

- 暂时停用 Magpie 自身自动更新与检查入口。
- 后续仅使用官方 release，验证签名和哈希，写入 App 私有可执行目录并支持切换、回滚和真实启动验证。
- 不直接覆盖只读 `nativeLibraryDir`。

### 其他待核实

- `cccocodroid` 的配置说明当前不在范围内；本机没有对应源码，需另行核实。

## 真机验证方式

debug 变体可连接 WebView CDP，读取实际布局与媒体状态。不要只凭截图判断溢出：同时检查 `scrollWidth > clientWidth` 和实际边界。

```bash
PID=$(adb -s <serial> shell pidof com.magpie.android)
adb -s <serial> forward tcp:9222 localabstract:webview_devtools_remote_$PID
curl -s http://127.0.0.1:9222/json
```
