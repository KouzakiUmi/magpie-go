package com.magpie.android.runtime

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import androidx.core.content.ContextCompat
import com.magpie.android.data.DnsResolver
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal class ProxyRestartException : IllegalStateException("代理已写入，但进程重启失败")

/** Single in-process owner of the packaged magpie process and its foreground service. */
class AndroidMagpieRuntime private constructor(context: Context) : MagpieRuntime {
    companion object {
        private const val VERSION = "0.1.2993"
        private const val MAGPIE_SHA256 = "f871152ff473bab587f62c99400c0d04c04de25cb8c43304e0aa185f4cd0df81"
        private const val ASSET_BUN = "runtime/bun-arm64"
        private const val BASE_URL = "http://127.0.0.1:3430"
        private const val GATEWAY_URL = "http://127.0.0.1:3425"
        @Volatile private var instance: AndroidMagpieRuntime? = null

        fun getInstance(context: Context): AndroidMagpieRuntime = instance ?: synchronized(this) {
            instance ?: AndroidMagpieRuntime(context.applicationContext).also {
                it.restoreStateOnColdStart()
                instance = it
            }
        }
    }

    private val app = context.applicationContext
    private val paths = MagpiePaths(app)
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val state = MutableStateFlow(MagpieStatus())
    private val logEvents = MutableSharedFlow<MagpieEvent>(extraBufferCapacity = 256)
    private val logLock = Any()
    private val dnsLock = Any()
    private val recent = ArrayDeque<String>()
    private val redactor: LogRedactor = DefaultLogRedactor
    private var process: Process? = null
    private var processMonitorJob: kotlinx.coroutines.Job? = null
    private var activeConfig: MagpieConfig? = null
    private var validated = false
    @Volatile private var serviceReady: CompletableDeferred<Unit>? = null
    @Volatile private var serviceInstance: MagpieService? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun status(): MagpieStatus = state.value

    // Each collector gets a current state first; status changes are never represented by logs.
    /**
     * 状态与日志的合并流。
     *
     * 用 `merge` 而不是 `channelFlow` + 内嵌 `launch`：后者里两个 `collect` 共用
     * 同一个 channel，某个收集者消费慢时会互相阻塞，实测表现为界面停在
     * 「正在启动」——因为收集者一旦跟不上，后面的状态更新就发不出去。
     *
     * `merge` 让两条流各自独立推送，任一订阅者都能持续收到。
     */
    override fun events(): Flow<MagpieEvent> = merge(
        state.map { MagpieEvent.Status(it) },
        logEvents.map { it },
    )

    /**
     * 冷启动时按磁盘上的实际情况给出初始状态，而不是一律 NotInstalled。
     *
     * 否则 App 每次重开都会退回「下载 magpie」页面，哪怕上一轮已经装好并跑着。
     * 这里只做只读探测，不启动任何进程。
     */
    private fun restoreStateOnColdStart() {
        // 只做便宜的只读探测：APK 里的可执行文件由系统管理，不会被篡改；
        // 私有目录里只关心安装时生成的 CA 与兼容名链接是否还在。
        // 不在这里重算 63 MB 的 SHA256——它由系统安装保证，且主线程上跑会卡 UI。
        //
        // 注意 libtalloc.so.2 是指向 nativeLibraryDir 的符号链接，而 App 每次重装
        // 都可能换一个 nativeLibraryDir（路径里带随机段），旧链接会变成断链。
        // 所以这里发现断链就地修复，而不是当成「没安装」。
        repairTallocLink()
        val assetsReady = paths.magpie.isFile && paths.ca.isFile && paths.talloc.isFile
        validated = assetsReady
        if (!assetsReady) return
        // 端口在听说明上一轮的 magpie 还活着（前台服务或系统留着的进程）
        val alive = isExpectedMagpieProcess()
        if (!alive) {
            update(MagpiePhase.Stopped)
            return
        }
        // 进程还活着，但内存里的 config 已经随上次的进程回收丢失了。
        // 这里必须把 URL 一并补齐：只把 phase 设成 Running 而 pageUrl 为空时，
        // 界面会判定"没有页面可显示"而停在启动页，看着像启动失败。
        // web key 从持久化存储取，恢复出来的 URL 才能直接免登录进入。
        val key: String? = runCatching { com.magpie.android.WebKeyStore.getOrCreate(app) }.getOrNull()
        activeConfig = MagpieConfig(webKey = key)
        val pidValue: Int? = runCatching { readPid(File(paths.run, "web.pid")) }.getOrNull()
        state.value = MagpieStatus(
            phase = MagpiePhase.Running,
            installedVersion = VERSION,
            pid = pidValue,
            baseUrl = BASE_URL,
            gatewayUrl = GATEWAY_URL,
            pageUrl = if (key != null) "$BASE_URL/?k=$key" else null,
        )
        // 接回存活进程的输出与退出监听，否则界面拿不到后续日志与退出事件
        attachToExistingProcess()
    }

