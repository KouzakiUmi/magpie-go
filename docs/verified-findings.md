# 实测结论（2026-11-05，Android 34 模拟器 x86_64）

本文件只记录**实际跑通或实际失败**的结果，不是设计推测。每条都附可复现命令。

## 摘要

magpie 本体可以在 Android 上运行，**完整 web UI 可用**，不依赖重新编译，也不依赖 Termux。

必要条件是三条，缺一条就起不来：

1. 用 PRoot 提供 `/etc/resolv.conf`；
2. 用 PRoot 提供 `/etc/ssl/certs/ca-certificates.crt`；
3. 设置 `PROOT_TMP_DIR`、`PROOT_LOADER`、`LD_LIBRARY_PATH`。

## 1. 静态 `linux-amd64` 能在 Android 上直接运行

Release 资产 `magpie-cli-linux-amd64` 是**完全静态链接**的 Go 二进制（无 `PT_INTERP`、无 `PT_DYNAMIC`），所以它不依赖 glibc，也不依赖 bionic：

```
$ file magpie-linux-amd64
ELF 64-bit LSB executable, x86-64, statically linked, Go BuildID=..., stripped
```

直接 `adb push` 后执行，输出 `magpie 0.1.2993`。

**这条证明 PRoot 不是为了让二进制能跑，而是为了给它提供上面那两个文件。**

## 2. 不套 PRoot 时 DNS 必然失败

Android 没有 `/etc/resolv.conf`，且 `/etc` 只读：

```
$ cat /etc/resolv.conf
cat: /etc/resolv.conf: No such file or directory
$ echo x > /etc/resolv.conf
/system/bin/sh: can't create /etc/resolv.conf: Read-only file system
```

于是 Go 拿到的是空配置，退化成 `[::1]:53`：

```
$ ./magpie sync
magpie: Get "https://models.dev/api.json": dial tcp: lookup models.dev on [::1]:53:
        read udp [::1]:39400->[::1]:53: read: connection refused
```

`GODEBUG=netdns=go+2` 确认了走的是 Go 解析器，且 `hostLookupOrder(models.dev) = files,dns`，但 files 里没有，DNS 服务器被判成 `::1`。

模拟器实际 DNS 是 `10.0.2.3` / `10.0.2.4` / `10.0.2.5`（`dumpsys connectivity` 的 `DnsAddresses`），但 Go 推断不出来。

**这是本项目最关键的约束**，也是必须上 PRoot 的唯一原因。

## 3. PRoot + resolv.conf 让 DNS 恢复

用 Termux 的 proot 5.1.107.102（x86_64），依赖 `libtalloc.so.2` 与 `libandroid-shmem.so`（同仓库 `libtalloc` / `libandroid-shmem` 包）。三个环境变量必须给：

| 变量 | 值 | 缺了会怎样 |
|---|---|---|
| `PROOT_LOADER` | loader 的路径 | |
| `LD_LIBRARY_PATH` | 放 `libtalloc.so.2` 的目录 | `CANNOT LINK EXECUTABLE: library "libtalloc.so.2" not found` |
| `PROOT_TMP_DIR` | 一个可写目录 | `proot error: can't create temporary directory` |

绑定一个自己写的 resolv.conf：

```
./proot -b <dir>/resolv.conf:/etc/resolv.conf -0 <dir>/magpie sync
```

DNS 错误消失，换成证书错误：

```
magpie: Get "https://models.dev/api.json": tls: failed to verify certificate:
        x509: certificate signed by unknown authority
```

## 4. 补 CA 证书后整条链路通

再绑一个 CA 包：

```
./proot -b <dir>/resolv.conf:/etc/resolv.conf \
        -b <dir>/ca-certificates.crt:/etc/ssl/certs/ca-certificates.crt \
        -0 <dir>/magpie sync
```

结果：

```
✓ catalog saved to <dir>/data/cache/models.json
```

`magpie web` 同样干净启动，没有 DNS 报错：

```
● magpie web on http://127.0.0.1:3430/?k=<key>
  the link carries MAGPIE_WEB_KEY · gateway http://127.0.0.1:3425 · Ctrl-C to stop
```

## 5. 完整 web UI 在手机上能显示

`adb forward tcp:13430 tcp:3430` 后，从主机验证：

| 检查 | 结果 |
|---|---|
| `GET /?k=<key>` | `303`（换 Cookie） |
| `GET /api/state`（带 Cookie） | JSON，含 `clients` 全量供应商 |
| `GET /`（带 Cookie） | 46321 字节 HTML |

在模拟器 Chrome 打开后截图确认：magpie logo、导航栏（Sessions / Providers / Routing / Gateway / Usage）、`Add your first provider`、Partners 与 Subscriptions 列表全部正常渲染，手机宽度下排版可用。

## 6. 便携模式有效

二进制旁边放一个 `data/` 目录，配置就写进 `data/`（`data/cache/models.json` 已实测生成），不碰 `$HOME`。这让 App 可以把整套状态放进自己的私有目录，重置/卸载可控。

