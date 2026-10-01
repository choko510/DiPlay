package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.media.NavigationAudioRoute
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AirPlayPersistenceAndroidTest {
    @Test
    fun navigationAudioRouteDefaultsToFullBandAndPersistsSelection() {
        val context = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
        val hadSavedRoute = preferences.contains("navigation_audio_route")
        val savedRoute = preferences.getString("navigation_audio_route", null)

        try {
            preferences.edit().remove("navigation_audio_route").commit()
            assertEquals(NavigationAudioRoute.FULL_BAND, AirPlayPersistence.loadNavigationAudioRoute(context))

            AirPlayPersistence.saveNavigationAudioRoute(context, NavigationAudioRoute.SYSTEM_NAVIGATION)
            assertEquals(
                NavigationAudioRoute.SYSTEM_NAVIGATION,
                AirPlayPersistence.loadNavigationAudioRoute(context),
            )
        } finally {
            val editor = preferences.edit()
            if (hadSavedRoute) editor.putString("navigation_audio_route", savedRoute)
            else editor.remove("navigation_audio_route")
            editor.commit()
        }
    }

    @Test
    fun defaultsOemLabelToDiPlayAndPreservesSavedValue() {
        val context = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
        val hadSavedLabel = preferences.contains("oem_label")
        val savedLabel = preferences.getString("oem_label", null)

        try {
            preferences.edit().remove("oem_label").commit()
            assertEquals("DiPlay", AirPlayPersistence.loadOemLabel(context))

            AirPlayPersistence.saveOemLabel(context, "Example OEM")
            assertEquals("Example OEM", AirPlayPersistence.loadOemLabel(context))
        } finally {
            val editor = preferences.edit()
            if (hadSavedLabel) editor.putString("oem_label", savedLabel)
            else editor.remove("oem_label")
            editor.commit()
        }
    }
}
