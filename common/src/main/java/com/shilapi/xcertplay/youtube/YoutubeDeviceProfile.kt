package com.shilapi.xcertplay.youtube

import java.security.MessageDigest

internal enum class IphoneIdentitySource(val logName: String) {
    CONTROLLER_ID("controller_id"),
    DEVICE_ID("device_id"),
    WIFI_MAC("wifi_mac"),
}

internal data class ConnectedIphoneIdentity(
    val canonicalKey: String,
    val source: IphoneIdentitySource,
    val displayName: String?,
    val model: String?,
)

internal object IphoneIdentityResolver {
    fun resolve(
        controllerId: String?,
        deviceId: String?,
        wifiMac: String?,
        displayName: String? = null,
        model: String? = null,
    ): ConnectedIphoneIdentity? {
        val (source, key) = sequenceOf(
            IphoneIdentitySource.CONTROLLER_ID to controllerId,
            IphoneIdentitySource.DEVICE_ID to deviceId,
            IphoneIdentitySource.WIFI_MAC to wifiMac,
        ).firstNotNullOfOrNull { (candidateSource, candidateKey) ->
            candidateKey?.trim()?.takeIf(String::isNotEmpty)?.let { candidateSource to it }
        } ?: return null

        return ConnectedIphoneIdentity(
            canonicalKey = key,
            source = source,
            displayName = displayName?.trim()?.takeIf(String::isNotEmpty),
            model = model?.trim()?.takeIf(String::isNotEmpty),
        )
    }
}

internal data class YoutubeDeviceProfile(
    val profileKey: String,
    val youtubeContextId: String,
    val displayName: String?,
    val model: String?,
    val splitRatio: Float,
)

internal object YoutubeDeviceProfileManager {
    fun getOrCreate(identity: ConnectedIphoneIdentity, splitRatio: Float = SplitLayoutConfig.DEFAULT_CARPLAY_FRACTION): YoutubeDeviceProfile {
        val identityMaterial = "diplay-youtube-profile-v1:${identity.source.name}:${identity.canonicalKey}"
        val profileKey = MessageDigest.getInstance("SHA-256")
            .digest(identityMaterial.toByteArray(Charsets.UTF_8))
            .take(PROFILE_KEY_BYTES)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

        return YoutubeDeviceProfile(
            profileKey = profileKey,
            youtubeContextId = "diplay_youtube_$profileKey",
            displayName = identity.displayName,
            model = identity.model,
            splitRatio = SplitLayoutConfig.normalizeCarPlayFraction(splitRatio),
        )
    }

    private const val PROFILE_KEY_BYTES = 16
}
