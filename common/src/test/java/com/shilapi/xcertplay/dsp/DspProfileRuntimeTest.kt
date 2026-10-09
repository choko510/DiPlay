package com.shilapi.xcertplay.dsp

import android.content.Context
import android.app.Application
import java.io.ByteArrayInputStream
import com.shilapi.xcertplay.media.dsp.DspCabinCorrectionConfig
import com.shilapi.xcertplay.media.dsp.DspCabinCut
import com.shilapi.xcertplay.media.dsp.DspCabinCutSource
import com.shilapi.xcertplay.media.dsp.DspEqBand
import com.shilapi.xcertplay.media.dsp.DspEqType
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
class DspProfileRuntimeTest {
    @Test
    fun globalMasterPreferenceEnablesLegacyDisabledCustomProfileAfterRuntimeRecreation() {
        val context: Application = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val profile = DspAudioProfile(
            id = "custom1",
            name = "Legacy off profile",
            enabled = false,
            preampDb = 2.0,
        )
        assertEquals(DspProfileSaveResult.SAVED, DspProfileRepository(context.filesDir).save(profile))
        preferences.edit()
            .putBoolean(KEY_DSP_ENABLED, true)
            .putString(KEY_SELECTED_PROFILE_ID, profile.id)
            .commit()

        DspProfileRuntime.resetForTesting()
        val runtime = DspProfileRuntime.get(context)
        assertTrue(runtime.configProvider.snapshot().enabled)
        assertEquals(2.0, runtime.configProvider.snapshot().gainDb, 0.0)

        DspProfileRuntime.resetForTesting()
        val recreated = DspProfileRuntime.get(context)
        assertTrue(recreated.isDspEnabled())
        assertTrue(recreated.configProvider.snapshot().enabled)
        assertEquals(2.0, recreated.configProvider.snapshot().gainDb, 0.0)
        DspProfileRuntime.resetForTesting()
    }

    @Test
    fun masterToggleChangesOnlyActiveProfileAndApplyCommitsTheDraft() {
        val context: Application = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        DspProfileRuntime.resetForTesting()
        val runtime = DspProfileRuntime.get(context)
        val active = DspAudioProfile(id = "custom2", name = "Active", enabled = false, preampDb = 0.0)
        val draft = active.copy(preampDb = 6.0)
        runtime.apply(active, enabled = false)

        runtime.setMasterEnabled(false)
        runtime.setMasterEnabled(true)

        assertTrue(runtime.configProvider.snapshot().enabled)
        assertEquals(0.0, runtime.configProvider.snapshot().gainDb, 0.0)
        assertEquals(active, runtime.selectedProfile())

        runtime.apply(draft, enabled = runtime.isDspEnabled())

        assertEquals(6.0, runtime.configProvider.snapshot().gainDb, 0.0)
        assertFalse(runtime.selectedProfile().enabled)
        assertTrue(runtime.configProvider.snapshot().enabled)
        DspProfileRuntime.resetForTesting()
    }

    @Test
    fun missingCalibrationDisablesOnlyCabinCorrectionAndKeepsTheProfileDraft() {
        val context: Application = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val profile = DspAudioProfile(
            id = "custom1",
            name = "Cabin reference missing",
            preampDb = 2.0,
            eqBands = listOf(DspEqBand(DspEqType.PEAK, 1_000.0, gainDb = 2.0)),
            cabinCorrection = DspCabinCorrectionConfig(
                enabled = true,
                calibrationId = "cal_missing_for_runtime_test",
                cuts = listOf(
                    DspCabinCut(
                        frequencyHz = 82.0,
                        q = 4.0,
                        reductionDb = -4.0,
                        enabled = true,
                        source = DspCabinCutSource.MEASUREMENT,
                        prominenceDb = 6.0,
                        confidence = 0.9,
                    ),
                ),
            ),
        )
        assertEquals(DspProfileSaveResult.SAVED, DspProfileRepository(context.filesDir).save(profile))
        preferences.edit().putBoolean(KEY_DSP_ENABLED, true).putString(KEY_SELECTED_PROFILE_ID, profile.id).commit()

        DspProfileRuntime.resetForTesting()
        val runtime = DspProfileRuntime.get(context)

        assertTrue(runtime.selectedProfile().cabinCorrection.enabled)
        assertFalse(runtime.configProvider.snapshot().cabinCorrection.enabled)
        assertEquals(2.0, runtime.configProvider.snapshot().preampDb, 0.0)
        assertEquals(profile.eqBands, runtime.configProvider.snapshot().peqBands)

        val imported = DspCabinCalibrationRepository(context).import(
            "cal_missing_for_runtime_test",
            ::cabinMeasurementStream,
        )
        assertTrue(imported is DspCabinCalibrationImportResult.Imported)
        runtime.apply(profile, enabled = true)
        assertTrue(runtime.configProvider.snapshot().cabinCorrection.enabled)
        DspProfileRuntime.resetForTesting()
    }

    private fun cabinMeasurementStream(): ByteArrayInputStream = ByteArrayInputStream(
        """
            # format=diplay-cabin-calibration-v1
            # formatVersion=1
            # sampleRate=48000
            # position=front seat
            # measuredAt=2026-10-09
            # microphoneCorrectionApplied=true
            frequencyHz,magnitudeDb
            40,-1.0
            60,0.0
            82,5.0
            150,0.0
            250,-2.0
        """.trimIndent().toByteArray(),
    )

    private companion object {
        const val PREFERENCES_NAME = "dsp_profile_selection"
        const val KEY_DSP_ENABLED = "enabled"
        const val KEY_SELECTED_PROFILE_ID = "selected_profile_id"
    }
}
