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
  - 网页左上角、路由卡片、脱敏头像和浅色/深色演示资源均已替换。
  - 横版视频为 672×448、27.5 秒，支持播放、步骤跳转、进度拖动、单遍结束和折叠释放。
  - 路由策略采用 3/3/3，网关 API 采用 3/2，检查间隔可全宽换行。

## DSH Go 接收端

- `flutter analyze`：通过。
- `flutter test test/providers/magpie_models_test.dart`：通过。
- `dart test test/provider_stream_idle_test.dart`：通过。
- `dart analyze`：通过。
- 真机打开 `dshgo://providers/magpie` 后显示确认框，确认后生成三条 Provider：
  - Magpie：55 个模型。
  - Magpie · Responses：15 个模型。
  - Magpie · Anthropic：4 个模型。
- 第二次接入只更新，不重复创建；revision 在每条路由更新前重新读取，避免连续写入冲突。

### 额外测试边界

`packages/dsh_client` 全量测试中有一项既有的 `healthy control ping/pong does not reconnect a quiet live surface` 失败；新增 Provider 测试与该项无调用关系。未把该结果记为全量通过。

## 发布排除项

设备截图、用户配置、API key、Web key、登录态、构建产物、测试备份和本地运行时缓存不进入发布仓库。Bun 二进制超过 GitHub 单文件限制，使用固定版本与哈希的恢复脚本替代。
