package com.shilapi.xcertplay.youtube

import java.net.URI

internal enum class YoutubeNavigationDecision {
    ALLOW_CURRENT,
    OPEN_POPUP,
    DENY,
}

internal object YoutubeNavigationPolicy {
    fun decide(uri: String, newWindow: Boolean): YoutubeNavigationDecision {
        val parsed = runCatching { URI(uri) }.getOrNull() ?: return YoutubeNavigationDecision.DENY
        return when (parsed.scheme?.lowercase()) {
            "https" -> if (parsed.host.isNullOrBlank()) {
                YoutubeNavigationDecision.DENY
            } else if (newWindow) {
                YoutubeNavigationDecision.OPEN_POPUP
            } else {
                YoutubeNavigationDecision.ALLOW_CURRENT
            }
            "about" -> if (newWindow && uri.equals("about:blank", ignoreCase = true)) {
                YoutubeNavigationDecision.OPEN_POPUP
            } else {
                YoutubeNavigationDecision.DENY
            }
            else -> YoutubeNavigationDecision.DENY
        }
    }
}

internal class YoutubePopupGate {
    private var requestPending = false
    var isOpen: Boolean = false
        private set

    fun request(uri: String): Boolean {
        if (isOpen || requestPending ||
            YoutubeNavigationPolicy.decide(uri, newWindow = true) != YoutubeNavigationDecision.OPEN_POPUP
        ) {
            return false
        }
        requestPending = true
        return true
    }

    fun consume(uri: String): Boolean {
        requestPending = false
        if (isOpen || YoutubeNavigationPolicy.decide(uri, newWindow = true) != YoutubeNavigationDecision.OPEN_POPUP) {
            return false
        }
        isOpen = true
        return true
    }

    fun close() {
        requestPending = false
        isOpen = false
    }
}
