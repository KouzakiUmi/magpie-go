package com.magpie.android.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 脱敏规则。
 *
 * 诊断包会被用户分享出去，所以这里既要求「敏感信息一定被擦掉」，
 * 也要求「排查必需的信息留下」——全部替换成占位符的日志没有价值。
 */
class LogSanitizerTest {

    private fun redacted(line: String) = LogSanitizer.redact(line)

    @Test
    fun `web key 从 URL 查询串里去掉`() {
        val out = redacted("GET http://127.0.0.1:3430/?k=abcDEF1234567890xyz HTTP/1.1")
        assertFalse("key 不能保留", out.contains("abcDEF1234567890xyz"))
        assertTrue("URL 结构应保留", out.contains("http://127.0.0.1:3430/"))
        assertTrue(out.contains("[REDACTED]"))
    }

    @Test
    fun `其他查询参数旁边的 key 也能识别`() {
        val out = redacted("/?view=providers&k=SECRETKEY1234567890")
        assertFalse(out.contains("SECRETKEY1234567890"))
        assertTrue("无关参数要保留", out.contains("view=providers"))
    }

    @Test
    fun `常见的凭据键值对都被擦掉`() {
        listOf(
            "api_key=sk-abcdefghijklmnop",
            "apiKey: sk-abcdefghijklmnop",
            "token=abcdef123456",
            "password=hunter2",
            "secret='my-secret-value'",
        ).forEach { input ->
            val out = redacted(input)
            assertTrue("应保留键名: $input -> $out", out.contains("=") || out.contains(":"))
            assertFalse("值必须擦掉: $input -> $out", out.contains("sk-abcdefghijklmnop"))
            assertFalse(out.contains("abcdef123456"))
            assertFalse(out.contains("hunter2"))
            assertFalse(out.contains("my-secret-value"))
        }
    }

    @Test
    fun `Authorization 头与 Bearer 令牌`() {
        val out = redacted("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload.sig")
        assertFalse(out.contains("eyJhbGciOiJIUzI1NiJ9"))
        assertTrue(out.contains("[REDACTED]"))
    }

    @Test
    fun `URL 里的 userinfo 被擦掉`() {
        val out = redacted("connecting to http://user:pass@example.com/v1")
        assertFalse(out.contains("user:pass"))
        assertTrue(out.contains("example.com"))
    }

    @Test
    fun `代理地址里的凭据被擦掉`() {
        listOf(
            "socks5://alice:secret@127.0.0.1:1080",
            "http://alice:secret@proxy.local:7890",
        ).forEach { input ->
            val out = redacted(input)
            assertFalse("凭据必须擦掉: $out", out.contains("alice"))
            assertFalse(out.contains("secret"))
            assertTrue("主机与端口要保留: $out", out.contains("127.0.0.1:1080") || out.contains("proxy.local:7890"))
        }
    }

    @Test
    fun `私有目录路径缩短但仍可辨识`() {
        val out = redacted("/data/user/0/com.magpie.android/files/magpie/bin/bun")
        assertFalse("不应出现原始绝对路径", out.contains("/data/user/0/"))
        assertTrue(out.contains("com.magpie.android"))
    }

    @Test
    fun `排查必需的信息不被误删`() {
        // 这三类信息是定位问题的关键，必须保留
        val error = redacted("2026-11-05 05:06:12 [system] process_exited code=2")
        assertTrue(error.contains("process_exited"))
        assertTrue(error.contains("code=2"))

        val port = redacted("bind: address already in use 127.0.0.1:3430")
        assertTrue(port.contains("address already in use"))
        assertTrue(port.contains("3430"))

        val dns = redacted("dial tcp: lookup models.dev on 172.19.0.2:53")
        assertTrue(dns.contains("models.dev"))
        assertTrue(dns.contains("172.19.0.2:53"))
    }

    @Test
    fun `堆栈噪声被识别`() {
        assertTrue(LogSanitizer.isNoise("   "))
        assertTrue(LogSanitizer.isNoise(""))
        assertTrue(LogSanitizer.isNoise("    at com.example.Foo.bar(Foo.kt:12)"))
        assertFalse(LogSanitizer.isNoise("java.lang.IllegalStateException: 启动失败"))
    }

    @Test
    fun `MAGPIE_WEB_KEY 环境变量被擦掉`() {
        val out = redacted("MAGPIE_WEB_KEY=mk3hfm-z11JLjms-pTrTusahL7890XrX")
        assertFalse(out.contains("mk3hfm-z11JLjms-pTrTusahL7890XrX"))
        assertTrue(out.contains("MAGPIE_WEB_KEY"))
    }
}
