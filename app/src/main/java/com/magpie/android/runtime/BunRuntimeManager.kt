package com.magpie.android.runtime

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipFile
import javax.net.ssl.HttpsURLConnection

/** Downloads the official Android Bun build used by Magpie's optional plugin runtime. */
internal object BunRuntimeManager {
    const val VERSION = "1.4.4"
    private const val URL = "https://github.com/oven-sh/bun/releases/download/bun-v$VERSION/bun-linux-aarch64-android.zip"
    private const val ZIP_SHA256 = "d72e42c60e895ccbc16144bb1e0e0ad7ee384841c35b5501a7ec28b4d6d759c9"
    private const val MAX_ZIP_BYTES = 64L * 1024 * 1024
    private const val MAX_BINARY_BYTES = 160L * 1024 * 1024
    private const val MAX_DURATION_NANOS = 300L * 1_000_000_000L
    private val installMutex = Mutex()

    fun isInstalled(context: Context): Boolean = MagpiePaths(context).bun.let { it.isFile && it.canExecute() }

    suspend fun install(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        installMutex.withLock {
        val paths = MagpiePaths(context.applicationContext)
        val archive = File(context.cacheDir, "bun-$VERSION.zip.part")
        val staged = File(paths.root, "bin/bun.tmp")
        val deadline = System.nanoTime() + MAX_DURATION_NANOS
        try {
            download(archive, deadline)
            check(sha256(archive) == ZIP_SHA256) { "Bun 安装包校验失败" }
            staged.parentFile?.let { check(it.isDirectory || it.mkdirs()) { "无法准备插件运行时目录" } }
            ZipFile(archive).use { zip ->
                val entry = zip.entries().asSequence().singleOrNull {
                    !it.isDirectory && (it.name == "bun" || it.name.endsWith("/bun"))
                } ?: throw IllegalStateException("Bun 安装包内容不符合预期")
                check(entry.size <= MAX_BINARY_BYTES) { "Bun 可执行文件过大" }
                zip.getInputStream(entry).use { input ->
                    FileOutputStream(staged).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            check(System.nanoTime() < deadline) { "Bun 下载或安装超时" }
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            check(total <= MAX_BINARY_BYTES) { "Bun 可执行文件过大" }
                            output.write(buffer, 0, count)
                        }
                    }
                }
            }
            check(staged.length() > 0 && staged.setExecutable(true, true)) { "无法设置 Bun 执行权限" }
            java.nio.file.Files.move(
                staged.toPath(),
                paths.bun.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
            check(isInstalled(context)) { "Bun 安装未完成" }
            Result.success(Unit)
        } catch (error: Exception) {
            staged.delete()
            Result.failure(IllegalStateException("Bun 安装失败：${error.message ?: error.javaClass.simpleName}"))
        } finally {
            archive.delete()
        }
        }
    }

    private fun download(target: File, deadline: Long) {
        var current = URL
        repeat(6) { redirect ->
            check(System.nanoTime() < deadline) { "Bun 下载或安装超时" }
            val connection = URL(current).openConnection() as HttpsURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.setRequestProperty("User-Agent", "Magpie-Go/$VERSION")
                val status = connection.responseCode
                if (status in 300..399) {
                    val location = connection.getHeaderField("Location") ?: throw IllegalStateException("下载重定向无效")
                    current = URL(URL(current), location).toString()
                    check(current.startsWith("https://")) { "下载重定向不受支持" }
                    return@repeat
                }
                check(status == HttpURLConnection.HTTP_OK) { "下载失败（HTTP $status）" }
                val length = connection.contentLengthLong
                check(length <= 0 || length <= MAX_ZIP_BYTES) { "Bun 安装包过大" }
                connection.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            check(System.nanoTime() < deadline) { "Bun 下载或安装超时" }
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            check(total <= MAX_ZIP_BYTES) { "Bun 安装包过大" }
                            output.write(buffer, 0, count)
                        }
                    }
                }
                return
            } finally {
                connection.disconnect()
            }
        }
        throw IllegalStateException("下载重定向次数过多")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
