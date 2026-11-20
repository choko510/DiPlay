// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiPlayNavigationPolicyTest {
    @After
    fun resetFocus() {
        DiPlayUiFocus.reset()
    }

    @Test
    fun onlyAPlainAppStartIsAnAutomaticConnectionTrigger() {
        assertEquals(
            DiPlayLaunchOrigin.APP_START,
            DiPlayLaunchIntentPolicy.origin(page = null, returnToMenu = false, openConnectedIphone = false),
        )
        assertEquals(
            DiPlayLaunchOrigin.USER_NAVIGATION,
            DiPlayLaunchIntentPolicy.origin(page = "settings", returnToMenu = false, openConnectedIphone = false),
        )
        assertEquals(
            DiPlayLaunchOrigin.USER_NAVIGATION,
            DiPlayLaunchIntentPolicy.origin(page = "settings", returnToMenu = true, openConnectedIphone = false),
        )
        assertEquals(
            DiPlayLaunchOrigin.USER_NAVIGATION,
            DiPlayLaunchIntentPolicy.origin(page = null, returnToMenu = true, openConnectedIphone = false),
        )
        assertEquals(
            DiPlayLaunchOrigin.EXPLICIT_CONNECT_REQUEST,
            DiPlayLaunchIntentPolicy.origin(page = "settings", returnToMenu = true, openConnectedIphone = true),
        )

        assertTrue(DiPlayLaunchIntentPolicy.shouldAutoConnectOnResume(DiPlayLaunchOrigin.APP_START))
        assertTrue(DiPlayLaunchIntentPolicy.shouldAutoLaunchUsbOnResume(DiPlayLaunchOrigin.APP_START))
        assertFalse(DiPlayLaunchIntentPolicy.shouldAutoConnectOnResume(DiPlayLaunchOrigin.USER_NAVIGATION))
        assertFalse(DiPlayLaunchIntentPolicy.shouldAutoLaunchUsbOnResume(DiPlayLaunchOrigin.USER_NAVIGATION))
        assertFalse(DiPlayLaunchIntentPolicy.shouldAutoLaunchUsbOnResume(DiPlayLaunchOrigin.EXPLICIT_CONNECT_REQUEST))
    }

    @Test
    fun onlySettingsMenuAndDspProtectTheUserFromAutomaticForegroundChanges() {
        assertTrue(DiPlaySurface.SETTINGS.isUserNavigationTarget)
        assertTrue(DiPlaySurface.DSP_SETTINGS.isUserNavigationTarget)
        assertTrue(DiPlaySurface.MENU.isUserNavigationTarget)
        assertFalse(DiPlaySurface.CARPLAY_HOST.isUserNavigationTarget)
        assertFalse(DiPlaySurface.HOME.isUserNavigationTarget)
        assertFalse(DiPlaySurface.NONE.isUserNavigationTarget)
    }

    @Test
    fun focusFollowsTheResumedOwnerAndIsNotClearedByAnOlderActivity() {
        val settings = Any()
        val host = Any()

        DiPlayUiFocus.resumed(settings, DiPlaySurface.SETTINGS)
        assertEquals(DiPlaySurface.SETTINGS, DiPlayUiFocus.current())
        assertTrue(DiPlayUiFocus.isUserNavigationTarget())

        DiPlayUiFocus.resumed(host, DiPlaySurface.CARPLAY_HOST)
        assertEquals(DiPlaySurface.CARPLAY_HOST, DiPlayUiFocus.current())
        assertFalse(DiPlayUiFocus.isUserNavigationTarget())

        // A late pause from the already paused settings activity must not clear the host.
        DiPlayUiFocus.paused(settings)
        assertEquals(DiPlaySurface.CARPLAY_HOST, DiPlayUiFocus.current())

        DiPlayUiFocus.paused(host)
        assertEquals(DiPlaySurface.NONE, DiPlayUiFocus.current())
    }

    @Test
    fun announcedNavigationProtectsTheDestinationUntilItResumes() {
        val host = Any()
        val settings = Any()

        DiPlayUiFocus.resumed(host, DiPlaySurface.CARPLAY_HOST)
        DiPlayUiFocus.navigatingTo(host, DiPlaySurface.SETTINGS)
        assertEquals(DiPlaySurface.SETTINGS, DiPlayUiFocus.current())

        // Android pauses the source before the destination resumes.
        DiPlayUiFocus.paused(host)
        assertEquals(
            "the announced settings screen still owns the display",
            DiPlaySurface.SETTINGS,
            DiPlayUiFocus.current(),
        )
        assertTrue(DiPlayUiFocus.isUserNavigationTarget())

        DiPlayUiFocus.resumed(settings, DiPlaySurface.SETTINGS)
        DiPlayUiFocus.paused(settings)
        assertEquals(
            "a screen the user left no longer blocks automatic launches",
            DiPlaySurface.NONE,
            DiPlayUiFocus.current(),
        )
    }

    @Test
    fun leavingDiPlayReleasesTheNavigationProtection() {
        val menu = Any()

        DiPlayUiFocus.resumed(menu, DiPlaySurface.MENU)
        DiPlayUiFocus.navigatingTo(menu, DiPlaySurface.NONE)
        DiPlayUiFocus.paused(menu)

        assertEquals(DiPlaySurface.NONE, DiPlayUiFocus.current())
        assertFalse(DiPlayUiFocus.isUserNavigationTarget())
    }

    @Test
    fun hostUiRequestsAreAcceptedOnlyFromTheVisibleCarPlayHost() {
        assertTrue(
            shouldOpenHostUi(
                hostVisible = true,
                menuOpen = false,
                surface = DiPlaySurface.CARPLAY_HOST,
                sessionCurrent = true,
                sessionClosed = false,
            ),
        )
        assertFalse(
            "settings must keep the screen",
            shouldOpenHostUi(true, false, DiPlaySurface.SETTINGS, true, false),
        )
        assertFalse(
            "DSP settings must keep the screen",
            shouldOpenHostUi(true, false, DiPlaySurface.DSP_SETTINGS, true, false),
        )
        assertFalse(
            "the menu is already open",
            shouldOpenHostUi(true, false, DiPlaySurface.MENU, true, false),
        )
        assertFalse(
            "the host is not resumed",
            shouldOpenHostUi(false, false, DiPlaySurface.CARPLAY_HOST, true, false),
        )
        assertFalse(
            "host settings are open",
            shouldOpenHostUi(true, true, DiPlaySurface.CARPLAY_HOST, true, false),
        )
        assertFalse(
            "a stale session must not open the menu",
            shouldOpenHostUi(true, false, DiPlaySurface.CARPLAY_HOST, false, false),
        )
        assertFalse(
            "a closed session must not open the menu",
            shouldOpenHostUi(true, false, DiPlaySurface.CARPLAY_HOST, true, true),
        )
    }

    @Test
    fun duplicateHostUiRequestsAreCollapsedForTheSameSession() {
        val gate = HostUiRequestGate(duplicateWindowMillis = 750L)
        val session = Any()

        assertTrue(gate.accept(session, nowMillis = 10_000L))
        repeat(20) {
            assertFalse(gate.accept(session, nowMillis = 10_100L))
        }
        assertTrue("a new deliberate tap after the window is accepted", gate.accept(session, nowMillis = 10_800L))
        assertTrue("a new session is accepted immediately", gate.accept(Any(), nowMillis = 10_850L))

        gate.reset()
        assertTrue("returning to CarPlay re-arms the gate", gate.accept(session, nowMillis = 10_900L))
    }
}
