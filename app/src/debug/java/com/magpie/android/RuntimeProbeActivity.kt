package com.magpie.android

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import java.io.File

/**
 * 诊断用：在真实 App 进程内、以 App 自己的权限启动 magpie，验证整条链路。
 *
 * 与 [ProbeActivity] 的区别：这个会真的 fork/exec PRoot + magpie，
 * 用真实 App 进程的 SELinux 域与网络权限，因此结论可信。
 */
class RuntimeProbeActivity : Activity() {

    private val lines = mutableListOf<String>()
    private lateinit var view: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        view = TextView(this).apply {
            textSize = 11f
            setPadding(24, 80, 24, 24)
            text = "starting…"
        }
        setContentView(view)

        val t = Thread {
            runCatching { probe() }.onFailure { log("EXCEPTION ${it.javaClass.simpleName}: ${it.message}") }
            runOnUiThread { view.text = lines.joinToString("\n") }
        }
        t.name = "runtime-probe"
        t.isDaemon = true
        t.start()
    }

    private fun log(s: String) {
        Log.i("MagpieRuntimeProbe", s)
        lines += s
        runOnUiThread { view.text = lines.joinToString("\n") }
    }

    private fun probe() {
        val lib = applicationInfo.nativeLibraryDir
        log("lib=$lib")

        val root = File(filesDir, "magpie")
        val run = File(root, "run")
        val tmp = File(run, "tmp")
        val cfg = File(root, "config")
        val cache = File(root, "cache")
        val etc = File(root, "etc")
        listOf(run, tmp, cfg, cache, etc).forEach { it.mkdirs() }

        // CA 证书：从 assets 拷出
        val ca = File(etc, "ca-certificates.crt")
        if (!ca.exists() || ca.length() == 0L) {
            assets.open("ca-certificates.crt").use { i -> ca.outputStream().use { o -> i.copyTo(o) } }
        }
        log("ca=${ca.length()} bytes")

        // 库名修正：libtalloc.so -> libtalloc.so.2
        val privLib = File(root, "lib").apply { mkdirs() }
        val tallocSrc = File(lib, "libtalloc.so")
        val tallocDst = File(privLib, "libtalloc.so.2")
        if (tallocSrc.exists() && !tallocDst.exists()) {
            tallocSrc.copyTo(tallocDst, overwrite = true)
            tallocDst.setExecutable(true)
        }
        File(lib, "libandroid-shmem.so").takeIf { it.exists() }?.let {
            val dst = File(privLib, "libandroid-shmem.so")
            if (!dst.exists()) it.copyTo(dst, overwrite = true)
        }
        log("privLib=${privLib.list()?.joinToString()}")

        // resolv.conf
        val resolv = File(run, "resolv.conf")
        val dns = com.magpie.android.data.DnsResolver.currentDnsServers(this)
        log("dns=$dns")
        resolv.writeText(com.magpie.android.data.DnsResolver.toResolvConf(dns))

        val pb = ProcessBuilder(
            "$lib/libproot.so",
            "-b", "$resolv:/etc/resolv.conf",
            "-b", "$ca:/etc/ssl/certs/ca-certificates.crt",
            "-0",
            "$lib/libmagpie.so",
            "sync",
        )
        pb.environment().apply {
            put("PROOT_TMP_DIR", tmp.absolutePath)
            put("PROOT_LOADER", "$lib/libproot-loader.so")
            put("LD_LIBRARY_PATH", "${privLib.absolutePath}:$lib")
            put("XDG_CONFIG_HOME", cfg.absolutePath)
            put("XDG_CACHE_HOME", cache.absolutePath)
            put("HOME", root.absolutePath)
        }
        pb.redirectErrorStream(true)
        log("exec…")
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        val code = p.waitFor()
        log("exit=$code")
        out.lineSequence().take(8).forEach { log("| $it") }
    }
}
