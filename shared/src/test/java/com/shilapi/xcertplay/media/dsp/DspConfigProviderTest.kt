package com.shilapi.xcertplay.media.dsp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DspConfigProviderTest {
    @Test
    fun disabledConfigIsTheSafeDefaultAndProviderReturnsTheCurrentSnapshot() {
        var current = DspRuntimeConfig.disabled()
        val provider = DspConfigProvider { current }

        assertFalse(provider.snapshot().enabled)
        assertSame(current, provider.snapshot())

        current = DspRuntimeConfig(enabled = true)
        assertSame(current, provider.snapshot())
        assertTrue(provider.snapshot().enabled)
    }
}
