package com.shilapi.xcertplay.speed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedCompensationCurveTest {
    @Test
    fun standardCurveInterpolatesTheDocumentedPointsLinearly() {
        val curve = SpeedCompensationCurve.STANDARD
        assertEquals(0.0, curve.volumeDbAt(0.0), 1e-9)
        assertEquals(0.7, curve.volumeDbAt(30.0), 1e-9)
        assertEquals(1.5, curve.volumeDbAt(60.0), 1e-9)
        assertEquals(2.6, curve.volumeDbAt(100.0), 1e-9)
        assertEquals(3.0, curve.volumeDbAt(120.0), 1e-9)

        assertEquals(1.1, curve.volumeDbAt(45.0), 1e-9)
        assertEquals(2.05, curve.volumeDbAt(80.0), 1e-9)
        assertEquals(0.65, curve.lowDbAt(45.0), 1e-9)
        assertEquals(0.1, curve.midDbAt(45.0), 1e-9)
        assertEquals(0.35, curve.highDbAt(45.0), 1e-9)
    }

    @Test
    fun speedsAboveTheLastPointClampInsteadOfExtrapolating() {
        val curve = SpeedCompensationCurve.STANDARD
        assertEquals(3.0, curve.volumeDbAt(120.0), 1e-9)
        assertEquals(3.0, curve.volumeDbAt(200.0), 1e-9)
        assertEquals(2.0, curve.lowDbAt(240.0), 1e-9)
        // Negative or NaN inputs fall back to the neutral origin instead of throwing.
        assertEquals(0.0, curve.volumeDbAt(-5.0), 1e-9)
        assertEquals(0.0, curve.volumeDbAt(Double.NaN), 1e-9)
    }

    @Test
    fun curveRejectsNonFiniteNegativeAndOutOfOrderPoints() {
        assertTrue(runCatching {
            SpeedCurvePoint(0.0, volumeDb = Double.NaN, lowDb = 0.0, midDb = 0.0, highDb = 0.0)
        }.isFailure)
        assertTrue(runCatching {
            SpeedCurvePoint(0.0, volumeDb = -0.1, lowDb = 0.0, midDb = 0.0, highDb = 0.0)
        }.isFailure)
        assertTrue(runCatching {
            SpeedCurvePoint(0.0, volumeDb = 0.0, lowDb = SpeedCurvePoint.MAX_LOW_DB + 0.1, midDb = 0.0, highDb = 0.0)
        }.isFailure)
        assertTrue(runCatching {
            SpeedCurvePoint(-1.0, volumeDb = 0.0, lowDb = 0.0, midDb = 0.0, highDb = 0.0)
        }.isFailure)
        assertTrue(
            runCatching {
                SpeedCompensationCurve(
                    listOf(
                        SpeedCurvePoint(0.0, 0.0, 0.0, 0.0, 0.0),
                        SpeedCurvePoint(60.0, 0.0, 0.0, 0.0, 0.0),
                        SpeedCurvePoint(30.0, 0.0, 0.0, 0.0, 0.0),
                    ),
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                SpeedCompensationCurve(
                    listOf(
                        SpeedCurvePoint(10.0, 0.0, 0.0, 0.0, 0.0),
                        SpeedCurvePoint(30.0, 0.0, 0.0, 0.0, 0.0),
                    ),
                )
            }.isFailure,
        )
        assertTrue(runCatching { SpeedCompensationCurve(emptyList()) }.isFailure)
    }

    @Test
    fun presetsScaleTheStandardCurveWithinTheSafetyCeilings() {
        assertEquals(0.0, SpeedCompensationSettings.ofPreset(SpeedCompensationPreset.OFF).curve.volumeDbAt(120.0), 1e-9)
        assertEquals(1.5, SpeedCompensationSettings.ofPreset(SpeedCompensationPreset.WEAK).curve.volumeDbAt(120.0), 1e-9)
        assertEquals(3.0, SpeedCompensationSettings.ofPreset(SpeedCompensationPreset.STANDARD).curve.volumeDbAt(120.0), 1e-9)
        val strong = SpeedCompensationSettings.ofPreset(SpeedCompensationPreset.STRONG)
        assertEquals(4.2, strong.curve.volumeDbAt(120.0), 1e-9)
        assertEquals(2.8, strong.curve.lowDbAt(120.0), 1e-9)
        assertTrue(strong.curve.maxVolumeDb <= SpeedCurvePoint.MAX_VOLUME_DB)
        assertTrue(strong.curve.maxLowDb <= SpeedCurvePoint.MAX_LOW_DB)
        assertTrue(strong.curve.maxMidDb <= SpeedCurvePoint.MAX_MID_DB)
        assertTrue(strong.curve.maxHighDb <= SpeedCurvePoint.MAX_HIGH_DB)
    }

    @Test
    fun settingsGateVolumeAndEqIndependentlyAndReserveOnlyTheActiveLayers() {
        val settings = SpeedCompensationSettings(
            masterEnabled = true,
            volumeEnabled = true,
            eqEnabled = true,
            preset = SpeedCompensationPreset.STANDARD,
        )
        assertTrue(settings.volumeActive)
        assertTrue(settings.eqActive)
        assertEquals(3.0 + 2.0 + 0.5 + 1.3, settings.reservedBoostDb, 1e-9)

        val volumeOnly = settings.copy(eqEnabled = false)
        assertTrue(volumeOnly.volumeActive)
        assertFalse(volumeOnly.eqActive)
        assertEquals(3.0, volumeOnly.reservedBoostDb, 1e-9)

        val disabled = settings.copy(masterEnabled = false)
        assertFalse(disabled.active)
        assertEquals(0.0, disabled.reservedBoostDb, 1e-9)
    }

    @Test
    fun customCurveEditsKeepPointsOrderedAndClampGains() {
        val curve = SpeedCompensationCurve.STANDARD
        val edited = curve.withPoint(1, speedKmh = 80.0, volumeDb = 9.0, lowDb = -1.0, midDb = 1.0, highDb = 99.0)
        assertEquals(80.0, edited.points[1].speedKmh, 1e-9)
        assertEquals(SpeedCurvePoint.MAX_VOLUME_DB, edited.points[1].volumeDb, 1e-9)
        assertEquals(0.0, edited.points[1].lowDb, 1e-9)
        assertEquals(SpeedCurvePoint.MAX_HIGH_DB, edited.points[1].highDb, 1e-9)
        // The following points are pushed forward to stay strictly ordered.
        assertEquals(81.0, edited.points[2].speedKmh, 1e-9)
        assertEquals(0.0, edited.points[0].speedKmh, 1e-9)

        val settings = SpeedCompensationSettings().withCustomCurve(edited)
        assertEquals(SpeedCompensationPreset.CUSTOM, settings.preset)
    }

    @Test
    fun switchingPresetsKeepsTheCustomCurveForLaterEditing() {
        val custom = SpeedCompensationCurve.STANDARD.scaledBy(0.8)
        val settings = SpeedCompensationSettings(masterEnabled = true)
            .withCustomCurve(custom)
            .withPreset(SpeedCompensationPreset.STRONG)
        assertEquals(SpeedCompensationPreset.STRONG, settings.preset)
        assertEquals(custom, settings.customCurve)
        assertEquals(4.2, settings.curve.volumeDbAt(120.0), 1e-9)
    }
}
