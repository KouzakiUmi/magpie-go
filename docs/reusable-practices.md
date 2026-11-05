# 可复用方法与规范

本文档收敛本项目反复用到的做法，避免重复踩坑。环境位置见 `docs/environment.md`，硬边界见 `AGENTS.md`。

## 1. WebView 页面改造

### 1.1 只做宿主级处理

- 不修改 Magpie 源码，不重写其 Web UI。
- 一切注入集中在 `applyNarrowScreenFixes()`，页面加载完成后执行一次。
- 用 `MutationObserver` 监听动态内容，但**每处替换必须幂等**：给节点打 `dataset` 标记后跳过，否则观察器会因自身改动反复触发，表现为白屏卡死。

### 1.2 定位元素要靠坐标与运行时证据

不要靠资源名或类名猜测：

- 先看资源名（`g.bird`、`img`、`svg use`）；
- 再用 `elementsFromPoint(x, y)` 按屏幕坐标命中真实元素；
- 最后查 `getBoundingClientRect()` 和 `getComputedStyle()`（含 `::before` / `::after`）。

本次旧喜鹊就经历了「`g.bird` → 实际是 `g.rt-flier`」的误判，靠坐标命中才定位正确。

### 1.3 替换动画节点时保留运动容器

页面上的运动通常由外层容器（`transform`、CSS 动画）驱动：

- 保留原容器与 `transform`，只替换容器内部图形；
- 不要用 `replaceWith` 换掉整个运动容器，否则动画停止；
- 多帧动画用 CSS `@keyframes` + `steps(1, end)` 交替 `opacity`，不用 JS 定时器。

### 1.4 兜底分支也要受控

`img` 回退会让图片按原始像素渲染成巨型图。所有回退节点显式设置 `width` / `height` / `object-fit`，并单独验证一次。

## 2. 主题联动

### 2.1 直接控制，不依赖上游状态

- Magpie 的主题是 `prefs.theme` + `applyPrefs`；`prefs` 在页面加载早期为 `null`，直接调用会抛 `Cannot read properties of null (reading 'theme')`。
- 正确顺序：先写 `document.documentElement.dataset.theme = 'light' | 'dark'`（立即生效），再在 `prefs` 就绪后可选调用 `prefWrite` 持久化。
- 持久化用 `prefWrite`（不触发过渡动画），不要用 `savePrefs`（会触发主题过渡闪烁）。

### 2.2「跟随系统」的语义

- 页面上锁定的值（`documentElement.dataset.theme` 与 `window.__magpieGoTheme`）是**当前系统亮暗的具体值**：系统变化时在 `onConfigurationChanged` 重新解析并写入——WebView 的 `prefers-color-scheme` 不一定跟系统走，不能只写 `system` 交给页面自查。
- 但持久化进网页 `prefs.theme` 的值保留 `system`：这样 magpie 自己的设置项仍显示「跟随系统」，不会把我们解析出的值固化成用户的显式选择。

### 2.3 页面会反写主题，需要一个回锁守卫

- magpie 的 `applyPrefs` 在每次状态刷新（含切换 tab 触发的前端重渲染）都会拿 `prefs.theme` 重写 `root.dataset.theme`——只写一次会被冲掉。
- 注入脚本里放一个只看 `data-theme` 的 MutationObserver：被改回别的值就立即写回 `window.__magpieGoTheme`；`applyThemePreference` 每次先更新 `__magpieGoTheme` 再写 `dataset.theme`，两者一致。

### 2.4 生命周期

- 页面加载完成（`onPageFinished`）、Activity `onResume`、系统 `onConfigurationChanged` 都要重新应用一次，否则后台返回后会退回默认主题。
- Activity 重建（`applyTheme`）时不要重复 `setContent`，用 `mutableStateOf` 更新 Compose 状态。
- 原生弹窗（About、代理设置、底部菜单）统一读取同一份偏好，避免只改外壳不改页面。

## 3. 网络与目标校验

