// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import android.app.Application
import android.content.Intent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The DSP editor is opened from the DiPlay settings screen and returns with `finish()`, so the
 * settings Activity never resumes before the editor pauses. The settings screen must stay protected
 * across that return instead of falling back to "no DiPlay screen is visible".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DspSettingsReturnFocusTest {
    private lateinit var app: Application

    @Before
    fun prepare() {
        app = RuntimeEnvironment.getApplication()
        DiPlayUiFocus.reset()
    }

    @After
    fun release() {
        DiPlayUiFocus.reset()
    }

    @Test
    fun closingTheDspEditorKeepsTheSettingsScreenProtected() {
        val settings = Robolectric.buildActivity(
            DiPlayActivity::class.java,
            Intent(app, DiPlayActivity::class.java).putExtra("page", "settings"),
        ).setup()
        assertEquals(DiPlaySurface.SETTINGS, DiPlayUiFocus.current())

        val dsp = Robolectric.buildActivity(DspSettingsActivity::class.java).setup()
        assertEquals("the editor owns the display", DiPlaySurface.DSP_SETTINGS, DiPlayUiFocus.current())

        dsp.get().finish()
        dsp.pause()

        assertEquals(
            "finish() must hand the protection to the settings screen it returns to",
            DiPlaySurface.SETTINGS,
            DiPlayUiFocus.current(),
        )
        assertTrue(DiPlayUiFocus.isUserNavigationTarget())
    }

    @Test
    fun theDspEditorStaysProtectedWhileItIsOnlyPaused() {
        val dsp = Robolectric.buildActivity(DspSettingsActivity::class.java).setup()
        assertEquals(DiPlaySurface.DSP_SETTINGS, DiPlayUiFocus.current())

        // A transparent receiver or a dialog pauses the editor without finishing it.
        dsp.pause()

        DiPlayUiFocus.transientOverlayResumed(Any())
        assertEquals(DiPlaySurface.DSP_SETTINGS, DiPlayUiFocus.current())
        assertTrue(DiPlayUiFocus.isUserNavigationTarget())

        dsp.resume()
        assertEquals(DiPlaySurface.DSP_SETTINGS, DiPlayUiFocus.current())
    }
}
