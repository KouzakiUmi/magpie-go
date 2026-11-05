package com.magpie.android.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class WebNavigationPolicyTest {
    @Test
    fun `back goes to previous WebView page when history exists`() {
        assertEquals(BackNavigationAction.GoBack, WebNavigationPolicy.backAction(canGoBack = true))
    }

    @Test
    fun `back asks for exit confirmation when WebView has no history`() {
        assertEquals(BackNavigationAction.ConfirmExit, WebNavigationPolicy.backAction(canGoBack = false))
    }

    @Test
    fun `loopback http and https URLs stay internal on any port`() {
        val urls = listOf(
            "http://127.0.0.1:3430/",
            "http://127.0.0.1:3430/api/state",
            "https://127.0.0.1/",
            "http://localhost:3430/?view=providers",
            "HTTP://LOCALHOST:3430/",
        )
        urls.forEach { url ->
            assertEquals(url, WebLinkDestination.Internal, WebNavigationPolicy.destination(url))
        }
    }

    @Test
    fun `external hosts and deceptive loopback names leave the WebView`() {
        val urls = listOf(
            "https://github.com/yetone/magpie",
            "http://127.0.0.1.example.com/",
            "https://localhost.example.org/docs",
            "http://example.com@127.0.0.1.evil.test/",
            "https://[::1]:3430/",
            "http://192.168.1.20:3430/",
        )
        urls.forEach { url ->
            assertEquals(url, WebLinkDestination.External, WebNavigationPolicy.destination(url))
        }
    }

    @Test
    fun `userinfo cannot disguise an external host as loopback`() {
        assertEquals(
            WebLinkDestination.External,
            WebNavigationPolicy.destination("http://localhost@evil.example/"),
        )
    }

    @Test
    fun `supported non-http app links open externally`() {
        assertEquals(
            WebLinkDestination.External,
            WebNavigationPolicy.destination("mailto:magpie@example.com"),
        )
        assertEquals(
            WebLinkDestination.External,
            WebNavigationPolicy.destination("intent://scan#Intent;scheme=zxing;end"),
        )
        assertEquals(
            WebLinkDestination.External,
            WebNavigationPolicy.destination("dshgo://providers/magpie"),
        )
    }

    @Test
    fun `malformed relative and unsafe URLs are blocked`() {
        val urls = listOf(
            null,
            "",
            "not a url",
            "/relative/path",
            "file:///sdcard/private.txt",
            "content://contacts/people",
            "javascript:alert(1)",
            "data:text/html,hello",
            "http:///missing-host",
        )
        urls.forEach { url ->
            assertEquals(url ?: "null", WebLinkDestination.Blocked, WebNavigationPolicy.destination(url))
        }
    }
}