## 7. 已知的模拟器环境问题（与方案本身无关）

Chrome 走 Vulkan 渲染会让模拟器（`-gpu swiftshader_indirect`）崩溃，日志里是 `Created VkDevice ... for application:'Chromium'` 后进程消失。用 `-feature -Vulkan -gpu swiftshader_indirect` 可稳定运行。

真机上不存在这个问题。

## 未验证

以下都还没做，不能当成结论：

- **真机 arm64**：所有实测都在 x86_64 模拟器上，arm64 真机未测（需要你的手机）。
- **原生 `android-arm64` 二进制**：它是 bionic ELF（`interp=/system/bin/linker64`），理论上可不上 PRoot 直跑，但同样受 `/etc/resolv.conf` 约束，且插件用的 Bun 要 Android 版构建，未验证。
- **插件系统**：Bun 下载（`bun-linux-aarch64-android` / `bun-linux-x64-android-baseline`）在 Android 上能否实际运行未验证。
- **作为网关给 DSH 用**：`127.0.0.1:3425` 的 `/v1/*` 从 DSH Go 侧实际调用未验证（只验证了 3430 的管理页）。
- **长期运行**：前台服务、电量、Doze 下的稳定性未验证。

## 复现步骤

```bash
# 1. 取二进制
curl -L -o magpie https://github.com/yetone/magpie/releases/latest/download/magpie-cli-linux-amd64

# 2. 取 proot（Termux x86_64）与依赖
#    proot_5.1.107.102_x86_64.deb / libtalloc_2.5.0_x86_64.deb / libandroid-shmem_0.7_x86_64.deb
#    来源：https://packages.termux.dev/apt/termux-main/pool/main/p/proot/ 等

# 3. 推上去
adb push magpie /data/local/tmp/magpie-bin/magpie
adb push proot /data/local/tmp/magpie-bin/proot
adb push loader /data/local/tmp/magpie-bin/loader
adb push libtalloc.so.2 /data/local/tmp/magpie-bin/
adb push libandroid-shmem.so /data/local/tmp/magpie-bin/
adb push cacert.pem /data/local/tmp/magpie-bin/etc/ca-certificates.crt
adb shell 'echo "nameserver 10.0.2.3" > /data/local/tmp/magpie-bin/resolv.conf'

# 4. 跑
adb shell 'cd /data/local/tmp/magpie-bin && mkdir -p tmp && \
  PROOT_TMP_DIR=/data/local/tmp/magpie-bin/tmp \
  PROOT_LOADER=/data/local/tmp/magpie-bin/loader \
  LD_LIBRARY_PATH=/data/local/tmp/magpie-bin \
  ./proot -b /data/local/tmp/magpie-bin/resolv.conf:/etc/resolv.conf \
          -b /data/local/tmp/magpie-bin/etc/ca-certificates.crt:/etc/ssl/certs/ca-certificates.crt \
          -0 /data/local/tmp/magpie-bin/magpie web --addr 127.0.0.1:3430 --no-open'

# 5. 看
adb forward tcp:13430 tcp:3430
# 浏览器打开 http://127.0.0.1:13430/?k=<MAGPIE_WEB_KEY>
```

## 对设计的直接影响

1. **不需要 1GB rootfs**。dsh-Go 那套 Ubuntu 24.04 解压是不必要的；这里只需 proot（277KB）+ loader（18KB）+ 两个 .so（47KB）+ magpie（68MB）+ CA 包（196KB）。
2. **resolv.conf 必须动态生成**。租户网络的 DNS 会变（Wi-Fi / 移动数据 / 私有 DNS），写死 `10.0.2.3` 只在模拟器成立。真机上应从 `ConnectivityManager` 读当前 DNS 再写文件。
3. **CA 包可以静态内置**，也可以从系统 `/system/etc/security/cacerts` 合并，但内置 `cacert.pem` 最简单且可控。
4. **启动必须是前台服务**，因为 magpie 是长期驻留的进程。
5. **首次启动要能离线降级**：`models.dev` 拿不到时 magpie 只是少了模型目录，网关仍可服务；App 不应把目录拉取失败当成致命错误。

## 8. 便携模式必须显式 `.portable`（真机实测）

只在二进制旁放 `data/` 目录不够。`/data/local/tmp` 是共享目录，magpie 会拒绝把它当作便携数据目录，转而写进 `~/.config/magpie`：

```
appdir: <dir>/data is beside magpie but not taken for its portable data:
it is in a shared folder and <dir>/.config/magpie has magpie's files too;
a .portable file beside magpie makes it portable
```

后果：`magpie settings set proxy X` 写进 `data/settings.json`，但运行时读的是 `.config/magpie/settings.json`，设置**看起来成功、实际不生效**。

解决：在二进制旁放一个 `.portable` 文件。实测加上后，只生成 `data/`，没有 `.config` 与 `.cache`，设置与读取一致。

