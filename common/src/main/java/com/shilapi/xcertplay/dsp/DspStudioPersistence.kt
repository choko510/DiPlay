package com.shilapi.xcertplay.dsp

import android.util.AtomicFile
import java.io.File
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject
import com.shilapi.xcertplay.media.dsp.*

internal sealed interface DspWorkspaceReadResult {
    data class Loaded(val workspace: DspStudioWorkspace) : DspWorkspaceReadResult
    data class FutureSchema(val schema: Int) : DspWorkspaceReadResult
    data object NotFound : DspWorkspaceReadResult
    data object Invalid : DspWorkspaceReadResult
}

internal enum class DspWorkspaceSaveResult { SAVED, STALE, FUTURE_SCHEMA_PRESERVED, INVALID, WRITE_FAILED }

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
            require(source == profile.id)
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

    fun referencesImpulseResponse(id: String): Boolean = when (val result = load()) {
        is DspWorkspaceReadResult.Loaded -> result.workspace.baseProfileSnapshot.convolver.impulseResponseId == id
        is DspWorkspaceReadResult.FutureSchema, DspWorkspaceReadResult.Invalid -> true
        DspWorkspaceReadResult.NotFound -> false
    }

    @Synchronized fun save(workspace: DspStudioWorkspace, updatedAtMs: Long = System.currentTimeMillis()): DspWorkspaceSaveResult {
        if (!validWorkspace(workspace) || updatedAtMs < 0L) return DspWorkspaceSaveResult.INVALID
        if (file.exists()) {
            if (!file.isFile || file.canonicalFile.parentFile != file.parentFile?.canonicalFile) {
                return DspWorkspaceSaveResult.INVALID
            }
            when (load()) {
                is DspWorkspaceReadResult.Loaded -> Unit
                is DspWorkspaceReadResult.FutureSchema -> return DspWorkspaceSaveResult.FUTURE_SCHEMA_PRESERVED
                DspWorkspaceReadResult.Invalid -> return DspWorkspaceSaveResult.INVALID
                DspWorkspaceReadResult.NotFound -> return DspWorkspaceSaveResult.WRITE_FAILED
            }
        }
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

    private fun validWorkspace(value: DspStudioWorkspace): Boolean = ID.matches(value.profileSourceId) && value.profileSourceId == value.baseProfileSnapshot.id && value.baseProfileSnapshot.hasValidEqCapacity() && value.selectedModulePresetIds.size <= MODULES.size && value.selectedModulePresetIds.all { (key, id) -> key in MODULES && PRESET_ID.matches(id) }
    private fun JSONObject.toMap(): Map<String, String> { require(length() <= MODULES.size); return keys().asSequence().associateWith { getString(it) } }
    companion object { const val SCHEMA = 1; private const val WORKSPACE_ID = "active_dsp_studio"; private const val MAX_BYTES = 1_048_576L; private val ID = Regex("^[A-Za-z0-9_-]{1,64}$"); private val PRESET_ID = ID; private val MODULES = setOf("peq", "bass", "seat", "advanced_ms", "compressor", "multiband", "dynamic_eq", "harmonic", "limiter", "convolver", "cabin", "smart_loudness", "dynamic_bass", "macros") }
}

internal enum class DspPresetStoreResult { SAVED, DELETED, NOT_FOUND, INVALID, FUTURE_SCHEMA_PRESERVED, WRITE_FAILED }
internal data class DspModulePreset(val id: String, val moduleKey: String, val name: String, val payload: JSONObject, val factory: Boolean = false)

