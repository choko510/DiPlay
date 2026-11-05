package com.shilapi.xcertplay.media.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DspStreamPolicyTest {
    @Test
    fun onlyMediaCanUseTheFullDspChain() {
        assertTrue(DspStreamPolicy.allowsFullDsp(DspStreamRole.MEDIA))
        listOf(
            DspStreamRole.NAVIGATION,
            DspStreamRole.ALERT,
            DspStreamRole.ASSISTANT,
            DspStreamRole.PHONE,
            DspStreamRole.OTHER_LOW_LATENCY,
        ).forEach { role -> assertFalse(DspStreamPolicy.allowsFullDsp(role)) }
    }

    @Test
    fun processingRequiresBothTheGlobalEnableFlagAndMediaRole() {
        assertFalse(DspStreamPolicy.shouldProcess(false, DspStreamRole.MEDIA))
        assertTrue(DspStreamPolicy.shouldProcess(true, DspStreamRole.MEDIA))
        assertFalse(DspStreamPolicy.shouldProcess(true, DspStreamRole.NAVIGATION))
    }

    @Test
    fun nonMediaSampleRateCannotReplaceActiveMediaRate() {
        val rates = linkedMapOf<Any, Int>()
        val report: (Any, Int?) -> Unit = { owner, rate ->
            if (rate == null) rates.remove(owner) else rates[owner] = rate
        }
        val mediaOwner = Any()

        DspStreamPolicy.reportPlaybackSampleRate(DspStreamRole.MEDIA, mediaOwner, 48_000, report)
        DspStreamPolicy.reportPlaybackSampleRate(DspStreamRole.NAVIGATION, Any(), 44_100, report)

        assertEquals(48_000, rates.values.last())
    }
}
