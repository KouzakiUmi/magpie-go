package com.magpie.android.data

import android.content.Context
import android.net.ConnectivityManager

/**
 * 从系统读 DNS，生成 PRoot 要绑进 `/etc/resolv.conf` 的内容。
 *
 * 为什么需要这个：Android 没有 `/etc/resolv.conf`（`/etc` 只读），
 * 静态 Go 二进制读不到配置就把 DNS 服务器退化成 `[::1]:53`，出网全失败。
 * 实测见 `docs/verified-findings.md` §2、§3。
 *
 * 已实测的结论（`docs/verified-findings.md`）：
 * - 私有 DNS（DoT）开启时，`getDnsServers()` 仍返回真实 DNS 地址，不会变成 loopback；
 * - 真机上可能同时有 IPv4 与 IPv6 DNS，两者都写进去没问题。
 */
object DnsResolver {

    /**
     * 当前网络可用的 DNS 服务器地址列表，IPv4 在前。
     *
     * 读不到、或没有网络时返回空列表——此时 [toResolvConf] 会产出一个
     * 不含 `nameserver` 的空文件，magpie 会退回 `[::1]:53` 并失败。
     * 调用方应当把这个情况报告给用户，而不是静默启动。
     */
    fun currentDnsServers(context: Context): List<String> {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        val network = cm.activeNetwork ?: return emptyList()
        val props = cm.getLinkProperties(network) ?: return emptyList()

        val seen = LinkedHashSet<String>()
        for (addr in props.dnsServers) {
            // 去掉 zone id（fe80::1%wlan0），Go 的解析器不认它
            val host = addr.hostAddress?.substringBefore('%') ?: continue
            if (host.isBlank()) continue
            seen += host
        }
        // IPv4 优先：部分网络的 IPv6 DNS 不可用但仍在列表里
        return seen.sortedBy { if (it.contains(':')) 1 else 0 }
    }

    /**
     * 生成 resolv.conf 文本。
     *
     * `nameserver` 最多写 3 条（glibc 的惯例，Go 也接受更多，但没必要）。
     */
    fun toResolvConf(servers: List<String>): String {
        if (servers.isEmpty()) return ""
        return buildString {
            for (s in servers.take(3)) {
                append("nameserver ").append(s).append('\n')
            }
        }
    }

    /**
     * 一次性拿到可直接写盘的 resolv.conf 内容。
     *
     * 返回空字符串表示当前网络拿不到 DNS，[MagpieRuntime.start] 不应把它
     * 当成成功条件；此时配了代理仍然可以出网（实测结论 §3.9）。
     */
    fun resolvConfFor(context: Context): String =
        toResolvConf(currentDnsServers(context))
}
