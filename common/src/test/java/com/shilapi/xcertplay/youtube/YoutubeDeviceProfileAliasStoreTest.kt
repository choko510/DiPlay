package com.shilapi.xcertplay.youtube

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class YoutubeDeviceProfileAliasStoreTest {
    @Test
    fun lateControllerIdAliasKeepsFallbackProfileAcrossSplitLifetimes() {
        val context = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences("youtube_profile_aliases_v1", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        YoutubeDeviceProfileManager.clearSplitSelection()
        try {
            val fallback = requireNotNull(IphoneIdentityResolver.resolve(null, "device-alias-test", "mac-alias-test"))
            val connection = Any()
            val first = YoutubeDeviceProfileManager.getOrCreateForSplit(
                context,
                connection,
                fallback,
                listOf(fallback),
            )

            val controller = requireNotNull(
                IphoneIdentityResolver.resolve("controller-alias-test", "device-alias-test", "mac-alias-test"),
            )
            val evidence = listOf(
                requireNotNull(IphoneIdentityResolver.resolve("controller-alias-test", null, null)),
                requireNotNull(IphoneIdentityResolver.resolve(null, "device-alias-test", null)),
                requireNotNull(IphoneIdentityResolver.resolve(null, null, "mac-alias-test")),
            )
            val promoted = YoutubeDeviceProfileManager.getOrCreateForSplit(
                context,
                connection,
                controller,
                evidence,
            )

            assertEquals(first.youtubeContextId, promoted.youtubeContextId)
            assertEquals(IphoneIdentitySource.DEVICE_ID, promoted.identitySource)

            YoutubeDeviceProfileManager.clearSplitSelection()
            val reopened = YoutubeDeviceProfileManager.getOrCreateForSplit(
                context,
                Any(),
                controller,
                evidence,
            )

            assertEquals(first.youtubeContextId, reopened.youtubeContextId)
            assertEquals(IphoneIdentitySource.DEVICE_ID, reopened.identitySource)

            YoutubeDeviceProfileManager.clearSplitSelection()
            val otherController = requireNotNull(
                IphoneIdentityResolver.resolve("controller-other", "device-alias-test", "mac-alias-test"),
            )
            val otherEvidence = listOf(
                requireNotNull(IphoneIdentityResolver.resolve("controller-other", null, null)),
                requireNotNull(IphoneIdentityResolver.resolve(null, "device-alias-test", null)),
                requireNotNull(IphoneIdentityResolver.resolve(null, null, "mac-alias-test")),
            )
            val isolated = YoutubeDeviceProfileManager.getOrCreateForSplit(
                context,
                Any(),
                otherController,
                otherEvidence,
            )

            assertNotEquals(first.youtubeContextId, isolated.youtubeContextId)
            assertFalse(preferences.all.toString().contains("device-alias-test"))
            assertFalse(preferences.all.toString().contains("controller-alias-test"))
            assertFalse(preferences.all.toString().contains("controller-other"))
            assertNotNull(preferences.all.keys.firstOrNull { it.startsWith("identity_alias_") })
        } finally {
            YoutubeDeviceProfileManager.clearSplitSelection()
            preferences.edit().clear().commit()
        }
    }
}
