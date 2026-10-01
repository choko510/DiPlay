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

internal data class CachedYoutubeProfileResolution(
    val profile: YoutubeDeviceProfile,
    val evidenceKeys: Set<String>,
)

internal class YoutubeProfileResolutionCache {
    private data class IdentityKey(
        val source: IphoneIdentitySource,
        val canonicalKey: String,
    )

    private data class ResolutionKey(
        val identities: Set<IdentityKey>,
        val splitRatio: Float,
    )

    private var connectionToken: WeakReference<Any>? = null
    private var resolutionKey: ResolutionKey? = null
    private var resolution: CachedYoutubeProfileResolution? = null

    @Synchronized
    fun find(
        token: Any,
        identity: ConnectedIphoneIdentity,
        evidence: List<ConnectedIphoneIdentity>,
        splitRatio: Float,
    ): CachedYoutubeProfileResolution? {
        if (connectionToken?.get() !== token) return null
        if (resolutionKey != key(identity, evidence, splitRatio)) return null
        val cached = resolution ?: return null
        return cached.copy(
            profile = cached.profile.copy(
                displayName = identity.displayName,
                model = identity.model,
                splitRatio = SplitLayoutConfig.normalizeCarPlayFraction(splitRatio),
            ),
        )
    }

    @Synchronized
    fun store(
        token: Any,
        identity: ConnectedIphoneIdentity,
        evidence: List<ConnectedIphoneIdentity>,
        splitRatio: Float,
        profile: YoutubeDeviceProfile,
        evidenceKeys: Set<String>,
    ) {
        connectionToken = WeakReference(token)
        resolutionKey = key(identity, evidence, splitRatio)
        resolution = CachedYoutubeProfileResolution(profile, evidenceKeys)
    }

    @Synchronized
    fun clear(token: Any? = null) {
        if (token != null && connectionToken?.get() !== token) return
        connectionToken = null
        resolutionKey = null
        resolution = null
    }

    private fun key(
        identity: ConnectedIphoneIdentity,
        evidence: List<ConnectedIphoneIdentity>,
        splitRatio: Float,
    ): ResolutionKey = ResolutionKey(
        identities = (evidence + identity).map { IdentityKey(it.source, it.canonicalKey) }.toSet(),
        splitRatio = SplitLayoutConfig.normalizeCarPlayFraction(splitRatio),
    )
}

internal object YoutubeDeviceProfileManager {
    private val splitSelection = YoutubeProfileSelection()
    private val resolutionCache = YoutubeProfileResolutionCache()

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

    @Synchronized
    fun getOrCreateForSplit(
        context: Context,
        connectionToken: Any,
        identity: ConnectedIphoneIdentity,
        evidence: List<ConnectedIphoneIdentity>,
        splitRatio: Float = SplitLayoutConfig.DEFAULT_CARPLAY_FRACTION,
    ): YoutubeDeviceProfile {
        val cached = resolutionCache.find(connectionToken, identity, evidence, splitRatio)
        if (cached != null) {
            SplitPerformanceTracer.increment(SplitPerformanceCounter.PROFILE_RESOLUTIONS)
            return splitSelection.select(connectionToken, cached.profile, cached.evidenceKeys)
        }

        val candidate = getOrCreate(identity, splitRatio)
        val evidenceKeys = (evidence + identity).map(::profileKey).distinct().toSet()
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
            splitSelection.select(connectionToken, candidate, evidenceKeys)
        } else {
            splitSelection.select(
                connectionToken,
                candidate.copy(
                    profileKey = existingBinding.profileKey,
                    youtubeContextId = "diplay_youtube_${existingBinding.profileKey}",
                    identitySource = existingBinding.identitySource,
                ),
                evidenceKeys,
                forceSelection = true,
            )
        }
        aliasStore.bind(persistenceKeys, selected.profileKey, selected.identitySource)
        resolutionCache.store(connectionToken, identity, evidence, splitRatio, selected, evidenceKeys)
        SplitPerformanceTracer.increment(SplitPerformanceCounter.PROFILE_RESOLUTIONS)
        return selected
    }

    @Synchronized
    fun clearSplitSelection() = splitSelection.clear()

    @Synchronized
    fun clearSessionCache(connectionToken: Any? = null) = resolutionCache.clear(connectionToken)

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
        val changedKeys = YoutubeProfileAliasWritePolicy.changedKeys(aliasKeys, value) { key ->
            preferences.getString(PREFERENCE_PREFIX + key, null)
        }
        if (changedKeys.isEmpty()) return
        preferences.edit().apply {
            changedKeys.forEach { aliasKey -> putString(PREFERENCE_PREFIX + aliasKey, value) }
        }.apply()
        SplitPerformanceTracer.increment(SplitPerformanceCounter.SHARED_PREFERENCES_WRITES)
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

internal object YoutubeProfileAliasWritePolicy {
    fun changedKeys(
        aliasKeys: List<String>,
        value: String,
        currentValue: (String) -> String?,
    ): List<String> = aliasKeys.distinct().filter { currentValue(it) != value }
}