internal class DspModulePresetRepository(
    private val filesDirectory: File,
    private val writer: (File, ByteArray) -> Unit = ::atomicWrite,
    private val irReferenceExists: (String) -> Boolean = { id -> DspImpulseResponseRepository(filesDirectory).load(id) != null },
) {
    val supportedModuleKeys: List<String> get() = MODULES.sorted()

    fun save(preset: DspModulePreset): DspPresetStoreResult {
        if (preset.factory || !validId(preset.id) || preset.moduleKey !in MODULES || preset.name.isBlank() || preset.name.length > 48 || !validPayload(preset.moduleKey, preset.payload) || preset.payload.toString().length > MAX_PAYLOAD_CHARS) return DspPresetStoreResult.INVALID
        if (!validReferences(preset.moduleKey, preset.payload)) return DspPresetStoreResult.INVALID
        val dir = moduleDirectory(preset.moduleKey) ?: return DspPresetStoreResult.INVALID
        val target = File(dir, "${preset.id}.json")
        if (target.exists() && (!target.isFile || target.canonicalFile.parentFile != dir.canonicalFile)) {
            return DspPresetStoreResult.INVALID
        }
        if (target.isFile && schema(target)?.let { it > SCHEMA } == true) return DspPresetStoreResult.FUTURE_SCHEMA_PRESERVED
        if ((dir.listFiles()?.count { it.extension == "json" } ?: 0) >= MAX_PRESETS && !target.exists()) return DspPresetStoreResult.INVALID
        if (!dir.exists() && !dir.mkdirs()) return DspPresetStoreResult.WRITE_FAILED
        return try { writer(target, JSONObject().put("schemaVersion", SCHEMA).put("id", preset.id).put("moduleKey", preset.moduleKey).put("name", preset.name).put("origin", "USER").put("payloadVersion", 1).put("payload", preset.payload).toString().toByteArray()); DspPresetStoreResult.SAVED } catch (_: Exception) { DspPresetStoreResult.WRITE_FAILED }
    }
    fun load(moduleKey: String, id: String): DspModulePreset? {
        if (moduleKey !in MODULES || !validId(id)) return null
        val directory = moduleDirectory(moduleKey) ?: return null
        val target = File(directory, "$id.json")
        if (target.exists() && (!target.isFile || target.canonicalFile.parentFile != directory.canonicalFile)) return null
        return try { if (target.length() > MAX_PAYLOAD_CHARS) return null; val j = JSONObject(AtomicFile(target).openRead().bufferedReader().use { it.readText() }); if (j.getInt("schemaVersion") != SCHEMA || j.getInt("payloadVersion") != 1 || j.getString("id") != id || j.getString("moduleKey") != moduleKey || j.optString("origin") != "USER") return null; val payload = j.getJSONObject("payload"); val name = j.getString("name"); if (name.isBlank() || name.length > 48 || !validPayload(moduleKey, payload) || !validReferences(moduleKey, payload)) null else DspModulePreset(id, moduleKey, name, payload) } catch (_: Exception) { null }
    }
    fun userPresets(moduleKey: String): List<DspModulePreset> {
        val directory = moduleDirectory(moduleKey) ?: return emptyList()
        return directory.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.extension == "json" }
            ?.mapNotNull { load(moduleKey, it.nameWithoutExtension) }
            ?.sortedBy(DspModulePreset::name)
            ?.toList()
            .orEmpty()
    }

    fun referencesImpulseResponse(id: String): Boolean {
        val directory = moduleDirectory("convolver") ?: return true
        if (!directory.exists()) return false
        val files = directory.listFiles() ?: return true
        val presetFiles = files
            .filter { it.name.endsWith(".json") || it.name.endsWith(".json.bak") }
            .map { file -> if (file.name.endsWith(".bak")) File(file.path.removeSuffix(".bak")) else file }
            .distinctBy(File::getAbsolutePath)
        return presetFiles.any { target ->
            val backup = File(target.path + ".bak")
            if ((target.exists() && (!target.isFile || target.canonicalFile.parentFile != directory.canonicalFile)) ||
                (backup.exists() && (!backup.isFile || backup.canonicalFile.parentFile != directory.canonicalFile)) ||
                target.length() > MAX_PAYLOAD_CHARS || backup.length() > MAX_PAYLOAD_CHARS
            ) {
                true
            } else {
                runCatching {
                    val json = JSONObject(AtomicFile(target).openRead().bufferedReader(StandardCharsets.UTF_8).use { it.readText() })
                    val version = json.optInt("schemaVersion", 0)
                    if (version != SCHEMA || json.optInt("payloadVersion", 0) != 1 ||
                        json.optString("moduleKey") != "convolver"
                    ) {
                        true
                    } else {
                        val payload = json.optJSONObject("payload") ?: return@runCatching true
                        payload.optString("impulseResponseId") == id
                    }
                }.getOrDefault(true)
            }
        }
    }

    fun deleteUserPreset(moduleKey: String, id: String): DspPresetStoreResult {
        if (moduleKey !in MODULES || !validId(id)) return DspPresetStoreResult.INVALID
        if (DspFactoryPresets.modulePresets().any { it.moduleKey == moduleKey && it.id == id }) {
            return DspPresetStoreResult.INVALID
        }
        val directory = moduleDirectory(moduleKey) ?: return DspPresetStoreResult.INVALID
        val target = File(directory, "$id.json")
        if (!target.exists()) return DspPresetStoreResult.NOT_FOUND
        if (!target.isFile || target.canonicalFile.parentFile != directory.canonicalFile) {
            return DspPresetStoreResult.INVALID
        }
        return try {
            if (target.length() > MAX_PAYLOAD_CHARS) return DspPresetStoreResult.FUTURE_SCHEMA_PRESERVED
            val json = JSONObject(AtomicFile(target).openRead().bufferedReader().use { it.readText() })
            val version = json.optInt("schemaVersion", 0)
            if (version > SCHEMA || json.optInt("payloadVersion", 0) != 1) {
                return DspPresetStoreResult.FUTURE_SCHEMA_PRESERVED
            }
            if (version != SCHEMA || json.optString("origin") != "USER" || json.optString("id") != id ||
                json.optString("moduleKey") != moduleKey
            ) return DspPresetStoreResult.INVALID
            AtomicFile(target).delete()
            if (!target.exists() && !File(target.path + ".bak").exists()) {
                DspPresetStoreResult.DELETED
            } else {
                DspPresetStoreResult.WRITE_FAILED
            }
        } catch (_: Exception) {
            DspPresetStoreResult.WRITE_FAILED
        }
    }

    fun saveCurrent(
        workspace: DspStudioWorkspace,
        moduleKey: String,
        id: String,
        name: String,
    ): DspPresetStoreResult {
        if (moduleKey !in MODULES || !workspace.baseProfileSnapshot.hasValidEqCapacity()) {
            return DspPresetStoreResult.INVALID
        }
        val profile = JSONObject(DspProfileRepository(filesDirectory).encodeDraftForSavedState(workspace.baseProfileSnapshot))
        val payload = when (moduleKey) {
            "peq" -> JSONObject().put("bands", profile.getJSONArray("peq"))
            "bass" -> profile.getJSONObject("bass")
            "seat" -> profile.getJSONObject("seatAlignment")
            "advanced_ms" -> profile.getJSONObject("advancedMs")
            "compressor" -> profile.getJSONObject("compressor")
            "multiband" -> profile.getJSONObject("multiband")
            "dynamic_eq" -> profile.getJSONObject("dynamicEq")
            "harmonic" -> profile.getJSONObject("harmonic")
            "limiter" -> profile.getJSONObject("limiter")
            "convolver" -> profile.getJSONObject("convolver")
            "cabin" -> profile.getJSONObject("cabinCorrection")
            "smart_loudness" -> workspace.smartLoudness.toJson()
            "dynamic_bass" -> workspace.dynamicBass.toJson()
            "macros" -> workspace.macros.toMacroJson()
            else -> return DspPresetStoreResult.INVALID
        }
        return save(DspModulePreset(id, moduleKey, name, payload))
    }

    fun applyTo(profile: DspAudioProfile, preset: DspModulePreset): DspAudioProfile? {
        if (preset.moduleKey !in MODULES || !validPayload(preset.moduleKey, preset.payload) || !validReferences(preset.moduleKey, preset.payload)) return null
        val p = preset.payload
        return try { when (preset.moduleKey) {
            "bass" -> profile.copy(bass = DspBassConfig(p.getBoolean("enabled"), p.getDouble("gainDb"), p.getDouble("frequencyHz")))
            "peq" -> profile.copy(eqBands = readEq(p.getJSONArray("bands")) ?: return null)
            "seat" -> profile.copy(seatAlignment = seat(p))
            "advanced_ms" -> profile.copy(advancedMs = advancedMs(p))
            "compressor" -> profile.copy(compressor = compressor(p))
            "multiband" -> profile.copy(multiband = multiband(p))
            "dynamic_eq" -> profile.copy(dynamicEq = dynamicEq(p))
            "harmonic" -> profile.copy(harmonic = harmonic(p))
            "limiter" -> profile.copy(limiter = limiter(p))
            "convolver" -> profile.copy(convolver = convolver(p))
            "cabin" -> profile.copy(cabinCorrection = cabin(p))
            else -> null
        }?.takeIf { it.hasValidEqCapacity() } } catch (_: Exception) { null }
    }
    fun applyTo(workspace: DspStudioWorkspace, preset: DspModulePreset): DspStudioWorkspace? {
        if (preset.moduleKey !in MODULES || !validPayload(preset.moduleKey, preset.payload) || !validReferences(preset.moduleKey, preset.payload)) return null
        val p = preset.payload
        return try { when (preset.moduleKey) {
            "macros" -> workspace.copy(macros = macroSettings(p))
            "smart_loudness" -> workspace.copy(smartLoudness = loudness(p))
            "dynamic_bass" -> workspace.copy(dynamicBass = dynamicBass(p))
            else -> applyTo(workspace.baseProfileSnapshot, preset)?.let { workspace.copy(baseProfileSnapshot = it) }
        } } catch (_: Exception) { null }
    }
    private fun validPayload(key: String, p: JSONObject): Boolean = try { when (key) {
        "bass" -> DspBassConfig(p.getBoolean("enabled"), p.getDouble("gainDb"), p.getDouble("frequencyHz"))
        "peq" -> requireNotNull(readEq(p.getJSONArray("bands")))
        "seat" -> seat(p)
        "advanced_ms" -> advancedMs(p)
        "compressor" -> compressor(p)
        "multiband" -> multiband(p)
        "dynamic_eq" -> dynamicEq(p)
        "harmonic" -> harmonic(p)
        "limiter" -> limiter(p)
        "convolver" -> convolver(p)
        "cabin" -> cabin(p)
        "smart_loudness" -> loudness(p)
        "dynamic_bass" -> dynamicBass(p)
        "macros" -> macroSettings(p)
        else -> return false
    }; true } catch (_: Exception) { false }
    private fun compressor(p: JSONObject) = DspCompressorConfig(p.getBoolean("enabled"), p.getDouble("thresholdDb"), p.getDouble("ratio"), p.getDouble("attackMs"), p.getDouble("releaseMs"), p.getDouble("kneeDb"), p.getDouble("makeupDb"))
    private fun limiter(p: JSONObject) = DspSafetyLimiterConfig(p.getBoolean("enabled"), p.getDouble("thresholdDb"), p.getDouble("releaseMs"))
    private fun seat(p: JSONObject) = DspSeatAlignmentConfig(p.getBoolean("enabled"), DspSeatPreset.valueOf(p.getString("preset")), DspSeatNearSpeaker.valueOf(p.getString("nearSpeaker")), p.getDouble("distanceDifferenceCm"), p.getDouble("leftTrimDb"), p.getDouble("rightTrimDb"), p.getBoolean("invertLeft"), p.getBoolean("invertRight"))
    private fun advancedMs(p: JSONObject) = DspAdvancedMsConfig(p.getBoolean("enabled"), p.getDouble("crossoverHz"), p.getDouble("centerFocusDb"), p.getDouble("midLowGainDb"), p.getDouble("midHighGainDb"), p.getDouble("sideLowGainDb"), p.getDouble("sideHighGainDb"), p.getDouble("lowBandWidth"), p.getDouble("highBandWidth"), p.getJSONObject("sideCompressor").let { DspMsSideCompressorConfig(it.getBoolean("enabled"), it.getDouble("thresholdDb"), it.getDouble("ratio"), it.getDouble("attackMs"), it.getDouble("releaseMs"), it.getDouble("kneeDb")) })
    private fun multiband(p: JSONObject) = DspMultibandConfig(p.getBoolean("enabled"), p.getDouble("lowMidCrossoverHz"), p.getDouble("midHighCrossoverHz"), compressor(p.getJSONObject("low")), compressor(p.getJSONObject("mid")), compressor(p.getJSONObject("high")))
    private fun dynamicEq(p: JSONObject): DspDynamicEqConfig { val bands = p.getJSONArray("bands"); require(bands.length() <= DspDynamicEqConfig.MAX_BANDS); return DspDynamicEqConfig(p.getBoolean("enabled"), List(bands.length()) { i -> val b = bands.getJSONObject(i); DspDynamicEqBandConfig(b.getBoolean("enabled"), b.getDouble("frequencyHz"), b.getDouble("q"), b.getDouble("thresholdDb"), b.getDouble("ratio"), b.getDouble("attackMs"), b.getDouble("releaseMs"), b.getDouble("maxBoostDb"), b.getDouble("maxCutDb"), DspDynamicEqMode.valueOf(b.getString("mode"))) }) }
    private fun harmonic(p: JSONObject) = DspHarmonicConfig(p.getBoolean("enabled"), DspHarmonicMode.valueOf(p.getString("mode")), DspHarmonicQuality.valueOf(p.getString("quality")), p.getBoolean("exciterEnabled"), p.getDouble("exciterAmount"), p.getDouble("exciterCutoffHz"), p.getBoolean("virtualBassEnabled"), p.getDouble("virtualBassAmount"), p.getDouble("virtualBassCutoffHz"), p.getBoolean("levelMatchEnabled"))
    private fun cabin(p: JSONObject): DspCabinCorrectionConfig { val notches = p.getJSONArray("notches"); require(notches.length() <= DspCabinCorrectionConfig.MAX_NOTCHES); val measurement = p.optJSONObject("measurement")?.let { DspCabinMeasurementSummary(it.getString("sourceSha256"), it.getInt("pointCount"), it.getDouble("minimumFrequencyHz"), it.getDouble("maximumFrequencyHz"), it.getDouble("noiseFloorDb"), it.getBoolean("clippingDetected")) }; return DspCabinCorrectionConfig(p.getBoolean("enabled"), measurement, List(notches.length()) { i -> val n = notches.getJSONObject(i); DspCabinCorrectionNotch(n.getBoolean("enabled"), n.getDouble("frequencyHz"), n.getDouble("gainDb"), n.getDouble("q")) }) }
    private fun macroSettings(p: JSONObject) = DspSoundMacroSettings(p.getBoolean("enabled"), p.getInt("bassImpact"), p.getInt("vocalPresence"), p.getInt("airDetail"), p.getInt("soundstageWidth"))
    private fun loudness(p: JSONObject) = p.toLoudness()
    private fun dynamicBass(p: JSONObject) = p.toDynamicBass()
    private fun convolver(p: JSONObject) = DspConvolverConfig(
        p.getBoolean("enabled"),
        p.optString("impulseResponseId").takeIf { it.isNotEmpty() && !p.isNull("impulseResponseId") },
        p.getDouble("wet"),
    )
    private fun validReferences(key: String, p: JSONObject): Boolean {
        if (key != "convolver" || !p.getBoolean("enabled")) return true
        val id = p.optString("impulseResponseId").takeIf { it.isNotEmpty() && !p.isNull("impulseResponseId") } ?: return false
        return irReferenceExists(id)
    }
    private fun moduleDirectory(moduleKey: String): File? {
        if (moduleKey !in MODULES) return null
        return runCatching {
            val appDirectory = filesDirectory.canonicalFile
            val moduleRoot = File(File(appDirectory, "dsp"), "modules").canonicalFile
            if (!moduleRoot.path.startsWith(appDirectory.path + File.separator)) return null
            val directory = File(moduleRoot, moduleKey)
            if (directory.canonicalFile.parentFile != moduleRoot) return null
            directory
        }.getOrNull()
    }
    private fun readEq(a: JSONArray): List<com.shilapi.xcertplay.media.dsp.DspEqBand>? = try { require(a.length() <= DspAudioProfile.MAX_EQ_BANDS); List(a.length()) { i -> val b = a.getJSONObject(i); com.shilapi.xcertplay.media.dsp.DspEqBand(com.shilapi.xcertplay.media.dsp.DspEqType.valueOf(b.getString("type")), b.getDouble("frequencyHz"), b.getDouble("gainDb"), b.getDouble("q"), b.getBoolean("enabled")) } } catch (_: Exception) { null }
    private fun schema(f: File): Int? = try { JSONObject(AtomicFile(f).openRead().bufferedReader().use { it.readText() }).optInt("schemaVersion", 0) } catch (_: Exception) { null }
    private fun validId(id: String) = ID.matches(id) && !id.contains("..")
    companion object { const val SCHEMA = 1; private const val MAX_PRESETS = 100; private const val MAX_PAYLOAD_CHARS = 65_536L; private val ID = Regex("^[A-Za-z0-9_-]{1,64}$"); private val MODULES = setOf("peq", "bass", "seat", "advanced_ms", "compressor", "multiband", "dynamic_eq", "harmonic", "limiter", "convolver", "cabin", "smart_loudness", "dynamic_bass", "macros") }
}