**App 必须在解压后立刻创建 `.portable`**，否则所有设置都会写错地方。

## 9. 全局代理可由 CLI 修改（真机实测）

```
$ magpie settings set proxy http://127.0.0.1:7890
✓ proxy http://127.0.0.1:7890
$ magpie settings get proxy
http://127.0.0.1:7890
```

写进 `data/settings.json` 的 `proxy` 与 `proxyCustom` 两个字段。

这给「自动回退代理」提供了实现基础：App 不需要自己实现代理逻辑，只需要
在检测到直连失败时调用这条命令并重启 magpie。

## 10. 普通应用权限下能跑（真机 + `nativeLibraryDir`）

前面 §1–§9 都是 `adb root` 下的验证。本节是**普通应用身份**的验证，这才是 App 的真实运行条件。

### 结论

| 项 | 结果 |
|---|---|
| `nativeLibraryDir` 里的 `libmagpie.so` 可执行 | ✅ `magpie 0.1.2993` |
| `nativeLibraryDir` 里的 `libproot.so` 可执行 | ✅ `5.1.107.102`，`process_vm = yes, seccomp_filter = yes` |
| PRoot 在 App 身份下启动 magpie | ✅ |
| 真实 App 进程内 DNS 解析 | ✅ `models.dev` → `104.21.51.36, 172.67.220.122` |
| 真实 App 进程内 TCP | ✅ wlan DNS、公网 DNS、Clash 代理 7890 全部连通 |

**不需要 root。** PRoot 本来就是免 root 设计。

### 关键实现：可执行文件必须放 jniLibs

Android 10+ 对应用私有目录有 exec 限制。可执行文件以 `lib*.so` 形式放
`app/src/main/jniLibs/<abi>/`，并设 `packaging { jniLibs { useLegacyPackaging = true } }`，
安装后它们被解压到 `applicationInfo.nativeLibraryDir`（带 `x` 位，上下文 `apk_data_file`）。

实测安装后的目录：

```
-rwxr-xr-x system system libmagpie.so        65208482
-rwxr-xr-x system system libproot.so           277144
-rwxr-xr-x system system libproot-loader.so     18368
-rwxr-xr-x system system libtalloc.so           32536
-rwxr-xr-x system system libandroid-shmem.so    14368
```

`libtalloc.so.2` 这个**名字不匹配** Android 的 `lib*.so` 解压规则，所以：
jniLibs 里叫 `libtalloc.so`，运行时复制到私有目录并改名为 `libtalloc.so.2`，
再把该目录加进 `LD_LIBRARY_PATH`。

### 配置目录：用 XDG 重定向，不用 `.portable`

`nativeLibraryDir` 只读，无法在二进制旁建 `data/` 或 `.portable`。改用标准环境变量：

```
XDG_CONFIG_HOME=<filesDir>/magpie/config
XDG_CACHE_HOME=<filesDir>/magpie/cache
```

实测有效：`magpie settings set proxy …` 写进
`<filesDir>/magpie/config/magpie/settings.json`，二进制旁不产生任何文件。

### 一个必须记录的坑：`run-as` 会给出假阴性

用 `adb shell run-as <pkg>` 测试网络会**误判**——它的 SELinux 上下文是
`runas_app`，连 TCP 都建不起来，报 `operation not permitted`：

```
# run-as 环境下（假阴性，不能作为结论）
magpie: lookup models.dev on 192.168.2.1:53: write udp ...: operation not permitted

# 真实 App 进程内（正确结论）
dns models.dev  OK  104.21.51.36, 172.67.220.122
```

**所以网络能力必须在真实 App 进程内验证**，不能用 `run-as`。

### Clash TUN 与 loopback（补充）

真机开着 Clash（TUN 模式，黑名单，新应用默认走代理）时：
- 路由表把默认流量指向 `tun0`，`ip route get 192.168.2.1 uid <app-uid>` 显示走 `tun0`；
- 但 `local 127.0.0.1 dev lo scope host` 仍是最高优先，**loopback 不进 TUN**；
- 实测真实 App 进程内 `127.0.0.1:7890` 与 DNS 都连通。

这印证了 `design.md` §3.8 的判断，也说明**自动代理功能在真实用户环境里是必要的**——用户未必知道要在 Clash 里配置。

## 11. App 侧真机验证（普通应用身份，端到端）

以下全部在真机（arm64 / Android 16 / 非 root）上以 **App 自身 UID** 完成。

### 已验证通过

| 项 | 结果 |
|---|---|
| 冷启动状态恢复 | ✅ 直接进 `Stopped`，不退回安装页 |
| 断链自愈 | ✅ App 重装后 `nativeLibraryDir` 换路径，`libtalloc.so.2` 旧符号链接失效，启动时自动重建 |
| 进程启动 | ✅ `phase=Starting → Running`，`health_check_ok` |
| 进程归属 | ✅ `libproot.so` 与 `libmagpie.so` 都以 `u0_a463`（App 用户）运行 |
| 端口监听 | ✅ `127.0.0.1:3430`（管理页）与 `127.0.0.1:3425`（网关），进程名 `libmagpie.so` |
| 日志脱敏 | ✅ 输出为 `[process_output] [REDACTED]`，原始行不外泄 |
| App 内 HTTP 到 3430 | ✅ `code=401 len=428`（未带 key 时的正常响应） |
| 网关 `/v1/models` | ✅ `{"data":[],"has_more":false,"object":"list"}` |

