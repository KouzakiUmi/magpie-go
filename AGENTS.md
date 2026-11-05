# Magpie Go 维护约定

## 目标与验收

项目的核心目标是**适配 DSH Go**：在安卓端提供 Magpie 兼容性，让 DSH Go 能直接使用本机 Magpie 的模型、供应商与路由能力。把 magpie 本体运行在本机，提供 `127.0.0.1:3430` 管理页和 `127.0.0.1:3425` 模型网关。App 界面使用 WebView 承载 magpie 自己的完整 Web UI，不重写供应商、代理、路由等配置界面。

## 核心设计与实施顺序

- 首要接入结果是让 DSH Go 一键获得 Magpie Provider：复用桌面端的 `llm-pi-ai` 路由合并、模型发现、凭据保存和断开还原语义，但 Android 上由 DSH Go 通过其 Provider/ConfigEditor 接口写入自己的 web profile，不跨应用沙箱直接修改文件。
- 先修复现有功能，再实现额外功能。当前顺序统一见 `docs/requirements-backlog.md`：配置导入/导出 → DSH Go 一键 Provider → 图标与布局 → About/主题 → 文档发布；余额通知与内核更新属于后续增强。
- Magpie 二进制、管理 API 和备份格式保持上游契约；Android 只补齐文件选择、系统分享、窄屏展示和进程生命周期等宿主能力。
- 配置与凭据留在各自应用私有目录。跨应用协作通过明确的链接或受控接口，不共享 web key、API key、登录态或完整配置正文。
- 配置备份仍由上游 `backup/export` / `backup/import` 生成和恢复。导出时按 PRoot home 映射文件，再复制到 FileProvider 的受控目录供系统分享；导入通过 `onShowFileChooser` 选择文件，禁止任意路径读取。
- 主界面返回使用 `moveTaskToBack(true)`，不销毁 WebView 或网关。启动时先接回健康进程，不能因 Java Process 句柄丢失而重复占用 loopback 端口。
- `files/runtime/` 存放可整体覆写的运行时，`files/magpie/` 存放用户配置、登录态、缓存和备份；升级和 `keepData` 重置不得清理用户数据。

每次修改至少运行 `:app:assembleDebug`；涉及纯 Kotlin 逻辑时运行 `:app:testDebugUnitTest`。真机渲染、下载、系统代理、Cookie 冷启动与生命周期行为需要设备验证，不能由单元测试代替。

## 架构与职责

- `runtime/MagpieRuntime.kt` 和 `data/AutoProxy.kt` 是共享契约，保持只读；需要变更时先由集成方统一决定。
- `ui/MainActivity.kt` 从 `Application` 获取 `MagpieRuntimeProvider` 与可选 `StartConfigProvider`。运行时具体实现不反向依赖 UI。
- UI 订阅 `MagpieRuntime.events()`，按 `MagpiePhase` 展示安装、进度、启动、错误、加载或 WebView 页面。
- 启动参数由 `StartConfigProvider` 注入。固定 web key 由运行时/Application 负责生成与保管，UI 不生成、不记录、不持久化 key。
- **运行中的网页由原生容器承载，不走 Compose 的 `AndroidView`**。实测（小米 MIUI、Android 16）把 WebView 放进 `AndroidView` 后，`factory` 正常执行、视图已 attach 且有尺寸，但 `loadUrl` 虽返回成功，WebView 内部 `url` 恒为 `null`、`progress` 恒为 `0`，页面永远空白；同一台机器上 WebView 直接挂在原生 `FrameLayout` 下则完全正常。实现见 `ui/MagpieWebContainer.kt`。
- 容器结构：`Activity` 根 `FrameLayout` 里同时放 `ComposeView`（安装/启动/错误各状态页）与 `MagpieWebContainer`，运行中把后者 `addView` + `bringToFront()`。**不能用 `setContent { }`** —— 它会替换 Activity 的内容视图，之后 `addView` 的原生视图挂在被替换的旧容器上，界面看不见。
- 只有 `http` / `https` 的 `127.0.0.1` 与 `localhost` 页面留在 WebView；外部网页与支持的应用链接交给系统，其他协议按导航策略阻止。
- 返回键先消费 WebView 历史；无历史时退到后台，不销毁 WebView 或网关进程。下载使用系统 `DownloadManager`。
- **不要给 WebView 套 `SwipeRefreshLayout` 之类的原生容器做根**，也不要把它放回 Compose 的 `AndroidView`：两者都实测过，均导致 WebView 无法加载。

## 安全与产品表达

