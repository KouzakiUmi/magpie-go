package com.magpie.android.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * 后台驻留所需的电源管理设置。
 *
 * ## 为什么需要
 *
 * magpie 是模型网关，被其他客户端长期调用；它跑在前台服务里，但国产 ROM 的
 * 省电策略仍会在锁屏一段时间后把它清掉。表现是「锁屏前正常，回来后连不上」。
 *
 * 用户能做的两件事：
 * 1. 让系统忽略对本 App 的电池优化（标准 Android 机制）；
 * 2. 在厂商自己的界面里把省电策略改成「无限制」（小米等国产 ROM 才有的额外一层）。
 *
 * 这里只负责**判断当前状态**和**打开对应的设置页**，具体文案由 UI 层决定。
 * 跳转顺序参考 dsh-Go 的实现：厂商定制页优先，其次系统标准页，最后退到应用详情页。
 */
object PowerSettings {

    /** 厂商标识；stock Android 与未适配的厂商返回 null。 */
    internal fun vendorOf(manufacturer: String, brand: String): String? {
        val values = listOf(manufacturer, brand).map { it.lowercase() }
        fun any(vararg names: String) = values.any { v -> names.any { v.contains(it) } }
        return when {
            any("xiaomi", "redmi", "poco") -> XIAOMI
            any("huawei") -> HUAWEI
            any("honor", "hihonor") -> HONOR
            any("oppo", "oneplus", "realme") -> OPPO
            any("vivo", "iqoo") -> VIVO
            any("samsung") -> SAMSUNG
            else -> null
        }
    }

    internal const val XIAOMI = "xiaomi"
    private const val HUAWEI = "huawei"
    private const val HONOR = "honor"
    private const val OPPO = "oppo"
    private const val VIVO = "vivo"
    private const val SAMSUNG = "samsung"

    /** 系统是否已忽略对本 App 的电池优化。 */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean = runCatching {
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(context.packageName)
    }.getOrDefault(false)

    /**
     * 打开能改「后台运行」相关开关的界面。
     *
     * @return 是否成功打开了任意一个界面。
     *
     * 顺序：
     * 1. 厂商定制的省电/后台管理页（国产 ROM 的实际开关在这里）；
     * 2. 系统的「忽略电池优化」请求弹窗；
     * 3. 系统的电池优化列表；
     * 4. 最后退到本应用的详情页。
     */
    fun openBackgroundSettings(context: Context): Boolean {
        for (intent in candidateIntents(context)) {
            val ok = runCatching {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            if (ok) return true
        }
        return false
    }

    private fun candidateIntents(context: Context): List<Intent> {
        val packageUri = Uri.parse("package:${context.packageName}")
        val vendor = vendorOf(Build.MANUFACTURER, Build.BRAND)
        val vendorIntents = vendorPages(vendor, context.packageName)
        return vendorIntents + listOf(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri),
        )
    }

    /**
     * 厂商自己的省电策略页。
     *
     * 这些 Activity 属于厂商私有应用，不同 ROM 版本路径会变，所以每个厂商都给
     * 多个候选，加上组件不保证存在——不存在时 [openBackgroundSettings] 会继续试下一个。
     */
    private fun vendorPages(vendor: String?, packageName: String): List<Intent> = when (vendor) {
        XIAOMI -> listOfNotNull(
            // 实测（HyperOS / Android 16）：com.miui.powerkeeper 已经没有任何 Activity，
            // 老的 HiddenAppsConfigActivity 会直接 "No activity found"，所以不再列它。
            // 省电策略并入系统设置，靠后面的 ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS 到达。
            //
            // 自启动管理仍然存在，且小米的后台限制与它相关，先给这个。
            component(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity",
            ),
        )
        HUAWEI, HONOR -> listOf(
            // 应用启动管理：启动与后台运行集中在这里
            component(
                if (vendor == HONOR) "com.hihonor.systemmanager" else "com.huawei.systemmanager",
                "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            ),
            component(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            ),
            component(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.optimize.process.ProtectActivity",
            ),
        )
        OPPO -> listOf(
            // 电池 › 应用耗电管理 › 允许后台运行
            component(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            ),
            component(
                "com.oplus.battery",
                "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity",
            ),
        )
        VIVO -> listOf(
            // 电池 › 后台耗电管理
            component(
                "com.vivo.abe",
                "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity",
            ),
            component(
                "com.iqoo.powersaving",
                "com.iqoo.powersaving.PowerSavingManagerActivity",
            ),
        )
        else -> emptyList()
    }

    private fun component(pkg: String, cls: String): Intent =
        Intent().setClassName(pkg, cls)
}