**网关在手机上真实可用**，`127.0.0.1:3425` 由普通 App 提供，DSH Go 可以直接把它当 `baseURL`。

### WebView 空白问题：已定位并修复

**现象**：WebView 区域全白，magpie 管理页不显示。

**根因（不是 MIUI）**：WebView 被放在 Compose 的 `AndroidView` 里时，`factory`
正常执行、视图 `isAttachedToWindow=true`、尺寸 `1200x2322`、`getGlobalVisibleRect`
为真，但 `loadUrl` 虽然"成功返回"，WebView 内部的 `url` 恒为 `null`、
`progress` 恒为 `0`，页面永远不加载。

```
I MagpieUiTrace: webview_factory_enter
I MagpieUiTrace: startLoad attempt=1 attached=true
I MagpieUiTrace: loadUrl_returned url=null      ← 调用成功但 URL 没进去
I MagpieUiTrace: post2 w=1200 h=2322 progress=0 url=null
```

排查中被排除的干扰项：
- MIUI `contentcatcher` 抛的 `java.lang.Throwable` —— 探针里同样有，页面照样正常；
- `SwipeRefreshLayout` 包裹 —— 去掉后问题依旧；
- `addDirect()` 代理覆盖 —— 回调确认触发，不影响 loopback；
- `run-as` 显示连不上 —— 假的，真实 App 进程内 HTTP 正常。

**修法**：运行态绕开 Compose 的 `AndroidView`，改成
`Activity 根 FrameLayout` + `ComposeView`（各状态页）+ `MagpieWebContainer`
（原生 WebView 容器）并存，运行中把原生容器 `addView` + `bringToFront()`。

关键细节：**不能用 `setContent { }`**，它会把 Activity 的内容视图整个换成
Compose 根，之后 `addView` 的原生视图挂在被替换的旧容器上，看不见。必须用显式的
`ComposeView` 作为 root 的子视图。

**修复后实测**：magpie 完整界面正常渲染——八个页签（会话/供应商/路由/网关/
用量/脱敏…）、供应商列表（Kimi Code、Claude、ChatGPT、ChatGPT API、Copilot、
Gemini CLI、Antigravity 等）全部显示，且**免登录直接进入**（`?k=` 换 Cookie 生效）。

### 顺带修掉的两个缺陷

1. **端口占用误报**：`isPortFree()` 用了 `reuseAddress = false`，上一次 magpie
   退出留下的 TIME_WAIT 会让 bind 失败，于是"刚停就重启"被误判成"端口已被占用"。
   改成 `reuseAddress = true`（真被占用时 bind 仍会失败）。
2. **events() 多订阅冲突**：Application 与 UI 同时收集 `events()` 时，`channelFlow`
   内两个 `collect` 共用一个 channel 会互相阻塞，表现为界面停在「正在启动」。
   改成 `merge(state.map { ... }, logEvents.map { it })`。

### 最终真机验收（全部通过）

| 项 | 结果 |
|---|---|
| 安装 → 启动 → 运行 | ✅ 全流程无人工干预 |
| **magpie 管理页渲染** | ✅ 八个页签 + 供应商列表完整显示 |
| 页面交互 | ✅ 点「网关」页签正确切页并渲染面板 |
| 网关面板显示 | ✅ 「网关 运行中」、`http://127.0.0.1:3425`、接入 `.../v1` |
| **冷启动免登录** | ✅ 重启后直接进网关页，无登录界面（固定 key + Cookie 生效） |
| 单元测试 | ✅ 15 项全过（DNS 3、代理 3、日志 2、UI 7） |
| 生产包不含诊断页 | ✅ ProbeActivity 等已移入 debug 源集 |

## 12. 插件系统（实测可用，推翻早期判断）

早期设计与 `design.md` §5 曾判断「插件可能不可用」，理由是 magpie 会给插件下载 glibc 版
Bun，而 Android 用 bionic。**这个判断是错的**：Bun 官方提供 Android 原生构建。

### 事实

- 内置插件 **14 个，全部 `state: active`**（budget / gateway / groups / library /
  middleware / otel / redact / rotation / routing / search / sessions / tools / usage / warmups）。
  它们是编译进 magpie 的，不依赖 Bun，开箱可用。
- 第三方插件由 Bun 驱动。magpie 默认下载 `bun-linux-aarch64`（glibc），在 Android 上报：

  ```
  bun add @magpie-community/calc@latest: this system can't start the Bun magpie downloaded
  (bun-linux-aarch64, .../magpie-bun), though it is there: the build doesn't match this
  system's C library (glibc or musl).
  ```