internal class DspMacroPresetRepository(private val directory: File, private val writer: (File, ByteArray) -> Unit = ::atomicWrite) {
    private val filesRoot = directory.canonicalFile

    fun load(id: String): Pair<String, DspSoundMacroSettings>? {
        val file = macroFile(id) ?: return null
        val backup = File(file.path + ".bak")
        return try {
            if (backup.exists() && (!backup.isFile || backup.canonicalFile.parentFile != file.parentFile)) return null
            if (file.length() > MAX_BYTES || backup.length() > MAX_BYTES) return null
            if (!file.isFile && !backup.isFile) return null
            val root = JSONObject(AtomicFile(file).openRead().bufferedReader().use { it.readText() })
            if (root.getInt("schemaVersion") != SCHEMA || root.getInt("payloadVersion") != PAYLOAD_VERSION || root.getString("id") != id) return null
            val payload = root.getJSONObject("payload")
            val name = root.getString("name").also { require(it.isNotBlank() && it.length <= 48) }
            name to DspSoundMacroSettings(payload.getBoolean("enabled"), payload.getInt("bassImpact"), payload.getInt("vocalPresence"), payload.getInt("airDetail"), payload.getInt("soundstageWidth"))
        } catch (_: Exception) { null }
    }
    fun save(id: String, name: String, settings: DspSoundMacroSettings): DspPresetStoreResult {
        if (macroFile(id) == null || name.isBlank() || name.length > 48) return DspPresetStoreResult.INVALID
        val file = requireNotNull(macroFile(id))
        val parent = file.parentFile ?: return DspPresetStoreResult.INVALID
        val backup = File(file.path + ".bak")
        if ((file.exists() && (!file.isFile || file.canonicalFile.parentFile != parent)) ||
            (backup.exists() && (!backup.isFile || backup.canonicalFile.parentFile != parent))
        ) return DspPresetStoreResult.INVALID
        if (file.length() > MAX_BYTES || backup.length() > MAX_BYTES) return DspPresetStoreResult.FUTURE_SCHEMA_PRESERVED
        if (file.exists() || backup.exists()) {
            val existing = runCatching {
                JSONObject(AtomicFile(file).openRead().bufferedReader().use { it.readText() })
            }.getOrElse { return DspPresetStoreResult.INVALID }
            if (existing.optInt("schemaVersion", 0) > SCHEMA || existing.optInt("payloadVersion", 0) > PAYLOAD_VERSION) {
                return DspPresetStoreResult.FUTURE_SCHEMA_PRESERVED
            }
            if (existing.optInt("schemaVersion", 0) != SCHEMA ||
                existing.optInt("payloadVersion", 0) != PAYLOAD_VERSION ||
                existing.optString("origin") != "USER" || existing.optString("id") != id
            ) return DspPresetStoreResult.INVALID
        }
        val presetFiles = if (parent.exists()) parent.listFiles() ?: return DspPresetStoreResult.WRITE_FAILED else emptyArray()
        if (presetFiles.count { it.extension == "json" } >= MAX_PRESETS && !file.exists() && !backup.exists()) {
            return DspPresetStoreResult.INVALID
        }
        if (!parent.exists() && !parent.mkdirs()) return DspPresetStoreResult.WRITE_FAILED
        val json = JSONObject()
            .put("schemaVersion", SCHEMA)
            .put("id", id)
            .put("name", name)
            .put("origin", "USER")
            .put("payloadVersion", PAYLOAD_VERSION)
            .put("payload", JSONObject().put("enabled", settings.enabled).put("bassImpact", settings.bassImpact)
                .put("vocalPresence", settings.vocalPresence).put("airDetail", settings.airDetail)
                .put("soundstageWidth", settings.soundstageWidth))
        return try {
            writer(file, json.toString().toByteArray())
            DspPresetStoreResult.SAVED
        } catch (_: Exception) {
            DspPresetStoreResult.WRITE_FAILED
        }
    }

