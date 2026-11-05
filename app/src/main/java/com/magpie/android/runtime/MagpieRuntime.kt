package com.magpie.android.runtime

/**
 * magpie 运行时的公开契约。
 *
 * 前后端两个工作包都只依赖本文件与 [MagpieConfig]，**不得各自修改**；
 * 需要变更契约时提出来，由主代理统一改。
 *
 * 设计依据见 `docs/design.md` 与 `docs/verified-findings.md`：
 * magpie 是完全静态链接的 Go 二进制，能直接在 Android 上执行，
 * 但必须经 PRoot 提供 `/etc/resolv.conf` 与 CA 证书包。
 */

/** 运行时阶段。与 dsh-Go 的 `RuntimePhase` 同构，便于将来互通。 */
enum class MagpiePhase {
    /** 二进制/资产尚未就绪（未下载或未解压）。 */
    NotInstalled,

    /** 正在下载或准备资产。 */
    Installing,

    /** 进程尚未启动。 */
    Stopped,

    /** 正在启动，尚未通过健康检查。 */
    Starting,

    /** 已通过健康检查，网关可用。 */
    Running,

    /** 正在停止。 */
    Stopping,

    /** 出错了，[MagpieStatus.error] 说明原因。 */
    Error,
}

/**
 * 一次运行的状态快照，供 UI 与诊断读取。
 *
 * 凭据纪律：**不得包含 web key 或任何 API key 的明文**。
 * [pageUrl] 里的 key 只在内存中传给 WebView，不写日志、不进诊断包。
 */
data class MagpieStatus(
    val phase: MagpiePhase = MagpiePhase.NotInstalled,

    /** 已安装的 magpie 版本，未安装为 null。 */
    val installedVersion: String? = null,

    /** 进程 pid，未运行或经 PRoot 启动时为 PRoot 的 pid。 */
    val pid: Int? = null,

    /** 管理页根地址，如 `http://127.0.0.1:3430`（不含 key）。 */
    val baseUrl: String? = null,

    /** 网关根地址，如 `http://127.0.0.1:3425`。 */
    val gatewayUrl: String? = null,

    /** 带 key 的管理页地址；只给 WebView 用，不要写进日志或诊断。 */
    val pageUrl: String? = null,

    /** 失败原因（已脱敏）。 */
    val error: String? = null,
) {
    val isRunning: Boolean get() = phase == MagpiePhase.Running
}

/** 启动参数。由设置层构造，传给 [MagpieRuntime]。 */
data class MagpieConfig(
    /** 管理页监听地址，固定 loopback，不允许改。 */
    val webAddr: String = "127.0.0.1:3430",

    /** 网关监听地址。默认 loopback；要开放给局域网时走 magpie 自己的共享开关。 */
    val gatewayAddr: String = "127.0.0.1:3425",

    /**
     * 固定的 web key（16+ 字符，只允许字母数字与 `-._~`）。
     *
     * 固定它，重启后 Cookie 仍有效，WebView 不必每次重新登录。
     * 不设置则由 magpie 每次运行随机生成，此时 [MagpieStatus.pageUrl] 每次都会变。
     */
    val webKey: String? = null,

    /**
     * 可选的默认代理，写进 magpie 的全局设置，例如 `http://127.0.0.1:7890`。
     *
     * 注意：这只是**默认值**。单个服务商的代理在 magpie 自己的 Providers 页面配，
     * App 不重造那层 UI（见 design.md §3.7）。
     */
    val defaultProxy: String? = null,
)

/** 运行时事件。UI 订阅它以刷新状态。 */
sealed interface MagpieEvent {
    data class Status(val status: MagpieStatus) : MagpieEvent

    /** 安装或下载进度。[total] 为 0 表示总量未知。 */
    data class Progress(val step: String, val done: Long, val total: Long) : MagpieEvent

    /** 一行日志。[line] 已脱敏。 */
    data class Log(val line: String, val stream: LogStream) : MagpieEvent
}

enum class LogStream { Stdout, Stderr, System }

/**
 * 运行时的能力边界。**所有实现必须遵守**：
 *
 * - 只操作 App 私有目录下的文件，不碰 `/sdcard` 与系统目录；
 * - 不改动 magpie 源码，只用它的官方 release 产物；
 * - 不把 web key、API key 写进日志、诊断包或崩溃报告；
 * - 端口只绑 loopback，除非用户显式开启局域网共享。
 */
interface MagpieRuntime {
    /** 当前状态。 */
    fun status(): MagpieStatus

    /** 状态与日志事件流。订阅即触发一次当前状态。 */
    fun events(): kotlinx.coroutines.flow.Flow<MagpieEvent>

    /** 确保二进制与 PRoot 资产就绪；已就绪且版本一致时立即返回。 */
    suspend fun install(): Result<Unit>

    /** 启动为前台服务。 */
    suspend fun start(config: MagpieConfig): Result<Unit>

    /** 停止。 */
    suspend fun stop(): Result<Unit>

    /** 取最近 [lines] 行日志（已脱敏）。 */
    fun logs(lines: Int = 200): List<String>

    /** 删除已安装的资产；[keepData] 为 true 时保留 magpie 的状态（供应商、密钥）。 */
    suspend fun reset(keepData: Boolean): Result<Unit>
}
