package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlayPersistenceTest {
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