- Provider 目标只接受真实 `http(s)` 主机：校验 scheme、host、拒绝含空白、含 `userInfo` 的值。
- `magpie providers` 的输出里混有「signed in as 邮箱」「订阅状态」等文本，必须过滤，否则代理检测会拿到非法 URL。
- 相关逻辑抽成顶层函数，便于单测（`normalizeProviderHost`）。

## 4. 下载与可选组件

- 固定版本、固定官方 URL、固定 SHA-256，下载后先校验再解压。
- 只允许 HTTPS 重定向，跳转次数设上限。
- 压缩包与解压后二进制都设字节上限，并设总时长时间上限，避免慢速流无限占用。
- 解压只写固定目标文件，不按压缩包内路径落盘。
- 安装到 App 私有目录并设置执行位，不覆盖只读 `nativeLibraryDir`。
- 安装与重启要在应用级作用域执行（单飞 + 互斥），Activity 销毁不能中断，也不能漏掉重启。

## 5. 图片与动效资产

- 需要连续动画时，用一次生成请求输出多帧并明确要求「统一身体比例、构图、光照、舞台位置」。
- 长图版多面板容易被画布压缩导致模糊；四帧一组比一次十几帧更清晰。
- 提取单帧时不要按面板硬切：
  1. 取透明连通区域，选面积最大的主体；
  2. 合并与主体邻近的小连通块（翼尖、星光）；
  3. 裁到主体边界后留透明边距；
  4. 用更大的透明画布居中，**不缩放角色像素**。
- 关键姿势之间要有停顿：稳定帧 550–750ms，过渡帧 150–180ms。
- 禁止用脚本修图改角色（改眼睛/改姿态），需要新帧就用图像生成；脚本只做裁切、拼装、校验。

## 6. 构建与验证

- Magpie Go：Windows Git Bash + JDK 21，`./gradlew :app:assembleDebug :app:testDebugUnitTest`。
- DSH Go：WSL archlinux + Flutter，只改界面时**不重建运行时**，用 `tooling/build-apk.sh --release -f arm64 --keep-daemon -- --no-pub`。
- 真机核对：APK 哈希要与设备上安装的一致，避免「以为装了新包」。
   ```bash
   sha256sum app/build/outputs/apk/debug/app-debug.apk
   PACKAGE=$(adb -s <serial> shell pm path com.magpie.android | sed 's/package://')
   adb -s <serial> shell sha256sum "$PACKAGE"
   ```
- WebView 布局与 DOM 用 CDP 读写，不靠截图猜。
- 屏幕处于 `Dozing` 时截图会全黑，先确认 `dumpsys power` 的 `mWakefulness=Awake`。

## 7. 上游适配

- DSH Go 上游不接受 PR，交付形式是**基于当前上游的修改版 APK**。
- 合并本地补丁时先保存快照（`backup/...` 分支），再以最新上游为基线**只挑必要提交**：
  - 用 `git cherry-pick <commit>` 逐条挑；
  - 二进制冲突取目标提交的版本；
  - 混合提交里只移植相关 hunk，不整条合并。
- Magpie 导入语义：
  - 按 API：固定 `magpie` / `magpie-responses` / `magpie-anthropic`。
  - 按 Provider：按 `owned_by` 分供应商，`auto/...` 独立为 Auto Routing。
  - 路由 ID 必须唯一：slug + 稳定哈希 + 协议，冲突直接报错，不覆盖。
  - 只写 `route.models`，不要写整组协议模型。
  - 清理旧路由前校验归属（`apiKeyEnv` + `baseUrl` + displayName），不要按前缀盲删。

## 8. 文档与发布

- 需求状态用「待办 / 进行中 / 已完成（有验证证据）」三态，未完成项集中在 `docs/unfinished-work.md`。
- 验证记录只写实际执行过的命令与结果，区分单元测试、真机构建、真机行为。
- 发布 Release 时附 APK 的 SHA-256，并写清配套要求（哪些功能依赖修改版客户端，哪些不需要）。
