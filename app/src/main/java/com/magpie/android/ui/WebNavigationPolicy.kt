package com.magpie.android.ui

import java.net.URI
import java.util.Locale

enum class BackNavigationAction {
    GoBack,
    ConfirmExit,
}

enum class WebLinkDestination {
    Internal,
    External,
    Blocked,
}

/** 可脱离 Android 运行与测试的 WebView 导航规则。 */
object WebNavigationPolicy {
    fun backAction(canGoBack: Boolean): BackNavigationAction =
        if (canGoBack) BackNavigationAction.GoBack else BackNavigationAction.ConfirmExit

    fun destination(rawUrl: String?): WebLinkDestination {
        if (rawUrl.isNullOrBlank()) return WebLinkDestination.Blocked

        val uri = try {
            URI(rawUrl.trim())
        } catch (_: Exception) {
            return WebLinkDestination.Blocked
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return WebLinkDestination.Blocked

        if (scheme != "http" && scheme != "https") {
            return if (scheme in EXTERNAL_SCHEMES) {
                WebLinkDestination.External
            } else {
                WebLinkDestination.Blocked
            }
        }

        val host = uri.host ?: return WebLinkDestination.Blocked
        return if (host.equals("127.0.0.1", ignoreCase = true) ||
            host.equals("localhost", ignoreCase = true)
        ) {
            WebLinkDestination.Internal
        } else {
            WebLinkDestination.External
        }
    }

    private val EXTERNAL_SCHEMES = setOf("mailto", "tel", "geo", "market", "intent", "dshgo")
}
