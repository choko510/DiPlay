// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import android.app.Application
import android.content.Intent
import android.os.Looper
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayTransport
import java.lang.reflect.Field
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Verifies that a CarPlay OEM (`requestUI`) request opens DiPlay Menu only while the CarPlay host
 * really owns the screen, and never twice for the same request burst.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CarPlayHostUiRequestTest {
    private lateinit var app: Application
    private var bootstrapReadyField: Field? = null
    private var previousBootstrapReady = false

    @Before
    fun prepare() {
        app = RuntimeEnvironment.getApplication()
        DiPlayUiFocus.reset()
        CarPlayBackgroundSession.clear()
        bootstrapReadyField = DiPlayBootstrap::class.java.getDeclaredField("ready").apply { isAccessible = true }
        previousBootstrapReady = bootstrapReadyField!!.getBoolean(DiPlayBootstrap)
        bootstrapReadyField!!.setBoolean(DiPlayBootstrap, true)
    }

    @After
    fun release() {
        DiPlayUiFocus.reset()
        CarPlayBackgroundSession.clear()
        bootstrapReadyField?.setBoolean(DiPlayBootstrap, previousBootstrapReady)
    }

    @Test
    fun oemRequestOpensTheMenuOnceWhileCarPlayIsVisible() {
        val host = launchHost()
        val session = presentSession(host)

        requestHostUi(host, session)

        val opened = menuIntents(host)
        assertEquals(1, opened.size)
        assertTrue(
            "the existing menu Activity is reused",
            opened.single().flags and Intent.FLAG_ACTIVITY_REORDER_TO_FRONT != 0,
        )

        requestHostUi(host, session)
        assertEquals("a repeated request must not start another menu", 0, menuIntents(host).size)
    }

    @Test
    fun oemRequestIsIgnoredWhileSettingsOwnTheScreen() {
        val host = launchHost()
        val session = presentSession(host)
        DiPlayUiFocus.resumed(Any(), DiPlaySurface.SETTINGS)

        repeat(3) { requestHostUi(host, session) }

        assertEquals("settings keep the screen", 0, menuIntents(host).size)
    }

    @Test
    fun oemRequestIsIgnoredWhileTheHostIsPaused() {
        val host = launchHost()
        val session = presentSession(host)
        invokeLifecycle(host, "onPause")

        requestHostUi(host, session)

        assertEquals(0, menuIntents(host).size)
    }

    @Test
    fun oemRequestFromAnOldSessionIsIgnored() {
        val host = launchHost()
        val current = presentSession(host)
        val stale = TestCarPlayPlaceholders.session()

        requestHostUi(host, stale)

        assertEquals(0, menuIntents(host).size)
        requestHostUi(host, current)
        assertEquals(1, menuIntents(host).size)
    }

    @Test
    fun oemRequestFromAClosedSessionIsIgnored() {
        val host = launchHost()
        val session = presentSession(host, closed = true)

        requestHostUi(host, session)

        assertEquals(0, menuIntents(host).size)
    }

    @Test
    fun returningToCarPlayReArmsTheOemRequest() {
        val host = launchHost()
        val session = presentSession(host)
        requestHostUi(host, session)
        assertEquals(1, menuIntents(host).size)

        // The menu took the screen; the user returns to CarPlay and taps the OEM icon again.
        invokeLifecycle(host, "onPause")
        invokeLifecycle(host, "onResume")
        requestHostUi(host, session)

        assertEquals(1, menuIntents(host).size)
    }

    private fun launchHost(): CarPlayHostActivity {
        val controller = Robolectric.buildActivity(CarPlayHostActivity::class.java).create()
        controller.resume()
        shadowOf(Looper.getMainLooper()).idle()
        return controller.get().also { host ->
            CarPlayBackgroundSession.store(
                TestCarPlayPlaceholders.controller(CarPlayTransport.WIRED),
                AndroidMediaSink(),
                800,
                480,
                owner = host,
                stop = { done -> done() },
            )
        }
    }

    private fun presentSession(host: CarPlayHostActivity, closed: Boolean = false): AirPlaySession =
        TestCarPlayPlaceholders.session(closed).also { session ->
            TestCarPlayPlaceholders.field(host, "activeAirPlaySession").set(host, session)
        }

    private fun menuIntents(host: CarPlayHostActivity): List<Intent> {
        val opened = mutableListOf<Intent>()
        while (true) {
            val started = shadowOf(host).nextStartedActivity ?: break
            if (started.component?.className == DiPlayMenuActivity::class.java.name) opened += started
        }
        return opened
    }

    private fun requestHostUi(host: CarPlayHostActivity, session: AirPlaySession) {
        val generation = TestCarPlayPlaceholders.field(host, "restartGeneration").get(host) as Int
        val epoch = TestCarPlayPlaceholders.field(host, "connectionEpoch").get(host) as Long
        val listener = host.javaClass.getDeclaredMethod(
            "createSessionListener",
            Int::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
        ).apply { isAccessible = true }.invoke(host, generation, epoch) as AirPlaySessionListener
        listener.onHostUiRequested(session)
    }

    private fun invokeLifecycle(host: CarPlayHostActivity, name: String) {
        android.app.Activity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(host)
    }
}
