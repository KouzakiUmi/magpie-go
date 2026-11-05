package com.magpie.android.data

import java.net.Proxy
import java.net.URI

/** Pure validation before passing an untrusted value to the magpie settings CLI. */
object ProxyAddress {
    internal data class Endpoint(val host: String, val port: Int, val type: Proxy.Type)

    /** Empty and direct are magpie's special settings; userinfo and HTTPS proxies are rejected. */
    fun validateSetting(raw: String): Boolean =
        raw.isEmpty() || raw.equals("direct", ignoreCase = true) || parse(raw) != null

    fun validateUrl(raw: String): Boolean = parse(raw) != null

    internal fun parse(value: String): Endpoint? {
        return try {
            if (value.isBlank() || value != value.trim() || value.any { it.isISOControl() }) return null
            val uri = URI(if (value.contains("://")) value else "http://$value")
            if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
                (!uri.rawPath.isNullOrEmpty() && uri.rawPath != "/") || uri.port !in 1..65535 || uri.host.isNullOrBlank()
            ) return null
            val type = when (uri.scheme?.lowercase()) {
                "http" -> Proxy.Type.HTTP
                "socks5" -> Proxy.Type.SOCKS
                else -> return null
            }
            Endpoint(uri.host.removePrefix("[").removeSuffix("]"), uri.port, type)
        } catch (_: Exception) { null }
    }
}
