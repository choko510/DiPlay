package com.shilapi.xcertplay.dsp

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.shilapi.xcertplay.media.dsp.DspImpulseResponse
import com.shilapi.xcertplay.media.dsp.DspCabinMeasurementCsv
import com.shilapi.xcertplay.media.dsp.DspCabinMeasurementParseResult
import com.shilapi.xcertplay.media.dsp.DspConfigProvider
import com.shilapi.xcertplay.media.dsp.DspRuntimeConfig
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal class DspProfileRuntime private constructor(
    context: Context,
    filesDirectory: File = context.applicationContext.filesDir,
    preferencesName: String = PREFERENCES_NAME,
) {
    private val applicationContext = context.applicationContext
    private val preferences = applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val repository = DspProfileRepository(filesDirectory)
    private val workspaceRepository = DspStudioWorkspaceRepository(filesDirectory)
    private val modulePresetRepository = DspModulePresetRepository(filesDirectory)
    private val impulseRepository = DspImpulseResponseRepository(filesDirectory)
    private val impulseCache = ConcurrentHashMap<String, DspImpulseResponse>()
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "dsp-profile-io").apply { isDaemon = true }
    }
    private val activeProfile: AtomicReference<DspAudioProfile>
    private val dspEnabled: AtomicBoolean
    private val runtimeConfig = DspRuntimeConfigStore()
    private val volumeSource = AtomicReference<DspVolumeSource>(ManualVolumeSource())
    private val cachedVolumeReading = AtomicReference(DspVolumeReading())
    private val studioApplyGeneration = AtomicLong()
    private val activeStudioWorkspace: AtomicReference<DspStudioWorkspace>
    private val committedStudioWorkspace: AtomicReference<DspStudioWorkspace>
    internal val editorSession: DspEditorSession by lazy {
        DspEditorSession(
            initialDraft = selectedProfile(),
            initialAppliedProfile = selectedProfile(),
            initialMasterEnabled = isDspEnabled(),
        )
    }
    val configProvider: DspConfigProvider = runtimeConfig

    init {
        val selectedId = preferences.getString(KEY_SELECTED_PROFILE_ID, DEFAULT_PROFILE_ID) ?: DEFAULT_PROFILE_ID
        val loadedProfile = loadProfile(selectedId) ?: DspProfilePresets.find(DEFAULT_PROFILE_ID)!!
        val loadedWorkspace = (workspaceRepository.load() as? DspWorkspaceReadResult.Loaded)?.workspace
            ?.takeIf { it.profileSourceId == selectedId && it.baseProfileSnapshot.id == selectedId }
        val initialWorkspace = loadedWorkspace ?: DspStudioWorkspace(loadedProfile.id, loadedProfile)
        val initialEnabled = preferences.getBoolean(KEY_DSP_ENABLED, false)
        activeStudioWorkspace = AtomicReference(initialWorkspace)
        committedStudioWorkspace = AtomicReference(initialWorkspace.deepCopy())
        activeProfile = AtomicReference(initialWorkspace.baseProfileSnapshot)
        dspEnabled = AtomicBoolean(initialEnabled)
        cachedVolumeReading.set(readVolumeSource())
        runtimeConfig.update(resolveStudioWorkspace(initialWorkspace, initialEnabled, impulseResponseFor(initialWorkspace.baseProfileSnapshot)))
    }

    fun selectedProfile(): DspAudioProfile = activeProfile.get()

    fun isDspEnabled(): Boolean = dspEnabled.get()

    fun createStudioSessionCoordinator(): DspStudioSessionCoordinator =
        DspStudioSessionCoordinator(committedStudioWorkspace.get().deepCopy(), workspaceRepository)

    fun applyStudioModulePreset(workspace: DspStudioWorkspace, preset: DspModulePreset): DspStudioWorkspace? =
        modulePresetRepository.applyTo(workspace, preset)

    fun loadModulePresetsAsync(moduleKey: String, onComplete: (List<DspModulePreset>) -> Unit) {
        ioExecutor.execute {
            val presets = DspFactoryPresets.modulePresets().filter { it.moduleKey == moduleKey } +
                modulePresetRepository.userPresets(moduleKey)
            Handler(Looper.getMainLooper()).post { onComplete(presets) }
        }
    }

    fun saveModulePresetAsync(
        workspace: DspStudioWorkspace,
        moduleKey: String,
        id: String,
        name: String,
        onComplete: (DspPresetStoreResult) -> Unit,
    ) {
        val snapshot = workspace.deepCopy()
        ioExecutor.execute {
            val result = modulePresetRepository.saveCurrent(snapshot, moduleKey, id, name)
            Handler(Looper.getMainLooper()).post { onComplete(result) }
        }
    }

    fun studioVolumeReading(): DspVolumeReading {
        val reading = readVolumeSource()
        val previous = cachedVolumeReading.getAndSet(reading)
        if (previous != reading) {
            val workspace = activeStudioWorkspace.get()
            val enabled = dspEnabled.get()
            ioExecutor.execute {
                if (activeStudioWorkspace.get() == workspace) {
                    runCatching {
                        applyStudioWorkspace(workspace, enabled, impulseResponseFor(workspace.baseProfileSnapshot))
                    }
                }
            }
        }
        return reading
    }

    internal fun setStudioVolumeSource(source: DspVolumeSource) {
        volumeSource.set(source)
        studioVolumeReading()
    }

    fun previewStudioWorkspaceAsync(
        workspace: DspStudioWorkspace,
        enabled: Boolean,
        onComplete: (Boolean) -> Unit,
    ) {
        val generation = studioApplyGeneration.incrementAndGet()
        if (!validStudioWorkspace(workspace)) {
            Handler(Looper.getMainLooper()).post { onComplete(false) }
            return
        }
        ioExecutor.execute {
            val applied = generation == studioApplyGeneration.get() && runCatching {
                applyStudioWorkspace(workspace, enabled, impulseResponseFor(workspace.baseProfileSnapshot))
            }.getOrDefault(false)
            Handler(Looper.getMainLooper()).post { onComplete(applied) }
        }
    }

    fun saveStudioWorkspaceAsync(
        workspace: DspStudioWorkspace,
        enabled: Boolean,
        onComplete: (DspWorkspaceSaveResult) -> Unit,
    ) {
        val generation = studioApplyGeneration.incrementAndGet()
        ioExecutor.execute {
            val candidate = workspace.deepCopy()
            val result = if (!validStudioWorkspace(candidate)) {
                DspWorkspaceSaveResult.INVALID
            } else {
                val activated = generation == studioApplyGeneration.get() && runCatching {
                    applyStudioWorkspace(candidate, enabled, impulseResponseFor(candidate.baseProfileSnapshot))
                }.getOrDefault(false)
                if (!activated) {
                    DspWorkspaceSaveResult.INVALID
                } else {
                    val saveResult = workspaceRepository.save(candidate)
                    if (saveResult == DspWorkspaceSaveResult.SAVED) {
                        committedStudioWorkspace.set(candidate)
                        preferences.edit()
                            .putBoolean(KEY_DSP_ENABLED, enabled)
                            .putString(KEY_SELECTED_PROFILE_ID, candidate.profileSourceId)
                            .apply()
                    }
                    saveResult
                }
            }
            Handler(Looper.getMainLooper()).post { onComplete(result) }
        }
    }

    fun restoreCommittedStudioWorkspaceAsync(enabled: Boolean, onComplete: (Boolean) -> Unit = {}) {
        val generation = studioApplyGeneration.incrementAndGet()
        ioExecutor.execute {
            val workspace = committedStudioWorkspace.get().deepCopy()
            val restored = generation == studioApplyGeneration.get() && runCatching {
                applyStudioWorkspace(workspace, enabled, impulseResponseFor(workspace.baseProfileSnapshot))
            }.getOrDefault(false)
            if (restored) {
                preferences.edit()
                    .putBoolean(KEY_DSP_ENABLED, enabled)
                    .putString(KEY_SELECTED_PROFILE_ID, workspace.profileSourceId)
                    .apply()
            }
            Handler(Looper.getMainLooper()).post { onComplete(restored) }
        }
    }

    fun availableImpulseResponseIds(): List<String> = impulseRepository.ids()

    fun discardImportedImpulseResponse(id: String) {
        val referencedByActiveProfile = activeProfile.get().convolver.impulseResponseId == id
        val referencedBySavedProfile = availableProfiles().any { it.convolver.impulseResponseId == id }
        if (referencedByActiveProfile || referencedBySavedProfile) return
        impulseCache.remove(id)
        impulseRepository.delete(id)
    }

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

    fun apply(profile: DspAudioProfile, enabled: Boolean): Boolean =
        applyWithImpulse(profile, enabled, cachedImpulseResponse(profile))

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

    fun applyAsync(profile: DspAudioProfile, enabled: Boolean, onComplete: (Boolean) -> Unit) {
        if (!profile.hasValidEqCapacity()) {
            Handler(Looper.getMainLooper()).post { onComplete(false) }
            return
        }
        ioExecutor.execute {
            val impulse = impulseResponseFor(profile)
            val applied = applyWithImpulse(profile, enabled, impulse)
            Handler(Looper.getMainLooper()).post { onComplete(applied) }
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

    fun importCabinMeasurementCsvAsync(
        openInput: () -> InputStream?,
        onComplete: (DspCabinMeasurementParseResult) -> Unit,
    ) {
        ioExecutor.execute {
            val result = runCatching {
                val input = openInput() ?: return@runCatching unavailableCabinMeasurement()
                val contents = input.use(::readBoundedCabinCsv)
                    ?: return@runCatching DspCabinMeasurementParseResult.CalibrationUnavailable(
                        DspCabinMeasurementParseResult.CalibrationUnavailable.Reason.INPUT_TOO_LARGE,
                    )
                DspCabinMeasurementCsv.parse(contents)
            }.getOrElse { unavailableCabinMeasurement() }
            Handler(Looper.getMainLooper()).post { onComplete(result) }
        }
    }

    private fun applyWithImpulse(profile: DspAudioProfile, enabled: Boolean, impulse: DspImpulseResponse?): Boolean {
        if (!profile.hasValidEqCapacity()) return false
        val workspace = activeStudioWorkspace.get().copy(
            profileSourceId = profile.id,
            baseProfileSnapshot = profile,
        )
        val resolved = runCatching { resolveStudioWorkspace(workspace, enabled, impulse) }.getOrNull() ?: return false
        activeStudioWorkspace.set(workspace)
        activeProfile.set(profile)
        dspEnabled.set(enabled)
        runtimeConfig.update(resolved)
        preferences.edit()
            .putBoolean(KEY_DSP_ENABLED, enabled)
            .putString(KEY_SELECTED_PROFILE_ID, profile.id)
            .apply()
        return true
    }

    private fun applyStudioWorkspace(
        workspace: DspStudioWorkspace,
        enabled: Boolean,
        impulse: DspImpulseResponse?,
    ): Boolean {
        if (!validStudioWorkspace(workspace)) return false
        val snapshot = workspace.deepCopy()
        val resolved = runCatching { resolveStudioWorkspace(snapshot, enabled, impulse) }.getOrNull() ?: return false
        activeStudioWorkspace.set(snapshot)
        activeProfile.set(snapshot.baseProfileSnapshot)
        dspEnabled.set(enabled)
        runtimeConfig.update(resolved)
        return true
    }

    private fun validStudioWorkspace(workspace: DspStudioWorkspace): Boolean =
        workspace.profileSourceId == workspace.baseProfileSnapshot.id && workspace.baseProfileSnapshot.hasValidEqCapacity()

    private fun resolveStudioWorkspace(
        workspace: DspStudioWorkspace,
        enabled: Boolean,
        impulse: DspImpulseResponse?,
    ): DspRuntimeConfig = DspTuningResolver.resolve(
        profile = workspace.baseProfileSnapshot,
        masterEnabled = enabled,
        macros = workspace.macros,
        loudness = workspace.smartLoudness,
        volumeReading = cachedVolumeReading.get(),
        dynamicBass = workspace.dynamicBass,
        impulseResponse = impulse,
    ).runtimeConfig

    private fun readVolumeSource(): DspVolumeReading = try {
        volumeSource.get().read()
    } catch (_: Exception) {
        DspVolumeReading()
    }

    fun saveCustomAsync(profile: DspAudioProfile, enabled: Boolean, onComplete: (DspProfileSaveResult) -> Unit) {
        if (profile.id !in CUSTOM_PROFILE_IDS) {
            onComplete(DspProfileSaveResult.INVALID_PROFILE_ID)
            return
        }
        if (!profile.hasValidEqCapacity()) {
            onComplete(DspProfileSaveResult.INVALID_EQ_CAPACITY)
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

    private fun readBoundedCabinCsv(input: InputStream): String? {
        val builder = StringBuilder()
        val reader = input.bufferedReader(Charsets.UTF_8)
        val buffer = CharArray(4096)
        while (true) {
            val count = reader.read(buffer)
            if (count < 0) break
            if (builder.length + count > DspCabinMeasurementCsv.MAX_INPUT_CHARACTERS) return null
            builder.append(buffer, 0, count)
        }
        return builder.toString()
    }

    private fun unavailableCabinMeasurement() = DspCabinMeasurementParseResult.CalibrationUnavailable(
        DspCabinMeasurementParseResult.CalibrationUnavailable.Reason.INVALID_DATA,
    )

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

        internal fun createForTesting(
            context: Context,
            filesDirectory: File,
            preferencesName: String,
        ): DspProfileRuntime = DspProfileRuntime(context, filesDirectory, preferencesName)

        internal fun peekInstance(): DspProfileRuntime? = instance

        internal fun resetForTesting() {
            synchronized(this) { instance = null }
        }
    }
}
