package com.shilapi.xcertplay.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YoutubeNavigationPolicyTest {
    @Test
    fun currentGoogleAndYoutubeHttpsPagesAreAllowed() {
        listOf(
            "https://m.youtube.com/",
            "https://accounts.google.com/signin",
            "https://google.com/",
        ).forEach { uri ->
            assertEquals(YoutubeNavigationDecision.ALLOW_CURRENT, YoutubeNavigationPolicy.decide(uri, false))
        }
    }

    @Test
    fun httpsNewWindowUsesOneTemporaryPopup() {
        assertEquals(
            YoutubeNavigationDecision.OPEN_POPUP,
            YoutubeNavigationPolicy.decide("https://accounts.google.com/", true),
        )
    }

    @Test
    fun aboutBlankIsAllowedOnlyAsANewWindow() {
        assertEquals(YoutubeNavigationDecision.DENY, YoutubeNavigationPolicy.decide("about:blank", false))
        assertEquals(YoutubeNavigationDecision.OPEN_POPUP, YoutubeNavigationPolicy.decide("about:blank", true))
        assertEquals(YoutubeNavigationDecision.DENY, YoutubeNavigationPolicy.decide("about:config", true))
    }

    @Test
    fun insecureAndExternalSchemesAreDenied() {
        listOf(
            "http://m.youtube.com/",
            "intent://youtube",
            "market://details?id=example",
            "file:///data/local/tmp/page.html",
            "javascript:alert(1)",
            "data:text/html,example",
            "not a URL",
        ).forEach { uri ->
            assertEquals(uri, YoutubeNavigationDecision.DENY, YoutubeNavigationPolicy.decide(uri, true))
            assertEquals(uri, YoutubeNavigationDecision.DENY, YoutubeNavigationPolicy.decide(uri, false))
        }
    }

    @Test
    fun popupGateAllowsOneAllowedPopupAndRejectsStaleOrNestedWindows() {
        val gate = YoutubePopupGate()

        assertTrue(gate.request("about:blank"))
        assertFalse(gate.request("https://accounts.google.com/"))
        assertFalse(gate.consume("intent://accounts.google.com/"))
        assertFalse(gate.isOpen)

        assertTrue(gate.request("https://accounts.google.com/"))
        assertTrue(gate.consume("https://accounts.google.com/signin/callback"))
        assertTrue(gate.isOpen)
        assertFalse(gate.request("https://www.youtube.com/"))

        gate.close()
        assertFalse(gate.isOpen)
        assertTrue(gate.consume("about:blank"))
        assertFalse(gate.consume("https://accounts.google.com/"))
    }
}
