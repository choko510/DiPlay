package com.shilapi.xcertplay.dsp

import android.util.AtomicFile
import java.io.File
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject

internal sealed interface DspWorkspaceReadResult {
    data class Loaded(val workspace: DspStudioWorkspace) : DspWorkspaceReadResult
    data class FutureSchema(val schema: Int) : DspWorkspaceReadResult
    data object NotFound : DspWorkspaceReadResult
    data object Invalid : DspWorkspaceReadResult
}

internal enum class DspWorkspaceSaveResult { SAVED, FUTURE_SCHEMA_PRESERVED, INVALID, WRITE_FAILED }

internal class DspStudioWorkspaceRepository(
    private val filesDirectory: File,
    private val writer: (File, ByteArray) -> Unit = ::atomicWrite,
) {
    private val file = File(File(filesDirectory, "dsp"), "workspace/current.json")

    fun load(): DspWorkspaceReadResult {
        if (!file.isFile) return DspWorkspaceReadResult.NotFound
        return try {
            require(file.length() <= MAX_BYTES)
            val json = JSONObject(AtomicFile(file).openRead().bufferedReader(StandardCharsets.UTF_8).use { it.readText() })
            val schema = json.optInt("workspaceSchema", 0)
            if (schema > SCHEMA) return DspWorkspaceReadResult.FutureSchema(schema)
            if (schema != SCHEMA || json.optString("id") != WORKSPACE_ID) return DspWorkspaceReadResult.Invalid
            val source = json.getString("baseProfileSourceId")
            require(ID.matches(source))
            val profile = DspProfileRepository(filesDirectory).decodeDraftFromSavedState(
                json.getJSONObject("baseProfileSnapshot").toString(),
            ) ?: return DspWorkspaceReadResult.Invalid
            val workspace = DspStudioWorkspace(
                profileSourceId = source,
                baseProfileSnapshot = profile,
                macros = json.getJSONObject("macroSettings").let {
                    DspSoundMacroSettings(it.getBoolean("enabled"), it.getInt("bassImpact"), it.getInt("vocalPresence"), it.getInt("airDetail"), it.getInt("soundstageWidth"))
                },
                smartLoudness = json.getJSONObject("smartLoudness").toLoudness(),
                dynamicBass = json.getJSONObject("dynamicBass").toDynamicBass(),
                selectedModulePresetIds = json.getJSONObject("selectedModulePresetIds").toMap(),
            )
            DspWorkspaceReadResult.Loaded(workspace)
        } catch (_: Exception) { DspWorkspaceReadResult.Invalid }
    }

    @Synchronized fun save(workspace: DspStudioWorkspace, updatedAtMs: Long = System.currentTimeMillis()): DspWorkspaceSaveResult {
        if (!validWorkspace(workspace) || updatedAtMs < 0L) return DspWorkspaceSaveResult.INVALID
        if (file.isFile && readSchema(file)?.let { it > SCHEMA } == true) return DspWorkspaceSaveResult.FUTURE_SCHEMA_PRESERVED
        val parent = file.parentFile ?: return DspWorkspaceSaveResult.WRITE_FAILED
        if (!parent.exists() && !parent.mkdirs()) return DspWorkspaceSaveResult.WRITE_FAILED
        return try {
            val snapshot = JSONObject(DspProfileRepository(filesDirectory).encodeDraftForSavedState(workspace.baseProfileSnapshot))
            // Profile codec is shared with v7; the profile id is stored inside the complete snapshot.
            val json = JSONObject()
                .put("workspaceSchema", SCHEMA).put("id", WORKSPACE_ID)
                .put("baseProfileSourceId", workspace.profileSourceId).put("baseProfileSnapshot", snapshot)
                .put("macroSettings", JSONObject().put("enabled", workspace.macros.enabled).put("bassImpact", workspace.macros.bassImpact).put("vocalPresence", workspace.macros.vocalPresence).put("airDetail", workspace.macros.airDetail).put("soundstageWidth", workspace.macros.soundstageWidth))
                .put("smartLoudness", workspace.smartLoudness.toJson()).put("dynamicBass", workspace.dynamicBass.toJson())
                .put("selectedModulePresetIds", JSONObject(workspace.selectedModulePresetIds))
                .put("lastUpdatedAtMs", updatedAtMs)
            writer(file, json.toString().toByteArray(StandardCharsets.UTF_8))
            DspWorkspaceSaveResult.SAVED
        } catch (_: Exception) { DspWorkspaceSaveResult.WRITE_FAILED }
    }

    private fun readSchema(target: File): Int? = try { JSONObject(AtomicFile(target).openRead().bufferedReader().use { it.readText() }).optInt("workspaceSchema", 0) } catch (_: Exception) { null }
    private fun validWorkspace(value: DspStudioWorkspace): Boolean = ID.matches(value.profileSourceId) && value.baseProfileSnapshot.hasValidEqCapacity() && value.selectedModulePresetIds.size <= MODULES.size && value.selectedModulePresetIds.all { (key, id) -> key in MODULES && PRESET_ID.matches(id) }
    private fun JSONObject.toMap(): Map<String, String> { require(length() <= MODULES.size); return keys().asSequence().associateWith { getString(it) } }
    companion object { const val SCHEMA = 1; private const val WORKSPACE_ID = "active_dsp_studio"; private const val MAX_BYTES = 1_048_576L; private val ID = Regex("^[A-Za-z0-9_-]{1,64}$"); private val PRESET_ID = ID; private val MODULES = setOf("peq", "bass", "seat", "advanced_ms", "compressor", "multiband", "dynamic_eq", "harmonic", "limiter", "convolver", "cabin", "smart_loudness", "dynamic_bass", "macros") }
}