`MagpieStatus.pageUrl` 只在内存中传给 WebView，不进入日志、SharedPreferences 或诊断内容。下载任务先移除 `k` 查询参数，再通过 Cookie 认证。顶部只显示网关地址，不显示 web key。

应用显示名统一为 **Magpie Go**，包名保持 `com.magpie.android`。默认文案为简体中文，`values-en` 提供英文。文案直接说明操作与结果，不增加免责声明或重复配置能力。首页与主题采用 Open Design `minimalist-ui` 的暖色单色方案，派卡主卡片、细边框、左对齐说明和单色主按钮；不使用渐变、重阴影或大段居中标题。

## 原创角色与品牌资产

吉祥物名为 **派卡（Pica，取 Picker 意象）**。用户已确认 `design/character-turnaround/kxyai-2ff44ba5.png` 的三视图。派卡使用深蓝冠羽、白脸胸、短鸟喙、蓝宝石眼睛、黑白双翼、钴蓝/青绿羽端和长尾羽；不复用官方小鹊、官方鸟标或早期候选，不加服装与兽类肢体。精细设定先定稿，像素化只用于应用资产。脱敏演示文案中的旧称呼“小鹊”统一替换为“派卡”。

- 启动图标和单色轮廓使用 `magpie_mascot`，完整双翼必须留在自适应图标安全区。Compose 加载页使用 `magpie_jump` 的 Q 版全身侧身图，3 秒循环右跳、落地、镜像左跳、落地；ImageBitmap 显式使用 `FilterQuality.None`。
- `MagpieWebContainer.brandedAsset()` 仅拦截 loopback 的 favicon、触控图标、大头照、派卡挥翼/飞行帧与 `xq_light/dark` 固定资源路径，返回 `assets/branding/` 本地素材，不改 `libmagpie.so` 或其他请求。路由运动组 `g.rt-flier` 保留 transform，内部使用图像生成的上举/中间/下落三帧拍翅动画，替换操作幂等，避免 MutationObserver 重建循环。视频响应提供完整流和 `Accept-Ranges: bytes`，由 Chromium 应用 Range；不要在应用层重复跳过字节或合成第二个 206。
- 网页左上角沿用 `.brand .logo` 点击区域，注入像素角色；演示入口使用 `mascot-head.png` 完整大头照；不重写管理页。
- 脱敏视频为 672×448 横版，以原关键帧动作和构图为目标替换角色，保留八段羽翼动作、27.5 秒、原舞台坐标、单遍播放、懒加载、折叠释放源、减少动态效果与无视频回退。
- 首页和全屏 About 使用图像生成的 3D 派卡原画帧与 `drawable/pica_frame_animation.xml`，组成 12 帧循环，关键动作之间保留停顿；中间帧按透明连通区域隔离、居中并扩大透明画布，不放大角色像素，避免面板硬切缺件。工具栏为菜单（含导出日志）/网络代理/关于；主题偏好保存在 `magpie_go_ui`，并直接写入 WebView 的 `data-theme`，跟随系统在当前系统亮暗间切换，不依赖上游 `system`。持久化使用 `prefWrite`，Compose 不整套重建。
- 代理设置页使用同一主题；`providerHosts()` 只接受真实 HTTP(S) 主机，过滤邮箱、订阅状态和非法 URL。中央卡片使用 `magpie-smile.png`，运动组使用飞行帧，静态回退保留且限制为 44dp。
- 原稿、派生脚本和维护说明见 `design/pixel-mascot/README.md`；不要覆盖原稿。

## WebView 窄屏适配

magpie 的 Web UI 是为桌面宽度设计的，在手机宽度（本机 369dp）下多处排版会坏掉。
适配集中在 `ui/MagpieWebContainer.kt` 的 `applyNarrowScreenFixes()`：在 `onPageFinished`
时向页面注入一个 `<style id="magpie-android-tune">` 和少量 JS，**只调整窄屏排版，
不改 magpie 的源码，也不重写它的界面**。

已修复的六处，每条都有实测数据支撑（详见 `docs/verified-findings.md` §18）：

| 选择器 | 现象 | 做法 |
|---|---|---|
| `.ehead` | 标题行被挤成 6 行 | 各成员按内容宽度、不换行 |
| `.ehead .link.site` | 完整域名把整行撑爆 | 文字改「官网」，原域名存进 `title` |
| `.segs` | 选项被折到第二行 | 整组不换行，选项内文字折两行居中 |
| `.bar` | 「保存」被压成 44×44 竖排 | 收起弹性占位 `.grow` |
| `.pm-manual` | 插件手动安装区错位 | 改两行网格 |
| `.rt-accts` | 路由页「还没有请求」栏右移 22px | 清掉 `<ol>` 的默认 `padding-left` |

