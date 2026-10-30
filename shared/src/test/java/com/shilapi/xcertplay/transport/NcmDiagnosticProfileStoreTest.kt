package com.shilapi.xcertplay.transport

import android.content.Context
import android.content.pm.ApplicationInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class NcmDiagnosticProfileStoreTest {
    @Test
    fun debuggableProfilesCanBeSavedAndRolledBackToAuto() {
        val application = RuntimeEnvironment.getApplication()
        val originalFlags = application.applicationInfo.flags
        val preferences = application.getSharedPreferences("ncm_diagnostics", Context.MODE_PRIVATE)
        val hadSavedProfile = preferences.contains("profile")
        val savedProfile = preferences.getString("profile", null)

        try {
            application.applicationInfo.flags = originalFlags or ApplicationInfo.FLAG_DEBUGGABLE
            preferences.edit().remove("profile").commit()
            assertEquals(NcmDiagnosticProfile.AUTO, NcmDiagnosticProfileStore.load(application))

            for (profile in NcmDiagnosticProfile.entries) {
                NcmDiagnosticProfileStore.save(application, profile)
                assertEquals(profile, NcmDiagnosticProfileStore.load(application))
                NcmDiagnosticProfileStore.save(application, NcmDiagnosticProfile.AUTO)
                assertEquals(NcmDiagnosticProfile.AUTO, NcmDiagnosticProfileStore.load(application))
            }
        } finally {
            application.applicationInfo.flags = originalFlags
            val editor = preferences.edit()
            if (hadSavedProfile) editor.putString("profile", savedProfile)
            else editor.remove("profile")
            editor.commit()
        }
    }

    @Test
    fun nonDebuggableBuildIgnoresAndCannotChangeSavedComparator() {
        val application = RuntimeEnvironment.getApplication()
        val originalFlags = application.applicationInfo.flags
        val preferences = application.getSharedPreferences("ncm_diagnostics", Context.MODE_PRIVATE)
        val hadSavedProfile = preferences.contains("profile")
        val savedProfile = preferences.getString("profile", null)
        val comparator = NcmDiagnosticProfile.NO_STATUS_POLLING.name

        try {
            application.applicationInfo.flags = originalFlags and ApplicationInfo.FLAG_DEBUGGABLE.inv()
            preferences.edit().putString("profile", comparator).commit()
            assertFalse(NcmDiagnosticProfileStore.isDebuggable(application))
            assertEquals(NcmDiagnosticProfile.AUTO, NcmDiagnosticProfileStore.load(application))

            NcmDiagnosticProfileStore.save(application, NcmDiagnosticProfile.OUT_TIMEOUT_500)

            assertEquals(comparator, preferences.getString("profile", null))
            assertEquals(NcmDiagnosticProfile.AUTO, NcmDiagnosticProfileStore.load(application))
        } finally {
            application.applicationInfo.flags = originalFlags
            val editor = preferences.edit()
            if (hadSavedProfile) editor.putString("profile", savedProfile)
            else editor.remove("profile")
            editor.commit()
        }
    }
}