    private fun macroFile(id: String): File? {
        if (!ID.matches(id) || id.contains("..")) return null
        return runCatching {
            val macroDirectory = File(filesRoot, "dsp/macros").canonicalFile
            if (!macroDirectory.path.startsWith(filesRoot.path + File.separator)) return null
            val file = File(macroDirectory, "$id.json").canonicalFile
            file.takeIf { it.parentFile == macroDirectory }
        }.getOrNull()
    }

    private companion object {
        const val SCHEMA = 1
        const val PAYLOAD_VERSION = 1
        const val MAX_PRESETS = 100
        const val MAX_BYTES = 16_384L
        val ID = Regex("^[A-Za-z0-9_-]{1,64}$")
    }
}

internal object DspFactoryPresets {
    val macroPresets: Map<String, DspSoundMacroSettings> = linkedMapOf(
        "macro_neutral_v1" to DspSoundMacroSettings(),
        "macro_vocal_v1" to DspSoundMacroSettings(true, vocalPresence = 75, airDetail = 60, soundstageWidth = 55),
        "macro_punch_v1" to DspSoundMacroSettings(true, bassImpact = 80, vocalPresence = 55, airDetail = 55, soundstageWidth = 55),
        "macro_clearwide_v1" to DspSoundMacroSettings(true, vocalPresence = 65, airDetail = 70, soundstageWidth = 80),
        "macro_warm_v1" to DspSoundMacroSettings(true, bassImpact = 65, airDetail = 40, soundstageWidth = 45),
        "macro_night_v1" to DspSoundMacroSettings(true, bassImpact = 40, vocalPresence = 60, airDetail = 40, soundstageWidth = 45),
    )

