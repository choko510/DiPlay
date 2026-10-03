package com.shilapi.xcertplay.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NcmDiagnosticProfileTest {
    @Test
    fun automaticProfileMatchesTheShippedDefaults() {
        val profile = NcmDiagnosticProfile.AUTO

        assertFalse(profile.statusPolling)
        assertEquals(100, profile.preReadyOutTimeoutMillis)
        assertFalse(profile.synchronousBulkIn)
        assertNull(profile.forcedFunctionPair)
    }

    @Test
    fun eachProfileChangesOnlyItsTargetedStartupVariable() {
        assertTrue(NcmDiagnosticProfile.STATUS_POLLING.statusPolling)
        assertEquals(100, NcmDiagnosticProfile.STATUS_POLLING.preReadyOutTimeoutMillis)
        assertFalse(NcmDiagnosticProfile.STATUS_POLLING.synchronousBulkIn)

        assertEquals(250, NcmDiagnosticProfile.OUT_TIMEOUT_250.preReadyOutTimeoutMillis)
        assertEquals(500, NcmDiagnosticProfile.OUT_TIMEOUT_500.preReadyOutTimeoutMillis)
        assertEquals(1_000, NcmDiagnosticProfile.OUT_TIMEOUT_1000.preReadyOutTimeoutMillis)
        assertEquals(2_000, NcmDiagnosticProfile.LEGACY_OUT_TIMEOUT.preReadyOutTimeoutMillis)
        assertFalse(NcmDiagnosticProfile.LEGACY_OUT_TIMEOUT.statusPolling)
        assertFalse(NcmDiagnosticProfile.LEGACY_OUT_TIMEOUT.synchronousBulkIn)

        assertTrue(NcmDiagnosticProfile.SYNC_BULK_IN.synchronousBulkIn)
        assertEquals(100, NcmDiagnosticProfile.SYNC_BULK_IN.preReadyOutTimeoutMillis)
        assertFalse(NcmDiagnosticProfile.SYNC_BULK_IN.statusPolling)

        assertEquals(3 to 4, NcmDiagnosticProfile.FORCE_3_4.forcedFunctionPair)
        assertEquals(5 to 6, NcmDiagnosticProfile.FORCE_5_6.forcedFunctionPair)
        assertEquals(100, NcmDiagnosticProfile.FORCE_5_6.preReadyOutTimeoutMillis)
        assertFalse(NcmDiagnosticProfile.FORCE_5_6.statusPolling)
    }

    @Test
    fun onlyDebuggableBuildsCanResolveSavedProfiles() {
        assertEquals(
            NcmDiagnosticProfile.STATUS_POLLING,
            resolveNcmDiagnosticProfile(debuggable = true, savedName = "STATUS_POLLING"),
        )
        assertEquals(
            NcmDiagnosticProfile.AUTO,
            resolveNcmDiagnosticProfile(debuggable = true, savedName = "not-a-profile"),
        )
        assertEquals(
            NcmDiagnosticProfile.AUTO,
            resolveNcmDiagnosticProfile(debuggable = false, savedName = "STATUS_POLLING"),
        )
    }
}
