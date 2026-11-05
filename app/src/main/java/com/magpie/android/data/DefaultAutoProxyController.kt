package com.magpie.android.data

import android.content.Context
import com.magpie.android.runtime.AndroidMagpieRuntime
import com.magpie.android.runtime.ProxyRestartException
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URI
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Direct first, then candidates in order, without ever persisting proxy credentials. */
class DefaultAutoProxyController(
    context: Context,
    private val runtime: AndroidMagpieRuntime = AndroidMagpieRuntime.getInstance(context),
) : AutoProxyController {
    private val decisionMutex = Mutex()

    override suspend fun current(): ProxySetting {
        val raw = runtime.readProxy()
        // Never return stored userinfo: a prior magpie configuration may have contained it.
        if (raw.contains('@')) throw IllegalStateException("现有代理包含凭据，无法安全返回")
        return ProxySetting.parse(raw)
    }

    override suspend fun apply(setting: ProxySetting): Result<Unit> = decisionMutex.withLock {
        val value = setting.toSettingValue()
        if (!ProxyAddress.validateSetting(value)) return@withLock Result.failure(IllegalArgumentException("代理地址无效、包含凭据或不受支持"))
        runtime.applyProxy(value).map { Unit }
    }

    override suspend fun probe(target: String, directTimeoutMs: Long, proxy: String?): Reachability = withContext(Dispatchers.IO) {
        val destination = parseTarget(target) ?: return@withContext Reachability.Unreachable("目标地址无效或包含凭据")
        val timeout = directTimeoutMs.coerceIn(1, 60_000).toInt()
        val direct = probeOnce(destination, null, timeout)
        if (direct != null) return@withContext Reachability.Direct(direct)
        if (proxy == null) return@withContext Reachability.Unreachable("直连不可达")
        val endpoint = ProxyAddress.parse(proxy)
            ?: return@withContext Reachability.Unreachable("代理地址无效、包含凭据或不受支持")
        // A loopback address must never be sent to a proxy, even after a direct failure.
        if (isLoopback(destination)) return@withContext Reachability.Unreachable("本地目标不可达")
        val proxied = probeOnce(destination, endpoint, timeout)
        if (proxied != null) Reachability.ViaProxy(proxy, proxied)
        else Reachability.Unreachable("直连和代理均不可达")
    }

    override suspend fun decide(params: AutoProxyParams): AutoProxyOutcome = decisionMutex.withLock {
        if (params.targets.isEmpty()) return@withLock AutoProxyOutcome(Reachability.Unreachable("没有探测目标"), note = "no_targets")
        val targets = params.targets.map { parseTarget(it) ?: return@withLock AutoProxyOutcome(Reachability.Unreachable("目标地址无效或包含凭据"), note = "invalid_target") }
        val timeout = params.timeoutMs.coerceIn(1, 60_000).toInt()
        val direct = measureAll(targets, null, timeout)
        if (direct != null) return@withLock applyOutcome(Reachability.Direct(direct), ProxySetting.Direct, params.applyChanges)
        for (candidate in params.candidates) {
            val endpoint = ProxyAddress.parse(candidate) ?: continue
            val elapsed = measureAll(targets, endpoint, timeout) ?: continue
            return@withLock applyOutcome(Reachability.ViaProxy(candidate, elapsed), ProxySetting.Custom(candidate), params.applyChanges)
        }
        AutoProxyOutcome(Reachability.Unreachable("所有候选代理不可达或不受支持"), note = "unreachable")
    }

    private suspend fun applyOutcome(reachability: Reachability, setting: ProxySetting, apply: Boolean): AutoProxyOutcome {
        if (!apply) return AutoProxyOutcome(reachability, note = "probe_only")
        val result = runtime.applyProxy(setting.toSettingValue())
        return result.fold(
            onSuccess = { restarted -> AutoProxyOutcome(reachability, setting, restarted, "applied") },
            onFailure = { error ->
                if (error is ProxyRestartException) AutoProxyOutcome(reachability, applied = setting, note = "restart_failed")
                else AutoProxyOutcome(reachability, note = "apply_failed")
            },
        )
    }

    private suspend fun measureAll(targets: List<Target>, proxy: ProxyAddress.Endpoint?, timeout: Int): Long? = withContext(Dispatchers.IO) {
        var elapsed = 0L
        for (target in targets) {
            val duration = probeOnce(target, if (isLoopback(target)) null else proxy, timeout) ?: return@withContext null
            elapsed += duration
        }
        elapsed
    }

    private data class Target(val host: String, val port: Int, val tls: Boolean, val path: String, val loopback: Boolean)

    private fun parseTarget(raw: String): Target? {
        return try {
            val uri = URI(raw)
            if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null || uri.host.isNullOrBlank()) return null
            val tls = when (uri.scheme?.lowercase()) {
                "http" -> false
                "https" -> true
                else -> return null
            }
            val host = uri.host.removePrefix("[").removeSuffix("]")
            val port = if (uri.port == -1) if (tls) 443 else 80 else uri.port
            if (port !in 1..65535) return null
            val path = uri.rawPath.orEmpty().ifEmpty { "/" }
            if (path.any { it.isISOControl() || it == ' ' }) return null
            val normalizedHost = host.trimEnd('.')
            val literalLoopback = (host.contains(':') || host.matches(Regex("[0-9.]+"))) &&
                runCatching { InetAddress.getByName(host).isLoopbackAddress }.getOrDefault(false)
            val loopback = normalizedHost.equals("localhost", true) || normalizedHost.endsWith(".localhost", true) || literalLoopback
            Target(host, port, tls, path, loopback)
        } catch (_: Exception) { null }
    }

    private fun isLoopback(target: Target): Boolean = target.loopback || runCatching {
        InetAddress.getAllByName(target.host).any { it.isLoopbackAddress }
    }.getOrDefault(false)

    private fun probeOnce(target: Target, proxy: ProxyAddress.Endpoint?, timeout: Int): Long? {
        val start = System.nanoTime()
        return try {
            val socket = when (proxy?.type) {
                Proxy.Type.SOCKS -> Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress(proxy.host, proxy.port)))
                else -> Socket(Proxy.NO_PROXY)
            }
            socket.use { raw ->
                raw.soTimeout = timeout
                if (proxy?.type == Proxy.Type.HTTP) {
                    raw.connect(InetSocketAddress(proxy.host, proxy.port), timeout)
                    if (target.tls) {
                        writeAscii(raw, "CONNECT ${authority(target)} HTTP/1.1\r\nHost: ${authority(target)}\r\nConnection: close\r\n\r\n")
                        if (readStatus(raw) !in 200..299) return null
                        skipHeaders(raw)
                    }
                } else {
                    raw.connect(InetSocketAddress(target.host, target.port), timeout)
                }
                val connection: Socket = if (target.tls) {
                    (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, target.host, target.port, true).also { tlsSocket ->
                        (tlsSocket as SSLSocket).apply {
                            sslParameters = sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                            soTimeout = timeout
                            startHandshake()
                        }
                    }
                } else raw
                val requestTarget = if (proxy?.type == Proxy.Type.HTTP && !target.tls) "http://${authority(target)}${target.path}" else target.path
                writeAscii(connection, "HEAD $requestTarget HTTP/1.1\r\nHost: ${authority(target)}\r\nConnection: close\r\n\r\n")
                val status = readStatus(connection)
                if (status !in 100..499 || status == 407) return null
                // For a plain HTTP request these responses may come from the proxy itself,
                // so they do not establish that the destination is reachable.
                if (proxy?.type == Proxy.Type.HTTP && !target.tls && status in setOf(407, 502, 503, 504)) return null
                TimeUnitNanos.toMillis(System.nanoTime() - start)
            }
        } catch (_: Exception) { null }
    }

    private fun authority(target: Target): String {
        val host = if (target.host.contains(':')) "[${target.host}]" else target.host
        return "$host:${target.port}"
    }

    private fun writeAscii(socket: Socket, request: String) {
        socket.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
        socket.getOutputStream().flush()
    }

    private fun readLine(socket: Socket): String {
        val input = socket.getInputStream()
        val output = StringBuilder()
        while (output.length < 8192) {
            val b = input.read()
            if (b == -1 || b == '\n'.code) break
            if (b != '\r'.code) output.append(b.toChar())
        }
        return output.toString()
    }

    private fun readStatus(socket: Socket): Int {
        var interim = 0
        while (true) {
            val parts = readLine(socket).split(' ')
            if (parts.size < 2 || !parts[0].startsWith("HTTP/")) throw IOException("invalid response")
            val status = parts[1].toIntOrNull() ?: throw IOException("invalid response")
            if (status == 101) throw IOException("unexpected protocol switch")
            if (status != 100) return status
            if (++interim > 3) throw IOException("too many interim responses")
            skipHeaders(socket)
        }
    }

    private fun skipHeaders(socket: Socket) {
        repeat(100) { if (readLine(socket).isEmpty()) return }
        throw IOException("too many headers")
    }

    private object TimeUnitNanos {
        fun toMillis(value: Long): Long = value / 1_000_000L
    }
}
