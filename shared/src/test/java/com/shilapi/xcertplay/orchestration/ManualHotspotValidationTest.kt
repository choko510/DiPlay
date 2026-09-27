package com.shilapi.xcertplay.orchestration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ManualHotspotValidationTest {
    @Test
    fun `accepts a WPA2 car hotspot and an open one`() {
        assertNull(ManualHotspotValidation.validate("BYD_1234", "12345678"))
        assertNull(ManualHotspotValidation.validate("BYD_1234", ""))
        assertNull(ManualHotspotValidation.issue("BYD_1234", "12345678"))
        assertNull(ManualHotspotValidation.issue("BYD_1234", ""))
        assertEquals(ManualHotspotSecurity.WPA2, ManualHotspotValidation.securityFor("12345678"))
        assertEquals(ManualHotspotSecurity.OPEN, ManualHotspotValidation.securityFor(""))
    }

    @Test
    fun `rejects what the iPhone cannot join`() {
        assertNotNull(ManualHotspotValidation.validate(" ", "12345678"))
        assertEquals(ManualHotspotValidation.Issue.EMPTY_SSID, ManualHotspotValidation.issue(" ", "12345678"))
        assertNotNull(ManualHotspotValidation.validate("BYD", "short"))
        assertEquals(
            ManualHotspotValidation.Issue.INVALID_PASSWORD_LENGTH,
            ManualHotspotValidation.issue("BYD", "short"),
        )
        assertNotNull(ManualHotspotValidation.validate("x".repeat(33), "12345678"))
        assertEquals(
            ManualHotspotValidation.Issue.SSID_TOO_LONG,
            ManualHotspotValidation.issue("x".repeat(33), "12345678"),
        )
        assertNotNull(ManualHotspotValidation.validate("BYD", "a".repeat(64)))
        assertEquals(
            ManualHotspotValidation.Issue.INVALID_PASSWORD_LENGTH,
            ManualHotspotValidation.issue("BYD", "a".repeat(64)),
        )
    }

    @Test
    fun `reports invalid null characters in the name or password`() {
        assertEquals(
            ManualHotspotValidation.Issue.INVALID_CHARACTER,
            ManualHotspotValidation.issue("BYD\u0000", "12345678"),
        )
        assertEquals(
            ManualHotspotValidation.Issue.INVALID_CHARACTER,
            ManualHotspotValidation.issue("BYD", "123456\u00008"),
        )
        assertEquals(
            "The name or password contains an invalid character",
            ManualHotspotValidation.validate("BYD\u0000", "12345678"),
        )
    }
}
