package com.shilapi.xcertplay.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NcmNtbParametersTest {
    @Test
    fun statusInterruptPollingIsEnabledByDefaultAndCanBeDisabledForComparison() {
        assertTrue(NcmDiagnosticProfile.AUTO.statusPolling)
        assertFalse(NcmDiagnosticProfile.NO_STATUS_POLLING.statusPolling)
    }

    @Test
    fun decodesNtb16FormatAndAdvertisedSizesWithoutChangingThem() {
        val bytes = ByteArray(28)
        put16(bytes, 0, 28)
        put16(bytes, 2, 1)
        put32(bytes, 4, 65_536)
        put16(bytes, 8, 4)
        put16(bytes, 10, 2)
        put16(bytes, 12, 4)
        put32(bytes, 16, 32_768)
        put16(bytes, 20, 8)
        put16(bytes, 22, 1)
        put16(bytes, 24, 8)
        put16(bytes, 26, 8)

        val parameters = NcmUsbBridge.parseNtbParameters(bytes)!!

        assertEquals(1, parameters.supportedFormats)
        assertTrue(parameters.supportedFormats and 1 != 0)
        assertEquals(65_536L, parameters.ntbInMaxSize)
        assertEquals(4, parameters.ndpInDivisor)
        assertEquals(2, parameters.ndpInRemainder)
        assertEquals(4, parameters.ndpInAlignment)
        assertEquals(32_768L, parameters.ntbOutMaxSize)
        assertEquals(8, parameters.ndpOutDivisor)
        assertEquals(1, parameters.ndpOutRemainder)
        assertEquals(8, parameters.ndpOutAlignment)
        assertEquals(8, parameters.ntbOutMaxDatagrams)
        assertTrue(parameters.summary().contains("readChunkBytes=32768"))
    }

    @Test
    fun shortOrMalformedParameterReplyIsUnavailable() {
        assertNull(NcmUsbBridge.parseNtbParameters(ByteArray(27)))

        val bytes = ByteArray(28)
        put16(bytes, 0, 27)
        assertNull(NcmUsbBridge.parseNtbParameters(bytes))
        assertFalse(NcmUsbBridge.parseNtbParameters(bytes, length = 27) != null)
    }

    private fun put16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = value.toByte()
        target[offset + 1] = (value ushr 8).toByte()
    }

    private fun put32(target: ByteArray, offset: Int, value: Int) {
        repeat(4) { index -> target[offset + index] = (value ushr (index * 8)).toByte() }
    }
}
