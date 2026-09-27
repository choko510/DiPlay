package com.shilapi.xcertplay

import android.content.Context
import android.content.res.Configuration
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.shared.AppLanguage
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AppLanguageTest {
    private val application = RuntimeEnvironment.getApplication()

    @Before
    fun clearSavedLanguage() {
        application.getSharedPreferences("diplay", Context.MODE_PRIVATE)
            .edit()
            .remove("app_language")
            .commit()
    }

    @Test
    fun defaultsToJapaneseWhenNoLanguageIsSaved() {
        assertEquals(AppLanguage.JAPANESE, AppLanguage.get(application))
        assertEquals(
            "基板 I2C 診断",
            AppLanguage.localizedContext(application).getString(R.string.main_diagnostic_title),
        )
    }

    @Test
    fun savesEnglishLanguageAndKeepsItAcrossContexts() {
        AppLanguage.set(application, AppLanguage.ENGLISH)

        val newContext = application.createConfigurationContext(Configuration(application.resources.configuration))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.get(newContext))
        assertEquals(
            "Board I2C diagnostic",
            AppLanguage.localizedContext(newContext).getString(R.string.main_diagnostic_title),
        )
    }

    @Test
    fun selectedLanguageOverridesTheDeviceLocale() {
        val japaneseDeviceConfig = Configuration(application.resources.configuration).apply {
            setLocale(Locale.JAPANESE)
        }
        val japaneseDeviceContext = application.createConfigurationContext(japaneseDeviceConfig)
        AppLanguage.set(application, AppLanguage.ENGLISH)
        val englishResources = AppLanguage.localizedContext(japaneseDeviceContext)

        assertEquals("Board I2C diagnostic", englishResources.getString(R.string.main_diagnostic_title))
        assertEquals("Disconnect", englishResources.getString(R.string.notification_disconnect))

        val englishDeviceConfig = Configuration(application.resources.configuration).apply {
            setLocale(Locale.ENGLISH)
        }
        val englishDeviceContext = application.createConfigurationContext(englishDeviceConfig)
        AppLanguage.set(application, AppLanguage.JAPANESE)
        val japaneseResources = AppLanguage.localizedContext(englishDeviceContext)

        assertEquals("基板 I2C 診断", japaneseResources.getString(R.string.main_diagnostic_title))
        assertEquals("切断", japaneseResources.getString(R.string.notification_disconnect))
    }
}
