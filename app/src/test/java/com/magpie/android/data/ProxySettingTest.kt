package com.magpie.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxySettingTest {
    @Test fun parseReflectsContractWithoutChangingInput() {
        assertEquals(ProxySetting.FollowSystem, ProxySetting.parse(null))
        assertEquals(ProxySetting.FollowSystem, ProxySetting.parse("  "))
        assertEquals(ProxySetting.Direct, ProxySetting.parse("DiReCt"))
        assertEquals(ProxySetting.Custom("127.0.0.1:7890"), ProxySetting.parse("127.0.0.1:7890"))
        assertEquals(ProxySetting.Custom("http://proxy.example:8080"), ProxySetting.parse("http://proxy.example:8080"))
    }

    @Test fun validSettingsIncludeDirectAndProxySchemes() {
        listOf("", "direct", "DIRECT", "127.0.0.1:7890", "http://proxy.example:8080", "socks5://[::1]:1080")
            .forEach { assertTrue(it, ProxyAddress.validateSetting(it)) }
    }

    @Test fun invalidSettingsAreRejectedWithoutThrowing() {
        listOf(" ", "https://proxy.example:443", "http://user:password@proxy.example:80", "http://user@proxy.example:80", "http://proxy.example:65536", "http://proxy.example", "http://proxy.example:80/path", "http://proxy.example:80/?token=x", "http://proxy.example:80\r\nX: y")
            .forEach { assertFalse(it, ProxyAddress.validateSetting(it)) }
    }
}
