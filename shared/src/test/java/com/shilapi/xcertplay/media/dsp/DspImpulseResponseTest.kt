package com.shilapi.xcertplay.media.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DspImpulseResponseTest {
    @Test
    fun rejectsFiniteSamplesOutsideTheNativeFftSafetyRange() {
        assertIllegalArgument {
            DspImpulseResponse("large", 48_000, 1, floatArrayOf(Float.MAX_VALUE))
        }
    }

    @Test
    fun rejectsFiniteImpulseResponsesWithExcessiveAccumulatedMagnitude() {
        assertIllegalArgument {
            DspImpulseResponse("large_sum", 48_000, 1, FloatArray(31_251) { 32.0f })
        }
    }

    @Test
    fun acceptsAnImpulseAtThePerSampleLimit() {
        val impulse = DspImpulseResponse("bounded", 48_000, 2, floatArrayOf(32.0f, -32.0f))

        assertEquals(1, impulse.frameCount)
        assertEquals(32.0f, impulse.sampleAt(0, 0), 0.0f)
        assertEquals(-32.0f, impulse.sampleAt(0, 1), 0.0f)
    }

    private fun assertIllegalArgument(action: () -> Unit) {
        var threw = false
        try {
            action()
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue("expected IR validation to reject the samples", threw)
    }
}