    /**
     * 进程已在运行（例如界面上次被系统回收，而前台服务与 magpie 还在）时，
     * 把状态流重新接上：启动网络监听，并在进程退出时更新状态。
     */
    private fun attachToExistingProcess() {
        watchNetwork()
        processMonitorJob?.cancel()
        processMonitorJob = scope.launch {
            while (true) {
                delay(2000)
                val listening = isPortListening(3430)
                if (!listening) {
                    mutex.withLock {
                        if (state.value.phase == MagpiePhase.Running) {
                            activeConfig = null
                            unwatchNetwork()
                            systemLog("process_gone_detected")
                            update(if (validated) MagpiePhase.Stopped else MagpiePhase.NotInstalled)
                        }
                    }
                    return@launch
                }
            }
        }
    }

    /**
     * 把随包的 Bun（assets 里压缩存放）解到私有目录并赋执行位。
     *
     * 为什么不在 jniLibs：`useLegacyPackaging` 为了保住可执行位必须不压缩，
     * 107 MB 会原样进包；放 assets 能压缩到约 47 MB。代价是首次解压一次。
     *
     * 解压失败不抛：网关本身不依赖 Bun，缺了只是第三方插件不能用。
     */
    private fun extractBun() {
        try {
            val target = paths.bun
            val expected = runCatching {
                app.assets.openFd(ASSET_BUN).use { it.length }
            }.getOrNull()
            if (target.isFile && expected != null && target.length() == expected) return
            target.parentFile?.let { if (!it.isDirectory) check(it.mkdirs()) { "无法准备插件运行时目录" } }
            app.assets.open(ASSET_BUN, android.content.res.AssetManager.ACCESS_STREAMING).use { input ->
                target.outputStream().use { output -> input.copyTo(output, 1 shl 16) }
            }
            check(target.length() > 0) { "插件运行时解压失败" }
            check(target.setExecutable(true)) { "无法设置插件运行时执行权限" }
            systemLog("bun_extracted")
        } catch (e: Exception) {
            systemLog("bun_unavailable")
        }
    }

    /** 将当前 nativeLibraryDir 的 talloc 复制到私有 lib，避免重装卸链。 */
    private fun repairTallocLink() {
        if (!paths.bundledTalloc.isFile) return
        runCatching {
            paths.lib.mkdirs()
            paths.talloc.delete()
            paths.bundledTalloc.copyTo(paths.talloc, overwrite = true)
        }
    }

    private fun isPortListening(port: Int): Boolean = try {
        java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", port), 300) }
        true
    } catch (e: Exception) {
        false
    }

    override fun logs(lines: Int): List<String> = synchronized(logLock) {
        recent.toList().takeLast(lines.coerceIn(0, 200))
    }

