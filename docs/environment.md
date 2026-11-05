# 开发环境索引

整理本机（Fractal 的 Windows 主机）上与本项目相关的全部工具链位置与使用方式。
本文件按「任务 → 环境 → 命令」组织，解决「环境都在 WSL/外部，下次找不到」的问题。

## 仓库分布

| 仓库 | 路径 | 说明 |
|---|---|---|
| Magpie Go | `D:\Software Projects\Magpie Go`（WSL 内 `/mnt/d/...`） | 本仓库，Android 壳 + WebView |
| DSH Go（Magpie 修改版） | `D:\Software Projects\dsh-Go-magpie-upstream` | 一键 Provider 接收端，独立上游 rebase 分支，见 `docs/dsh-go-pr-handoff.md` |
| DSH Go（工作副本） | `D:\Software Projects\dsh-Go` | 上游官方代码工作副本 |
| DeepSeek Harness 桌面端 | `D:\Software Projects\deepseek-harness` | DSH Desktop 源码研究用 |
| DSH NEXT（已安装） | `C:\Program Files\DSH NEXT\resources\app.asar\` | 当前 DSH 实现目录（只读参考） |

## 一、Magpie Go 构建（纯 Windows，**不要**进 WSL）

按 `AGENTS.md` 硬规则，本仓库全部 Android 构建在 Windows Git Bash 中执行：

```bash
cd "/d/Software Projects/Magpie Go"
export JAVA_HOME="/d/Android/jdk-21.0.13+9"
export ANDROID_HOME="/d/Android/sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
./gradlew :app:assembleDebug
```

- **JDK**：`D:\Android\jdk-21.0.13+9`（Temurin 21，**不是** WSL 里的 JDK 17）。
- **Android SDK**：`D:\Android\sdk`（含 `platform-tools`、`ndk` 等）。
- **Gradle**：用仓库自带 `./gradlew` wrapper，不依赖全局安装。
- **jniLibs 预编译资产**：`app/src/main/jniLibs/arm64-v8a/`（`libmagpie.so`、`libproot.so`、`libproot-loader.so`、`libtalloc.so`、`libandroid-shmem.so`），直接随 APK 打包，不需要每次重编。
- **local.properties**：`sdk.dir=D:/Android/sdk`。

### 真机安装 / 诊断

```bash
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> shell am start -n com.magpie.android/.RuntimeProbeActivity
adb -s <serial> logcat -s MagpieRuntimeProbe
```

- Windows 侧 adb：`D:\Android\sdk\platform-tools\adb.exe`（version 1.0.41 / 37.0.1）。
- Git Bash 会把 `/data/local/tmp/...` 当本机路径，`adb push` 前加 `MSYS2_ARG_CONV_EXCL='*'`。
- 不要用 `adb shell run-as`（SELinux 域不同，网络探针会给出假阴性）。

## 二、DSH Go（Flutter）环境（WSL archlinux）

`dsh-Go-magpie-upstream` 是 Flutter 项目，其工具链**不在 Windows 上**，全在 WSL2 `archlinux` 发行版里。

### 启动 WSL

```bash
wsl -d archlinux
# 或一次性执行（Git Bash 里需 MSYS2_ARG_CONV_EXCL='*' 防路径转换）：
MSYS2_ARG_CONV_EXCL='*' wsl -d archlinux -e sh -c '...'
```

### 工具链位置（WSL 内绝对路径）

| 工具 | 路径 | 版本（2026-11 核实） |
|---|---|---|
| Flutter | `~/dev/flutter` | 3.47.7 stable（Dart 3.13.5 内置） |
| Dart（独立） | `~/dev/flutter/bin/cache/dart-sdk/bin/dart` | 3.13.5 |
| JDK | `~/dev/jdk-17` | Temurin 17.0.21+9（给 Android 构建用，**不在 PATH**） |
| Android SDK | `~/dev/android-sdk` | 含 platform-tools/build-tools/ndk；adb 在 `platform-tools/adb` |
| Python3 | `/usr/sbin/python3` | 系统自带 |
| Node | **WSL 无**；`/mnt/c/Program Files/nodejs/node.exe` | Windows 24.13.0（npm 经 `/mnt/c` 调用） |
| Go | **WSL 无** | magpie 是预编译二进制，不需要 Go 工具链 |

**PATH 约定**：`~/.zshrc` / `~/.profile` 只加了 `~/.local/bin`，**Flutter/JDK/Android SDK 都不在默认 PATH**。每次使用前先 export：

```bash
export JAVA_HOME="$HOME/dev/jdk-17"
export ANDROID_HOME="$HOME/dev/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PUB_HOSTED_URL="https://pub.flutter-io.cn"
export FLUTTER_STORAGE_BASE_URL="https://storage.flutter-io.cn"
export PATH="$JAVA_HOME/bin:$HOME/dev/flutter/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
```

`tooling/setup-toolchain.sh` 是上游的一键安装脚本，它把以上 export 写进 `tooling/env.sh`——**但当前仓库没生成该文件**（`~/dev` 已装好却忘了 `source` 这一步）。下次直接手动 export 上面五行即可，不必重跑安装。

`app/android/local.properties` 已固定：`sdk.dir=/home/fractal/dev/android-sdk`、`flutter.sdk=/home/fractal/dev/flutter`。

### 常用命令（在 WSL 里执行）

```bash
# 测试 / 静态检查
cd "/mnt/d/Software Projects/dsh-Go-magpie-upstream/app"
export PATH=/home/fractal/dev/flutter/bin:$PATH
flutter test test/providers/magpie_models_test.dart
flutter test test/providers/
flutter analyze

