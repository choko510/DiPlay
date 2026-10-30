package com.shilapi.xcertplay.dsp

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.edit
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

internal data class DspStudioRestoreRequest(
    val configGeneration: Long,
    val rendererOwnerToken: Long?,
)

internal class DspProfileRuntime private constructor(
    context: Context,
    filesDirectory: File = context.applicationContext.filesDir,
    preferencesName: String = PREFERENCES_NAME,
    workspaceWriterForTesting: ((File, ByteArray) -> Unit)? = null,
) {
    private val applicationContext = context.applicationContext
    private val preferences = applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val repository = DspProfileRepository(filesDirectory)
    private val workspaceRepository = DspStudioWorkspaceRepository(filesDirectory, workspaceWriterForTesting)
    private val customProfileWorkspaceSave = DspCustomProfileWorkspaceSave(
        filesDirectory,
        repository,
        workspaceRepository,
    )
    private val modulePresetRepository = DspModulePresetRepository(filesDirectory)
    private val impulseRepository = DspImpulseResponseRepository(filesDirectory)
    private val studioFeatureFlags = AtomicReference(DspStudioFeatureFlags.load(preferences))
    private val impulseCache = ConcurrentHashMap<String, DspImpulseResponse>()
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "dsp-profile-io").apply { isDaemon = true }
    }
    private val activeProfile: AtomicReference<DspAudioProfile>
    private val dspEnabled: AtomicBoolean
    private val runtimeConfig = DspRuntimeConfigStore()
    private val volumeSource = AtomicReference<DspVolumeSource>(ManualVolumeSource())
    private val cachedVolumeReading = AtomicReference(DspVolumeReading())
    private val playbackVolumeMonitor = AndroidPlaybackVolumeMonitor(applicationContext) { reading ->
        onPlaybackVolumeReadingChanged(reading)
    }
    private val playbackRouteOwners = PlaybackRouteOwners()
    private val studioApplyLock = Any()
    private val studioApplyGeneration = AtomicLong()
    private val pendingStudioExitOperations = AtomicLong()
    internal var afterStudioFeatureFlagRuntimeApplyForTest: (() -> Unit)? = null
    internal var afterStudioWorkspaceApplyForTest: (() -> Unit)? = null
    internal var beforeStudioWorkspaceCommitForTest: (() -> Unit)? = null
    internal var afterStudioWorkspaceCommitDecisionForTest: (() -> Unit)? = null
    internal var beforeStudioWorkspaceRestoreForTest: (() -> Boolean)? = null
    internal var persistStudioFeatureFlagsForTest: (() -> Boolean)? = null
    internal var beforeCustomWorkspaceCommitForTest: (() -> Unit)? = null
    internal var afterCustomWorkspaceSavedForTest: (() -> Unit)? = null
    private val activeStudioWorkspace: AtomicReference<DspStudioWorkspace>
    private val committedStudioWorkspace: AtomicReference<DspStudioWorkspace>
    private val studioSessionCoordinator by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        DspStudioSessionCoordinator(committedStudioWorkspace.get().deepCopy(), workspaceRepository)
    }
    internal val editorSession: DspEditorSession by lazy {
        DspEditorSession(
            initialDraft = selectedProfile(),
            initialAppliedProfile = selectedProfile(),
            initialMasterEnabled = isDspEnabled(),
        )
    }
    val configProvider: DspConfigProvider = runtimeConfig

    init {
        customProfileWorkspaceSave.recoverPending()
        val selectedId = preferences.getString(KEY_SELECTED_PROFILE_ID, DEFAULT_PROFILE_ID) ?: DEFAULT_PROFILE_ID
        val loadedProfile = loadProfile(selectedId) ?: DspProfilePresets.find(DEFAULT_PROFILE_ID)!!
        val loadedWorkspace = (workspaceRepository.load() as? DspWorkspaceReadResult.Loaded)?.workspace
        val initialWorkspace = loadedWorkspace ?: DspStudioWorkspace(loadedProfile.id, loadedProfile)
        if (loadedWorkspace != null && selectedId != loadedWorkspace.profileSourceId) {
            preferences.edit().putString(KEY_SELECTED_PROFILE_ID, loadedWorkspace.profileSourceId).apply()
        }
        val initialEnabled = preferences.getBoolean(KEY_DSP_ENABLED, false)
        activeStudioWorkspace = AtomicReference(initialWorkspace)
        committedStudioWorkspace = AtomicReference(initialWorkspace.deepCopy())
        activeProfile = AtomicReference(initialWorkspace.baseProfileSnapshot)
        dspEnabled = AtomicBoolean(initialEnabled)
        playbackVolumeMonitor.start()
        volumeSource.set(playbackVolumeMonitor)
        runtimeConfig.setStudioHotUpdatesEnabled(studioFeatureFlags.get().nativeHotUpdatesEnabled)
        runtimeConfig.setStudioLocalWarmEnabled(studioFeatureFlags.get().localWarmEnabled)
        cachedVolumeReading.set(readVolumeSource())
        runtimeConfig.update(resolveStudioWorkspace(initialWorkspace, initialEnabled, impulseResponseFor(initialWorkspace.baseProfileSnapshot)))
    }

    fun selectedProfile(): DspAudioProfile = activeProfile.get()

    fun isDspEnabled(): Boolean = dspEnabled.get()

    fun activePlaybackSampleRateHz(): Int? = runtimeConfig.activePlaybackSampleRateHz()

    fun createStudioSessionCoordinator(): DspStudioSessionCoordinator = studioSessionCoordinator

    internal fun requiresStudioExitReview(): Boolean =
        studioSessionCoordinator.isDirty || editorSession.hasUnappliedChanges ||
            studioSessionCoordinator.isCancelPending || editorSession.pendingOperation != null ||
            pendingStudioExitOperations.get() > 0L

    fun currentStudioFeatureFlags(): DspStudioFeatureFlags = studioFeatureFlags.get()

    fun updateStudioFeatureFlagsAsync(
        flags: DspStudioFeatureFlags,
        onComplete: (Boolean) -> Unit,
    ) {
        if (flags.irWarmBudgetEnabled) {
            Handler(Looper.getMainLooper()).post { onComplete(false) }
            return
        }
        val generation = nextStudioApplyGeneration()
        pendingStudioExitOperations.incrementAndGet()
        ioExecutor.execute {
            val previous = studioFeatureFlags.get()
            val previousActiveWorkspace = activeStudioWorkspace.get().deepCopy()
            val audioFeaturesChanged = previous.macroOverlayEnabled != flags.macroOverlayEnabled ||
                previous.smartLoudnessEnabled != flags.smartLoudnessEnabled ||
                previous.dynamicBassEnabled != flags.dynamicBassEnabled
            val enablingAudioFeature = (!previous.macroOverlayEnabled && flags.macroOverlayEnabled) ||
                (!previous.smartLoudnessEnabled && flags.smartLoudnessEnabled) ||
                (!previous.dynamicBassEnabled && flags.dynamicBassEnabled)
            val workspace = if (enablingAudioFeature) {
                committedStudioWorkspace.get().deepCopy()
            } else {
                activeStudioWorkspace.get().deepCopy()
            }
            val applied = if (generation != studioApplyGeneration.get()) {
                false
            } else {
                studioFeatureFlags.set(flags)
                runtimeConfig.setStudioHotUpdatesEnabled(flags.nativeHotUpdatesEnabled)
                runtimeConfig.setStudioLocalWarmEnabled(flags.localWarmEnabled)
                val runtimeApplied = !audioFeaturesChanged || runCatching {
                    applyStudioWorkspace(workspace, dspEnabled.get(), impulseResponseFor(workspace.baseProfileSnapshot), generation)
                }.getOrDefault(false)
                if (runtimeApplied) afterStudioFeatureFlagRuntimeApplyForTest?.invoke()
                val persisted = runtimeApplied && studioApplyGeneration.get() == generation && runCatching {
                    persistStudioFeatureFlagsForTest?.invoke()
                        ?: preferences.edit().also(flags::writeTo).commit()
                }.getOrDefault(false)
                if (!persisted) {
                    studioFeatureFlags.set(previous)
                    runtimeConfig.setStudioHotUpdatesEnabled(previous.nativeHotUpdatesEnabled)
                    runCatching { preferences.edit().also(previous::writeTo).commit() }
                    if (audioFeaturesChanged) {
                        runCatching {
                            applyStudioWorkspace(
                                previousActiveWorkspace,
                                dspEnabled.get(),
                                impulseResponseFor(previousActiveWorkspace.baseProfileSnapshot),
                                generation,
                            )
                        }
                    }
                    runtimeConfig.setStudioLocalWarmEnabled(previous.localWarmEnabled)
                }
                persisted
            }
            Handler(Looper.getMainLooper()).post {
                pendingStudioExitOperations.decrementAndGet()
                onComplete(applied)
            }
        }
    }

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

    fun deleteModulePresetAsync(
        moduleKey: String,
        id: String,
        onComplete: (DspPresetStoreResult) -> Unit,
    ) {
        ioExecutor.execute {
            val workspaceRead = workspaceRepository.load()
            val savedWorkspace = (workspaceRead as? DspWorkspaceReadResult.Loaded)?.workspace
            val sanitizedSavedWorkspace = savedWorkspace?.takeIf {
                it.selectedModulePresetIds[moduleKey] == id
            }?.copy(selectedModulePresetIds = savedWorkspace.selectedModulePresetIds - moduleKey)
            val result = when (workspaceRead) {
                is DspWorkspaceReadResult.FutureSchema -> DspPresetStoreResult.FUTURE_SCHEMA_PRESERVED
                DspWorkspaceReadResult.Invalid -> DspPresetStoreResult.WRITE_FAILED
                is DspWorkspaceReadResult.Loaded, DspWorkspaceReadResult.NotFound -> {
                    val referenceCleared = sanitizedSavedWorkspace == null ||
                        workspaceRepository.save(sanitizedSavedWorkspace) == DspWorkspaceSaveResult.SAVED
                    if (referenceCleared) modulePresetRepository.deleteUserPreset(moduleKey, id)
                    else DspPresetStoreResult.WRITE_FAILED
                }
            }
            if (result == DspPresetStoreResult.DELETED) {
                sanitizedSavedWorkspace?.let { committedStudioWorkspace.set(it.deepCopy()) }
                activeStudioWorkspace.updateAndGet { workspace ->
                    if (workspace.selectedModulePresetIds[moduleKey] == id) {
                        workspace.copy(selectedModulePresetIds = workspace.selectedModulePresetIds - moduleKey)
                    } else {
                        workspace
                    }
                }
                committedStudioWorkspace.updateAndGet { workspace ->
                    if (workspace.selectedModulePresetIds[moduleKey] == id) {
                        workspace.copy(selectedModulePresetIds = workspace.selectedModulePresetIds - moduleKey)
                    } else {
                        workspace
                    }
                }
            } else if (sanitizedSavedWorkspace != null) {
                workspaceRepository.save(savedWorkspace)
            }
            Handler(Looper.getMainLooper()).post {
                if (result == DspPresetStoreResult.DELETED) {
                    studioSessionCoordinator.clearSelectedPresetReference(moduleKey, id)
                }
                onComplete(result)
            }
        }
    }

    fun studioVolumeReading(): DspVolumeReading {
        val reading = readVolumeSource()
        val previous = cachedVolumeReading.getAndSet(reading)
        if (previous != reading) {
            val workspace = activeStudioWorkspace.get()
            val enabled = dspEnabled.get()
            val generation = studioApplyGeneration.get()
            ioExecutor.execute {
                if (activeStudioWorkspace.get() == workspace && generation == studioApplyGeneration.get()) {
                    runCatching {
                        applyStudioWorkspace(workspace, enabled, impulseResponseFor(workspace.baseProfileSnapshot), generation)
                    }
                }
            }
        }
        return reading
    }

    internal fun onPlaybackRouteChanged(owner: Long, deviceType: Int?, deviceAddress: String?, usesMusicStream: Boolean) {
        playbackRouteOwners.updateAndPublish(owner, deviceType, deviceAddress, usesMusicStream) { route ->
            playbackVolumeMonitor.setPlaybackRoute(route)
        }
    }

    internal fun onPlaybackVolumeReadingChanged(reading: DspVolumeReading) {
        val previous = cachedVolumeReading.getAndSet(reading)
        if (previous == reading) return
        val workspace = activeStudioWorkspace.get()
        val enabled = dspEnabled.get()
        val generation = studioApplyGeneration.get()
        ioExecutor.execute {
            if (activeStudioWorkspace.get() == workspace && generation == studioApplyGeneration.get()) {
                runCatching { applyStudioWorkspace(workspace, enabled, impulseResponseFor(workspace.baseProfileSnapshot), generation) }
            }
        }
    }

    internal fun closeForTesting() {
        playbackVolumeMonitor.stop()
        ioExecutor.shutdownNow()
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
        val generation = nextStudioApplyGeneration()
        if (!validStudioWorkspace(workspace)) {
            Handler(Looper.getMainLooper()).post { onComplete(false) }
            return
        }
        ioExecutor.execute {
            val applied = generation == studioApplyGeneration.get() && runCatching {
                applyStudioWorkspace(workspace, enabled, impulseResponseFor(workspace.baseProfileSnapshot), generation)
            }.getOrDefault(false)
            Handler(Looper.getMainLooper()).post { onComplete(applied) }
        }
    }

    fun saveStudioWorkspaceAsync(
        workspace: DspStudioWorkspace,
        enabled: Boolean,
        onComplete: (DspWorkspaceSaveResult) -> Unit,
    ) {
        val generation = nextStudioApplyGeneration()
        pendingStudioExitOperations.incrementAndGet()
        ioExecutor.execute {
            val candidate = workspace.deepCopy()
            val result = if (!validStudioWorkspace(candidate)) {
                DspWorkspaceSaveResult.INVALID
            } else {
                val activated = generation == studioApplyGeneration.get() && runCatching {
                    applyStudioWorkspace(candidate, enabled, impulseResponseFor(candidate.baseProfileSnapshot), generation)
                }.getOrDefault(false)
                if (!activated) {
                    DspWorkspaceSaveResult.INVALID
                } else {
                    afterStudioWorkspaceApplyForTest?.invoke()
                    if (generation != studioApplyGeneration.get()) {
                        DspWorkspaceSaveResult.STALE
                    } else {
                        val saveResult = workspaceRepository.save(candidate, beforeCommit = {
                            beforeStudioWorkspaceCommitForTest?.invoke()
                            val shouldCommit = generation == studioApplyGeneration.get()
                            if (shouldCommit) afterStudioWorkspaceCommitDecisionForTest?.invoke()
                            shouldCommit
                        })
                        if (saveResult == DspWorkspaceSaveResult.SAVED) {
                            committedStudioWorkspace.set(candidate)
                            studioSessionCoordinator.commitSaved(candidate)
                            preferences.edit {
                                putBoolean(KEY_DSP_ENABLED, enabled)
                                putString(KEY_SELECTED_PROFILE_ID, candidate.profileSourceId)
                            }
                        }
                        saveResult
                    }
                }
            }
            Handler(Looper.getMainLooper()).post {
                pendingStudioExitOperations.decrementAndGet()
                onComplete(result)
            }
        }
    }

    fun requestCommittedStudioWorkspaceRestoreAsync(
        enabled: Boolean,
        onComplete: (DspStudioRestoreRequest?) -> Unit,
    ) {
        val rendererOwnerTokenAtRequestStart = runtimeConfig.rendererApplyStatus()?.rendererOwnerToken
        val generation = nextStudioApplyGeneration()
        pendingStudioExitOperations.incrementAndGet()
        ioExecutor.execute {
            val workspace = committedStudioWorkspace.get().deepCopy()
            val restored = generation == studioApplyGeneration.get() &&
                (beforeStudioWorkspaceRestoreForTest?.invoke() != false) &&
                runCatching {
                    applyStudioWorkspace(workspace, enabled, impulseResponseFor(workspace.baseProfileSnapshot), generation)
                }.getOrDefault(false) && generation == studioApplyGeneration.get()
            val request = if (restored) {
                val snapshot = runtimeConfig.versionedSnapshot()
                val rendererOwnerToken = runtimeConfig.rendererApplyStatus()?.rendererOwnerToken
                    ?: rendererOwnerTokenAtRequestStart
                preferences.edit {
                    putBoolean(KEY_DSP_ENABLED, enabled)
                    putString(KEY_SELECTED_PROFILE_ID, workspace.profileSourceId)
                }
                DspStudioRestoreRequest(snapshot.generation, rendererOwnerToken)
            } else {
                null
            }
            Handler(Looper.getMainLooper()).post {
                pendingStudioExitOperations.decrementAndGet()
                onComplete(request)
            }
        }
    }

    fun isCommittedStudioWorkspaceActive(): Boolean = activeStudioWorkspace.get() == committedStudioWorkspace.get()

    fun availableImpulseResponseIds(): List<String> = impulseRepository.ids()

    @Synchronized fun discardImportedImpulseResponse(id: String) {
        runCatching {
            val referencedOutsideSession = activeProfile.get().convolver.impulseResponseId == id ||
                activeStudioWorkspace.get().baseProfileSnapshot.convolver.impulseResponseId == id ||
                committedStudioWorkspace.get().baseProfileSnapshot.convolver.impulseResponseId == id ||
                workspaceRepository.referencesImpulseResponse(id) ||
                repository.referencesImpulseResponse(id) ||
                modulePresetRepository.referencesImpulseResponse(id)
            if (referencedOutsideSession) return
            if (studioSessionCoordinator.deleteImpulseResponseIfUnreferenced(id) {
                    impulseRepository.delete(id)
                }
            ) {
                impulseCache.remove(id)
            }
        }
    }

    fun discardImportedImpulseResponseAsync(id: String, onComplete: () -> Unit) {
        ioExecutor.execute {
            try {
                discardImportedImpulseResponse(id)
            } finally {
                Handler(Looper.getMainLooper()).post(onComplete)
            }
        }
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

    @Synchronized fun apply(profile: DspAudioProfile, enabled: Boolean): Boolean {
        val generation = nextStudioApplyGeneration()
        return applyWithImpulse(profile, enabled, cachedImpulseResponse(profile), generation)
    }

    fun setMasterEnabled(enabled: Boolean) {
        val generation = nextStudioApplyGeneration()
        val profile = activeProfile.get()
        applyWithImpulse(profile, enabled, cachedImpulseResponse(profile), generation)
    }

    fun setMasterEnabledAsync(enabled: Boolean, onComplete: () -> Unit) {
        val generation = nextStudioApplyGeneration()
        ioExecutor.execute {
            if (generation == studioApplyGeneration.get()) {
                val profile = activeProfile.get()
                val impulse = impulseResponseFor(profile)
                applyWithImpulse(profile, enabled, impulse, generation)
            }
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
        val generation = nextStudioApplyGeneration()
        ioExecutor.execute {
            val impulse = impulseResponseFor(profile)
            val applied = applyWithImpulse(profile, enabled, impulse, generation)
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

    private fun applyWithImpulse(
        profile: DspAudioProfile,
        enabled: Boolean,
        impulse: DspImpulseResponse?,
        generation: Long? = null,
    ): Boolean {
        if (!profile.hasValidEqCapacity()) return false
        val previousWorkspace = activeStudioWorkspace.get()
        val workspace = invalidateChangedPresetSelections(previousWorkspace, previousWorkspace.copy(
            profileSourceId = profile.id,
            baseProfileSnapshot = profile,
        ))
        val resolved = runCatching { resolveStudioWorkspace(workspace, enabled, impulse) }.getOrNull() ?: return false
        synchronized(studioApplyLock) {
            if (generation != null && generation != studioApplyGeneration.get()) return false
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
    }

    private fun applyStudioWorkspace(
        workspace: DspStudioWorkspace,
        enabled: Boolean,
        impulse: DspImpulseResponse?,
        generation: Long? = null,
    ): Boolean {
        if (!validStudioWorkspace(workspace)) return false
        val snapshot = workspace.deepCopy()
        val resolved = runCatching { resolveStudioWorkspace(snapshot, enabled, impulse) }.getOrNull() ?: return false
        synchronized(studioApplyLock) {
            if (generation != null && generation != studioApplyGeneration.get()) return false
            activeStudioWorkspace.set(snapshot)
            activeProfile.set(snapshot.baseProfileSnapshot)
            dspEnabled.set(enabled)
            runtimeConfig.update(resolved)
            return true
        }
    }

    private fun validStudioWorkspace(workspace: DspStudioWorkspace): Boolean =
        workspace.profileSourceId == workspace.baseProfileSnapshot.id && workspace.baseProfileSnapshot.hasValidEqCapacity()

    private fun nextStudioApplyGeneration(): Long = synchronized(studioApplyLock) {
        studioApplyGeneration.incrementAndGet()
    }

    private fun resolveStudioWorkspace(
        workspace: DspStudioWorkspace,
        enabled: Boolean,
        impulse: DspImpulseResponse?,
    ): DspRuntimeConfig {
        val flags = studioFeatureFlags.get()
        return DspTuningResolver.resolve(
            profile = workspace.baseProfileSnapshot,
            masterEnabled = enabled,
            macros = if (flags.macroOverlayEnabled) workspace.macros else DspSoundMacroSettings(),
            loudness = if (flags.smartLoudnessEnabled) workspace.smartLoudness else DspSmartLoudnessSettings(),
            volumeReading = cachedVolumeReading.get(),
            dynamicBass = if (flags.dynamicBassEnabled) workspace.dynamicBass else DspDynamicBassSettings(),
            impulseResponse = impulse,
        ).runtimeConfig
    }

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
        val generation = nextStudioApplyGeneration()
        ioExecutor.execute {
            val result = if (generation != studioApplyGeneration.get()) {
                DspProfileSaveResult.STALE
            } else {
                val previousCommitted = committedStudioWorkspace.get()
                val persistedWorkspace = invalidateChangedPresetSelections(
                    previousCommitted,
                    previousCommitted.copy(
                        profileSourceId = profile.id,
                        baseProfileSnapshot = profile,
                    ),
                )
                val previousActive = activeStudioWorkspace.get()
                val activeWorkspace = invalidateChangedPresetSelections(
                    previousActive,
                    previousActive.copy(
                        profileSourceId = profile.id,
                        baseProfileSnapshot = profile,
                    ),
                )
                val impulse = impulseResponseFor(profile)
                val canResolve = validStudioWorkspace(persistedWorkspace) && validStudioWorkspace(activeWorkspace) &&
                    runCatching { resolveStudioWorkspace(activeWorkspace, enabled, impulse) }.isSuccess
                if (!canResolve) {
                    DspProfileSaveResult.WRITE_FAILED
                } else {
                    customProfileWorkspaceSave.afterWorkspaceSavedForTesting = afterCustomWorkspaceSavedForTest
                    val saved = try {
                        customProfileWorkspaceSave.save(profile, persistedWorkspace) {
                            beforeCustomWorkspaceCommitForTest?.invoke()
                            generation == studioApplyGeneration.get()
                        }
                    } finally {
                        customProfileWorkspaceSave.afterWorkspaceSavedForTesting = null
                    }
                    if (saved == DspProfileSaveResult.SAVED) {
                        committedStudioWorkspace.set(persistedWorkspace.deepCopy())
                        studioSessionCoordinator.commitCustomProfileSave(persistedWorkspace)
                        applyStudioWorkspace(activeWorkspace, enabled, impulse)
                        preferences.edit()
                            .putBoolean(KEY_DSP_ENABLED, enabled)
                            .putString(KEY_SELECTED_PROFILE_ID, profile.id)
                            .apply()
                    }
                    saved
                }
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
            workspaceWriter: ((File, ByteArray) -> Unit)? = null,
        ): DspProfileRuntime = DspProfileRuntime(context, filesDirectory, preferencesName, workspaceWriter)

        internal fun peekInstance(): DspProfileRuntime? = instance

        internal fun resetForTesting() {
            synchronized(this) {
                instance?.closeForTesting()
                instance = null
            }
        }
    }
}
