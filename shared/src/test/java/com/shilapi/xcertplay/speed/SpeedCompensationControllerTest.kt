package com.shilapi.xcertplay.speed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedCompensationControllerTest {
    private val second = 1_000_000_000L
    private val tick = 200_000_000L

    private class Harness(initialNow: Long, private val tickNanos: Long = 200_000_000L) {
        val controller = SpeedCompensationController(onStateChanged = {
            published += it
        })
        val published = mutableListOf<SpeedCompensationState>()
        var now = initialNow

        fun drive(speedKmh: Double, ticks: Int, fixEveryTicks: Int = 5) {
            repeat(ticks) { index ->
                // A realistic ~1 Hz GPS cadence: the controller ticks faster than the fix rate.
                if (index % fixEveryTicks == 0) {
                    controller.onFix(SpeedFix(speedKmh, now, SpeedFixSource.GPS))
                }
                now += tickNanos
                controller.tick(now)
            }
        }

        fun coast(ticks: Int) {
            repeat(ticks) {
                now += tickNanos
                controller.tick(now)
            }
        }
    }

    private fun newHarness(): Harness {
        val harness = Harness(second)
        harness.controller.start()
        return harness
    }

    private fun Harness.enableStandard() = controller.updateSettings(
        SpeedCompensationSettings(masterEnabled = true, preset = SpeedCompensationPreset.STANDARD),
    )

    @Test
    fun disabledFeatureNeverLeavesNeutral() {
        val harness = newHarness()
        harness.drive(120.0, ticks = 20)
        val state = harness.controller.state()
        assertTrue(state.gains.isNeutral)
        assertEquals(0.0, state.gains.worstCaseBoostDb, 0.0)
        harness.controller.stop()
    }

    @Test
    fun standardPresetConvergesToTheCurveWithoutOvershoot() {
        val harness = newHarness()
        harness.enableStandard()
        harness.drive(60.0, ticks = 20)
        val gains = harness.controller.state().gains
        assertEquals(1.5, gains.volumeDb, 0.02)
        assertEquals(0.9, gains.lowDb, 0.02)
        assertEquals(0.2, gains.midDb, 0.02)
        assertEquals(0.5, gains.highDb, 0.02)
        assertTrue(harness.published.all { it.gains.volumeDb <= 1.5 + 1e-9 })
        assertTrue(harness.published.all { it.gains.lowDb <= 0.9 + 1e-9 })

        // A settled speed must not keep publishing DSP config updates.
        val countAtRest = harness.published.size
        harness.drive(60.0, ticks = 10)
        assertEquals(countAtRest, harness.published.size)
        harness.controller.stop()
    }

    @Test
    fun theLastPublishedGainsMatchTheSettledGains() {
        val harness = newHarness()
        harness.enableStandard()
        harness.drive(60.0, ticks = 60)

        // The emit threshold is compared against the last value the DSP received, so the tail of
        // the ramp must still be published once it accumulates past the threshold.
        val settled = harness.controller.state().gains
        assertEquals(settled.volumeDb, harness.published.last().gains.volumeDb, 1e-9)
        assertEquals(settled.lowDb, harness.published.last().gains.lowDb, 1e-9)
        harness.controller.stop()
    }

    @Test
    fun staleGpsDecaysToNeutralAndRecoversSmoothly() {
        val harness = newHarness()
        harness.enableStandard()
        harness.drive(100.0, ticks = 20)
        assertTrue(harness.controller.state().gains.volumeDb > 2.0)

        // No fixes for longer than the staleness timeout: the layer fades away.
        harness.coast(ticks = 40)
        val faded = harness.controller.state()
        assertTrue(faded.stale)
        assertTrue(faded.gains.isNeutral)

        // Recovery from a real fix ramps back instead of jumping.
        harness.controller.onFix(SpeedFix(100.0, harness.now, SpeedFixSource.GPS))
        harness.now += tick
        harness.controller.tick(harness.now)
        val firstStep = harness.controller.state().gains.volumeDb
        assertTrue(firstStep > 0.0)
        assertTrue(firstStep < 2.6)
        harness.controller.stop()
    }

    @Test
    fun volumeAndEqLayersCanBeDisabledIndependently() {
        val harness = newHarness()
        harness.controller.updateSettings(
            SpeedCompensationSettings(
                masterEnabled = true,
                volumeEnabled = false,
                eqEnabled = true,
                preset = SpeedCompensationPreset.STANDARD,
            ),
        )
        harness.drive(120.0, ticks = 20)
        val eqOnly = harness.controller.state().gains
        assertEquals(0.0, eqOnly.volumeDb, 0.0)
        assertTrue(eqOnly.lowDb > 1.5)

        harness.controller.updateSettings(
            SpeedCompensationSettings(
                masterEnabled = true,
                volumeEnabled = true,
                eqEnabled = false,
                preset = SpeedCompensationPreset.STANDARD,
            ),
        )
        harness.drive(120.0, ticks = 20)
        val volumeOnly = harness.controller.state().gains
        assertEquals(3.0, volumeOnly.volumeDb, 0.02)
        assertEquals(0.0, volumeOnly.lowDb, 0.0)
        assertEquals(0.0, volumeOnly.midDb, 0.0)
        assertEquals(0.0, volumeOnly.highDb, 0.0)
        harness.controller.stop()
    }

    @Test
    fun switchingTheFeatureOffRampsDownInsteadOfStoppingAbruptly() {
        val harness = newHarness()
        val settings = SpeedCompensationSettings(masterEnabled = true, preset = SpeedCompensationPreset.STANDARD)
        harness.controller.updateSettings(settings)
        harness.drive(120.0, ticks = 20)
        assertTrue(harness.controller.state().gains.volumeDb > 2.5)

        harness.controller.updateSettings(settings.copy(masterEnabled = false))
        assertTrue(
            "turning the feature off must not drop the gain instantly",
            harness.controller.state().gains.volumeDb > 2.5,
        )
        harness.drive(120.0, ticks = 40)
        assertTrue(harness.controller.state().gains.isNeutral)
        assertTrue(harness.published.none { !it.gains.volumeDb.isFinite() })
        harness.controller.stop()
    }

    @Test
    fun manualTestSpeedIsDistinctFromGpsAndNeverPersisted() {
        val harness = newHarness()
        harness.enableStandard()
        harness.controller.setTestSpeed(100.0)
        harness.coast(ticks = 20)
        val state = harness.controller.state()
        assertTrue(state.testMode)
        assertFalse(state.stale)
        assertEquals(2.6, state.gains.volumeDb, 0.02)

        harness.controller.setTestSpeed(null)
        harness.coast(ticks = 40)
        assertFalse(harness.controller.state().testMode)
        assertTrue(harness.controller.state().stale)
        assertTrue(harness.controller.state().gains.isNeutral)
        harness.controller.stop()
    }

    @Test
    fun stoppingTheControllerReturnsToNeutral() {
        val harness = newHarness()
        harness.controller.updateSettings(
            SpeedCompensationSettings(masterEnabled = true, preset = SpeedCompensationPreset.STRONG),
        )
        harness.drive(120.0, ticks = 20)
        assertTrue(harness.controller.state().active)
        harness.controller.stop()
        assertFalse(harness.controller.isStarted())
        assertTrue(harness.controller.state().gains.isNeutral)
        assertEquals(null, harness.controller.testSpeed())
    }

    @Test
    fun gpsStatusIsReportedSeparatelyFromTheSpeedValue() {
        val controller = SpeedCompensationController()
        controller.start()
        controller.onGpsStatus(SpeedSourceStatus(SpeedGpsState.PERMISSION_DENIED, rejectReason = "permission_denied"))
        assertEquals(SpeedGpsState.PERMISSION_DENIED, controller.state().gpsState)
        assertEquals(null, controller.state().speedKmh)
        controller.stop()
    }
}
