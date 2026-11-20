package com.shilapi.xcertplay.dsp

import com.shilapi.xcertplay.media.dsp.DspRuntimeConfig
import com.shilapi.xcertplay.media.dsp.DspRendererApplyPhase
import com.shilapi.xcertplay.media.dsp.DspRendererApplyStatus
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DspRuntimeConfigStoreTest {
    @Test
    fun studioHotUpdateKillSwitchDoesNotChangeTheActiveAudioSnapshot() {
        val config = DspRuntimeConfig(enabled = true)
        val store = DspRuntimeConfigStore(config)

        assertTrue(store.studioHotUpdatesEnabled())
        assertTrue(store.studioLocalWarmEnabled())
        store.setStudioHotUpdatesEnabled(false)
        store.setStudioLocalWarmEnabled(false)

        assertFalse(store.studioHotUpdatesEnabled())
        assertFalse(store.studioLocalWarmEnabled())
        assertSame(config, store.snapshot())
    }

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

    @Test
    fun rendererApplyStatusTracksProgressAndIgnoresStaleGenerations() {
        val store = DspRuntimeConfigStore(DspRuntimeConfig.disabled())
        store.update(DspRuntimeConfig(enabled = true))

        assertEquals(
            DspRendererApplyStatus(1L, DspRendererApplyPhase.REQUESTED),
            store.rendererApplyStatus(),
        )
        store.reportRendererApplyStatus(DspRendererApplyStatus(1L, DspRendererApplyPhase.PREPARING))
        store.reportRendererApplyStatus(DspRendererApplyStatus(1L, DspRendererApplyPhase.PREPARED))
        store.reportRendererApplyStatus(DspRendererApplyStatus(1L, DspRendererApplyPhase.DEFERRED, activeGeneration = 0L))
        store.update(DspRuntimeConfig(enabled = false))
        store.reportRendererApplyStatus(DspRendererApplyStatus(1L, DspRendererApplyPhase.ACTIVE, activeGeneration = 1L))

        assertEquals(
            DspRendererApplyStatus(2L, DspRendererApplyPhase.REQUESTED, activeGeneration = 0L),
            store.rendererApplyStatus(),
        )
    }

    @Test
    fun activeRendererStatusCannotRegressToPrepared() {
        val store = DspRuntimeConfigStore(DspRuntimeConfig.disabled())
        store.update(DspRuntimeConfig(enabled = true))
        store.reportRendererApplyStatus(DspRendererApplyStatus(1L, DspRendererApplyPhase.ACTIVE, activeGeneration = 1L))
        store.reportRendererApplyStatus(DspRendererApplyStatus(1L, DspRendererApplyPhase.PREPARED))

        assertEquals(
            DspRendererApplyStatus(1L, DspRendererApplyPhase.ACTIVE, activeGeneration = 1L),
            store.rendererApplyStatus(),
        )
    }

    @Test
    fun replacedRendererCannotOverwriteNewOwnerStatusForSameGeneration() {
        val store = DspRuntimeConfigStore(DspRuntimeConfig.disabled())
        store.update(DspRuntimeConfig(enabled = true))
        store.claimRendererApplyStatusOwner(100L)
        store.reportRendererApplyStatus(
            DspRendererApplyStatus(
                generation = 1L,
                phase = DspRendererApplyPhase.PREPARED,
                rendererOwnerToken = 100L,
            ),
        )

        store.claimRendererApplyStatusOwner(101L)
        store.reportRendererApplyStatus(
            DspRendererApplyStatus(
                generation = 1L,
                phase = DspRendererApplyPhase.ACTIVE,
                activeGeneration = 1L,
                rendererOwnerToken = 101L,
            ),
        )
        store.reportRendererApplyStatus(
            DspRendererApplyStatus(
                generation = 1L,
                phase = DspRendererApplyPhase.FAILED,
                activeGeneration = 0L,
                rendererOwnerToken = 100L,
            ),
        )

        assertEquals(
            DspRendererApplyStatus(
                generation = 1L,
                phase = DspRendererApplyPhase.ACTIVE,
                activeGeneration = 1L,
                rendererOwnerToken = 101L,
            ),
            store.rendererApplyStatus(),
        )
    }

    @Test
    fun delayedOlderRendererClaimCannotReplaceTheCurrentOwner() {
        val store = DspRuntimeConfigStore(DspRuntimeConfig.disabled())
        store.update(DspRuntimeConfig(enabled = true))
        store.claimRendererApplyStatusOwner(101L)
        store.reportRendererApplyStatus(
            DspRendererApplyStatus(
                generation = 1L,
                phase = DspRendererApplyPhase.ACTIVE,
                activeGeneration = 1L,
                rendererOwnerToken = 101L,
            ),
        )

        store.claimRendererApplyStatusOwner(100L)
        store.reportRendererApplyStatus(
            DspRendererApplyStatus(
                generation = 1L,
                phase = DspRendererApplyPhase.FAILED,
                rendererOwnerToken = 100L,
            ),
        )

        assertEquals(101L, store.rendererApplyStatus()?.rendererOwnerToken)
        assertEquals(DspRendererApplyPhase.ACTIVE, store.rendererApplyStatus()?.phase)
    }

    @Test
    fun concurrentRendererReplacementRejectsLateStatusFromThePreviousOwner() {
        val store = DspRuntimeConfigStore(DspRuntimeConfig.disabled())
        store.update(DspRuntimeConfig(enabled = true))
        store.claimRendererApplyStatusOwner(100L)
        val start = CountDownLatch(1)
        val oldOwner = Thread {
            check(start.await(5, TimeUnit.SECONDS))
            repeat(500) {
                store.reportRendererApplyStatus(
                    DspRendererApplyStatus(
                        generation = 1L,
                        phase = DspRendererApplyPhase.PREPARED,
                        rendererOwnerToken = 100L,
                    ),
                )
            }
        }
        val newOwner = Thread {
            check(start.await(5, TimeUnit.SECONDS))
            store.claimRendererApplyStatusOwner(101L)
            store.reportRendererApplyStatus(
                DspRendererApplyStatus(
                    generation = 1L,
                    phase = DspRendererApplyPhase.ACTIVE,
                    activeGeneration = 1L,
                    rendererOwnerToken = 101L,
                ),
            )
        }

        oldOwner.start()
        newOwner.start()
        start.countDown()
        oldOwner.join(5_000)
        newOwner.join(5_000)
        assertFalse(oldOwner.isAlive)
        assertFalse(newOwner.isAlive)
        store.reportRendererApplyStatus(
            DspRendererApplyStatus(
                generation = 1L,
                phase = DspRendererApplyPhase.FAILED,
                rendererOwnerToken = 100L,
            ),
        )

        assertEquals(101L, store.rendererApplyStatus()?.rendererOwnerToken)
        assertEquals(DspRendererApplyPhase.ACTIVE, store.rendererApplyStatus()?.phase)
    }

    @Test
    fun activePlaybackSampleRateTracksNewestRendererAndFallsBackWhenItReleases() {
        val store = DspRuntimeConfigStore(DspRuntimeConfig.disabled())
        val firstOwner = Any()
        val secondOwner = Any()

        assertEquals(null, store.activePlaybackSampleRateHz())
        store.reportPlaybackSampleRate(firstOwner, 44_100)
        store.reportPlaybackSampleRate(secondOwner, 48_000)
        assertEquals(48_000, store.activePlaybackSampleRateHz())

        store.reportPlaybackSampleRate(secondOwner, null)
        assertEquals(44_100, store.activePlaybackSampleRateHz())
        store.reportPlaybackSampleRate(firstOwner, 1_000)
        assertEquals(44_100, store.activePlaybackSampleRateHz())
        store.reportPlaybackSampleRate(firstOwner, null)
        assertEquals(null, store.activePlaybackSampleRateHz())
    }

    @Test
    fun diagnosticSummaryIsFencedByOwnerAndGenerationAndClearsOnRelease() {
        val store = DspRuntimeConfigStore(DspRuntimeConfig.disabled())
        store.update(DspRuntimeConfig(enabled = true))
        store.claimRendererApplyStatusOwner(100L)
        store.reportDiagnosticSummary(100L, 1L, "role=MEDIA, notes=none")
        assertEquals("role=MEDIA, notes=none", store.diagnosticSummary())

        // Older generation and non-owner reports are ignored.
        store.reportDiagnosticSummary(100L, 0L, "stale-gen")
        store.reportDiagnosticSummary(99L, 5L, "stale-owner")
        assertEquals("role=MEDIA, notes=none", store.diagnosticSummary())

        // Claiming a new owner clears the prior owner's summary.
        store.claimRendererApplyStatusOwner(101L)
        assertEquals(null, store.diagnosticSummary())
        store.reportDiagnosticSummary(100L, 2L, "old-owner-after-transfer")
        assertEquals(null, store.diagnosticSummary())

        store.reportDiagnosticSummary(101L, 1L, "new-owner-active")
        assertEquals("new-owner-active", store.diagnosticSummary())

        // Releasing a non-owning renderer leaves the active owner summary intact; releasing the active owner clears it.
        store.clearDiagnosticSummary(100L)
        assertEquals("new-owner-active", store.diagnosticSummary())
        store.clearDiagnosticSummary(101L)
        assertEquals(null, store.diagnosticSummary())
    }

    @Test
    fun concurrentOwnerTransferPreventsStaleDiagnosticSummaryCasOverwrite() {
        val store = DspRuntimeConfigStore(DspRuntimeConfig.disabled())
        store.update(DspRuntimeConfig(enabled = true))
        store.claimRendererApplyStatusOwner(100L)

        val enteredCasWindow = CountDownLatch(1)
        val allowStaleCasResume = CountDownLatch(1)
        store.beforeDiagnosticSummaryCasForTesting = {
            store.beforeDiagnosticSummaryCasForTesting = null
            enteredCasWindow.countDown()
            check(allowStaleCasResume.await(5, TimeUnit.SECONDS))
        }

        val staleThread = Thread {
            store.reportDiagnosticSummary(100L, 1L, "stale-old-owner-summary")
        }.apply { isDaemon = true }
        staleThread.start()

        assertTrue(enteredCasWindow.await(5, TimeUnit.SECONDS))
        store.claimRendererApplyStatusOwner(101L)
        assertEquals(null, store.diagnosticSummary())

        allowStaleCasResume.countDown()
        staleThread.join(5_000)
        assertFalse(staleThread.isAlive)
        assertEquals(null, store.diagnosticSummary())

        store.reportDiagnosticSummary(101L, 1L, "new-owner-summary")
        assertEquals("new-owner-summary", store.diagnosticSummary())
    }
}