internal enum class DspPresetStoreResult { SAVED, NOT_FOUND, INVALID, FUTURE_SCHEMA_PRESERVED, WRITE_FAILED }
internal data class DspModulePreset(val id: String, val moduleKey: String, val name: String, val payload: JSONObject, val factory: Boolean = false)

internal class DspModulePresetRepository(
    private val filesDirectory: File,
    private val writer: (File, ByteArray) -> Unit = ::atomicWrite,
) {
    fun save(preset: DspModulePreset): DspPresetStoreResult {
        if (preset.factory || !validId(preset.id) || preset.moduleKey !in MODULES || preset.name.isBlank() || preset.name.length > 48 || !validPayload(preset.moduleKey, preset.payload) || preset.payload.toString().length > MAX_PAYLOAD_CHARS) return DspPresetStoreResult.INVALID
        val dir = File(File(filesDirectory, "dsp/modules"), preset.moduleKey)
        val target = File(dir, "${preset.id}.json")
        if (target.isFile && schema(target)?.let { it > SCHEMA } == true) return DspPresetStoreResult.FUTURE_SCHEMA_PRESERVED
        if ((dir.listFiles()?.count { it.extension == "json" } ?: 0) >= MAX_PRESETS && !target.exists()) return DspPresetStoreResult.INVALID
        if (!dir.exists() && !dir.mkdirs()) return DspPresetStoreResult.WRITE_FAILED
        return try { writer(target, JSONObject().put("schemaVersion", SCHEMA).put("id", preset.id).put("moduleKey", preset.moduleKey).put("name", preset.name).put("origin", "USER").put("payloadVersion", 1).put("payload", preset.payload).toString().toByteArray()); DspPresetStoreResult.SAVED } catch (_: Exception) { DspPresetStoreResult.WRITE_FAILED }
    }
    fun load(moduleKey: String, id: String): DspModulePreset? {
        if (moduleKey !in MODULES || !validId(id)) return null
        val target = File(File(filesDirectory, "dsp/modules"), "$moduleKey/$id.json")
        return try { if (target.length() > MAX_PAYLOAD_CHARS) return null; val j = JSONObject(AtomicFile(target).openRead().bufferedReader().use { it.readText() }); if (j.getInt("schemaVersion") != SCHEMA || j.getInt("payloadVersion") != 1 || j.getString("id") != id || j.getString("moduleKey") != moduleKey) return null; val payload = j.getJSONObject("payload"); val name = j.getString("name"); if (name.isBlank() || name.length > 48 || !validPayload(moduleKey, payload)) null else DspModulePreset(id, moduleKey, name, payload) } catch (_: Exception) { null }
    }
    fun applyTo(profile: DspAudioProfile, preset: DspModulePreset): DspAudioProfile? {
        if (preset.moduleKey !in MODULES || !validPayload(preset.moduleKey, preset.payload)) return null
        val p = preset.payload
        return when (preset.moduleKey) {
            "bass" -> profile.copy(bass = profile.bass.copy(enabled = p.getBoolean("enabled"), gainDb = p.getDouble("gainDb"), frequencyHz = p.getDouble("frequencyHz")))
            "peq" -> profile.copy(eqBands = readEq(p.getJSONArray("bands")) ?: return null)
            "limiter" -> profile.copy(limiter = profile.limiter.copy(enabled = p.getBoolean("enabled"), thresholdDb = p.getDouble("thresholdDb"), releaseMs = p.getDouble("releaseMs")))
            else -> null
        }
    }
    private fun validPayload(key: String, p: JSONObject): Boolean = try { when (key) { "bass" -> com.shilapi.xcertplay.media.dsp.DspBassConfig(p.getBoolean("enabled"), p.getDouble("gainDb"), p.getDouble("frequencyHz")); "peq" -> requireNotNull(readEq(p.getJSONArray("bands"))); "limiter" -> com.shilapi.xcertplay.media.dsp.DspSafetyLimiterConfig(p.getBoolean("enabled"), p.getDouble("thresholdDb"), p.getDouble("releaseMs")); else -> return false }; true } catch (_: Exception) { false }
    private fun readEq(a: JSONArray): List<com.shilapi.xcertplay.media.dsp.DspEqBand>? = try { require(a.length() <= DspAudioProfile.MAX_EQ_BANDS); List(a.length()) { i -> val b = a.getJSONObject(i); com.shilapi.xcertplay.media.dsp.DspEqBand(com.shilapi.xcertplay.media.dsp.DspEqType.valueOf(b.getString("type")), b.getDouble("frequencyHz"), b.getDouble("gainDb"), b.getDouble("q"), b.getBoolean("enabled")) } } catch (_: Exception) { null }
    private fun schema(f: File): Int? = try { JSONObject(AtomicFile(f).openRead().bufferedReader().use { it.readText() }).optInt("schemaVersion", 0) } catch (_: Exception) { null }
    private fun validId(id: String) = ID.matches(id) && !id.contains("..")
    companion object { const val SCHEMA = 1; private const val MAX_PRESETS = 100; private const val MAX_PAYLOAD_CHARS = 65_536L; private val ID = Regex("^[A-Za-z0-9_-]{1,64}$"); private val MODULES = setOf("peq", "bass", "seat", "advanced_ms", "compressor", "multiband", "dynamic_eq", "harmonic", "limiter", "convolver", "cabin", "smart_loudness", "dynamic_bass", "macros") }
}

