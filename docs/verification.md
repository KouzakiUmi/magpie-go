# 验证记录

## Magpie Go

- `./gradlew :app:assembleDebug`：通过。
- `./gradlew :app:testDebugUnitTest`：通过。
- 在 Android 16 arm64 真机上验证：
  - 启动器名称 `Magpie Go`、完整双翼图标和侧身加载角色。
  - 配置导出后通过系统分享获得 `.magpie-backup`；导入通过系统文件选择并完成恢复。
  - 返回主界面后管理服务仍监听，重新打开应用可继续使用；健康进程接回不重复占用端口。
  - 结束 Magpie 子进程后显示可点击的“重试”，重试后恢复；重装后的 talloc 兼容库以实体文件替换旧链接。
  - 运行时与用户数据分别位于 `files/runtime/` 和 `files/magpie/`。
  - 脱敏演示中的旧“小鹊”称呼已替换为“派卡”，管理页无偏好初始化异常。
  - 网关页仅显示 DSH Go 一键入口；Agents、会话、资源库、模型列表、API 示例和 Codex/CLI 设置均隐藏。
  - 首页采用 Open Design minimalist-ui 的暖色单色卡片布局，启动、安装、加载和重试状态均已显示。
  - 菜单“重启网关”在延长退出等待并取消旧进程监听后，能重新进入运行中的管理页。
  - 代理检测使用请求序号、输入变更失效和非 5xx/407 判定；改回跟随系统也走同一重启路径。
  - 导入备份仅接受 `content://`，复制到私有导入目录；导出按 Downloads 优先并支持多语言提示。
  - 会话检测、资源库等桌面入口隐藏，插件页保留；用量页横向溢出与会话 tab 已隐藏。
  - 前台服务按模型列表指纹提醒重新导入，不自动覆盖 Provider 配置。
  - 全屏 About 可打开，作者头像、Magpie 配置/官网/社区/许可链接均可用。
  - 浅色/深色主题可切换并写入 `magpie_go_ui` 偏好，冷启动恢复；开源许可弹窗已打开，全部组件许可正文已打包。
  - 首页原画帧动画已实际切换动作；About 使用 12 帧 3D 循环，关键动作有停顿，透明区域隔离避免缺件，派卡显示为 240dp。
  - 深色 About 与完整许可证正文已打开，LGPL3 与 MIT 文本均已显示。
  - 官方 Bun 1.4.4 已通过应用内下载安装，`bun --version` 返回 1.4.4；网关环境包含私有 `MAGPIE_BUN` 路径。
  - 路由运动组 `rt-flier` 已替换为派卡上举/中间/下落三帧拍翅动画，保留移动路径且无站立图；替换幂等，避免 MutationObserver 白屏循环。
  - 中央卡片使用派卡静态头像，回退图片限制为 44dp；`data-theme` 可直接控制亮暗，切换使用 250ms 冷却，恢复时重新应用主题，避免默认主题覆盖。
  - 代理设置页暗色已显示；非法邮箱/订阅状态目标被 `normalizeProviderHost` 过滤，相关单测通过。
  - 网页左上角、路由卡片、脱敏头像和浅色/深色演示资源均已替换。
  - 横版视频为 672×448、27.5 秒，支持播放、步骤跳转、进度拖动、单遍结束和折叠释放。
  - 路由策略采用 3/3/3，网关 API 采用 3/2，检查间隔可全宽换行。

## DSH Go 接收端

- `flutter analyze`：通过。
- `flutter test test/providers/magpie_models_test.dart`：通过。
- `dart test test/provider_stream_idle_test.dart`：通过。
- `dart analyze`：通过。
- 真机打开 `dshgo://providers/magpie` 后显示确认框，确认后生成三条 Provider：
  - Magpie：49 个模型。
  - Magpie · Responses：25 个模型。
  - Magpie · Anthropic：3 个模型。
- 第二次接入只更新，不重复创建；revision 在每条路由更新前重新读取，避免连续写入冲突。
- Auto Routing 与供应商分组单测通过：`auto/...` 原 ID 保留，普通供应商按协议分路由，视频模态按 DSH 支持范围过滤。

### 额外测试边界

`packages/dsh_client` 全量测试中有一项既有的 `healthy control ping/pong does not reconnect a quiet live surface` 失败；新增 Provider 测试与该项无调用关系。未把该结果记为全量通过。

## 发布排除项

设备截图、用户配置、API key、Web key、登录态、构建产物、测试备份和本地运行时缓存不进入发布仓库。Bun 不随仓库分发，由用户从官方 release 下载，固定版本和 ZIP SHA-256 在安装时核验。
