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
