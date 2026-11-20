package com.shilapi.xcertplay.dsp

import android.app.Application
import com.shilapi.xcertplay.media.dsp.DspSpeedCompensationConfig
import com.shilapi.xcertplay.speed.SpeedCompensationSettings
import com.shilapi.xcertplay.speed.SpeedFixListener
import com.shilapi.xcertplay.speed.SpeedGpsState
import com.shilapi.xcertplay.speed.SpeedSource
import com.shilapi.xcertplay.speed.SpeedSourceStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DspSpeedCompensationRuntimeTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val published = mutableListOf<DspSpeedCompensationConfig>()
    private val source = FakeSpeedSource()
    private var nowNanos = 0L
    private lateinit var runtime: DspSpeedCompensationRuntime

    private fun createRuntime(dspEnabled: Boolean = true) {
        published.clear()
        runtime = DspSpeedCompensationRuntime(
            context = context,
            publishConfig = { published += it },
            dspEnabledProvider = { dspEnabled },
            nowNanos = { nowNanos },
            store = DspSpeedCompensationStore(context),
            sourceFactory = { source },
        )
    }

    /** Drives one controller tick by advancing the injected clock and re-publishing settings. */
    private fun tick(steps: Int = 1) {
        repeat(steps) {
            nowNanos += TICK_NANOS
            runtime.previewSettings(runtime.settings())
        }
    }

    @Test
    fun featureStaysOffAndDoesNotTouchTheSourceUntilItIsEnabled() {
        createRuntime()
        runtime.attachHost()

        assertEquals(SpeedGpsState.STOPPED, runtime.status().state.gpsState)
        assertFalse(runtime.status().running)
        assertEquals(0, source.startCount)
        assertTrue("an inactive layer must not publish anything", published.isEmpty())
    }

    @Test
    fun enablingWithoutTestSpeedSubscribesToTheGpsSource() {
        createRuntime()
        runtime.updateSettings(SpeedCompensationSettings().withMasterEnabled(true))
        runtime.attachHost()
        runtime.setTestSpeed(null)

        assertTrue(runtime.status().running)
        assertEquals(1, source.startCount)
        assertTrue(published.last().enabled)
    }

    @Test
    fun enablingStartsTheLayerAndRampsGainsUpGradually() {
        createRuntime()
        val settings = SpeedCompensationSettings().withMasterEnabled(true)
        runtime.updateSettings(settings)
        runtime.attachHost()
        runtime.setTestSpeed(100.0)

        assertTrue(runtime.status().running)
        assertTrue(published.last().enabled)

        val reserveOnEnable = published.last().reservedBoostDb
        assertTrue(reserveOnEnable > 0.0)
        assertEquals(0.0, runtime.status().state.gains.volumeDb, 1.0e-9)

        val ramp = mutableListOf<Double>()
        repeat(20) {
            tick()
            ramp += runtime.status().state.gains.volumeDb
        }

        assertTrue("gains should rise monotonically", ramp.zipWithNext().all { (a, b) -> b >= a })
        assertTrue("gains should stay below the curve maximum", ramp.all { it <= 6.0 })
        assertTrue("gains should actually be applied", ramp.last() > 1.0)
        assertTrue(runtime.status().state.gains.volumeDb > 0.0)
        // The reserve must not shrink while the layer is audible, or the preamp would move.
        assertTrue(published.filter { it.enabled }.all { it.reservedBoostDb >= reserveOnEnable - 1.0e-9 })
    }

    @Test
    fun disablingDecaysToNeutralBeforeTheRuntimeStops() {
        createRuntime()
        val settings = SpeedCompensationSettings().withMasterEnabled(true)
        runtime.updateSettings(settings)
        runtime.attachHost()
        runtime.setTestSpeed(80.0)
        tick(steps = 20)
        val boosted = runtime.status().state.gains.volumeDb
        assertTrue(boosted > 0.5)
        val reserveWhileBoosted = published.last().reservedBoostDb

        runtime.updateSettings(settings.copy(masterEnabled = false))
        tick()
        val afterDisable = runtime.status().state.gains.volumeDb
        assertTrue("disabling must not snap the level", afterDisable > 0.0)
        assertTrue(afterDisable < boosted)
        assertTrue("the runtime keeps ticking while it decays", runtime.status().running)
        assertEquals(
            "the headroom reserve is released only once the layer is neutral",
            reserveWhileBoosted,
            published.last().reservedBoostDb,
            1.0e-9,
        )

        var previous = afterDisable
        repeat(100) {
            tick()
            val current = runtime.status().state.gains.volumeDb
            assertTrue("decay must stay monotonic", current <= previous + 1.0e-9)
            previous = current
        }

        assertFalse(runtime.status().running)
        assertEquals(0.0, runtime.status().state.gains.volumeDb, 1.0e-9)
        assertFalse(published.last().enabled)
        assertEquals(0.0, published.last().reservedBoostDb, 1.0e-9)
        assertEquals(SpeedGpsState.STOPPED, runtime.status().state.gpsState)
    }

    @Test
    fun detachingTheLastHostStopsTheSourceOnceTheLayerIsNeutral() {
        createRuntime()
        runtime.updateSettings(SpeedCompensationSettings().withMasterEnabled(true))
        runtime.attachHost()
        runtime.setTestSpeed(60.0)
        tick(steps = 20)
        assertTrue(runtime.status().running)

        runtime.detachHost()
        tick(steps = 100)

        assertFalse(runtime.status().running)
        assertEquals(SpeedGpsState.STOPPED, runtime.status().state.gpsState)
        assertEquals(0.0, published.last().reservedBoostDb, 1.0e-9)
    }

    @Test
    fun dspMasterOffKeepsTheFeatureSilent() {
        createRuntime(dspEnabled = false)
        runtime.updateSettings(SpeedCompensationSettings().withMasterEnabled(true))
        runtime.attachHost()
        runtime.setTestSpeed(100.0)
        tick(steps = 10)

        assertFalse(runtime.status().running)
        assertEquals(0, source.startCount)
        assertEquals(0.0, runtime.status().state.gains.volumeDb, 1.0e-9)
        assertTrue(published.all { it.reservedBoostDb == 0.0 })
    }

    private class FakeSpeedSource : SpeedSource {
        var startCount = 0
        private var listener: SpeedFixListener? = null

        override fun start(listener: SpeedFixListener): Boolean {
            startCount += 1
            this.listener = listener
            return true
        }

        override fun stop() {
            listener = null
        }

        override fun status(): SpeedSourceStatus = SpeedSourceStatus(
            state = if (listener != null) SpeedGpsState.RECEIVING else SpeedGpsState.STOPPED,
        )
    }

    private companion object {
        const val TICK_NANOS = 200_000_000L
    }
}
