package com.shilapi.xcertplay.dsp

import com.shilapi.xcertplay.media.dsp.DspRuntimeConfig
import java.io.Closeable
import org.junit.Assert.assertEquals
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

    @Test
    fun versionedSnapshotsAdvanceAndListenersCanBeRemoved() {
        val store = DspRuntimeConfigStore(DspRuntimeConfig(enabled = false))
        val received = mutableListOf<Long>()
        val listener: Closeable = store.addListener { received += it.generation }

        store.update(DspRuntimeConfig(enabled = true))
        assertEquals(1L, store.versionedSnapshot().generation)
        listener.close()
        store.update(DspRuntimeConfig(enabled = false))

        assertEquals(listOf(0L, 1L), received)
        assertEquals(2L, store.versionedSnapshot().generation)
    }
}