    fun modulePresets(): List<DspModulePreset> {
        val flat = DspProfilePresets.find("flat")!!
        val loudness = DspSmartLoudnessSettings()
        val dynamicBass = DspDynamicBassSettings()
        return listOf(
            DspModulePreset("factory-peq-flat", "peq", "Flat", JSONObject().put("bands", JSONArray().apply { flat.eqBands.forEach { put(JSONObject().put("type", it.type.name).put("frequencyHz", it.frequencyHz).put("gainDb", it.gainDb).put("q", it.q).put("enabled", it.enabled)) } }), true),
            DspModulePreset("factory-bass-neutral", "bass", "Neutral", JSONObject().put("enabled", false).put("gainDb", 0.0).put("frequencyHz", 80.0), true),
            DspModulePreset("factory-seat-center", "seat", "Center", JSONObject().put("enabled", false).put("preset", "CENTER").put("nearSpeaker", "CENTER").put("distanceDifferenceCm", 0.0).put("leftTrimDb", 0.0).put("rightTrimDb", 0.0).put("invertLeft", false).put("invertRight", false), true),
            DspModulePreset("factory-advanced-ms-neutral", "advanced_ms", "Neutral", JSONObject().put("enabled", false).put("crossoverHz", 250.0).put("centerFocusDb", 0.0).put("midLowGainDb", 0.0).put("midHighGainDb", 0.0).put("sideLowGainDb", 0.0).put("sideHighGainDb", 0.0).put("lowBandWidth", 1.0).put("highBandWidth", 1.0).put("sideCompressor", JSONObject().put("enabled", false).put("thresholdDb", -24.0).put("ratio", 2.0).put("attackMs", 10.0).put("releaseMs", 100.0).put("kneeDb", 6.0)), true),
            DspModulePreset("factory-compressor-off", "compressor", "Off", compressorJson(flat.compressor), true),
            DspModulePreset("factory-multiband-off", "multiband", "Off", JSONObject().put("enabled", false).put("lowMidCrossoverHz", 120.0).put("midHighCrossoverHz", 2500.0).put("low", compressorJson(DspCompressorConfig())).put("mid", compressorJson(DspCompressorConfig())).put("high", compressorJson(DspCompressorConfig())), true),
            DspModulePreset("factory-dynamic-eq-off", "dynamic_eq", "Off", JSONObject().put("enabled", false).put("bands", JSONArray().apply { listOf(63.0, 250.0, 1000.0, 4000.0, 10000.0).forEach { frequency -> put(JSONObject().put("enabled", false).put("frequencyHz", frequency).put("q", 1.0).put("thresholdDb", -24.0).put("ratio", 2.0).put("attackMs", 10.0).put("releaseMs", 100.0).put("maxBoostDb", 6.0).put("maxCutDb", 6.0).put("mode", "CUT")) } }), true),
            DspModulePreset("factory-harmonic-off", "harmonic", "Off", JSONObject().put("enabled", false).put("mode", "MIXED").put("quality", "MEDIUM").put("exciterEnabled", false).put("exciterAmount", .2).put("exciterCutoffHz", 6000.0).put("virtualBassEnabled", false).put("virtualBassAmount", .2).put("virtualBassCutoffHz", 120.0).put("levelMatchEnabled", true), true),
            DspModulePreset("factory-limiter-safe", "limiter", "Safety", JSONObject().put("enabled", true).put("thresholdDb", -1.0).put("releaseMs", 60.0), true),
            DspModulePreset("factory-convolver-off", "convolver", "Off", JSONObject().put("enabled", false).put("impulseResponseId", JSONObject.NULL).put("wet", 1.0), true),
            DspModulePreset("factory-cabin-off", "cabin", "Off", JSONObject().put("enabled", false).put("measurement", JSONObject.NULL).put("notches", JSONArray()), true),
            DspModulePreset("factory-loudness-off", "smart_loudness", "Off", loudness.toJson(), true),
            DspModulePreset("factory-dynamic-bass-off", "dynamic_bass", "Off", dynamicBass.toJson(), true),
        ) + macroPresets.map { (id, settings) ->
            val name = when (id) {
                "macro_neutral_v1" -> "Neutral"
                "macro_vocal_v1" -> "Vocal"
                "macro_punch_v1" -> "Punch"
                "macro_clearwide_v1" -> "Clear Wide"
                "macro_warm_v1" -> "Warm"
                else -> "Night"
            }
            DspModulePreset(id, "macros", name, settings.toMacroJson(), true)
        }
    }

