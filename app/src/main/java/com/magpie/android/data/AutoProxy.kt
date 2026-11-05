package com.magpie.android.data

/**
 * magpie 全局代理的取值。对应 `settings.json` 的 `proxy` 字段。
 *
 * 依据（`internal/settings/settings.go:108-117`）：
 * - `""` 跟随环境变量，再跟随系统；
 * - `"direct"` 不用代理；
 * - 其他值是代理地址：`http://`、`https://`、`socks5://`；`host:port` 当作 http。
 *
 * 注意：这只是**默认值**。单个服务商的代理在 magpie 自己的 Providers 页面配置
 * （`provider.Proxy` / `provider.AccountProxies`），App 不重造那层 UI。
 */
sealed interface ProxySetting {
    /** 跟随环境与系统。 */
    data object FollowSystem : ProxySetting

    /** 不走代理。 */
    data object Direct : ProxySetting

    /** 指定的代理地址。 */
    data class Custom(val url: String) : ProxySetting

    /** 写进 `magpie settings set proxy` 的字符串。 */
    fun toSettingValue(): String = when (this) {
        FollowSystem -> ""
        Direct -> "direct"
        is Custom -> url
    }

    companion object {
        fun parse(raw: String?): ProxySetting = when {
            raw.isNullOrBlank() -> FollowSystem
            raw.equals("direct", ignoreCase = true) -> Direct
            else -> Custom(raw)
        }
    }
}

/** 一次连通性探测的结果。 */
sealed interface Reachability {
    /** 直连可用。 */
    data class Direct(val latencyMs: Long) : Reachability

    /** 直连不可用，但经代理可用。 */
    data class ViaProxy(val url: String, val latencyMs: Long) : Reachability

    /** 直连与代理都不可用。 */
    data class Unreachable(val reason: String) : Reachability

    val usable: Boolean get() = this !is Unreachable
}

/**
 * 自动代理：先试直连，失败则回退到代理。
 *
 * ## 为什么需要
 *
 * 不同服务商的出网要求不一样：国内厂商和局域网模型必须直连，
 * 境外服务商在部分网络下必须走代理。而用户的设备本身可能没有开启
 * 系统级代理，或者开了 TUN 但目标域名走不通。
 *
 * ## 落在哪一层
 *
 * **不改 magpie 的代码**，只操作它的全局 `proxy` 设置与进程重启。
 * 依据：实测 `magpie settings set proxy <url>` 有效（`docs/verified-findings.md` §9），
 * 但运行中的进程不会热加载该设置，需要重启才生效。
 *
 * 单服务商级别的代理仍由 magpie 自己的 Providers 页面管理，
 * 本类只负责「全局默认值」这一层，避免与上游能力重复。
 *
 * ## 边界
 *
 * - 探测只发 HEAD 请求到用户指定的目标，不发送任何凭据；
 * - 探测目标、超时、重试次数都有界；
 * - 不记录、不持久化代理的登录凭据明文。
 */
interface AutoProxyController {
    /** 当前生效的全局代理。 */
    suspend fun current(): ProxySetting

    /** 设置全局代理，并让运行中的 magpie 重新读取（必要时重启）。 */
    suspend fun apply(setting: ProxySetting): Result<Unit>

    /**
     * 探测 [target] 的可达性。
     *
     * @param target 探测目标，通常是某个服务商的 API 基址。
     * @param directTimeoutMs 直连超时。
     * @param proxy 候选代理；为 null 时不试代理。
     */
    suspend fun probe(
        target: String,
        directTimeoutMs: Long = 5_000,
        proxy: String? = null,
    ): Reachability

    /**
     * 一次完整的自动决策：直连可用则用直连，否则尝试代理，
     * 两者都不行就报告失败并保持原设置。
     *
     * 返回值说明做了什么，供 UI 展示。
     */
    suspend fun decide(
        params: AutoProxyParams,
    ): AutoProxyOutcome
}

/** 自动代理的输入参数。 */
data class AutoProxyParams(
    /** 探测目标；多个时**全部**可达才算直连可用。 */
    val targets: List<String>,

    /** 候选代理地址，按顺序尝试。 */
    val candidates: List<String>,

    /** 单个请求的超时。 */
    val timeoutMs: Long = 5_000,

    /** 是否允许在直连失败时自动改设置；false 时只探测、不改。 */
    val applyChanges: Boolean = true,
)

/** 自动代理的决策结果。 */
data class AutoProxyOutcome(
    val reachability: Reachability,
    /** 实际写入的代理设置；null 表示没有改动。 */
    val applied: ProxySetting? = null,
    /** 是否为此重启了 magpie。 */
    val restarted: Boolean = false,
    /** 给用户看的一句话结论（已本地化前的 key，见 ui 层）。 */
    val note: String? = null,
)
