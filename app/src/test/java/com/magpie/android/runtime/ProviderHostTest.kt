package com.magpie.android.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderHostTest {
    @Test
    fun `accepts real provider endpoints`() {
        assertEquals("https://api.kimi.com", normalizeProviderHost("https://api.kimi.com"))
        assertEquals("https://api.githubcopilot.com", normalizeProviderHost("api.githubcopilot.com"))
        assertEquals("https://api.z.ai", normalizeProviderHost("https://api.z.ai"))
        assertEquals("https://chatgpt.com", normalizeProviderHost("chatgpt.com"))
    }

    @Test
    fun `rejects account identities and invalid urls`() {
        assertNull(normalizeProviderHost("https://● signed in as user@example.com"))
        assertNull(normalizeProviderHost("https://● signed in as user@example.com · KouzakiUmi"))
        assertNull(normalizeProviderHost("signed in as user@example.com"))
        assertNull(normalizeProviderHost("https://user@example.com"))
        assertNull(normalizeProviderHost("not a url"))
        assertNull(normalizeProviderHost(""))
    }
}
