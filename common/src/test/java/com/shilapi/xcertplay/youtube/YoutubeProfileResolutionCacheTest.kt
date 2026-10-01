package com.shilapi.xcertplay.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YoutubeProfileResolutionCacheTest {
    @Test
    fun unchangedIdentityEvidenceReusesTheResolvedProfile() {
        val cache = YoutubeProfileResolutionCache()
        val connection = Any()
        val identity = requireNotNull(IphoneIdentityResolver.resolve(null, "test-device-id", "test-wifi-mac"))
        val evidence = listOf(identity)
        val profile = YoutubeDeviceProfileManager.getOrCreate(identity)
        val evidenceKeys = setOf(profile.profileKey)
        cache.store(connection, identity, evidence, profile.splitRatio, profile, evidenceKeys)

        val cached = cache.find(connection, identity, evidence, profile.splitRatio)

        assertEquals(profile, cached?.profile)
        assertEquals(evidenceKeys, cached?.evidenceKeys)
    }

    @Test
    fun identityChangeOrDifferentSessionMissesTheCache() {
        val cache = YoutubeProfileResolutionCache()
        val connection = Any()
        val identity = requireNotNull(IphoneIdentityResolver.resolve(null, "test-device-id", null))
        val profile = YoutubeDeviceProfileManager.getOrCreate(identity)
        cache.store(connection, identity, listOf(identity), profile.splitRatio, profile, setOf(profile.profileKey))

        val verified = requireNotNull(IphoneIdentityResolver.resolve("test-controller-id", "test-device-id", null))
        assertNull(cache.find(connection, verified, listOf(verified), profile.splitRatio))
        assertNull(cache.find(Any(), identity, listOf(identity), profile.splitRatio))
    }

    @Test
    fun aliasPersistenceWritesOnlyChangedDistinctKeys() {
        val current = mapOf(
            "fallback" to "DEVICE_ID|0123456789abcdef0123456789abcdef",
            "verified" to "CONTROLLER_ID|fedcba9876543210fedcba9876543210",
        )

        val changed = YoutubeProfileAliasWritePolicy.changedKeys(
            aliasKeys = listOf("fallback", "verified", "new", "new"),
            value = "DEVICE_ID|0123456789abcdef0123456789abcdef",
            currentValue = current::get,
        )

        assertEquals(listOf("verified", "new"), changed)
        assertTrue(changed.none { it.contains("test-device-id") })
    }
}
