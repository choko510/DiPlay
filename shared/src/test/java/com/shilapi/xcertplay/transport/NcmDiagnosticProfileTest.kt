package com.shilapi.xcertplay.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NcmDiagnosticProfileTest {
    @Test
    fun automaticProfileEnablesStatusPollingAndKeepsOtherDefaults() {
        val profile = NcmDiagnosticProfile.AUTO

        assertTrue(profile.statusPolling)
        assertTrue(profile.shouldPollStatusEndpoint(statusEndpointAvailable = true))
        assertFalse(profile.shouldPollStatusEndpoint(statusEndpointAvailable = false))
        assertEquals(100, profile.preReadyOutTimeoutMillis)
        assertFalse(profile.synchronousBulkIn)
        assertNull(profile.forcedFunctionPair)
    }

    @Test
    fun eachProfileChangesOnlyItsTargetedStartupVariable() {
        assertFalse(NcmDiagnosticProfile.NO_STATUS_POLLING.statusPolling)
        assertFalse(NcmDiagnosticProfile.NO_STATUS_POLLING.shouldPollStatusEndpoint(statusEndpointAvailable = true))
        assertEquals(100, NcmDiagnosticProfile.NO_STATUS_POLLING.preReadyOutTimeoutMillis)
        assertFalse(NcmDiagnosticProfile.NO_STATUS_POLLING.synchronousBulkIn)
        assertNull(NcmDiagnosticProfile.NO_STATUS_POLLING.forcedFunctionPair)

        assertEquals(250, NcmDiagnosticProfile.OUT_TIMEOUT_250.preReadyOutTimeoutMillis)
        assertTrue(NcmDiagnosticProfile.OUT_TIMEOUT_250.statusPolling)
        assertEquals(500, NcmDiagnosticProfile.OUT_TIMEOUT_500.preReadyOutTimeoutMillis)
        assertTrue(NcmDiagnosticProfile.OUT_TIMEOUT_500.statusPolling)
        assertEquals(1_000, NcmDiagnosticProfile.OUT_TIMEOUT_1000.preReadyOutTimeoutMillis)
        assertTrue(NcmDiagnosticProfile.OUT_TIMEOUT_1000.statusPolling)
        assertEquals(2_000, NcmDiagnosticProfile.LEGACY_OUT_TIMEOUT.preReadyOutTimeoutMillis)
        assertTrue(NcmDiagnosticProfile.LEGACY_OUT_TIMEOUT.statusPolling)
        assertFalse(NcmDiagnosticProfile.LEGACY_OUT_TIMEOUT.synchronousBulkIn)

        assertTrue(NcmDiagnosticProfile.SYNC_BULK_IN.synchronousBulkIn)
        assertEquals(100, NcmDiagnosticProfile.SYNC_BULK_IN.preReadyOutTimeoutMillis)
        assertTrue(NcmDiagnosticProfile.SYNC_BULK_IN.statusPolling)

        assertEquals(3 to 4, NcmDiagnosticProfile.FORCE_3_4.forcedFunctionPair)
        assertEquals(5 to 6, NcmDiagnosticProfile.FORCE_5_6.forcedFunctionPair)
        assertEquals(100, NcmDiagnosticProfile.FORCE_5_6.preReadyOutTimeoutMillis)
        assertTrue(NcmDiagnosticProfile.FORCE_5_6.statusPolling)
    }

    @Test
    fun onlyDebuggableBuildsCanResolveSavedProfiles() {
        assertEquals(
            NcmDiagnosticProfile.AUTO,
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
