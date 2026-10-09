package com.shilapi.xcertplay.dsp

import android.content.Context
import android.app.Application
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

    private companion object {
        const val PREFERENCES_NAME = "dsp_profile_selection"
        const val KEY_DSP_ENABLED = "enabled"
        const val KEY_SELECTED_PROFILE_ID = "selected_profile_id"
    }
}