internal class DspMacroPresetRepository(private val directory: File, private val writer: (File, ByteArray) -> Unit = ::atomicWrite) {
    fun load(id: String): Pair<String, DspSoundMacroSettings>? {
        if (!ID.matches(id) || id.contains("..")) return null
        val file = File(directory, "dsp/macros/$id.json")
        return try {
            if (file.length() > 16_384L) return null
            val root = JSONObject(AtomicFile(file).openRead().bufferedReader().use { it.readText() })
            if (root.getInt("schemaVersion") != 1 || root.getInt("payloadVersion") != 1 || root.getString("id") != id) return null
            val payload = root.getJSONObject("payload")
            val name = root.getString("name").also { require(it.isNotBlank() && it.length <= 48) }
            name to DspSoundMacroSettings(payload.getBoolean("enabled"), payload.getInt("bassImpact"), payload.getInt("vocalPresence"), payload.getInt("airDetail"), payload.getInt("soundstageWidth"))
        } catch (_: Exception) { null }
    }
    fun save(id: String, name: String, settings: DspSoundMacroSettings): DspPresetStoreResult {
        if (!ID.matches(id) || id.contains("..") || name.isBlank() || name.length > 48) return DspPresetStoreResult.INVALID
        val file = File(directory, "dsp/macros/$id.json")
        if (file.parentFile?.listFiles()?.count { it.extension == "json" }?.let { it >= 100 && !file.exists() } == true) return DspPresetStoreResult.INVALID
        if (file.isFile && runCatching { JSONObject(AtomicFile(file).openRead().bufferedReader().use { it.readText() }).optInt("schemaVersion") }.getOrNull()?.let { it > 1 } == true) return DspPresetStoreResult.FUTURE_SCHEMA_PRESERVED
        val parent = file.parentFile!!; if (!parent.exists() && !parent.mkdirs()) return DspPresetStoreResult.WRITE_FAILED
        return try { writer(file, JSONObject().put("schemaVersion", 1).put("id", id).put("name", name).put("origin", "USER").put("payloadVersion", 1).put("payload", JSONObject().put("enabled", settings.enabled).put("bassImpact", settings.bassImpact).put("vocalPresence", settings.vocalPresence).put("airDetail", settings.airDetail).put("soundstageWidth", settings.soundstageWidth)).toString().toByteArray()); DspPresetStoreResult.SAVED } catch (_: Exception) { DspPresetStoreResult.WRITE_FAILED }
    }
    companion object { private val ID = Regex("^[A-Za-z0-9_-]{1,64}$") }
}

