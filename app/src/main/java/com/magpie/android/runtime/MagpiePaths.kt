package com.magpie.android.runtime

import android.content.Context
import java.io.File

/** All executable and writable paths used by the backend live here. */
class MagpiePaths(context: Context) {
    /** 可整体覆写的运行时；与用户配置、登录态和备份分离。 */
    val root = File(context.filesDir, "runtime")
    /** 需要跨运行时更新保留的用户数据。 */
    val dataRoot = File(context.filesDir, "magpie")
    val binDir = File(context.applicationInfo.nativeLibraryDir)
    val proot = File(binDir, "libproot.so")
    val loader = File(binDir, "libproot-loader.so")
    val magpie = File(binDir, "libmagpie.so")
    val bundledTalloc = File(binDir, "libtalloc.so")
    val shmem = File(binDir, "libandroid-shmem.so")

    /**
     * Bun 的 Android 构建，插件靠它运行。
     *
     * 为什么需要单独提供：magpie 默认下载 `bun-linux-aarch64`，那是 glibc 版，
     * Android 用的是 bionic，执行时报 "no such file or directory"。Bun 官方另有
     * `bun-linux-aarch64-android` 构建（ELF 的 interpreter 指向 /system/bin/linker64），
     * 通过 `MAGPIE_BUN` 环境变量指过去就能跑。
     *
     * 为什么在私有目录而不是 nativeLibraryDir：它随包放在 assets 里（可压缩，
     * APK 少约 60 MB），首次使用时解压到这里并赋执行位。
     */
    val bun = File(root, "bin/bun")

    val lib = File(root, "lib")
    val talloc = File(lib, "libtalloc.so.2")
    val run = File(root, "run")
    val tmp = File(run, "tmp")
    val resolv = File(run, "resolv.conf")
    val etc = File(root, "etc")
    val ca = File(etc, "ca-certificates.crt")
    val config = File(dataRoot, "config")
    val cache = File(dataRoot, "cache")
    val home = File(dataRoot, "home")

    fun prepareDirectories() {
        listOf(root, lib, run, tmp, etc, config, cache, home).forEach {
            check(it.isDirectory || it.mkdirs()) { "无法准备运行时私有目录" }
        }
    }
}