- **Bun 官方有 Android 构建**：`bun-linux-aarch64-android.zip`。其 ELF 特征：

  ```
  ELF 64-bit LSB pie executable, ARM aarch64, dynamically linked,
  interpreter /system/bin/linker64, for Android 28, built by NDK r27c
  ```

  在真机上执行 `bun --version` 正常输出 `1.4.4`。

### 集成方式（已落地）

`MAGPIE_BUN` 环境变量指向随包的 Android 版 Bun。实测端到端成功：

```
✓ added @magpie-community/tools-calc@latest
  tools  calc_convert, calc_date, calc_eval

Web UI 插件页：Calc v0.1.0 [工具]
              给每个智能体的工具: calc_eval, calc_convert, calc_date
              插件运行在 Bun 1.4.4 上
```

运行中进程的环境变量确认注入成功：

```
MAGPIE_BUN=/data/user/0/com.magpie.android/files/magpie/bin/bun
```

### 打包取舍

- 放 `jniLibs` 会因 `useLegacyPackaging = true`（保执行位）**不压缩**，APK 从 74 MB 涨到 244 MB；
- 改放 `assets/runtime/bun-arm64`（可压缩），首次使用时解压到私有目录并赋执行位，
  APK 为 **136.6 MB**；
- 解压失败不阻断启动，只记 `bun_unavailable`，网关功能不受影响。

### 坑

Bun 必须放在 App 私有目录。放 `/data/local/tmp` 时 PRoot 内报 `permission denied`。


## 13. 一键导出日志（真机验证通过）

主界面左下角「导出日志」按钮 → 生成 `cache/diagnostics/magpie-diagnostics-<时间>.txt`
→ 走系统分享。实测产物：

```
magpie-android 诊断包
生成时间: 2026-11-05 05:19:43

[设备]
厂商: Xiaomi    型号: 24129PN74C
Android: 16 (API 36)    ABI: arm64-v8a

[运行时]
阶段: Running   已安装版本: 0.1.2993   正在运行: true
管理页端口: 3430   网关端口: 3425

[运行日志]
[system] process_start_requested
[process_output] [REDACTED]
[system] health_check_ok
```

### 脱敏设计

早期 `DefaultLogRedactor` 把整行替换成 `[process_output] [REDACTED]`——安全但没诊断价值。
诊断包改用 `LogSanitizer`：**按模式擦除敏感片段，保留排查必需信息**。

擦除：`?k=` 的 web key、`MAGPIE_WEB_KEY`、`api_key`/`token`/`secret`/`password` 等键值、
`Authorization` 与 `Bearer`/`Basic` 令牌、URL userinfo、代理里的凭据、App 私有绝对路径。

保留：错误类型、状态码、端口、主机名、域名（如 `models.dev`、`127.0.0.1:3430`）。

**顺序坑**：`Authorization: Bearer xxx` 若先被通用键值规则匹配，`Bearer` 会被当成"值"、
只擦掉 `Bearer` 本身，真令牌留在原地。必须先处理 Bearer/Authorization，再处理通用键值。

10 项脱敏单测固定了上述行为（`LogSanitizerTest`）。

### 按钮位置

放左下角。右上角是 magpie 自己的刷新/齿轮、左上角是它的 logo，
按钮压上去会导致那些控件点不到（实测过一次）。页内横幅方案也试过并被否决：
`enableEdgeToEdge` 下横幅会顶到状态栏底下，且长期占用网页空间；改由通知栏承载省电入口。

## 14. 权限与省电设置生效后的真机复核

用户在系统里授权后，三项状态全部确认（`adb shell dumpsys`）：

```
电池优化豁免:      user,com.magpie.android,10464        ← 已进白名单
通知总开关:        importance=DEFAULT userSet=true      ← 从 NONE 变 DEFAULT
POST_NOTIFICATIONS: granted=true USER_FIXED             ← 用户已固定授予
```

通知栏实测显示 **「Magpie · 网关运行中」**，`NotificationRecord` 的
`importance=2`、`flags=ONGOING_EVENT|NO_CLEAR|FOREGROUND_SERVICE` 均正常。

### 通知内容会随状态更新

豁免生效后，通知里「省电设置」那一项应当消失。但通知只在服务 `onCreate` 时构建一次，
用户之后去系统里改了豁免，旧通知还挂着失效按钮，看起来像设置没生效。

修法：`MagpieService.refreshNotification()` + `MainActivity.onResume()` 调用一次。
实测豁免后 `actions=` 字段消失，符合预期。

### 导出按钮的落位（试了三轮）

| 位置 | 问题 |
|---|---|
| 右下角大按钮 | 压住网页正文（「界面字体」那行被盖） |
| 右上角 | 压住 magpie 自己的刷新与齿轮，那些控件点不到 |
| 左下角 | 仍然压住正文末行 |

最终：右下角**小圆形图标**（`⤓`），长按显示「导出日志」说明。
实测不遮挡正文，点击可正常导出。

