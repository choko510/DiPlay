package com.shilapi.xcertplay.dsp

import com.shilapi.xcertplay.media.dsp.DspRuntimeConfig
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DspRuntimeConfigStoreTest {
    @Test
    fun existingRendererSnapshotStaysStableWhileNewSnapshotsSeeTheUpdatedConfig() {
        val configA = DspRuntimeConfig(enabled = true, gainDb = -3.0)
        val configB = DspRuntimeConfig(enabled = true, gainDb = 6.0)
        val store = DspRuntimeConfigStore(configA)
        val existingRendererConfig = store.snapshot()

        store.update(configB)

        assertSame(configA, existingRendererConfig)
        assertSame(configB, store.snapshot())
        assertTrue(existingRendererConfig.gainDb == -3.0)
    }
}
