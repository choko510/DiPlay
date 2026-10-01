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
    fun aboutBlankIsAllowedOnlyForPopupFlows() {
        assertEquals(YoutubeNavigationDecision.DENY, YoutubeNavigationPolicy.decide("about:blank", false))
        assertEquals(YoutubeNavigationDecision.OPEN_POPUP, YoutubeNavigationPolicy.decide("about:blank", true))
        assertEquals(
            YoutubeNavigationDecision.ALLOW_CURRENT,
            YoutubeNavigationPolicy.decide("about:blank", false, isPopup = true),
        )
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
    fun popupGateHasNoPendingRequestAndAllowsOnlyOnePopup() {
        val gate = YoutubePopupGate()

        assertTrue(gate.canOpen("about:blank"))
        assertTrue(gate.canOpen("https://accounts.google.com/"))
        assertFalse(gate.canOpen("intent://accounts.google.com/"))
        assertFalse(gate.isOpen)

        assertTrue(gate.markOpened())
        assertTrue(gate.isOpen)
        assertFalse(gate.canOpen("https://www.youtube.com/"))
        assertFalse(gate.markOpened())

        gate.close()
        assertFalse(gate.isOpen)
        assertTrue(gate.canOpen("https://accounts.google.com/"))
    }

    @Test
    fun aboutBlankPopupCanRedirectToAnHttpsCurrentPage() {
        val gate = YoutubePopupGate()

        assertTrue(gate.canOpen("about:blank"))
        assertTrue(gate.markOpened())
        assertEquals(
            YoutubeNavigationDecision.ALLOW_CURRENT,
            YoutubeNavigationPolicy.decide("https://accounts.google.com/signin", newWindow = false),
        )
        assertFalse(gate.canOpen("https://accounts.google.com/signin"))
    }
}
