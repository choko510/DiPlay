package com.shilapi.xcertplay.media.dsp

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeDspBridgeInstrumentedTest {
    @Test
    fun loadCreateProcessResetDestroyAndRejectInvalidHandle() {
        assertTrue(NativeDspLibrary.ensureLoaded())
        assertTrue(NativeDspJni.reset(0L) != 0)
        val format = DspAudioFormat(sampleRate = 48_000, channels = 2)
        val processor = NativeDspProcessor.createOrNull(format, gainDb = 0.0)
        assertNotNull(processor)
        processor ?: return

        try {
            val input = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder()).apply {
                putFloat(0.25f)
                putFloat(-0.5f)
                putFloat(0.75f)
                putFloat(-1.0f)
                flip()
            }
            val output = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder())

            val result = processor.process(input, output, frames = 2)

            assertEquals(DspProcessStatus.SUCCESS, result.status)
            assertEquals(2, result.outputFrames)
            output.flip()
            assertEquals(0.25f, output.float, 0f)
            assertEquals(-0.5f, output.float, 0f)
            assertEquals(0.75f, output.float, 0f)
            assertEquals(-1.0f, output.float, 0f)
            assertEquals(2L, processor.diagnostics().processedFrames)
            processor.reset()
            assertEquals(0L, processor.diagnostics().processedFrames)
        } finally {
            processor.close()
        }
    }
}