**注意**：`<ol>`/`<ul>` 的浏览器默认 `padding-left: 22px` 是这类错位的常见来源，
magpie 多处已设 `list-style: none` 却没清 padding。

**不要用 `overflow-x: auto` 做横向滚动**：只设 x 轴会让 y 轴的 `visible` 一并变成
`auto`，容器因此出现纵向滚动条，看起来像页面在上下滚。改用「允许换行」。

### 改这些规则时怎么验证

不必每次装机。WebView 开了调试（debug 变体），可以直连 CDP 在真机上量出真实像素，
确认后再构建：

```bash
PID=$(adb -s <serial> shell pidof com.magpie.android)
adb -s <serial> forward tcp:9222 localabstract:webview_devtools_remote_$PID
curl -s http://127.0.0.1:9222/json     # 取 webSocketDebuggerUrl
```

用 `Runtime.evaluate` 读 `getBoundingClientRect()` 判断是否折行／被截断。
装机验证时也用同一通道，不要靠肉眼看截图猜。

## 维护边界

界面代码集中在 `app/src/main/java/com/magpie/android/ui/`，导航策略单测位于 `app/src/test/java/com/magpie/android/ui/`。不要为修复 UI 编译而修改其他工作包正在实现的 runtime/data 文件；先区分并报告失败来源。

详细架构依据见 `docs/design.md`，已实测事实见 `docs/verified-findings.md`，
未完成的进度与接续指引见 `docs/pending-work.md`。
仓库目录为 `D:\Software Projects\Magpie Go`，App 包名仍是 `com.magpie.android`。

## 关键实测事实（不要凭直觉推翻）

依据 [`docs/verified-findings.md`](docs/verified-findings.md)：

- magpie 的 `linux-arm64` / `linux-amd64` 是**完全静态链接**的 Go 二进制，Android 上可直接执行；
- Android 没有 `/etc/resolv.conf`（`/etc` 只读），静态 Go 会退化成 `[::1]:53`。**必须用 PRoot 绑定动态生成的 resolv.conf**，否则全部出网失败；
- 同样必须绑定 CA 证书包，否则 HTTPS 报 `x509: certificate signed by unknown authority`；
- PRoot **不需要 root**；
- 可执行文件必须放 `jniLibs`（`lib*.so`），靠 `useLegacyPackaging = true` 解压到 `nativeLibraryDir` 才可执行；
- 配置用 `XDG_CONFIG_HOME` / `XDG_CACHE_HOME` 重定向，**不要用 `.portable`**（`nativeLibraryDir` 只读，做不到）；
- **网络能力必须在真实 App 进程内验证**。`adb shell run-as` 的 SELinux 域不同，会给出假阴性（连 TCP 都建不起来）。

## 环境（本机）

工具链在 `D:\Android`，**不在 `.dsh` 里**（不能用 `.dsh` 内组件，会有权限问题）：

```bash
cd "/d/Software Projects/Magpie Go"
export JAVA_HOME="/d/Android/jdk-21.0.13+9"
export ANDROID_HOME="/d/Android/sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
./gradlew :app:assembleDebug
```

真机诊断（不用 `run-as`）：

```bash
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> shell am start -n com.magpie.android/.RuntimeProbeActivity
adb -s <serial> logcat -s MagpieRuntimeProbe
```

Windows 的 Git Bash 会把 `/data/local/tmp/...` 当本机路径，`adb push` 前加
`MSYS2_ARG_CONV_EXCL='*'`。

## 硬规则

- **不要引入 root 依赖**。批量改配置等需要更高权限的场景用 Shizuku 挂载 adb，不是 `su`。
- **不要修改 magpie 源码**，只用官方 release 产物（固定 SHA256）。
- **不要重写 magpie 的 web UI**。
- **不要泄漏** `MAGPIE_WEB_KEY`、API key、代理凭据（日志、诊断包、崩溃报告、`SharedPreferences` 明文都算）。
- **proot / loader / 依赖库 / magpie 必须同一架构**。曾经误把 x86_64 版 proot 放进 arm64 jniLibs，导致 `not executable: 64-bit ELF file`。

## 已知限制

- **插件框架保留**：会话检测、资源库等桌面端内置入口隐藏；第三方插件与自动签到可用。首页「下载额外组件」按固定官方 release 和 SHA-256 安装 Android arm64 Bun 到 `files/runtime/bin/bun`，再通过 `MAGPIE_BUN` 注入；不下载不影响核心网关。
- **只支持 arm64 真机**：`jniLibs/arm64-v8a` 是唯一当前目标，x86_64 模拟器是后续工作。