# 构建 APK（走 Gradle，需要 JDK17 + Android SDK 环境变量）
cd "/mnt/d/Software Projects/dsh-Go-magpie-upstream"
source <(cat <<'EOF'
export JAVA_HOME="$HOME/dev/jdk-17"
export ANDROID_HOME="$HOME/dev/android-sdk"
export PATH="$JAVA_HOME/bin:$HOME/dev/flutter/bin:$ANDROID_HOME/platform-tools:$PATH"
EOF
)
tooling/build-apk.sh --build-name 1.0.12
```

- `tooling/dev-run.sh` 在 **Windows** 下会找 `DSH_ANDROID_TOOLS`（默认 `D:/Tools/android`，本机不存在），所以它只适合在 WSL 里跑（那里它走 PATH）。
- Pub 镜像：`package_config.json` 的 `rootUri` 指向 `pub.flutter-io.cn`，`PUB_HOSTED_URL` 必须保持这个镜像，否则 `flutter pub get` 会走不通。

### 真机调试

WSL adb 与 Windows adb 是**两个独立 server**，设备默认被 Windows adb 抢占：

```bash
# WSL 内：
/home/fractal/dev/android-sdk/platform-tools/adb devices   # 通常空
# Windows Git Bash：
/d/Android/sdk/platform-tools/adb.exe devices              # 设备在这里
```

要 WSL 直装手机：先用 Windows adb `adb tcpip 5555`，再在 WSL `adb connect <phone-ip>:5555`；或干脆所有装机/日志命令都在 Windows Git Bash 跑。

## 三、运行时资产与第三方源码（Magpie Go 仓库内）

- `third_party/`：PRoot 5.1.107.102、talloc 2.5.0、android-shmem、magpie 0.1.2993 源码与许可证，见 `third_party/SOURCE.md`。
- `third_party/proot/src/proot-5.1.107.102/`：完整上游源码（含 AGENTS.md）。
- `design/research/`：上游 `dsh.go`、`llm-pi-ai.js`、桌面端 settings UI 的离线副本，排查「上游原来怎么写」时**优先查这里**，不要重新下载。
- `assets/`、`app/src/main/assets/`：CA 证书、WebView 注入脚本、像素派卡素材。

## 四、外部服务

- **本机 Magpie 网关**：`http://127.0.0.1:3425/v1`（OpenAI 兼容）、`http://127.0.0.1:3425`（Anthropic Messages）。
- **固定互通 key**：`Bearer magpie`（`MagpieService`/`magpie_models.dart` 里写死的本地互通值，不是用户凭据）。
- **管理页**：`http://127.0.0.1:3430`（WebView 承载的 magpie 自身 UI）。
- **查询模型**：`curl -s -H 'Authorization: Bearer magpie' http://127.0.0.1:3425/v1/models`，字段含 `supported_reasoning_levels`（`{effort}` 对象数组）、`reasoning_levels`（字符串数组）、`native_endpoints`、`modalities`、`owned_by`。

## 五、常见坑

1. **Git Bash 路径转换**：对 `adb`、`wsl`、`proot` 调 `/data/...`、`/home/...` 参数前 `MSYS2_ARG_CONV_EXCL='*'`。
2. **JDK 版本分裂**：Magpie Go 用 Windows JDK 21，DSH Go Android 构建用 WSL JDK 17，别混用。
3. **Flutter 不在 PATH**：每次 WSL 会话都要重新 export，或在脚本里 source 一段 here-doc。
4. **WSL node 缺失**：`sdk/plugin-kit` 的 `npm test` 需要在 WSL 用 `/mnt/c/Program Files/nodejs/node.exe` 或回 Windows Git Bash 执行。
5. **不要装重复环境**：`~/dev/.downloads/` 已有 Flutter/JDK/cmdline-tools 的安装包缓存；`D:\Android\` 是 Windows 侧完整 SDK。两套并行，按任务选对一边。
