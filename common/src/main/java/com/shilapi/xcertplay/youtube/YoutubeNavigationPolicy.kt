package com.shilapi.xcertplay.youtube

import java.net.URI

internal enum class YoutubeNavigationDecision {
    ALLOW_CURRENT,
    OPEN_POPUP,
    DENY,
}

internal object YoutubeNavigationPolicy {
    fun decide(uri: String, newWindow: Boolean, isPopup: Boolean = false): YoutubeNavigationDecision {
        val parsed = runCatching { URI(uri) }.getOrNull() ?: return YoutubeNavigationDecision.DENY
        return when (parsed.scheme?.lowercase()) {
            "https" -> if (parsed.host.isNullOrBlank()) {
                YoutubeNavigationDecision.DENY
            } else if (newWindow) {
                YoutubeNavigationDecision.OPEN_POPUP
            } else {
                YoutubeNavigationDecision.ALLOW_CURRENT
            }
            "about" -> when {
                !uri.equals("about:blank", ignoreCase = true) -> YoutubeNavigationDecision.DENY
                newWindow -> YoutubeNavigationDecision.OPEN_POPUP
                isPopup -> YoutubeNavigationDecision.ALLOW_CURRENT
                else -> YoutubeNavigationDecision.DENY
            }
            else -> YoutubeNavigationDecision.DENY
        }
    }
}

internal class YoutubePopupGate {
    var isOpen: Boolean = false
        private set

    fun canOpen(uri: String): Boolean =
        !isOpen && YoutubeNavigationPolicy.decide(uri, newWindow = true) == YoutubeNavigationDecision.OPEN_POPUP

    fun markOpened(): Boolean {
        if (isOpen) return false
        isOpen = true
        return true
    }

    fun close() {
        isOpen = false
    }
}
