# Magpie Go

面向 **DSH Go** 的 Android Magpie 兼容应用。应用在设备本机运行 Magpie，提供管理页和模型网关，并通过 DSH Go 的 Provider 接口接入三种协议。

## 已实现

- 本机 Magpie 运行时：`127.0.0.1:3430` 管理页、`127.0.0.1:3425` 网关。
- 原生 WebView 管理界面，不重写 Magpie 的供应商、路由和设置页面。
- 一键配置 DSH Go：`magpie`、`magpie-responses`、`magpie-anthropic` 三条 Provider 路由。
- 配置备份导出分享与导入文件选择。
- 网关后台运行、返回最小化、健康进程接回和意外退出恢复。
- 原创 Kemono 喜鹊启动器、加载跳跃、网页角色及横版脱敏演示。
- 浅色/深色主题选择、About 作者与许可信息、图像生成的派卡原画帧动画；路由飞行使用上举/中间/下落三帧拍翅。
- 首页「下载额外组件」安装官方 Android Bun，保留插件框架与自动签到。
- 浅色/深色页面素材、窄屏布局、循环播放与减少动态效果兼容。

## 架构

- Kotlin / Jetpack Compose：安装、启动、状态与工具栏。
- 原生 `FrameLayout` + WebView：Magpie 管理页，避免 Compose `AndroidView` 的加载兼容问题。
- PRoot：提供 DNS、CA 证书和 Magpie 所需运行环境。
- DSH Go：通过独立上游项目的 Provider/ConfigEditor 写入 `cordis.patch.yml`，不跨应用直接读写私有文件。

DSH Go 的接收端改动是独立上游 PR 工作，不属于本仓库的 Magpie Go 包。接入设计见 [`docs/dsh-go-one-click-provider.md`](docs/dsh-go-one-click-provider.md)。

## 构建

要求：Android SDK 34、JDK 21、arm64 真机。

```bash
export JAVA_HOME="/d/Android/jdk-21.0.13+9"
export ANDROID_HOME="/d/Android/sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

安装：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 许可与角色素材

- 项目代码：[CC BY-NC 4.0](LICENSE)。
- 内置 Magpie：遵循 [yetone/magpie 的 MIT License](https://github.com/yetone/magpie/blob/main/LICENSE)。
- 原创 Kemono 喜鹊 **派卡（Pica）**的角色、图标和动画：**All Rights Reserved（保留所有权利）**。
- 作者头像：由 Kouzaki Umi 提供，未经许可不单独授权复用。

详见 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)。

## 文档

- [`AGENTS.md`](AGENTS.md)：核心设计、边界和维护约定。
- [`docs/requirements-backlog.md`](docs/requirements-backlog.md)：需求优先级与后续增强。
- [`docs/verification.md`](docs/verification.md)：构建和真机验证结果。
- [`design/pixel-mascot/README.md`](design/pixel-mascot/README.md)：角色资产和派生脚本。

作者：[Kouzaki Umi](https://github.com/KouzakiUmi)