    fun neutralModulePreset(moduleKey: String): DspModulePreset? {
        val id = when (moduleKey) {
            "peq" -> "factory-peq-flat"
            "bass" -> "factory-bass-neutral"
            "seat" -> "factory-seat-center"
            "advanced_ms" -> "factory-advanced-ms-neutral"
            "compressor" -> "factory-compressor-off"
            "multiband" -> "factory-multiband-off"
            "dynamic_eq" -> "factory-dynamic-eq-off"
            "harmonic" -> "factory-harmonic-off"
            "limiter" -> "factory-limiter-safe"
            "convolver" -> "factory-convolver-off"
            "cabin" -> "factory-cabin-off"
            "smart_loudness" -> "factory-loudness-off"
            "dynamic_bass" -> "factory-dynamic-bass-off"
            "macros" -> "macro_neutral_v1"
            else -> return null
        }
        return modulePresets().firstOrNull { it.moduleKey == moduleKey && it.id == id }
    }
}

private fun compressorJson(c: DspCompressorConfig) = JSONObject().put("enabled", c.enabled).put("thresholdDb", c.thresholdDb).put("ratio", c.ratio).put("attackMs", c.attackMs).put("releaseMs", c.releaseMs).put("kneeDb", c.kneeDb).put("makeupDb", c.makeupDb)
private fun DspSoundMacroSettings.toMacroJson() = JSONObject().put("enabled", enabled).put("bassImpact", bassImpact).put("vocalPresence", vocalPresence).put("airDetail", airDetail).put("soundstageWidth", soundstageWidth)

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

internal class DspStudioEditTransaction internal constructor(val id: Long)

internal data class DspStudioComparisonSnapshot(
    val scopeKey: String,
    val a: DspStudioWorkspace,
    val b: DspStudioWorkspace,
)

internal enum class DspStudioComparisonSide(val label: String) {
    DRAFT("Draft"),
    A("A"),
    B("B"),
}

