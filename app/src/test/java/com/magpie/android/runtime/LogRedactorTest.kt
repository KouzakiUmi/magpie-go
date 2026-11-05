package com.magpie.android.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogRedactorTest {
    @Test fun rawKeyAndUrlNeverAppearInRedactedOutput() {
        val secrets = listOf("sk-synthetic-test", "my-unknown-token", "sample-web-key-value")
        val input = "web http://127.0.0.1:3430/?k=${secrets[2]} token=${secrets[1]} ${secrets[0]}"
        val result = DefaultLogRedactor.redact(input)
        assertEquals("[process_output] [REDACTED]", result)
        secrets.forEach { assertFalse(result.contains(it)) }
        assertFalse(result.contains("http://"))
    }

    @Test fun knownDiagnosticCategoriesAreFixedStrings() {
        assertEquals("[permission_denied] [REDACTED]", DefaultLogRedactor.redact("Permission denied sensitive data"))
        assertEquals("[address_in_use] [REDACTED]", DefaultLogRedactor.redact("address already in use secret"))
        assertEquals("[certificate] [REDACTED]", DefaultLogRedactor.redact("x509: sensitive"))
        assertEquals("[proot_error] [REDACTED]", DefaultLogRedactor.redact("proot error: sensitive"))
        assertTrue(DefaultLogRedactor.redact("other sensitive message").contains("[REDACTED]"))
    }
}
