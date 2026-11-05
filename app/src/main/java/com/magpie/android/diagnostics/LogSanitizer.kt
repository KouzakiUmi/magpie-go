package com.magpie.android.diagnostics

/**
 * 把一行日志清洗成可以放进诊断包的文本。
 *
 * ## 为什么不是全量脱敏
 *
 * 早期实现是把每一行整体替换成 `[process_output] [REDACTED]`。安全但没用——
 * 用户导出的包看不出任何原因，等于没有诊断价值。
 *
 * 这里改成**按模式擦除敏感片段，其余保留**：
 * - web key、Bearer token、API key 这类凭据全部替换掉；
 * - 绝对路径里的用户名等私有信息替换掉；
 * - 保留错误类型、状态码、主机名、端口等排查必需的信息。
 *
 * ## 用法
 *
 * 所有要写进诊断包的内容都必须先经过 [redact]。新增日志来源时同样处理。
 */
object LogSanitizer {

    /**
     * 擦除敏感片段。
     *
     * 顺序有讲究：先处理带上下文的（`key=xxx`），再处理裸的（`xxx`），
     * 否则会把已经替换出来的占位符再匹配一次。
     */
    fun redact(line: String): String {
        var out = line

        // web key：出现在 ?k= 查询参数里
        out = out.replace(Regex("([?&]k=)[^&\\s\"']+"), "$1[REDACTED]")

        // 先处理 Authorization 与 Bearer/Basic 令牌。
        // 必须早于下面的通用键值规则：否则 `Authorization: Bearer xxx` 会先被
        // 那条规则匹配到，`Bearer` 顶替成"值"、只擦掉 `Bearer` 本身，真正的令牌留在原地。
        out = out.replace(Regex("(?i)\\b(Bearer|Basic)\\s+[A-Za-z0-9._~+/=-]{8,}"), "$1 [REDACTED]")
        out = out.replace(Regex("(?i)\\bAuthorization\\s*[:=]\\s*\\S+"), "Authorization: [REDACTED]")

        // 常见的键值对写法
        out = out.replace(
            Regex("(?i)\\b(api[_-]?key|apikey|access[_-]?token|refresh[_-]?token|token|secret|password|passwd|pwd)\\b(\\s*[=:]\\s*)(\"[^\"]*\"|'[^']*'|\\S+)"),
            "$1$2[REDACTED]",
        )
        out = out.replace(Regex("(?i)\\b(MAGPIE_WEB_KEY)(\\s*[=:]\\s*)\\S+"), "$1$2[REDACTED]")

        // URL 里的 userinfo：scheme://user:pass@host
        out = out.replace(Regex("(://)[^/@\\s]+@"), "$1[REDACTED]@")

        // 代理地址里可能带凭据
        out = out.replace(Regex("(?i)\\b(socks5?|https?)://([^/@\\s:]+):([^/@\\s]+)@"), "$1://[REDACTED]@")

        // App 私有目录 → 相对路径，避免泄露用户名/UID 之外还让日志更长
        out = out.replace(Regex("/data/user/\\d+/([A-Za-z0-9_.]+)/"), "~/$1/")
        out = out.replace(Regex("/data/app/[^/\\s]+/[A-Za-z0-9_.-]+-[A-Za-z0-9_-]+/"), "/data/app/[PKG]/")

        return out
    }

    /** 判断一行是否值得保留在诊断包里（过滤纯噪声）。 */
    fun isNoise(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return true
        // 未捕获异常的堆栈里大量 at xxx 行，保留首行即可
        return trimmed.startsWith("at ") || trimmed.startsWith("...")
    }
}