### 冷启动耗时实测

```
05:31:43.677  MainActivity Displayed（368 ms）
05:31:54.482  前台服务启动
05:31:54.834  状态进入 Running
```

`Running` 之后网页很快出现；中间约 11 秒是「点启动 → 服务拉起」本身的时间，
界面在此期间显示「正在启动」，不是卡死。

## 15. 自动代理真机验证（可用）

结论：**自动代理本身可用，不需要降级为手动配置。** 缺的是 UI 入口（尚未接入）。

真机环境：Clash 在 `127.0.0.1:7890`（HTTP 代理），App 以普通身份运行。

### 实测结果

| 用例 | 结果 | 判定 |
|---|---|---|
| `current()` | `FollowSystem` | ✅ |
| 直连境外 `api.anthropic.com` | `Direct(423ms)` usable | ✅ |
| 直连国内 `api.deepseek.com` | `Direct(112ms)` usable | ✅ |
| 目标不可达 `example.invalid` | `Unreachable`「直连和代理均不可达」 | ✅ 不误报 |
| 不存在域名 + 代理 | `Unreachable` | ✅ 不误报成功 |
| `decide(applyChanges=false)` | `Direct(434ms)` note=`probe_only` | ✅ 只探测不改设置 |
| loopback + 带 query | `Unreachable`「目标地址无效或包含凭据」 | ✅ 拒绝 |
| 目标带 userinfo | `Unreachable` | ✅ 拒绝 |
| `apply` 非法协议 | `Failure(IllegalArgumentException)` | ✅ 拒绝 |

### 修掉一个真实缺陷：`settings get proxy` 的引号

`magpie settings get proxy` 在没有设置时输出的是 **`""`（两个引号字符）**，不是空行：

```
$ magpie settings get proxy | od -c
0000000   "   "  

```

`trim()` 之后得到 `\"\"`，交给 `ProxySetting.parse` 被判成 `Custom("\"\"")`，
表现为「用户配置了一个空代理」。已在 `readProxy()` 里剥掉成对引号，修复后返回 `FollowSystem`。

### 已知边界（未修，记录在案）

`probe()` 在**直连成功时直接返回**，不会再校验 `proxy` 参数：

```
probe(https://api.anthropic.com, proxy="http://user:pass@127.0.0.1:7890")
  -> Direct(570ms)      ← 期望是拒绝
```

影响有限：直连可用时代理本来不会被使用，凭据也不会被发送。
但若调用方期望「非法代理一律报错」，这里是漏洞。`decide()` 走的是候选列表，
每个候选都会先 `ProxyAddress.parse` 校验，不受影响。

另：单次探测的超时只覆盖 socket 的 connect/read，`InetAddress` 解析没有独立截止时间。

## 16. 半自动代理界面（已接入真机验证）

入口：右下角工具图标 → 「网络代理」。实现见 `ui/ProxySettingsDialog.kt`。

### 为什么是半自动

探测本身可靠，但**替用户悄悄改 magpie 的全局代理**影响面太大：他可能已经在
magpie 的 Providers 页给单个服务商配了代理，全局值一改会牵连所有没单独配置的服务商。
所以只做两件事：用户点「检测」才探测，用户点「应用」才写入并重启。

### 真机验证

| 操作 | 界面结论 |
|---|---|
| 打开对话框 | `当前：跟随系统`（引号 bug 修复后正确） |
| 检测 `api.anthropic.com` + `127.0.0.1:7890` | `直连可用（489 ms），不需要代理。`「使用这个代理」保持禁用 |
| 检测 `www.google.com`（TUN 黑名单下直连实际走代理） | `直连可用（114 ms）` |
| 检测 `example.invalid` | `直连和该代理都不可达。` 不误报、不禁用错按钮 |
| 点「改回跟随系统」 | `已应用。网关已重启以读取新设置。` |
| 外部写入 `127.0.0.1:7890` 后重开对话框 | `当前：127.0.0.1:7890`（读得回真值） |
| 再点「改回跟随系统」 | 恢复 `""`，与初始状态一致 |

写入路径的真实性用 CLI 独立核对过：

```
$ magpie settings set proxy 127.0.0.1:7890
✓ proxy 127.0.0.1:7890
$ magpie settings get proxy
127.0.0.1:7890        ← 有值时不带引号
$ magpie settings get proxy | od -c     （清空后）
0000000   "   "  
                        ← 空值带引号，这就是 §15 那个 bug 的来源
```

### 设计要点

- 只探测不改：「使用这个代理」在直连可用或不可达时都是禁用状态，避免误导。
- 代理地址先过 `ProxyAddress.validateSetting`，带凭据/非 http/socks5 的直接拒绝并给出原因。
- 工具入口在 `onCreate` 挂载，**启动页也能用**——用户可以先配好代理再启动网关。
  （早前只在 `showWeb` 里挂，启动页看不到入口，已修。）

### 未覆盖

