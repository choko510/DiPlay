package com.shilapi.xcertplay.media.dsp

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
}