internal object DspFactoryPresets {
    fun modulePresets(): List<DspModulePreset> = listOf(
        DspModulePreset("factory-bass-neutral", "bass", "Neutral", JSONObject().put("enabled", false).put("gainDb", 0.0).put("frequencyHz", 80.0), true),
        DspModulePreset("factory-limiter-safe", "limiter", "Safety", JSONObject().put("enabled", true).put("thresholdDb", -1.0).put("releaseMs", 60.0), true),
    )
    val macroPresets: Map<String, DspSoundMacroSettings> = mapOf(
        "neutral" to DspSoundMacroSettings(),
        "warm" to DspSoundMacroSettings(true, bassImpact = 60),
        "vocal" to DspSoundMacroSettings(true, vocalPresence = 65),
        "air" to DspSoundMacroSettings(true, airDetail = 65),
    )
}

internal fun DspStudioWorkspace.deepCopy(): DspStudioWorkspace = copy(
    baseProfileSnapshot = baseProfileSnapshot.copy(
        eqBands = baseProfileSnapshot.eqBands.toList(),
        cabinCorrection = baseProfileSnapshot.cabinCorrection.copy(notches = baseProfileSnapshot.cabinCorrection.notches.toList()),
        dynamicEq = com.shilapi.xcertplay.media.dsp.DspDynamicEqConfig(
            enabled = baseProfileSnapshot.dynamicEq.enabled,
            bands = baseProfileSnapshot.dynamicEq.bands.toList(),
        ),
    ),
    selectedModulePresetIds = selectedModulePresetIds.toMap(),
)

