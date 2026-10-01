package com.shilapi.xcertplay.youtube

import android.content.Context
import java.lang.ref.WeakReference
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
    val identitySource: IphoneIdentitySource,
    val displayName: String?,
    val model: String?,
    val splitRatio: Float,
)

internal class YoutubeProfileSelection {
    private var connectionToken: WeakReference<Any>? = null
    private var selectedProfile: YoutubeDeviceProfile? = null
    private var selectedEvidence = emptySet<String>()

    fun select(
        connectionToken: Any,
        candidate: YoutubeDeviceProfile,
        evidence: Set<String>,
        forceSelection: Boolean = false,
    ): YoutubeDeviceProfile {
        if (this.connectionToken?.get() !== connectionToken) {
            this.connectionToken = WeakReference(connectionToken)
            selectedProfile = null
            selectedEvidence = emptySet()
        }
        val current = selectedProfile
        if (
            forceSelection || current == null || selectedEvidence.intersect(evidence).isEmpty() ||
            (current.identitySource == IphoneIdentitySource.CONTROLLER_ID &&
                candidate.identitySource == IphoneIdentitySource.CONTROLLER_ID &&
                current.profileKey != candidate.profileKey)
        ) {
            selectedProfile = candidate
            selectedEvidence = evidence
        } else {
            selectedEvidence = selectedEvidence + evidence
        }
        return requireNotNull(selectedProfile)
    }

    fun clear() {
        connectionToken = null
        selectedProfile = null
        selectedEvidence = emptySet()
    }
}

internal object YoutubeDeviceProfileManager {
    private val splitSelection = YoutubeProfileSelection()

    fun getOrCreate(identity: ConnectedIphoneIdentity, splitRatio: Float = SplitLayoutConfig.DEFAULT_CARPLAY_FRACTION): YoutubeDeviceProfile {
        val profileKey = profileKey(identity)

        return YoutubeDeviceProfile(
            profileKey = profileKey,
            youtubeContextId = "diplay_youtube_$profileKey",
            identitySource = identity.source,
            displayName = identity.displayName,
            model = identity.model,
            splitRatio = SplitLayoutConfig.normalizeCarPlayFraction(splitRatio),
        )
    }

    fun getOrCreateForSplit(
        context: Context,
        connectionToken: Any,
        identity: ConnectedIphoneIdentity,
        evidence: List<ConnectedIphoneIdentity>,
        splitRatio: Float = SplitLayoutConfig.DEFAULT_CARPLAY_FRACTION,
    ): YoutubeDeviceProfile {
        val candidate = getOrCreate(identity, splitRatio)
        val evidenceKeys = (evidence + identity).map(::profileKey).distinct()
        val aliasStore = YoutubeDeviceProfileAliasStore(context.applicationContext)
        val persistenceKeys = if (identity.source == IphoneIdentitySource.CONTROLLER_ID) {
            listOf(candidate.profileKey)
        } else {
            (evidence + identity)
                .filter { it.source != IphoneIdentitySource.CONTROLLER_ID }
                .map(::profileKey)
                .distinct()
        }
        val existingBinding = aliasStore.find(persistenceKeys)
        val selected = if (existingBinding == null) {
            splitSelection.select(connectionToken, candidate, evidenceKeys.toSet())
        } else {
            splitSelection.select(
                connectionToken,
                candidate.copy(
                    profileKey = existingBinding.profileKey,
                    youtubeContextId = "diplay_youtube_${existingBinding.profileKey}",
                    identitySource = existingBinding.identitySource,
                ),
                evidenceKeys.toSet(),
                forceSelection = true,
            )
        }
        aliasStore.bind(persistenceKeys, selected.profileKey, selected.identitySource)
        return selected
    }

    fun clearSplitSelection() = splitSelection.clear()

    private fun profileKey(identity: ConnectedIphoneIdentity): String {
        val identityMaterial = "diplay-youtube-profile-v1:${identity.source.name}:${identity.canonicalKey}"
        return MessageDigest.getInstance("SHA-256")
            .digest(identityMaterial.toByteArray(Charsets.UTF_8))
            .take(PROFILE_KEY_BYTES)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private const val PROFILE_KEY_BYTES = 16
}

internal data class YoutubeProfileAliasBinding(
    val profileKey: String,
    val identitySource: IphoneIdentitySource,
)

internal class YoutubeDeviceProfileAliasStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun find(aliasKeys: List<String>): YoutubeProfileAliasBinding? = aliasKeys.firstNotNullOfOrNull { aliasKey ->
        preferences.getString(PREFERENCE_PREFIX + aliasKey, null)?.let(::decode)
    }

    fun bind(aliasKeys: List<String>, profileKey: String, source: IphoneIdentitySource) {
        val value = "${source.name}|$profileKey"
        preferences.edit().apply {
            aliasKeys.forEach { aliasKey -> putString(PREFERENCE_PREFIX + aliasKey, value) }
        }.apply()
    }

    private fun decode(value: String): YoutubeProfileAliasBinding? {
        val parts = value.split('|', limit = 2)
        if (parts.size != 2 || !parts[1].matches(PROFILE_KEY_PATTERN)) return null
        val source = runCatching { IphoneIdentitySource.valueOf(parts[0]) }.getOrNull() ?: return null
        return YoutubeProfileAliasBinding(parts[1], source)
    }

    private companion object {
        const val PREFERENCES_NAME = "youtube_profile_aliases_v1"
        const val PREFERENCE_PREFIX = "identity_alias_"
        val PROFILE_KEY_PATTERN = Regex("[0-9a-f]{32}")
    }
}
