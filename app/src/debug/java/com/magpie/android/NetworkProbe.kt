package com.magpie.android

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 诊断用：在**真实 App 进程**内验证网络能力。
 *
 * 为什么不用 adb `run-as`：`run-as` 得到的 SELinux 上下文是 `runas_app`，
 * 与 App 真实运行时的域不同，且实测它连 TCP 都无法建立——
 * 用它验证网络会得到假阴性。必须让代码跑在真正的 App 进程里。
 */
object NetworkProbe {

    fun probe(onDone: (List<String>) -> Unit) {
        val t = Thread {
            val out = mutableListOf<String>()
            out += probeTcp("192.168.2.1", 53, "wlan DNS (tcp/53)")
            out += probeTcp("223.5.5.5", 53, "public DNS (tcp/53)")
            out += probeTcp("127.0.0.1", 7890, "clash proxy (tcp/7890)")
            out += probeDns("models.dev")
            out += probeDns("github.com")
            out += probeHttp("http://127.0.0.1:3430/")
            onDone(out)
        }
        t.name = "net-probe"
        t.isDaemon = true
        t.start()
    }

    private fun probeTcp(host: String, port: Int, label: String): String =
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), 4000)
                "$label  OK"
            }
        } catch (e: Exception) {
            "$label  FAIL  ${e.javaClass.simpleName}: ${e.message}"
        }

    private fun probeHttp(url: String): String = try {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 5000
        c.readTimeout = 5000
        c.instanceFollowRedirects = false
        val code = c.responseCode
        val len = c.getHeaderField("Content-Length") ?: "?"
        "http $url  code=$code len=$len"
    } catch (e: Exception) {
        "http $url  FAIL  ${e.javaClass.simpleName}: ${e.message}"
    }

    private fun probeDns(host: String): String = try {
        val addrs = InetAddress.getAllByName(host)
        "dns $host  OK  ${addrs.joinToString { it.hostAddress ?: "?" }}"
    } catch (e: Exception) {
        "dns $host  FAIL  ${e.javaClass.simpleName}: ${e.message}"
    }
}