internal class DspStudioSessionCoordinator(
    initial: DspStudioWorkspace,
    private val repository: DspStudioWorkspaceRepository,
) {
    private var committed = initial.deepCopy()
    private var draft = initial.deepCopy()
    private val undo = mutableListOf<DspStudioWorkspace>()
    private val redo = mutableListOf<DspStudioWorkspace>()
    private var generation = 0L
    private var nextTransactionId = 0L
    private var activeTransaction: ActiveTransaction? = null
    private var comparisonSnapshot: DspStudioComparisonSnapshot? = null

    var comparisonSide: DspStudioComparisonSide = DspStudioComparisonSide.DRAFT
        private set

    var preview: DspStudioWorkspace = draft.deepCopy()
        private set
    var isDirty: Boolean = false
        private set

    val canUndo: Boolean get() = undo.isNotEmpty()
    val canRedo: Boolean get() = redo.isNotEmpty()

    @Synchronized fun beginEditTransaction(): DspStudioEditTransaction? {
        if (activeTransaction != null) return null
        nextTransactionId += 1
        val transaction = DspStudioEditTransaction(nextTransactionId)
        activeTransaction = ActiveTransaction(transaction, draft.deepCopy())
        return transaction
    }

    @Synchronized fun edit(transform: (DspStudioWorkspace) -> DspStudioWorkspace): Long {
        finishActiveTransaction()
        return updateDraft(transform, recordUndo = true)
    }

    @Synchronized fun edit(
        transaction: DspStudioEditTransaction,
        transform: (DspStudioWorkspace) -> DspStudioWorkspace,
    ): Long? {
        if (activeTransaction?.token != transaction) return null
        return updateDraft(transform, recordUndo = false)
    }

    @Synchronized fun commitEditTransaction(transaction: DspStudioEditTransaction): Boolean {
        val active = activeTransaction?.takeIf { it.token == transaction } ?: return false
        activeTransaction = null
        if (draft != active.before) {
            pushHistory(undo, active.before)
            redo.clear()
        }
        return true
    }

    @Synchronized fun cancelEditTransaction(transaction: DspStudioEditTransaction): Boolean {
        val active = activeTransaction?.takeIf { it.token == transaction } ?: return false
        activeTransaction = null
        if (draft != active.before) {
            draft = active.before.deepCopy()
            updatePreviewAndDirty()
            generation++
        }
        return true
    }

    @Synchronized fun undo(): Boolean {
        finishActiveTransaction()
        if (undo.isEmpty()) return false
        resetComparison()
        pushHistory(redo, draft)
        draft = undo.removeAt(undo.lastIndex).deepCopy()
        updatePreviewAndDirty()
        generation++
        return true
    }

    @Synchronized fun redo(): Boolean {
        finishActiveTransaction()
        if (redo.isEmpty()) return false
        resetComparison()
        pushHistory(undo, draft)
        draft = redo.removeAt(redo.lastIndex).deepCopy()
        updatePreviewAndDirty()
        generation++
        return true
    }

    @Synchronized fun cancelPreview(): Long {
        resetComparison()
        activeTransaction = null
        draft = committed.deepCopy()
        undo.clear()
        redo.clear()
        updatePreviewAndDirty()
        generation++
        return generation
    }

    @Synchronized fun compareA(): DspStudioWorkspace = committed.deepCopy()

    @Synchronized fun compareB(): DspStudioWorkspace = draft.deepCopy()

    @Synchronized fun compareWorkspace(): DspStudioComparisonSnapshot = DspStudioComparisonSnapshot(
        scopeKey = WORKSPACE_SCOPE,
        a = committed.deepCopy(),
        b = draft.deepCopy(),
    )

    @Synchronized fun compareModule(moduleKey: String): DspStudioComparisonSnapshot? {
        val currentDraft = draft.deepCopy()
        val fromCommitted = currentDraft.copyModuleValuesFrom(committed, moduleKey)?.deepCopy() ?: return null
        return DspStudioComparisonSnapshot(
            scopeKey = moduleKey,
            a = fromCommitted,
            b = currentDraft,
        )
    }

    @Synchronized fun beginModuleComparison(moduleKey: String): DspStudioComparisonSnapshot? =
        compareModule(moduleKey)?.also { snapshot ->
            comparisonSnapshot = snapshot
            comparisonSide = DspStudioComparisonSide.DRAFT
        }

    @Synchronized fun selectComparisonSide(side: DspStudioComparisonSide): DspStudioWorkspace? {
        if (side == DspStudioComparisonSide.DRAFT) {
            resetComparison()
            return draft.deepCopy()
        }
        val snapshot = comparisonSnapshot ?: return null
        comparisonSide = side
        return when (side) {
            DspStudioComparisonSide.A -> snapshot.a.deepCopy()
            DspStudioComparisonSide.B -> snapshot.b.deepCopy()
            DspStudioComparisonSide.DRAFT -> draft.deepCopy()
        }
    }

    @Synchronized fun resetComparison() {
        comparisonSnapshot = null
        comparisonSide = DspStudioComparisonSide.DRAFT
    }

    @Synchronized fun clearSelectedPresetReference(moduleKey: String, presetId: String): Boolean {
        resetComparison()
        fun clear(workspace: DspStudioWorkspace): DspStudioWorkspace =
            if (workspace.selectedModulePresetIds[moduleKey] == presetId) {
                workspace.copy(selectedModulePresetIds = workspace.selectedModulePresetIds - moduleKey)
            } else {
                workspace
            }

        val previousCommitted = committed
        committed = clear(committed)
        var changed = committed != previousCommitted
        val nextDraft = clear(draft)
        if (nextDraft != draft) {
            draft = nextDraft
            changed = true
        }
        for (index in undo.indices) undo[index] = clear(undo[index])
        for (index in redo.indices) redo[index] = clear(redo[index])
        activeTransaction = activeTransaction?.let { ActiveTransaction(it.token, clear(it.before)) }
        if (changed) {
            updatePreviewAndDirty()
            generation++
        }
        return changed
    }

    @Synchronized fun save(): DspWorkspaceSaveResult {
        finishActiveTransaction()
        val result = repository.save(draft)
        if (result == DspWorkspaceSaveResult.SAVED) {
            resetComparison()
            committed = draft.deepCopy()
            updatePreviewAndDirty()
            undo.clear()
            redo.clear()
        }
        return result
    }

    @Synchronized fun commitSaved(workspace: DspStudioWorkspace) {
        resetComparison()
        finishActiveTransaction()
        committed = workspace.deepCopy()
        if (draft == committed) {
            draft = committed.deepCopy()
            undo.clear()
            redo.clear()
        } else {
            val savedUndoIndex = undo.indexOfLast { it == committed }
            if (savedUndoIndex >= 0) {
                repeat(savedUndoIndex) { undo.removeAt(0) }
            } else if (redo.none { it == committed }) {
                undo.clear()
                pushHistory(undo, committed)
            }
        }
        updatePreviewAndDirty()
        generation++
    }

    @Synchronized fun generation(): Long = generation

    @Synchronized fun referencesImpulseResponse(id: String): Boolean =
        referencesImpulseResponseLocked(id)

    @Synchronized fun deleteImpulseResponseIfUnreferenced(id: String, delete: () -> Boolean): Boolean {
        if (referencesImpulseResponseLocked(id)) return false
        return delete()
    }

    private fun referencesImpulseResponseLocked(id: String): Boolean {
        fun DspStudioWorkspace.references(): Boolean = baseProfileSnapshot.convolver.impulseResponseId == id
        return committed.references() || draft.references() || undo.any { it.references() } ||
            redo.any { it.references() } || activeTransaction?.before?.references() == true ||
            comparisonSnapshot?.let { it.a.references() || it.b.references() } == true
    }

    private fun updateDraft(
        transform: (DspStudioWorkspace) -> DspStudioWorkspace,
        recordUndo: Boolean,
    ): Long {
        val candidate = invalidateChangedPresetSelections(draft, transform(draft)).deepCopy()
        if (candidate == draft) return generation
        resetComparison()
        if (recordUndo) {
            pushHistory(undo, draft)
            redo.clear()
        }
        draft = candidate
        updatePreviewAndDirty()
        generation++
        return generation
    }

    private fun invalidateChangedPresetSelections(
        previous: DspStudioWorkspace,
        candidate: DspStudioWorkspace,
    ): DspStudioWorkspace {
        val before = previous.baseProfileSnapshot
        val after = candidate.baseProfileSnapshot
        val changedModules = buildSet {
            if (before.eqBands != after.eqBands) add("peq")
            if (before.bass != after.bass) add("bass")
            if (before.seatAlignment != after.seatAlignment) add("seat")
            if (before.advancedMs != after.advancedMs) add("advanced_ms")
            if (before.compressor != after.compressor) add("compressor")
            if (before.multiband != after.multiband) add("multiband")
            if (before.dynamicEq != after.dynamicEq) add("dynamic_eq")
            if (before.harmonic != after.harmonic) add("harmonic")
            if (before.limiter != after.limiter) add("limiter")
            if (before.convolver != after.convolver) add("convolver")
            if (before.cabinCorrection != after.cabinCorrection) add("cabin")
            if (previous.macros != candidate.macros) add("macros")
            if (previous.smartLoudness != candidate.smartLoudness) add("smart_loudness")
            if (previous.dynamicBass != candidate.dynamicBass) add("dynamic_bass")
        }
        if (changedModules.isEmpty()) return candidate
        val selected = candidate.selectedModulePresetIds.toMutableMap()
        changedModules.forEach { moduleKey ->
            if (selected[moduleKey] == previous.selectedModulePresetIds[moduleKey]) {
                selected.remove(moduleKey)
            }
        }
        return candidate.copy(selectedModulePresetIds = selected)
    }

    private fun finishActiveTransaction() {
        val active = activeTransaction ?: return
        commitEditTransaction(active.token)
    }

    private fun updatePreviewAndDirty() {
        isDirty = draft != committed
        preview = draft.deepCopy()
    }

    private fun pushHistory(history: MutableList<DspStudioWorkspace>, workspace: DspStudioWorkspace) {
        history.add(workspace.deepCopy())
        if (history.size > MAX_HISTORY) history.removeAt(0)
    }

    private data class ActiveTransaction(
        val token: DspStudioEditTransaction,
        val before: DspStudioWorkspace,
    )

    private companion object {
        const val MAX_HISTORY = 100
        const val WORKSPACE_SCOPE = "workspace"
    }
}

