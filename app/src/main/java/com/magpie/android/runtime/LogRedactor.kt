package com.magpie.android.runtime

/** Converts untrusted process output into a safe, credential-free diagnostic line. */
fun interface LogRedactor {
    fun redact(line: String): String
}

/** Never forwards source text: classifications are selected from fixed strings only. */
object DefaultLogRedactor : LogRedactor {
    override fun redact(line: String): String {
        val category = when {
            line.contains("permission denied", ignoreCase = true) -> "permission_denied"
            line.contains("address already in use", ignoreCase = true) -> "address_in_use"
            line.contains("certificate", ignoreCase = true) || line.contains("x509", ignoreCase = true) -> "certificate"
            line.contains("proot", ignoreCase = true) && line.contains("error", ignoreCase = true) -> "proot_error"
            line.contains("error", ignoreCase = true) -> "process_error"
            else -> "process_output"
        }
        return "[$category] [REDACTED]"
    }
}