- 代理需要认证（`http://user:pass@host`）的情况直接拒绝，不支持。
- 探测只验证 TCP/TLS 与 HTTP 状态可达，不校验该代理对具体服务商的可用性。

## 17. 导航、下载与崩溃恢复（真机验证）

这一轮系统过了一遍非核心功能，**发现三个真实缺陷，均已修复**。

### 缺陷一：导航策略与下载处理被丢失（功能回退）

重写 `MagpieWebContainer` 时漏掉了外部链接拦截与下载监听：

```
grep WebNavigationPolicy  →  只有测试文件引用它，主代码零调用
```

`WebNavigationPolicy` 有 7 项单测保护，却成了死代码。后果是 magpie 页面里的外链
会直接在 App 内的 WebView 打开——没有地址栏也没法返回，用户会困在里面。

已补回：`shouldOverrideUrlLoading` 按 `Internal` / `External` / `Blocked` 三分支处理，
外加 `setDownloadListener`（下载 URL 去掉 `k` 参数后交给系统 `DownloadManager`）。

真机验证（用 CDP 注入 `location.href` 模拟点击）：

| 注入的 URL | 结果 |
|---|---|
| `https://example.com/probe` | 弹出 `com.miui.securitycenter/ConfirmStartActivity`（系统接管），WebView URL 不变 |
| `file:///etc/passwd` | 直接拦掉，WebView URL 仍是 magpie 页面 |

### 缺陷二：渲染进程崩溃后不恢复

杀掉 chromium 渲染进程后（`Renderer process crash detected (code -1)`）：

- **修复前**：WebView 从视图树里彻底消失（`grep -c WebView` = 0），界面回落成
  占位页「正在启动」，用户只能杀 App。原因是 `onRenderProcessGone` 完全没实现，
  且 `pageUrl` 没变化不会触发 Compose 的 `LaunchedEffect`。
- **修复后**：`onRenderProcessGone` 返回 `true` 并拆掉容器，然后 `post { showWeb }` 重建。

修复后实测日志：

```
W MagpieWeb: renderer gone crashed=false
W MagpieWeb: renderer gone, rebuilding generation=1
I MagpieWeb: showWeb existing=false childCount=2
→ WebView 数量 = 1（没有双份）
→ 页面正常显示「网关 运行中」面板
```

### 缺陷三：`showWeb` 依赖 tag 查找导致重复挂载风险

早前用 `root.findViewWithTag(WEB_TAG)` 判断是否已挂载。渲染进程崩溃时旧容器
残留，tag 仍在，新容器又建了一个，视图树里出现**两个叠在一起的 WebView**
（一个 `clickable=false` 的死的、一个 `clickable=true` 的活的）。
现在崩溃回调里先 `removeView` 再重建，实测恢复后只有 1 个。

### 其他已验证正常的行为

| 项 | 结果 |
|---|---|
| 返回键消费 WebView 历史 | `?view=usage` → 返回 → `?view=settings&tab=network`，App 未退出 |
| 历史耗尽后返回 | 正确退到桌面 `com.miui.home/.launcher` |
| 切后台再回来 | WebView 保留，服务未中断 |
| WebView 挂载 | `parent=ViewFactoryHolder`、尺寸 `1200x2322` |

### 顺带发现

magpie 自己就有代理设置（设置 › 网络与共享 › 代理：测试 / 自动 / 关闭 / 自定义），
和 App 的半自动代理界面互补。App 只改全局默认值，单服务商仍走 magpie 自己的页面。

## 18. WebView 窄屏排版适配（真机验证）

magpie 的 Web UI 按桌面宽度设计。本机屏幕 370dp、`devicePixelRatio` 3.25，布局宽度
`innerWidth = 369`，多处排版在这个宽度下会坏掉。

适配集中在 `ui/MagpieWebContainer.kt` 的 `applyNarrowScreenFixes()`：`onPageFinished`
时注入一个 `<style id="magpie-android-tune">` 和少量 JS。**只改窄屏排版，不碰 magpie 源码**。

所有数字都是通过 CDP 在真机上读 `getBoundingClientRect()` 得到的，不是目测。

### 18.1 供应商编辑弹窗标题行 `.ehead`

这是个 flex 行：图标 + 名称 + "Token Plan · 按量付费" + 弹性占位 + 站外链接 + 开关。

| 状态 | 容器宽 | 标题区高 | 名称 | 说明 | 链接 |
|---|---|---|---|---|---|
| 原始 369dp | 353 | 85 | 46×39（2 行） | **32×84（6 行）** | 166 |
| 加宽 viewport 到 480 | 464 | 48 | 72×39 | 98×34 | 166 |
| **最终方案** | 353 | 48 | 88×20 单行 | 120×17 单行 | 32 |

试过把 viewport 的 `width` 提到 480：这一行确实恢复（98×34），但**整页跟着变宽**，
所有元素都变，还要缩小才装得下，反而更难看。改为让这一行的成员
`flex: 0 0 auto`、`white-space: nowrap`，并把链接文字换成「官网」。