internal class DspStudioSessionCoordinator(initial: DspStudioWorkspace, private val repository: DspStudioWorkspaceRepository) {
    private var committed = initial.deepCopy()
    private var draft = initial.deepCopy()
    private var undo = mutableListOf<DspStudioWorkspace>()
    private var redo = mutableListOf<DspStudioWorkspace>()
    private var generation = 0L
    var preview: DspStudioWorkspace = draft.deepCopy(); private set
    var isDirty: Boolean = false; private set
    fun edit(transform: (DspStudioWorkspace) -> DspStudioWorkspace): Long { undo.add(draft.deepCopy()); if (undo.size > 100) undo.removeAt(0); draft = transform(draft).deepCopy(); redo.clear(); isDirty = draft != committed; generation++; preview = draft.deepCopy(); return generation }
    fun undo(): Boolean { if (undo.isEmpty()) return false; redo.add(draft); draft = undo.removeAt(undo.lastIndex); isDirty = draft != committed; generation++; preview = draft.deepCopy(); return true }
    fun redo(): Boolean { if (redo.isEmpty()) return false; undo.add(draft); draft = redo.removeAt(redo.lastIndex); isDirty = draft != committed; generation++; preview = draft.deepCopy(); return true }
    fun cancelPreview(): Long { draft = committed.deepCopy(); isDirty = false; generation++; preview = draft.deepCopy(); return generation }
    fun compareA(): DspStudioWorkspace = committed.deepCopy()
    fun compareB(): DspStudioWorkspace = draft.deepCopy()
    fun save(): DspWorkspaceSaveResult { val result = repository.save(draft); if (result == DspWorkspaceSaveResult.SAVED) { committed = draft.deepCopy(); isDirty = false; undo.clear(); redo.clear() }; return result }
    fun generation(): Long = generation
}

private fun DspSmartLoudnessSettings.toJson() = JSONObject().put("mode", mode.name).put("strength", strength).put("maxLowBoostDb", maxLowBoostDb).put("maxHighBoostDb", maxHighBoostDb).put("calibration", calibration?.let { JSONObject().put("routeToken", it.routeToken).put("quietVolumeFraction", it.quietVolumeFraction).put("referenceVolumeFraction", it.referenceVolumeFraction) }).put("quietListeningLevel", quietListeningLevel).put("referenceListeningLevel", referenceListeningLevel).put("manualListeningLevel", manualListeningLevel).put("fixedAmount", fixedAmount)
private fun JSONObject.toLoudness() = DspSmartLoudnessSettings(DspSmartLoudnessMode.valueOf(getString("mode")), getDouble("strength"), getDouble("maxLowBoostDb"), getDouble("maxHighBoostDb"), optJSONObject("calibration")?.let { DspLoudnessCalibration(it.getString("routeToken"), it.getDouble("quietVolumeFraction"), it.getDouble("referenceVolumeFraction")) }, getDouble("quietListeningLevel"), getDouble("referenceListeningLevel"), getDouble("manualListeningLevel"), getDouble("fixedAmount"))
private fun DspDynamicBassSettings.toJson() = JSONObject().put("enabled", enabled).put("strength", strength).put("maxBoostDb", maxBoostDb).put("detectorCutoffHz", detectorCutoffHz).put("silenceGateDbfs", silenceGateDbfs).put("lowThresholdDbfs", lowThresholdDbfs).put("highThresholdDbfs", highThresholdDbfs).put("attackMs", attackMs).put("releaseMs", releaseMs)
private fun JSONObject.toDynamicBass() = DspDynamicBassSettings(getBoolean("enabled"), getDouble("strength"), getDouble("maxBoostDb"), getDouble("detectorCutoffHz"), getDouble("silenceGateDbfs"), getDouble("lowThresholdDbfs"), getDouble("highThresholdDbfs"), getDouble("attackMs"), getDouble("releaseMs"))

private fun atomicWrite(file: File, bytes: ByteArray) { val atomic = AtomicFile(file); var stream: java.io.FileOutputStream? = null; try { val out = atomic.startWrite(); stream = out; out.write(bytes); atomic.finishWrite(out) } catch (e: Exception) { stream?.let(atomic::failWrite); throw e } }
