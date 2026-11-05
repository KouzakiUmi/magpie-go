# Magpie Go 当前技术设计

## 1. 目标

项目的核心目标是**适配 DSH Go**：在本机提供 Magpie 的供应商、模型和路由能力，并让 DSH Go 通过受控的 Provider 接口使用。应用不把 Magpie 的完整 Web UI 重写为原生页面，也不修改 Magpie 二进制。

本机服务：

- `127.0.0.1:3430`：管理页。
- `127.0.0.1:3425`：模型网关。
- 管理密钥只保存在受保护的本地存储和内存中，不进入日志、截图、诊断包或公开仓库。

## 2. 运行时

- 官方 Magpie ARM64 release 放在 `app/src/main/jniLibs/arm64-v8a/libmagpie.so`，使用固定版本和 SHA-256 校验。
- PRoot、loader、talloc、android-shmem 使用同架构组件。PRoot 提供动态 `resolv.conf` 与 CA 证书绑定，解决 Android 上静态 Go 的 DNS 和 HTTPS 问题。
- 可执行文件通过 `useLegacyPackaging = true` 解压到 `nativeLibraryDir`，不依赖 root。
- 运行时文件位于 App 私有 `files/runtime/`，可随更新整体覆写；用户配置、登录态、模型缓存和备份位于独立的 `files/magpie/`，升级时保留。`keepData` 重置只清理运行时，完整重置才同时清理数据。
- 配置使用 `XDG_CONFIG_HOME` / `XDG_CACHE_HOME` 重定向到 App 私有目录，不使用 `.portable`。
- 第三方 Magpie 插件使用 Android 版 Bun。Bun 不随仓库分发，`tooling/download-bun.py` 按固定版本和哈希恢复。

### 进程生命周期

- 前台服务负责长期运行，主 Activity 返回时 `moveTaskToBack(true)`，不销毁 WebView 或网关。
- 启动先识别并接回健康进程，验证 PID 所属 UID、内置可执行文件和 Magpie 管理页，再提供管理页地址；不能只凭端口或 HTTP 状态码接回。
- 停止接回进程时按 PID 终止，并等待管理端口关闭，避免立即重启撞上旧进程。
- 非 Running 状态移除旧 WebView，让 Compose 的安装、启动、错误和重试界面可见。

## 3. UI 架构

- Compose 负责安装、启动、错误和加载页。
- 运行中由原生 `FrameLayout` 承载 WebView，不能把 WebView 移入 Compose `AndroidView`，也不能用 `setContent { }` 替换 Activity 根视图。
- 管理页仍由 Magpie 自己提供，Android 只注入窄屏适配、品牌角色、备份辅助和 DSH Go 入口。
- 返回键先消费 WebView 历史，无历史时退到后台。
- 外部链接交系统处理；只有 loopback 页面留在 WebView。`dshgo://providers/magpie` 交给 DSH Go。

## 4. DSH Go 一键 Provider

Magpie Go 只发起 `dshgo://providers/magpie`。入口位于网关页顶部；通用 API 示例、模型列表和桌面 CLI 集成功能不在 Android 管理页展示。Agents、会话检测、资源库、Codex/CLI 预热及相关设置入口已移除，因为它们不适用于当前 DSH Go 适配范围。DSH Go 显示确认框，然后由自己的 Provider/ConfigEditor 写入 web profile 的 `cordis.patch.yml`，不跨应用直接读写文件。

| 路由 | 协议 | Base URL |
|---|---|---|
| `magpie` | `openai-completions` | `http://127.0.0.1:3425/v1` |
| `magpie-responses` | `openai-responses` | `http://127.0.0.1:3425/v1` |
| `magpie-anthropic` | `anthropic-messages` | `http://127.0.0.1:3425` |

实现要求：

- 使用权威 `ProviderBackend.read()` 读取现有配置和 revision；每条更新前重新读取 revision。
- `/v1/models` 提供模型和原生端点，按 Responses 优先、Anthropic 其次、Chat 默认分组。
- `MAGPIE_GATEWAY_KEY` 复用桌面端凭据名；`streamIdleTimeoutMs` 默认 600000ms，已有自定义值保留。
- Responses 模型默认 `supportsStrictMode`；Claude 4.6+ 与 GLM-5.2/5.3 默认 `forceAdaptiveThinking`。
- 保留同协议下已有 compat 和用户设置的 `maxTokens`，不重复创建 Provider。

DSH Go 的接收端是独立上游 PR 工作，详见 `docs/dsh-go-one-click-provider.md` 与 `docs/dsh-go-pr-handoff.md`。

## 5. 配置备份

- 导出仍调用上游 `backup/export` 和加密备份格式。PRoot 内的 `~/...` 只用于 Magpie，Android 将其映射为 App 私有 home 下的文件，再复制到 FileProvider 的受控缓存目录，供系统分享。
- 导入使用 WebView `onShowFileChooser` 和系统文件选择器，文件内容仍由上游 `backup/import` 恢复。
- 只允许时间戳格式的 `.magpie-backup` 文件，不开放任意路径读取。诊断日志分享与配置备份是两条独立流程。

## 6. 品牌与主题资源

- 原创角色先确定三视图，再派生像素启动器、跳跃图、笑脸图、大头照和横版演示。
- 启动器保留完整双翼；WebView 左上角与启动器使用不同姿态；动态加载使用全身侧身图。
- 角色素材不复用官方小鹊或官方单色鸟标。原稿、参数与派生脚本见 `design/pixel-mascot/`。
- 脱敏演示保持 27.5 秒、八步流程、懒加载、折叠释放、减少动态效果与无视频回退。

## 7. 安全与发布

- 不记录或发布 `MAGPIE_WEB_KEY`、API key、代理凭据、登录态、配置正文或设备截图。
- Magpie 与 PRoot/talloc 等上游组件分别保留许可证和对应源码，见 `third_party/SOURCE.md`。
- Magpie Go 代码使用 CC BY-NC；原创角色和动画 ARR；内置 Magpie 按 MIT 许可。
- 设备截图、测试备份、构建产物、用户配置和运行时缓存不进入公开仓库。

后续增强与实施顺序见 `docs/requirements-backlog.md`；实际验证结果见 `docs/verification.md`。
