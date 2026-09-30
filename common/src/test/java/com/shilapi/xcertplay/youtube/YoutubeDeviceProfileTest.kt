package com.shilapi.xcertplay.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YoutubeDeviceProfileTest {
    @Test
    fun profileKeyIsDeterministicAndDoesNotContainTheRawControllerId() {
        val identity = requireNotNull(
            IphoneIdentityResolver.resolve("controller-abc-123", null, null),
        )

        val first = YoutubeDeviceProfileManager.getOrCreate(identity)
        val second = YoutubeDeviceProfileManager.getOrCreate(identity)

        assertEquals(first, second)
        assertEquals(32, first.profileKey.length)
        assertTrue(first.profileKey.matches(Regex("[0-9a-f]{32}")))
        assertFalse(first.profileKey.contains(identity.canonicalKey))
        assertFalse(first.youtubeContextId.contains(identity.canonicalKey))
        assertTrue(first.youtubeContextId.startsWith("diplay_youtube_"))
    }

    @Test
    fun differentControllerIdsUseDifferentContexts() {
        val first = YoutubeDeviceProfileManager.getOrCreate(
            requireNotNull(IphoneIdentityResolver.resolve("controller-a", null, null)),
        )
        val second = YoutubeDeviceProfileManager.getOrCreate(
            requireNotNull(IphoneIdentityResolver.resolve("controller-b", null, null)),
        )

        assertNotEquals(first.youtubeContextId, second.youtubeContextId)
    }

    @Test
    fun identityResolutionUsesControllerThenDeviceThenWifiMac() {
        assertEquals(
            IphoneIdentitySource.CONTROLLER_ID,
            IphoneIdentityResolver.resolve("controller", "device", "aa:bb:cc:dd:ee:ff")?.source,
        )
        assertEquals(
            IphoneIdentitySource.DEVICE_ID,
            IphoneIdentityResolver.resolve(null, "device", "aa:bb:cc:dd:ee:ff")?.source,
        )
        assertEquals(
            IphoneIdentitySource.WIFI_MAC,
            IphoneIdentityResolver.resolve(null, null, "aa:bb:cc:dd:ee:ff")?.source,
        )
        assertNull(IphoneIdentityResolver.resolve(null, null, null))
    }

    @Test
    fun profileKeepsOptionalDisplayMetadataInMemory() {
        val identity = IphoneIdentityResolver.resolve(
            controllerId = "controller",
            deviceId = null,
            wifiMac = null,
            displayName = "Test iPhone",
            model = "iPhone",
        )

        val profile = YoutubeDeviceProfileManager.getOrCreate(requireNotNull(identity))

        assertEquals("Test iPhone", profile.displayName)
        assertNotNull(profile.model)
    }
}