private fun DspStudioWorkspace.copyModuleValuesFrom(
    source: DspStudioWorkspace,
    moduleKey: String,
): DspStudioWorkspace? {
    val targetProfile = baseProfileSnapshot
    val sourceProfile = source.baseProfileSnapshot
    val merged = when (moduleKey) {
        "peq" -> copy(baseProfileSnapshot = targetProfile.copy(eqBands = sourceProfile.eqBands))
        "bass" -> copy(baseProfileSnapshot = targetProfile.copy(bass = sourceProfile.bass))
        "seat" -> copy(baseProfileSnapshot = targetProfile.copy(seatAlignment = sourceProfile.seatAlignment))
        "advanced_ms" -> copy(baseProfileSnapshot = targetProfile.copy(advancedMs = sourceProfile.advancedMs))
        "compressor" -> copy(baseProfileSnapshot = targetProfile.copy(compressor = sourceProfile.compressor))
        "multiband" -> copy(baseProfileSnapshot = targetProfile.copy(multiband = sourceProfile.multiband))
        "dynamic_eq" -> copy(baseProfileSnapshot = targetProfile.copy(dynamicEq = sourceProfile.dynamicEq))
        "harmonic" -> copy(baseProfileSnapshot = targetProfile.copy(harmonic = sourceProfile.harmonic))
        "limiter" -> copy(baseProfileSnapshot = targetProfile.copy(limiter = sourceProfile.limiter))
        "convolver" -> copy(baseProfileSnapshot = targetProfile.copy(convolver = sourceProfile.convolver))
        "cabin" -> copy(baseProfileSnapshot = targetProfile.copy(cabinCorrection = sourceProfile.cabinCorrection))
        "macros" -> copy(macros = source.macros)
        "smart_loudness" -> copy(smartLoudness = source.smartLoudness)
        "dynamic_bass" -> copy(dynamicBass = source.dynamicBass)
        else -> return null
    }
    val presetIds = selectedModulePresetIds.toMutableMap()
    source.selectedModulePresetIds[moduleKey]?.let { presetIds[moduleKey] = it } ?: presetIds.remove(moduleKey)
    return merged.copy(selectedModulePresetIds = presetIds)
}

private fun DspSmartLoudnessSettings.toJson() = JSONObject().put("mode", mode.name).put("strength", strength).put("maxLowBoostDb", maxLowBoostDb).put("maxHighBoostDb", maxHighBoostDb).put("calibration", calibration?.let { JSONObject().put("routeToken", it.routeToken).put("quietVolumeFraction", it.quietVolumeFraction).put("referenceVolumeFraction", it.referenceVolumeFraction) }).put("quietListeningLevel", quietListeningLevel).put("referenceListeningLevel", referenceListeningLevel).put("manualListeningLevel", manualListeningLevel).put("fixedAmount", fixedAmount)
private fun JSONObject.toLoudness() = DspSmartLoudnessSettings(DspSmartLoudnessMode.valueOf(getString("mode")), getDouble("strength"), getDouble("maxLowBoostDb"), getDouble("maxHighBoostDb"), optJSONObject("calibration")?.let { DspLoudnessCalibration(it.getString("routeToken"), it.getDouble("quietVolumeFraction"), it.getDouble("referenceVolumeFraction")) }, getDouble("quietListeningLevel"), getDouble("referenceListeningLevel"), getDouble("manualListeningLevel"), getDouble("fixedAmount"))
private fun DspDynamicBassSettings.toJson() = JSONObject().put("enabled", enabled).put("strength", strength).put("maxBoostDb", maxBoostDb).put("detectorCutoffHz", detectorCutoffHz).put("silenceGateDbfs", silenceGateDbfs).put("lowThresholdDbfs", lowThresholdDbfs).put("highThresholdDbfs", highThresholdDbfs).put("attackMs", attackMs).put("releaseMs", releaseMs)
private fun JSONObject.toDynamicBass() = DspDynamicBassSettings(getBoolean("enabled"), getDouble("strength"), getDouble("maxBoostDb"), getDouble("detectorCutoffHz"), getDouble("silenceGateDbfs"), getDouble("lowThresholdDbfs"), getDouble("highThresholdDbfs"), getDouble("attackMs"), getDouble("releaseMs"))

private fun atomicWrite(file: File, bytes: ByteArray) { val atomic = AtomicFile(file); var stream: java.io.FileOutputStream? = null; try { val out = atomic.startWrite(); stream = out; out.write(bytes); atomic.finishWrite(out) } catch (e: Exception) { stream?.let(atomic::failWrite); throw e } }
