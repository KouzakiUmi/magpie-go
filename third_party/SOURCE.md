# 二进制与对应源码

本目录保存随 Magpie Go 分发的第三方组件的许可证和对应源码，便于审查与再构建。独立 Magpie Go 代码的 CC BY-NC 许可不改变这些上游组件的许可证。

## Magpie

- 版本：`0.1.2993`
- 发布页：<https://github.com/yetone/magpie/releases/tag/v0.1.2993>
- 源码：<https://github.com/yetone/magpie/tree/v0.1.2993>
- 二进制：`app/src/main/jniLibs/arm64-v8a/libmagpie.so`
- 二进制 SHA-256：`f871152ff473bab587f62c99400c0d04c04de25cb8c43304e0aa185f4cd0df81`
- 许可证：`magpie/LICENSE`（MIT，随二进制附带）。

## PRoot / PRoot Loader

- 版本：`5.1.107.102`
- 上游源码：<https://github.com/termux/proot/tree/v5.1.107.102>
- 对应源码目录：`proot/src/proot-5.1.107.102/`
- 源码压缩包：<https://github.com/termux/proot/archive/v5.1.107.102.zip>
- 压缩包 SHA-256：`30d12a6e2cc4147123756ff58edea262d3083bbc238c12d0ab9b39e3d1c40852`
- Termux 构建定义：`proot/termux-build.sh`
- 许可证：`proot/COPYING`（GPL-2.0）。
- 当前发布二进制位于 `app/src/main/jniLibs/arm64-v8a/libproot.so` 和 `libproot-loader.so`，均为 Android ARM64。

## talloc

- 版本：`2.5.0`
- 上游源码：<https://www.samba.org/ftp/talloc/talloc-2.5.0.tar.gz>
- 对应源码目录：`talloc/src/talloc-2.5.0/`
- 压缩包 SHA-256：`912afa237510ae542a7733998eb18a12bcda35ab6729c8e2ddb43e8d0ebab007`
- Termux 构建定义：`talloc/termux-build.sh`
- 许可证：源码头声明 LGPL-3.0-or-later，见 `talloc/COPYING.LGPL`；Termux 包元数据中的 GPL-3.0 声明和许可证另存于 `talloc/termux-build.sh` 与 `talloc/COPYING`。
- 当前发布二进制为 `app/src/main/jniLibs/arm64-v8a/libtalloc.so`。

## android-shmem

- 上游：<https://github.com/pelya/android-shmem>
- 许可证：`android-shmem/LICENSE`（BSD-2-Clause）。
- 当前发布二进制为 `app/src/main/jniLibs/arm64-v8a/libandroid-shmem.so`。
- PRoot 的 Android 构建通过 `PROOT_WITH_LIBANDROID_SHMEM=true` 使用该兼容层，见 `proot/termux-build.sh`。

## Android / Kotlin / Gradle / JUnit

- AndroidX、Jetpack Compose、Kotlin、Kotlin coroutines、Android Gradle Plugin 与 Gradle wrapper 使用 Apache-2.0，许可证见 `android-libraries/LICENSE-2.0.txt`。
- 版本以 `app/build.gradle.kts`、`gradle/libs.versions.toml` 和 `gradle/wrapper/gradle-wrapper.properties` 为准。
- JUnit 4 使用 EPL-1.0，许可证见 `junit/LICENSE-junit.txt`。

## CA certificates

- 文件：`app/src/main/assets/ca-certificates.crt`，是 curl CA Extract 生成的公开 CA 根证书集合。
- 来源说明：<https://curl.se/docs/caextract.html>；Mozilla `certdata.txt`：<https://raw.githubusercontent.com/mozilla-firefox/firefox/refs/heads/release/security/nss/lib/ckfw/builtins/certdata.txt>。
- Mozilla NSS 许可证见 `ca-certificates/COPYING`。各 CA 自身的服务条款不因此变成应用私钥。

## 不随仓库分发的组件

- `assets/proot/` 中的旧 x86_64 PRoot 副本不进入发布仓库；当前目标为 ARM64。
- Bun 不进入发布仓库。需要时由 `tooling/download-bun.py` 从官方 release 恢复，版本和压缩包哈希固定。