    override suspend fun install(): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock { installLocked() }
    }

    private fun installLocked(): Result<Unit> {
        if (validated && paths.ca.isFile && paths.talloc.isFile) return Result.success(Unit)
        // 进程重启后内存标志归零，但资产其实还在磁盘上：
        // 先看磁盘，能直接用就不必再走一遍安装（否则冷启动会退回安装页）。
        if (paths.ca.isFile && paths.talloc.isFile && paths.magpie.isFile &&
            runCatching { sha256(paths.magpie) == MAGPIE_SHA256 }.getOrDefault(false)
        ) {
            validated = true
            update(MagpiePhase.Stopped)
            return Result.success(Unit)
        }
        update(MagpiePhase.Installing)
        return try {
            paths.prepareDirectories()
            listOf(paths.proot, paths.loader, paths.magpie, paths.bundledTalloc, paths.shmem).forEach {
                check(it.isFile && it.length() > 0) { "设备 ABI 不支持或 APK 缺少运行时资产" }
            }
            // Bun 缺失不阻断启动：网关本身不需要它，只是没装插件的用户会少一项功能。
            if (!paths.bun.isFile) systemLog("bun_missing_plugins_disabled")
            check(sha256(paths.magpie) == MAGPIE_SHA256) { "内置 magpie SHA256 校验失败" }
            // Keep native code in the read-only APK extraction directory: app-data code
            // mappings may be blocked on Android 10+. Only the compatibility name is writable.
            try {
                android.system.Os.remove(paths.talloc.absolutePath)
            } catch (e: android.system.ErrnoException) {
                if (e.errno != android.system.OsConstants.ENOENT) throw e
            }
            android.system.Os.symlink(paths.bundledTalloc.absolutePath, paths.talloc.absolutePath)
            app.assets.open("ca-certificates.crt").use { input -> paths.ca.outputStream().use(input::copyTo) }
            check(paths.ca.length() > 0 && paths.talloc.length() > 0) { "运行时资产不完整" }
            extractBun()
            validated = true
            update(MagpiePhase.Stopped)
            systemLog("runtime_assets_verified")
            Result.success(Unit)
        } catch (e: Exception) {
            validated = false
            fail("运行时资产准备失败：${safeReason(e)}")
            Result.failure(IllegalStateException("运行时资产准备失败：${safeReason(e)}"))
        }
    }

    override suspend fun start(config: MagpieConfig): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                require(config.webAddr == "127.0.0.1:3430" && config.gatewayAddr == "127.0.0.1:3425") {
                    "仅允许指定的 loopback 地址"
                }
                require(config.webKey == null || config.webKey.matches(Regex("[A-Za-z0-9._~-]{16,}"))) {
                    "web key 格式不正确"
                }
                if (process?.isAlive == true) return@withLock Result.success(Unit)
                if (isExpectedMagpieProcess()) {
                    // Activity 被回收后 Java Process 句柄可能丢失，但 magpie 仍在监听。
                    // 接回它，而不是再次启动并撞上同一个 loopback 端口。
                    val key = config.webKey ?: com.magpie.android.WebKeyStore.getOrCreate(app)
                    activeConfig = config.copy(webKey = key)
                    startService()
                    state.value = MagpieStatus(
                        phase = MagpiePhase.Running,
                        installedVersion = VERSION,
                        pid = readPid(File(paths.run, "web.pid")),
                        baseUrl = BASE_URL,
                        gatewayUrl = GATEWAY_URL,
                        pageUrl = "$BASE_URL/?k=$key",
                    )
                    attachToExistingProcess()
                    return@withLock Result.success(Unit)
                }
                installLocked().getOrThrow()
                val key = config.webKey ?: randomWebKey()
                val effective = config.copy(webKey = key)
                config.defaultProxy?.let { value ->
                    require(com.magpie.android.data.ProxyAddress.validateSetting(value)) { "代理地址无效或不受支持" }
                    prepareDns()
                    runCli(listOf("settings", "set", "proxy", value), capture = false)
                }
                startService()
                try {
                    startProcess(effective)
                } catch (e: Exception) {
                    stopService()
                    throw e
                }
                Result.success(Unit)
            } catch (e: CancellationException) {
                stopProcess()
                activeConfig = null
                stopService()
                update(if (validated) MagpiePhase.Stopped else MagpiePhase.NotInstalled)
                throw e
            } catch (e: Exception) {
                stopService()
                val message = safeReason(e)
                fail("启动失败：$message")
                Result.failure(IllegalStateException("启动失败：$message"))
            }
        }
    }

    private suspend fun startService() {
        if (serviceInstance != null) return
        val ready = CompletableDeferred<Unit>()
        serviceReady = ready
        try {
            ContextCompat.startForegroundService(app, Intent(app, MagpieService::class.java))
            withTimeout(10_000) { ready.await() }
        } finally {
            serviceReady = null
        }
    }

    /** 当前活着的前台服务实例，供界面刷新通知用。 */
    internal fun activeService(): MagpieService? = serviceInstance

    internal fun onServiceReady(service: MagpieService) {
        serviceInstance = service
        serviceReady?.complete(Unit)
    }
    internal fun onServiceError() { serviceReady?.completeExceptionally(IllegalStateException("前台服务启动失败")) }

    internal fun onServiceDestroyed(service: MagpieService) {
        if (serviceInstance !== service) return
        serviceInstance = null
        scope.launch {
            mutex.withLock {
                if (serviceInstance == null) {
                    stopProcess()
                    activeConfig = null
                    update(if (validated) MagpiePhase.Stopped else MagpiePhase.NotInstalled)
                }
            }
        }
    }

    private suspend fun startProcess(config: MagpieConfig) {
        check(isPortFree(3430) && isPortFree(3425)) { "本地端口已被占用" }
        prepareDns()
        update(MagpiePhase.Starting)
        systemLog("process_start_requested")
        val pidFile = File(paths.run, "web.pid")
        if (pidFile.exists()) check(pidFile.delete()) { "无法清理旧进程标记" }
        val child = buildProcess(listOf("web", "--addr", config.webAddr, "--no-open"), pidFile, config.webKey).start()
        process = child
        activeConfig = config
        captureLines(child.inputStream, LogStream.Stdout)
        captureLines(child.errorStream, LogStream.Stderr)
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            var healthy = false
            while (System.nanoTime() < deadline) {
                check(child.isAlive) { "magpie 进程提前退出" }
                if (healthCheck()) { healthy = true; break }
                delay(300)
            }
            check(healthy) { "管理页健康检查超时" }
            check(child.isAlive) { "magpie 进程提前退出" }
            state.value = MagpieStatus(
                phase = MagpiePhase.Running,
                installedVersion = VERSION,
                pid = readPid(pidFile),
                baseUrl = BASE_URL,
                gatewayUrl = GATEWAY_URL,
                pageUrl = "$BASE_URL/?k=${config.webKey}",
            )
            systemLog("health_check_ok")
            watchNetwork()
            processMonitorJob?.cancel()
            processMonitorJob = scope.launch {
                val code = child.waitFor()
                mutex.withLock {
                    if (process === child) {
                        process = null
                        unwatchNetwork()
                        activeConfig = null
                        systemLog("process_exited code=$code")
                        fail("magpie 进程意外退出")
                        stopService()
                    }
                }
            }
        } catch (e: Exception) {
            stopProcess()
            activeConfig = null
            throw e
        }
    }

    override suspend fun stop(): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                if (process != null) update(MagpiePhase.Stopping)
                stopProcess()
                activeConfig = null
                stopService()
                update(if (validated) MagpiePhase.Stopped else MagpiePhase.NotInstalled)
                Result.success(Unit)
            } catch (_: Exception) {
                fail("停止进程失败")
                Result.failure(IllegalStateException("停止进程失败"))
            }
        }
    }

    /** Returns true only if this operation actually restarted a running process. */
    internal suspend fun applyProxy(value: String): Result<Boolean> = withContext(Dispatchers.IO) {
        mutex.withLock {
            var restarting = false
            try {
                require(com.magpie.android.data.ProxyAddress.validateSetting(value)) { "代理地址无效或不受支持" }
                installLocked().getOrThrow()
                prepareDns()
                val previous = activeConfig
                runCli(listOf("settings", "set", "proxy", value), capture = false)
                if (previous != null && process?.isAlive == true) {
                    restarting = true
                    update(MagpiePhase.Stopping)
                    stopProcess()
                    startProcess(previous.copy(defaultProxy = null))
                    Result.success(true)
                } else Result.success(false)
            } catch (e: CancellationException) {
                if (restarting) {
                    stopProcess()
                    activeConfig = null
                    stopService()
                    update(if (validated) MagpiePhase.Stopped else MagpiePhase.NotInstalled)
                }
                throw e
            } catch (e: Exception) {
                if (restarting) {
                    activeConfig = null
                    fail("代理设置后重启失败")
                    if (process == null || process?.isAlive == false) stopService()
                    Result.failure(ProxyRestartException())
                } else {
                    Result.failure(IllegalStateException("代理设置失败：${safeReason(e)}"))
                }
            }
        }
    }

    /**
     * 读取全局代理设置。
     *
     * 注意 `magpie settings get proxy` 输出的是**带引号的字面量**：没有设置时是
     * `""`（两个引号字符），而不是空行。直接 trim 会得到 `\"\"`，再交给
     * `ProxySetting.parse` 就被判成 `Custom("\"\"")`，看起来像"用户配了一个空代理"。
     * 这里统一剥掉外层引号，让空值真的变成空串。
     */
    /**
     * 已配置服务商的主机名，用来当代理检测目标。
     *
     * `magpie providers` 的输出形如：
     * ```
     *   DeepSeek  deepseek  api.deepseek.com  ● sk-t…t123  3 models
     *   OpenAI    openai    api.openai.com    ● sk-p…abcd  12 models
     * ```
     * 取第 3 列的主机。没有任何服务商时输出是
     * `no providers yet · …`，这里按"空列表"处理。
     *
     * 用真实配置而不是让用户手填：用户已经告诉了 magpie 自己在用哪些服务，
     * 检测就应该针对这些地址，否则测出来的结论和实际能不能用无关。
     */
    internal suspend fun providerHosts(): List<String> = withContext(Dispatchers.IO) {
        val output = runCatching {
            mutex.withLock {
                installLocked().getOrThrow()
                prepareDns()
                runCli(listOf("providers"), capture = true)
            }
        }.getOrDefault("")

        output.lineSequence()
            .mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || !line.startsWith(" ")) return@mapNotNull null
                // 列之间是连续空白；第 3 列是 host
                trimmed.split(Regex("""\s{2,}"""))
                    .getOrNull(2)
                    ?.takeIf { it.contains('.') || it.contains(':') }
            }
            .distinct()
            .toList()
    }

    internal suspend fun readProxy(): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            installLocked().getOrThrow()
            prepareDns()
            val raw = runCli(listOf("settings", "get", "proxy"), capture = true).trim()
            unquote(raw)
        }
    }

    /** 去掉 magpie CLI 输出两端成对的引号；不是成对引号就原样返回。 */
    private fun unquote(value: String): String =
        if (value.length >= 2 && value.first() == '"' && value.last() == '"') {
            value.substring(1, value.length - 1)
        } else {
            value
        }

    override suspend fun reset(keepData: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                stopProcess()
                activeConfig = null
                stopService()
                val targets = if (keepData) listOf(paths.root) else listOf(paths.root, paths.dataRoot)
                targets.forEach { check(!it.exists() || it.deleteRecursively()) { "清理私有运行目录失败" } }
                validated = false
                update(MagpiePhase.NotInstalled)
                systemLog(if (keepData) "runtime_reset_data_kept" else "runtime_reset")
                Result.success(Unit)
            } catch (_: Exception) {
                fail("重置运行时失败")
                Result.failure(IllegalStateException("重置运行时失败"))
            }
        }
    }

    private fun buildProcess(args: List<String>, pidFile: File, webKey: String? = null): ProcessBuilder {
        val prootArgs = listOf(
            paths.proot.absolutePath, "--kill-on-exit",
            "-b", "${paths.resolv.absolutePath}:/etc/resolv.conf",
            "-b", "${paths.ca.absolutePath}:/etc/ssl/certs/ca-certificates.crt",
            "-0", paths.magpie.absolutePath,
        ) + args
        // The fixed shell fragment records the future PRoot pid; no user value is evaluated as shell source.
        return ProcessBuilder(listOf("/system/bin/sh", "-c", "echo \"\$\$\" > \"\$1\"; shift; exec \"\$@\"", "magpie", pidFile.absolutePath) + prootArgs)
            .directory(paths.root)
            .apply {
                environment().apply {
                    put("PROOT_TMP_DIR", paths.tmp.absolutePath)
                    put("PROOT_LOADER", paths.loader.absolutePath)
                    put("LD_LIBRARY_PATH", "${paths.lib.absolutePath}:${paths.binDir.absolutePath}")
                    put("HOME", paths.home.absolutePath)
                    put("XDG_CONFIG_HOME", paths.config.absolutePath)
                    put("XDG_CACHE_HOME", paths.cache.absolutePath)
                    put("NO_PROXY", "localhost,127.0.0.1,::1")
                    put("no_proxy", "localhost,127.0.0.1,::1")
                    // 插件跑在 Bun 上。magpie 默认会下载 glibc 版 Bun，Android 用的是
                    // bionic，那份执行不了；这里指向随包提供的 Android 构建。
                    if (paths.bun.isFile) put("MAGPIE_BUN", paths.bun.absolutePath)
                    remove("MAGPIE_WEB_KEY")
                    webKey?.let { put("MAGPIE_WEB_KEY", it) }
                }
            }
    }

    private fun runCli(args: List<String>, capture: Boolean): String {
        val pidFile = File(paths.run, "cli.pid")
        if (pidFile.exists()) check(pidFile.delete()) { "无法清理旧进程标记" }
        val cli = buildProcess(args, pidFile).start()
        val output = StringBuilder()
        val outReader = Thread {
            runCatching {
                cli.inputStream.reader().use { reader ->
                    val buffer = CharArray(1024)
                    while (true) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        if (capture && output.length < 8192) output.append(buffer, 0, minOf(count, 8192 - output.length))
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        val errReader = Thread { runCatching { cli.errorStream.use { it.copyTo(DiscardOutputStream) } } }
            .apply { isDaemon = true; start() }
        try {
            if (!cli.waitFor(30, TimeUnit.SECONDS)) throw IllegalStateException("代理命令超时")
            outReader.join(2_000)
            errReader.join(2_000)
            check(!outReader.isAlive && !errReader.isAlive) { "代理命令输出读取超时" }
            check(cli.exitValue() == 0) { "代理命令执行失败" }
            return output.toString()
        } finally {
            if (cli.isAlive) terminate(cli, pidFile)
            else if (pidFile.exists()) pidFile.delete()
        }
    }

    private object DiscardOutputStream : java.io.OutputStream() {
        override fun write(b: Int) = Unit
        override fun write(b: ByteArray, off: Int, len: Int) = Unit
    }

    private suspend fun stopProcess() {
        processMonitorJob?.cancel()
        processMonitorJob = null
        unwatchNetwork()
        val pidFile = File(paths.run, "web.pid")
        val child = process
        if (child == null) {
            // 冷启动接回的进程没有 Java 句柄，只能按 pid 停掉。
            val parent = readPid(pidFile)
            if (parent != null && isOwnedMagpieProcess()) {
                childPids(parent).forEach { runCatching { android.system.Os.kill(it, android.system.OsConstants.SIGTERM) } }
                runCatching { android.system.Os.kill(parent, android.system.OsConstants.SIGTERM) }
                if (!waitForPortClosed(3430, 5_000)) {
                    childPids(parent).forEach { runCatching { android.system.Os.kill(it, android.system.OsConstants.SIGKILL) } }
                    runCatching { android.system.Os.kill(parent, android.system.OsConstants.SIGKILL) }
                    check(waitForPortClosed(3430)) { "旧进程未在限定时间内停止" }
                }
                if (pidFile.exists()) pidFile.delete()
            }
            systemLog("process_stopped")
            return
        }
        terminate(child, pidFile)
        if (!waitForPortClosed(3430, 5_000)) {
            val parent = readPid(pidFile)
            if (parent != null && isOwnedMagpieProcess()) {
                childPids(parent).forEach { runCatching { android.system.Os.kill(it, android.system.OsConstants.SIGKILL) } }
                runCatching { android.system.Os.kill(parent, android.system.OsConstants.SIGKILL) }
            }
            check(waitForPortClosed(3430)) { "旧进程未在限定时间内停止" }
        }
        if (pidFile.exists()) pidFile.delete()
        process = null
        systemLog("process_stopped")
    }

    private suspend fun waitForPortClosed(port: Int, timeoutMs: Long = 15_000): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (isPortListening(port) && System.nanoTime() < deadline) delay(100)
        return !isPortListening(port)
    }

    private fun terminate(child: Process, pidFile: File) {
        // Signal the tracee first so magpie gets a graceful shutdown; PRoot's --kill-on-exit
        // then cleans up remaining traced descendants. /proc visibility is device-dependent.
        if (!child.isAlive) return
        val parent = readPid(pidFile)
        val children = parent?.let(::childPids).orEmpty()
        children.forEach { runCatching { android.system.Os.kill(it, android.system.OsConstants.SIGTERM) } }
        if (!child.waitFor(10, TimeUnit.SECONDS)) {
            child.destroy()
            if (!child.waitFor(5, TimeUnit.SECONDS)) {
                children.forEach { runCatching { android.system.Os.kill(it, android.system.OsConstants.SIGKILL) } }
                child.destroyForcibly()
                check(child.waitFor(3, TimeUnit.SECONDS)) { "无法停止运行时进程" }
            }
        }
        if (pidFile.exists()) pidFile.delete()
    }

    private fun childPids(pid: Int): List<Int> = runCatching {
        File("/proc/$pid/task/$pid/children").readText().trim().split(Regex("\\s+"))
            .mapNotNull { it.toIntOrNull() }.filter { it > 0 }
    }.getOrDefault(emptyList())

    private fun readPid(file: File): Int? = runCatching { file.readText().trim().toInt().takeIf { it > 0 } }.getOrNull()

    private fun prepareDns() = synchronized(dnsLock) {
        val text = DnsResolver.resolvConfFor(app)
        paths.resolv.writeText(text)
        if (text.isEmpty()) systemLog("dns_unavailable")
    }

    private fun watchNetwork() {
        unwatchNetwork()
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                scope.launch { runCatching { prepareDns() }.onFailure { systemLog("dns_update_failed") } }
            }
            override fun onLost(network: Network) {
                scope.launch { runCatching { prepareDns() }.onFailure { systemLog("dns_update_failed") } }
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(callback) }
            .onSuccess { networkCallback = callback }
            .onFailure { systemLog("network_observer_unavailable") }
    }

    private fun unwatchNetwork() {
        networkCallback?.let { callback ->
            runCatching { app.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback) }
        }
        networkCallback = null
    }

    private fun captureLines(input: InputStream, stream: LogStream) {
        scope.launch {
            runCatching {
                input.bufferedReader().use { reader ->
                    val line = StringBuilder()
                    var truncated = false
                    while (true) {
                        val ch = reader.read()
                        if (ch < 0 || ch == '\n'.code) {
                            if (line.isNotEmpty() || truncated) appendLog(redactor.redact(line.toString()), stream)
                            line.setLength(0)
                            truncated = false
                            if (ch < 0) break
                        } else if (line.length < 4096) {
                            line.append(ch.toChar())
                        } else {
                            truncated = true
                        }
                    }
                }
            }.onFailure { systemLog("process_output_closed") }
        }
    }

    private fun systemLog(event: String) = appendLog("[system] $event", LogStream.System)
    private fun appendLog(line: String, stream: LogStream) {
        synchronized(logLock) {
            if (recent.size == 200) recent.removeFirst()
            recent.addLast(line)
        }
        logEvents.tryEmit(MagpieEvent.Log(line, stream))
    }

    private fun update(phase: MagpiePhase) {
        state.value = MagpieStatus(phase = phase, installedVersion = if (validated) VERSION else null)
    }
    private fun fail(message: String) {
        state.value = MagpieStatus(phase = MagpiePhase.Error, installedVersion = if (validated) VERSION else null, error = message)
        systemLog("runtime_error")
    }
    private fun stopService() {
        serviceInstance = null
        app.stopService(Intent(app, MagpieService::class.java))
    }

    private fun healthCheck(): Boolean = try {
        val connection = URL("$BASE_URL/").openConnection(Proxy.NO_PROXY) as HttpURLConnection
        try {
            connection.connectTimeout = 600
            connection.readTimeout = 600
            connection.instanceFollowRedirects = false
            val status = connection.responseCode
            val stream = if (status == 401) connection.errorStream else connection.inputStream
            val body = stream.bufferedReader().use { reader ->
                val buffer = CharArray(8192)
                val count = reader.read(buffer)
                if (count > 0) String(buffer, 0, count) else ""
            }
            status in setOf(200, 401) && body.contains("magpie", ignoreCase = true)
        } finally { connection.disconnect() }
    } catch (_: Exception) { false }

    /** Only a process owned by this app and launched from the packaged Magpie. */
    private fun isOwnedMagpieProcess(): Boolean {
        val pid = readPid(File(paths.run, "web.pid")) ?: return false
        val owned = runCatching {
            val status = File("/proc/$pid/status").readText()
            val ownerUid = status.lineSequence().firstOrNull { it.startsWith("Uid:") }
                ?.substringAfter("Uid:")?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.toInt()
            ownerUid == android.system.Os.getuid()
        }.getOrDefault(false)
        if (!owned) return false
        return runCatching {
            File("/proc/$pid/cmdline").readText().contains(paths.magpie.name)
        }.getOrDefault(false)
    }

    private fun isExpectedMagpieProcess(): Boolean = isOwnedMagpieProcess() && healthCheck()

    /**
     * 端口是否可用。
     *
     * 关键：必须 `reuseAddress = true`。上一次 magpie 退出后，它的监听套接字
     * 会留一段 TIME_WAIT，此时 bind 一个未开 SO_REUSEADDR 的套接字会失败，
     * 于是「刚停掉就重启」会被误判成「端口已被占用」，用户看到的是一个假错误。
     *
     * 真正被别的进程占着时，Linux 的 bind 仍然会失败（SO_REUSEADDR 不会让两个
     * 进程同时监听同一个地址），所以这个判断依然有效。
     */
    private fun isPortFree(port: Int): Boolean = try {
        ServerSocket().use { it.reuseAddress = true; it.bind(InetSocketAddress("127.0.0.1", port)) }
        true
    } catch (_: Exception) { false }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(bytes)
                if (count < 0) break
                digest.update(bytes, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun randomWebKey(): String = ByteArray(24).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun safeReason(e: Exception): String = when (e) {
        is IllegalArgumentException -> e.message?.takeIf { it in setOf("仅允许指定的 loopback 地址", "web key 格式不正确", "代理地址无效或不受支持") } ?: "参数无效"
        is IllegalStateException -> e.message?.takeIf { it in setOf("设备 ABI 不支持或 APK 缺少运行时资产", "内置 magpie SHA256 校验失败", "运行时资产不完整", "无法准备运行时私有目录", "本地端口已被占用", "magpie 进程提前退出", "管理页健康检查超时", "代理命令超时", "代理命令执行失败", "前台服务启动失败", "旧进程未在限定时间内停止") } ?: "运行时错误"
        else -> "运行时异常"
    }
}
