package com.magpie.android.diagnostics

import android.content.Context
import android.os.Build
import com.magpie.android.runtime.AndroidMagpieRuntime
import com.magpie.android.runtime.MagpiePhase
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 一键导出诊断包。
 *
 * ## 解决什么问题
 *
 * 用户遇到「启动失败」「页面空白」「连不上网关」时，只看界面上一句结论往往定位不了。
 * 让用户自己抓 logcat 不现实，所以提供一个按钮，把当前环境和运行日志打成一个 txt，
 * 用户可以直接分享出来。
 *
 * ## 包里有什么
 *
 * - 设备与系统版本、ABI；
 * - App 版本、运行时阶段、进程是否在跑（不含 pid 之外的进程细节）；
 * - 运行时最近若干行日志。
 *
 * ## 包里没有什么
 *
 * web key、API key、代理凭据、用户的服务商配置。所有文本都过 [LogSanitizer]。
 * 网关地址里不含 key（key 只在 `pageUrl` 的查询串里，而 `pageUrl` 不导出）。
 */
object DiagnosticsExporter {

    private const val APP_LOG_LIMIT = 200

    private const val TAG = "MagpieDiagnostics"

    /** 生成诊断文本并写入缓存目录，返回文件。失败返回 null。 */
    fun export(context: Context): File? = runCatching {
        val runtime = AndroidMagpieRuntime.getInstance(context)
        val status = runtime.status()

        val text = buildString {
            appendLine("magpie-android 诊断包")
            appendLine("生成时间: ${timestamp()}")
            appendLine()

            appendLine("[设备]")
            appendLine("厂商: ${Build.MANUFACTURER}")
            appendLine("型号: ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine()

            appendLine("[应用]")
            appendLine("包名: ${context.packageName}")
            appendLine("版本: ${versionName(context)}")
            appendLine()

            appendLine("[运行时]")
            appendLine("阶段: ${status.phase}")
            appendLine("已安装版本: ${status.installedVersion ?: "-"}")
            appendLine("正在运行: ${status.phase == MagpiePhase.Running}")
            appendLine("管理页端口: ${status.baseUrl?.substringAfterLast(':') ?: "-"}")
            appendLine("网关端口: ${status.gatewayUrl?.substringAfterLast(':') ?: "-"}")
            appendLine("错误: ${status.error?.let(LogSanitizer::redact) ?: "-"}")
            appendLine()

            appendLine("[运行日志]")
            val lines = runtime.logs(APP_LOG_LIMIT)
                .asSequence()
                .filterNot(LogSanitizer::isNoise)
                .map(LogSanitizer::redact)
                .toList()
            if (lines.isEmpty()) appendLine("(无)") else lines.forEach { appendLine(it) }
        }

        val dir = File(context.cacheDir, "diagnostics").apply { mkdirs() }
        // 只保留最近几份，避免长期堆积占空间
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(4)?.forEach { it.delete() }

        val file = File(dir, "magpie-diagnostics-${fileStamp()}.txt")
        file.writeText(text)
        file
    }.onFailure {
        android.util.Log.w(TAG, "export failed: ${it.javaClass.simpleName}: ${it.message}")
    }.getOrNull()

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    private fun fileStamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    private fun versionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "-"
    }.getOrDefault("-")
}
