package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AirPlaySessionDiagnosticPrivacyTest {
    @Test
    fun touchDiagnosticContainsCountWithoutCoordinatesOrHidPayload() {
        val diagnostic = touchReportSentDiagnostic(2)

        assertEquals("airplay touch report sent contacts=2", diagnostic)
        assertFalse(diagnostic.contains("x="))
        assertFalse(diagnostic.contains("y="))
        assertFalse(diagnostic.contains("report="))
    }
}