链接换短标签会丢掉"指向哪个域名"的信息——而这在排查时有用（本次正是靠
`platform.xiaomimimo.com` 与 `token-plan-cn.xiaomimimo.com` 的差异判断出 preset 走的是
国内套餐入口），所以原域名仍写进 `title`。

实测：总宽 292 < 容器 353，五个成员 `top` 全部为 58，确实同一行。

### 18.2 选项组 `.segs`（「区域」「模型」）

成员宽度合计超出容器，最后一个选项被折到第二行（高度 60）。

改成整组 `flex-wrap: nowrap` + 选项内文字 `white-space: normal`、居中、`line-height: 1.25`。
实测四组全部回到单行，高度 60 → 32：

```
套餐·中 / 套餐·新加 / 套餐·欧 / 按量付
   国        坡         洲        费
```

**踩过的坑**：先用 `overflow-x: auto` 做横向滚动。CSS 里只设 x 轴会让 y 轴的
`visible` 一并计算成 `auto`，容器因此出现纵向滚动条，看起来像页面在上下滚。已改掉。

### 18.3 底部操作栏 `.bar`

「保存」被挤成 `44×44`，两个字竖排。

```
.bar  宽 353，子项合计 352 + 间距 → 刚好溢出
      移除 44 | 再添加一个 Xiaomi MiMo 155 | 复制 44 | grow 21 | 取消 44 | 保存 44
```

元凶是弹性占位 `.grow` 占掉 21px。`display: none` 收起后：

```
barH 93 → 48，total 331 < 353，全部单行，「保存」拿到自然宽度
```

### 18.4 插件页手动安装区 `.pm-manual`

```
FORM.pm-manual  337×118  (flex, wrap)
  ├ SPAN.pm-mlabel   72×17   top=664
  ├ INPUT.pm-minput 232×44   top=651   ← 提示文字需要 527px，被切掉大半
  └ BUTTON.text      44×44   top=703   ← 与输入框错位 52px
```

改成两行网格（`grid-template-columns: 1fr auto`）：标签 `grid-column: 1/-1` 独占一行，
输入框与按钮同一行 `align-items: center`。实测 `rowH 118 → 91`，输入框 `232 → 260`，
两者 `top` 都是 703。

### 18.5 「npm 源」那一行 `.pm-registry`

```
DIV.pm-registry  337×73  (flex, wrap, gap 8)
  npm 源 40 | https://registry.npmjs.org 187 | npm 官方源 64   → 合计 307
  .grow 261（被挤到第二行）| BUTTON.text 44 → 「修改」竖排、截成单个「修」
```

同样收起 `.grow`，并给按钮 `min-width: 64px`。实测按钮宽 44 → 64，
「修改」完整显示（折到第二行，因为前三个元素已占满 313px，这是 337px 宽度下的必然结果）。

### 18.6 路由页「还没有请求」栏 `.rt-accts`

```
OL.rt-accts  304×38  padding: 0 0 0 22px   ← 元凶
  LI.idle    282×38  left shift = 22
```

它是 `<ol>`，浏览器默认的 `padding-left: 22px` 没有被 magpie 重置；而 magpie 已设
`list-style: none`，根本不需要留序号位置。整栏因此比上方卡片右移 22px，看起来是歪的。

清掉后：`left shift 22 → 0`，`li` 宽 `282 → 304`，与卡片左边缘齐平。
全站扫过，只有这一处 `<ol>/<ul>` 还带默认缩进。

### 18.7 其它

- **隐藏「更新」按钮**（`button.update`，54×36）：它跳 GitHub 发布页，但 App 里的
  magpie 版本随 APK 打包，用户在那里更新不了。`display: none` 隐藏 `.update-wrap`。
- **关闭 WebView 内置缩放控件**：`builtInZoomControls = true` 时，这台设备仍在右侧
  浮出上下两个箭头，盖住页面右下角的按钮；`displayZoomControls = false` 压不住。
  改为 `builtInZoomControls = false`，双指缩放靠 `setSupportZoom(true)` 保留。
- **输入框字号 16 → 17px**：magpie 用 16px 字配 `min-height: 44px`（移动端可点尺寸，
  不该缩小），字明显偏小。注意别去改 `height`——`min-height: 44px` 会把它顶回来，
  改了也没用。

### 18.8 验证方式

改这些规则不需要每次装机。debug 变体的 WebView 开了调试：

```bash
PID=$(adb -s <serial> shell pidof com.magpie.android)
adb -s <serial> forward tcp:9222 localabstract:webview_devtools_remote_$PID
curl -s http://127.0.0.1:9222/json
```

再用 `Runtime.evaluate` 读 `getBoundingClientRect()`，判断某个元素是否折行、是否被
`text-overflow: ellipsis` 截断、各成员 `top` 是否在同一行。
本轮所有结论都来自这套测量；目测截图会误判——例如 44px 高的按钮按
`height/lineHeight` 算会被错误地当成 3 行。
