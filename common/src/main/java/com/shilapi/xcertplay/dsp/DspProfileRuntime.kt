package com.shilapi.xcertplay.dsp

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.shilapi.xcertplay.media.dsp.DspImpulseResponse
import com.shilapi.xcertplay.media.dsp.DspConfigProvider
import com.shilapi.xcertplay.media.dsp.DspRuntimeConfig
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class DspProfileRuntime private constructor(context: Context) {
    private val applicationContext = context.applicationContext
    private val preferences = applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val repository = DspProfileRepository(applicationContext.filesDir)
    private val impulseRepository = DspImpulseResponseRepository(applicationContext.filesDir)
    private val impulseCache = ConcurrentHashMap<String, DspImpulseResponse>()
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "dsp-profile-io").apply { isDaemon = true }
    }
    private val activeProfile: AtomicReference<DspAudioProfile>
    private val dspEnabled: AtomicBoolean
    private val runtimeConfig = DspRuntimeConfigStore()
    val configProvider: DspConfigProvider = runtimeConfig

    init {
        val selectedId = preferences.getString(KEY_SELECTED_PROFILE_ID, DEFAULT_PROFILE_ID) ?: DEFAULT_PROFILE_ID
        val loadedProfile = loadProfile(selectedId) ?: DspProfilePresets.find(DEFAULT_PROFILE_ID)!!
        val initialEnabled = preferences.getBoolean(KEY_DSP_ENABLED, false)
        activeProfile = AtomicReference(loadedProfile)
        dspEnabled = AtomicBoolean(initialEnabled)
        runtimeConfig.update(loadedProfile.toRuntimeConfig(initialEnabled, impulseResponseFor(loadedProfile)))
    }

    fun selectedProfile(): DspAudioProfile = activeProfile.get()

    fun isDspEnabled(): Boolean = dspEnabled.get()

    fun availableImpulseResponseIds(): List<String> = impulseRepository.ids()

    fun availableProfiles(): List<DspAudioProfile> {
        val defaults = DspProfilePresets.all().associateBy(DspAudioProfile::id)
        return defaults.map { (id, defaultProfile) ->
            if (id !in CUSTOM_PROFILE_IDS) {
                defaultProfile
            } else {
                when (val result = repository.load(id)) {
                    is DspProfileReadResult.Loaded -> result.profile
                    else -> defaultProfile
                }
            }
        }
    }

    fun profile(profileId: String): DspAudioProfile? {
        if (profileId in CUSTOM_PROFILE_IDS) {
            return when (val result = repository.load(profileId)) {
                is DspProfileReadResult.Loaded -> result.profile
                else -> DspProfilePresets.find(profileId)
            }
        }
        return DspProfilePresets.find(profileId)
    }

    fun apply(profile: DspAudioProfile, enabled: Boolean) {
        applyWithImpulse(profile, enabled, cachedImpulseResponse(profile))
    }

    fun setMasterEnabled(enabled: Boolean) {
        val profile = activeProfile.get()
        applyWithImpulse(profile, enabled, cachedImpulseResponse(profile))
    }

    fun setMasterEnabledAsync(enabled: Boolean, onComplete: () -> Unit) {
        ioExecutor.execute {
            val profile = activeProfile.get()
            val impulse = impulseResponseFor(profile)
            applyWithImpulse(profile, enabled, impulse)
            Handler(Looper.getMainLooper()).post(onComplete)
        }
    }

    internal fun encodeProfileDraftForSavedState(profile: DspAudioProfile): String =
        repository.encodeDraftForSavedState(profile)

    internal fun decodeProfileDraftFromSavedState(serialized: String): DspAudioProfile? =
        repository.decodeDraftFromSavedState(serialized)

    fun applyAsync(profile: DspAudioProfile, enabled: Boolean, onComplete: () -> Unit) {
        ioExecutor.execute {
            val impulse = impulseResponseFor(profile)
            applyWithImpulse(profile, enabled, impulse)
            Handler(Looper.getMainLooper()).post(onComplete)
        }
    }

    fun importImpulseResponseAsync(
        id: String,
        openInput: () -> InputStream?,
        onComplete: (DspImpulseImportResult) -> Unit,
    ) {
        ioExecutor.execute {
            val result = runCatching {
                val input = openInput() ?: return@runCatching DspImpulseImportResult.InvalidWav
                impulseRepository.import(id, input)
            }.getOrElse { DspImpulseImportResult.InvalidWav }
            if (result is DspImpulseImportResult.Imported) impulseCache[result.impulseResponse.id] = result.impulseResponse
            Handler(Looper.getMainLooper()).post { onComplete(result) }
        }
    }

    private fun applyWithImpulse(profile: DspAudioProfile, enabled: Boolean, impulse: DspImpulseResponse?) {
        activeProfile.set(profile)
        dspEnabled.set(enabled)
        runtimeConfig.update(profile.toRuntimeConfig(enabled, impulse))
        preferences.edit()
            .putBoolean(KEY_DSP_ENABLED, enabled)
            .putString(KEY_SELECTED_PROFILE_ID, profile.id)
            .apply()
    }

    fun saveCustomAsync(profile: DspAudioProfile, enabled: Boolean, onComplete: (DspProfileSaveResult) -> Unit) {
        if (profile.id !in CUSTOM_PROFILE_IDS) {
            onComplete(DspProfileSaveResult.INVALID_PROFILE_ID)
            return
        }
        ioExecutor.execute {
            val result = repository.save(profile)
            if (result == DspProfileSaveResult.SAVED) {
                applyWithImpulse(profile, enabled, impulseResponseFor(profile))
            }
            Handler(Looper.getMainLooper()).post { onComplete(result) }
        }
    }

    private fun cachedImpulseResponse(profile: DspAudioProfile): DspImpulseResponse? =
        if (profile.convolver.enabled) profile.convolver.impulseResponseId?.let(impulseCache::get) else null

    private fun impulseResponseFor(profile: DspAudioProfile): DspImpulseResponse? {
        if (!profile.convolver.enabled) return null
        val id = profile.convolver.impulseResponseId ?: return null
        return cachedImpulseResponse(profile) ?: impulseRepository.load(id)?.also { impulseCache[id] = it }
    }

    private fun loadProfile(profileId: String): DspAudioProfile? {
        DspProfilePresets.find(profileId)?.let { preset ->
            if (profileId !in CUSTOM_PROFILE_IDS) return preset
        }
        return when (val result = repository.load(profileId)) {
            is DspProfileReadResult.Loaded -> result.profile
            else -> DspProfilePresets.find(profileId)
        }
    }

    companion object {
        private const val PREFERENCES_NAME = "dsp_profile_selection"
        private const val KEY_DSP_ENABLED = "enabled"
        private const val KEY_SELECTED_PROFILE_ID = "selected_profile_id"
        private const val DEFAULT_PROFILE_ID = "flat"
        private val CUSTOM_PROFILE_IDS = setOf("custom1", "custom2")
        @Volatile private var instance: DspProfileRuntime? = null

        fun get(context: Context): DspProfileRuntime = instance ?: synchronized(this) {
            instance ?: DspProfileRuntime(context).also { instance = it }
        }

        internal fun resetForTesting() {
            synchronized(this) { instance = null }
        }
    }
}
