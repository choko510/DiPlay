package com.shilapi.xcertplay

import android.app.Application
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AirPlayPersistenceTest {
    @Test
    fun defaultsOemLabelToDiPlayAndPreservesSavedValue() {
        val context = RuntimeEnvironment.getApplication<Application>()
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

    @Test
    fun normalizesAsciiWhitespaceAndBlankNames() {
        assertEquals("DiPlay", AirPlayPersistence.normalizeCarPlayName("DiPlay"))
        assertTrue(AirPlayPersistence.isCarPlayNameValid("DiPlay"))
        assertEquals("DiPlay", AirPlayPersistence.normalizeCarPlayName("  DiPlay  "))
        assertEquals(
            AirPlayPersistence.DEFAULT_CARPLAY_NAME,
            AirPlayPersistence.normalizeCarPlayName(""),
        )
        assertEquals(
            AirPlayPersistence.DEFAULT_CARPLAY_NAME,
            AirPlayPersistence.normalizeCarPlayName("   "),
        )
    }

    @Test
    fun replacesNullCharactersWithSpaces() {
        val normalized = AirPlayPersistence.normalizeCarPlayName("My\u0000Car")

        assertEquals("My Car", normalized)
        assertFalse('\u0000' in normalized)
    }

    @Test
    fun validatesAsciiUtf8ByteBoundaries() {
        assertTrue(AirPlayPersistence.isCarPlayNameValid("a".repeat(63)))
        assertFalse(AirPlayPersistence.isCarPlayNameValid("a".repeat(64)))
    }

    @Test
    fun validatesThreeByteUtf8Characters() {
        val twentyOneCharacters = "あ".repeat(21)
        val twentyTwoCharacters = "あ".repeat(22)

        assertEquals(63, twentyOneCharacters.toByteArray(Charsets.UTF_8).size)
        assertTrue(AirPlayPersistence.isCarPlayNameValid(twentyOneCharacters))
        assertEquals(66, twentyTwoCharacters.toByteArray(Charsets.UTF_8).size)
        assertFalse(AirPlayPersistence.isCarPlayNameValid(twentyTwoCharacters))
    }

    @Test
    fun validatesFourByteUtf8Characters() {
        assertTrue(AirPlayPersistence.isCarPlayNameValid("😀".repeat(15)))
        assertFalse(AirPlayPersistence.isCarPlayNameValid("😀".repeat(16)))
    }
}
