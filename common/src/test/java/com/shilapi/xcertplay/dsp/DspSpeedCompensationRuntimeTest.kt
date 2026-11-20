package com.shilapi.xcertplay.dsp

import android.app.Application
import com.shilapi.xcertplay.media.dsp.DspSpeedCompensationConfig
import com.shilapi.xcertplay.speed.SpeedCompensationSettings
import com.shilapi.xcertplay.speed.SpeedFix
import com.shilapi.xcertplay.speed.SpeedFixListener
import com.shilapi.xcertplay.speed.SpeedFixSource
import com.shilapi.xcertplay.speed.SpeedGpsState
import com.shilapi.xcertplay.speed.SpeedSource
import com.shilapi.xcertplay.speed.SpeedSourceStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    private fun createRuntime(dspEnabled: Boolean = true, autoHeadroomEnabled: Boolean = true) {
        published.clear()
        runtime = DspSpeedCompensationRuntime(
            context = context,
            publishConfig = { published += it },
            dspEnabledProvider = { dspEnabled },
            autoHeadroomEnabledProvider = { autoHeadroomEnabled },
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
        // No fix yet: the stage must stay disabled and claim no headroom.
        assertFalse(published.last().enabled)
        assertEquals(0.0, published.last().reservedBoostDb, 1.0e-9)
    }

    @Test
    fun waitingForAFixNeverReservesHeadroom() {
        createRuntime()
        runtime.updateSettings(SpeedCompensationSettings().withMasterEnabled(true))
        runtime.attachHost()
        tick(steps = 50)

        assertTrue("the runtime keeps waiting for a fix", runtime.status().running)
        assertNull(runtime.status().state.speedKmh)
        assertTrue("a missing fix must not attenuate the music", published.all { it.reservedBoostDb == 0.0 })
        assertTrue(published.all { !it.enabled })
        assertEquals(0.0, runtime.status().state.gains.volumeDb, 1.0e-9)
    }

    @Test
    fun aFixReservesHeadroomBeforeTheBoostIsApplied() {
        createRuntime()
        val settings = SpeedCompensationSettings().withMasterEnabled(true)
        runtime.updateSettings(settings)
        runtime.attachHost()
        source.emit(90.0, nowNanos)
        tick()

        assertTrue(published.last().enabled)
        assertEquals(settings.reservedBoostDb, published.last().reservedBoostDb, 1.0e-9)
        // The very first boosted publish already carries the reserve.
        assertTrue(published.last().volumeGainDb <= published.last().reservedBoostDb)
    }

    @Test
    fun losingTheFixDecaysTheBoostBeforeReleasingHeadroom() {
        createRuntime()
        val settings = SpeedCompensationSettings().withMasterEnabled(true)
        runtime.updateSettings(settings)
        runtime.attachHost()
        source.emit(90.0, nowNanos)
        repeat(20) {
            tick()
            source.emit(90.0, nowNanos)
        }
        assertTrue(runtime.status().state.gains.volumeDb > 0.5)

        // Stop delivering fixes and let the estimate go stale.
        repeat(60) { tick() }

        assertEquals(0.0, runtime.status().state.gains.volumeDb, 1.0e-9)
        assertFalse(published.last().enabled)
        assertEquals(0.0, published.last().reservedBoostDb, 1.0e-9)
    }

    @Test
    fun theReserveIsAppliedBeforeTheBoostStarts() {
        createRuntime()
        val settings = SpeedCompensationSettings().withMasterEnabled(true)
        runtime.updateSettings(settings)
        runtime.attachHost()
        source.emit(90.0, nowNanos)
        tick()

        // The first publish after the fix carries the headroom but no boost yet.
        assertEquals(settings.reservedBoostDb, published.last().reservedBoostDb, 1.0e-9)
        assertEquals(0.0, published.last().volumeGainDb, 1.0e-9)
        assertTrue(published.last().enabled)

        tick()
        assertEquals("the boost waits for the reserve to settle", 0.0, runtime.status().state.gains.volumeDb, 1.0e-9)

        repeat(20) {
            tick()
            source.emit(90.0, nowNanos)
        }
        assertTrue(runtime.status().state.gains.volumeDb > 0.5)
    }

    @Test
    fun aDisabledProviderStopsTheBoostWithoutWaitingForStaleness() {
        createRuntime()
        runtime.updateSettings(SpeedCompensationSettings().withMasterEnabled(true))
        runtime.attachHost()
        source.emit(90.0, nowNanos)
        repeat(25) {
            tick()
            source.emit(90.0, nowNanos)
        }
        val boosted = runtime.status().state.gains.volumeDb
        assertTrue(boosted > 0.5)

        source.overrideState = SpeedGpsState.PROVIDER_DISABLED
        tick()
        assertTrue(runtime.status().state.gains.volumeDb < boosted)
        // The reserve must survive until the gains are neutral.
        assertTrue(published.last().reservedBoostDb > 0.0)

        tick(steps = 40)
        assertEquals(0.0, runtime.status().state.gains.volumeDb, 1.0e-9)
        assertEquals(0.0, published.last().reservedBoostDb, 1.0e-9)
        assertFalse(published.last().enabled)
    }

    @Test
    fun noGpsSubscriptionIsOpenedWhenTheBoostIsDisallowed() {
        createRuntime(autoHeadroomEnabled = false)
        runtime.updateSettings(SpeedCompensationSettings().withMasterEnabled(true))
        runtime.attachHost()
        tick(steps = 10)

        assertEquals(0, source.startCount)
        assertTrue(source.isStopped)
    }

    @Test
    fun autoHeadroomOffDisablesTheBoostInsteadOfClipping() {
        createRuntime(autoHeadroomEnabled = false)
        runtime.updateSettings(SpeedCompensationSettings().withMasterEnabled(true))
        runtime.attachHost()
        runtime.setTestSpeed(120.0)
        tick(steps = 30)

        assertEquals(0.0, runtime.status().state.gains.volumeDb, 1.0e-9)
        assertTrue(published.all { it.volumeGainDb == 0.0 })
        assertTrue(published.all { it.reservedBoostDb == 0.0 })
        assertTrue(published.all { !it.enabled })
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
        var overrideState: SpeedGpsState? = null
        private var listener: SpeedFixListener? = null
        val isStopped: Boolean get() = listener == null && overrideState == null

        fun emit(speedKmh: Double, elapsedRealtimeNanos: Long) {
            listener?.onSpeedFix(SpeedFix(speedKmh, elapsedRealtimeNanos, SpeedFixSource.GPS))
        }

        override fun start(listener: SpeedFixListener): Boolean {
            startCount += 1
            this.listener = listener
            return true
        }

        override fun stop() {
            listener = null
        }

        override fun status(): SpeedSourceStatus = SpeedSourceStatus(
            state = overrideState
                ?: if (listener != null) SpeedGpsState.RECEIVING else SpeedGpsState.STOPPED,
        )
    }

    private companion object {
        const val TICK_NANOS = 200_000_000L
    }
}
