package com.shilapi.xcertplay.dsp

import android.util.AtomicFile
import com.shilapi.xcertplay.media.dsp.DspBassConfig
import com.shilapi.xcertplay.media.dsp.DspCompressorConfig
import com.shilapi.xcertplay.media.dsp.DspConvolverConfig
import com.shilapi.xcertplay.media.dsp.DspEqBand
import com.shilapi.xcertplay.media.dsp.DspEqType
import com.shilapi.xcertplay.media.dsp.DspMonoBassConfig
import com.shilapi.xcertplay.media.dsp.DspMultibandConfig
import com.shilapi.xcertplay.media.dsp.DspSafetyLimiterConfig
import java.io.File
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject

internal sealed interface DspProfileReadResult {
    data class Loaded(val profile: DspAudioProfile, val migratedFromVersion: Int? = null) : DspProfileReadResult
    data class FutureSchema(val schemaVersion: Int) : DspProfileReadResult
    data object NotFound : DspProfileReadResult
    data object Invalid : DspProfileReadResult
}

internal enum class DspProfileSaveResult {
    SAVED,
    INVALID_PROFILE_ID,
    FUTURE_SCHEMA_PRESERVED,
    WRITE_FAILED,
}

internal class DspProfileRepository(
    filesDirectory: File,
    private val atomicWriter: (File, ByteArray) -> Unit = ::writeProfileAtomically,
) {
    internal val profilesDirectory = File(File(filesDirectory, PROFILE_DIRECTORY), "profiles")

    fun load(profileId: String): DspProfileReadResult {
        val file = profileFile(profileId) ?: return DspProfileReadResult.Invalid
        if (!file.isFile) return DspProfileReadResult.NotFound
        return try {
            val json = AtomicFile(file).openRead().bufferedReader(StandardCharsets.UTF_8).use { reader ->
                JSONObject(reader.readText())
            }
            val schemaVersion = json.optInt(KEY_SCHEMA_VERSION, LEGACY_SCHEMA_VERSION)
            if (schemaVersion > CURRENT_SCHEMA_VERSION) {
                DspProfileReadResult.FutureSchema(schemaVersion)
            } else if (schemaVersion < LEGACY_SCHEMA_VERSION) {
                DspProfileReadResult.Invalid
            } else {
                val profile = decode(profileId, json, schemaVersion)
                DspProfileReadResult.Loaded(
                    profile = profile,
                    migratedFromVersion = if (schemaVersion < CURRENT_SCHEMA_VERSION) schemaVersion else null,
                )
            }
        } catch (_: Exception) {
            DspProfileReadResult.Invalid
        }
    }

    @Synchronized
    fun save(profile: DspAudioProfile): DspProfileSaveResult {
        if (!isValidProfileId(profile.id) || profile.id !in CUSTOM_PROFILE_IDS) {
            return DspProfileSaveResult.INVALID_PROFILE_ID
        }
        val file = profileFile(profile.id) ?: return DspProfileSaveResult.INVALID_PROFILE_ID
        if (file.isFile && schemaVersion(file)?.let { it > CURRENT_SCHEMA_VERSION } == true) {
            return DspProfileSaveResult.FUTURE_SCHEMA_PRESERVED
        }
        if (!profilesDirectory.exists() && !profilesDirectory.mkdirs()) return DspProfileSaveResult.WRITE_FAILED

        return try {
            atomicWriter(file, encode(profile).toString().toByteArray(StandardCharsets.UTF_8))
            DspProfileSaveResult.SAVED
        } catch (_: Exception) {
            DspProfileSaveResult.WRITE_FAILED
        }
    }

    fun customProfileIds(): List<String> = profilesDirectory.listFiles()
        .orEmpty()
        .asSequence()
        .filter { it.isFile && it.extension == PROFILE_EXTENSION }
        .map { it.nameWithoutExtension }
        .filter(::isValidProfileId)
        .distinct()
        .sorted()
        .toList()

    internal fun profileFileForTesting(profileId: String): File? = profileFile(profileId)

    private fun profileFile(profileId: String): File? {
        if (!isValidProfileId(profileId)) return null
        val directory = profilesDirectory.canonicalFile
        val file = File(directory, "$profileId.$PROFILE_EXTENSION").canonicalFile
        return file.takeIf { it.parentFile == directory }
    }

    private fun schemaVersion(file: File): Int? = try {
        AtomicFile(file).openRead().bufferedReader(StandardCharsets.UTF_8).use { reader ->
            JSONObject(reader.readText()).optInt(KEY_SCHEMA_VERSION, LEGACY_SCHEMA_VERSION)
        }
    } catch (_: Exception) {
        null
    }

    private fun decode(profileId: String, json: JSONObject, schemaVersion: Int): DspAudioProfile {
        val storedId = json.optString(KEY_ID, profileId)
        require(storedId == profileId)
        val legacy = schemaVersion == LEGACY_SCHEMA_VERSION
        val bands = readBands(json.optJSONArray(if (legacy) "eq" else "peq"))
        val bassJson = json.optJSONObject("bass") ?: JSONObject()
        val compressorJson = json.optJSONObject("compressor") ?: JSONObject()
        val stereoJson = json.optJSONObject("stereo") ?: JSONObject()
        val convolverJson = json.optJSONObject("convolver") ?: JSONObject()
        val limiterJson = json.optJSONObject("limiter") ?: JSONObject()
        val multibandJson = json.optJSONObject("multiband") ?: JSONObject()
        val monoBassJson = stereoJson.optJSONObject("monoBass") ?: JSONObject()
        fun readCompressor(values: JSONObject) = DspCompressorConfig(
            enabled = values.optBoolean("enabled", false),
            thresholdDb = values.readFiniteDouble("thresholdDb", -20.0),
            ratio = values.readFiniteDouble("ratio", 4.0),
            attackMs = values.readFiniteDouble("attackMs", 10.0),
            releaseMs = values.readFiniteDouble("releaseMs", 100.0),
            kneeDb = values.readFiniteDouble("kneeDb", 0.0),
            makeupDb = values.readFiniteDouble("makeupDb", 0.0),
        )
        return DspAudioProfile(
            id = profileId,
            name = json.optString(KEY_NAME, profileId),
            enabled = json.optBoolean("enabled", true),
            preampDb = json.readFiniteDouble(if (legacy) "preamp" else "preampDb", 0.0),
            autoHeadroomEnabled = json.optBoolean("autoHeadroom", true),
            autoHeadroomMarginDb = json.readFiniteDouble("autoHeadroomMarginDb", 1.0),
            eqBands = bands,
            bass = DspBassConfig(
                enabled = bassJson.optBoolean("enabled", false),
                gainDb = bassJson.readFiniteDouble("gainDb", 0.0),
                frequencyHz = bassJson.readFiniteDouble("frequencyHz", 80.0),
            ),
            compressor = readCompressor(compressorJson),
            stereoWidth = stereoJson.readFiniteDouble("width", 1.0),
            monoBass = DspMonoBassConfig(
                enabled = monoBassJson.optBoolean("enabled", false),
                cutoffHz = monoBassJson.optInt("cutoffHz", 120),
            ),
            convolver = DspConvolverConfig(
                enabled = convolverJson.optBoolean("enabled", false),
                impulseResponseId = convolverJson.optString("impulseResponseId").takeIf { it.isNotEmpty() },
                wet = convolverJson.readFiniteDouble("wet", 1.0),
            ),
            multiband = DspMultibandConfig(
                enabled = multibandJson.optBoolean("enabled", false),
                lowMidCrossoverHz = multibandJson.readFiniteDouble(
                    "lowMidCrossoverHz",
                    DspMultibandConfig.DEFAULT_LOW_MID_CROSSOVER_HZ,
                ),
                midHighCrossoverHz = multibandJson.readFiniteDouble(
                    "midHighCrossoverHz",
                    DspMultibandConfig.DEFAULT_MID_HIGH_CROSSOVER_HZ,
                ),
                low = readCompressor(multibandJson.optJSONObject("low") ?: JSONObject()),
                mid = readCompressor(multibandJson.optJSONObject("mid") ?: JSONObject()),
                high = readCompressor(multibandJson.optJSONObject("high") ?: JSONObject()),
            ),
            limiter = DspSafetyLimiterConfig(
                enabled = limiterJson.optBoolean("enabled", true),
                thresholdDb = limiterJson.readFiniteDouble("thresholdDb", -1.0),
                releaseMs = limiterJson.readFiniteDouble("releaseMs", 60.0),
            ),
        )
    }

    private fun readBands(array: JSONArray?): List<DspEqBand> {
        if (array == null) return DspProfilePresets.flatBands()
        require(array.length() <= DspAudioProfile.MAX_EQ_BANDS)
        return List(array.length()) { index ->
            val json = array.getJSONObject(index)
            DspEqBand(
                type = DspEqType.valueOf(json.optString("type", DspEqType.PEAK.name)),
                frequencyHz = json.readFiniteDouble("frequencyHz", DEFAULT_BAND_CENTERS_HZ[index].toDouble()),
                gainDb = json.readFiniteDouble("gainDb", 0.0),
                q = json.readFiniteDouble("q", 1.0),
                enabled = json.optBoolean("enabled", true),
            )
        }
    }

    private fun encode(profile: DspAudioProfile): JSONObject {
        val eq = JSONArray()
        profile.eqBands.forEach { band ->
            eq.put(
                JSONObject()
                    .put("enabled", band.enabled)
                    .put("type", band.type.name)
                    .put("frequencyHz", band.frequencyHz)
                    .put("gainDb", band.gainDb)
                    .put("q", band.q),
            )
        }
        return JSONObject()
            .put(KEY_SCHEMA_VERSION, CURRENT_SCHEMA_VERSION)
            .put(KEY_ID, profile.id)
            .put(KEY_NAME, profile.name)
            .put("enabled", profile.enabled)
            .put("preampDb", profile.preampDb)
            .put("autoHeadroom", profile.autoHeadroomEnabled)
            .put("autoHeadroomMarginDb", profile.autoHeadroomMarginDb)
            .put("peq", eq)
            .put(
                "bass",
                JSONObject()
                    .put("enabled", profile.bass.enabled)
                    .put("gainDb", profile.bass.gainDb)
                    .put("frequencyHz", profile.bass.frequencyHz),
            )
            .put(
                "compressor",
                JSONObject()
                    .put("enabled", profile.compressor.enabled)
                    .put("thresholdDb", profile.compressor.thresholdDb)
                    .put("ratio", profile.compressor.ratio)
                    .put("attackMs", profile.compressor.attackMs)
                    .put("releaseMs", profile.compressor.releaseMs)
                    .put("kneeDb", profile.compressor.kneeDb)
                    .put("makeupDb", profile.compressor.makeupDb),
            )
            .put(
                "stereo",
                JSONObject()
                    .put("width", profile.stereoWidth)
                    .put(
                        "monoBass",
                        JSONObject()
                            .put("enabled", profile.monoBass.enabled)
                            .put("cutoffHz", profile.monoBass.cutoffHz),
                    ),
            )
            .put(
                "convolver",
                JSONObject()
                    .put("enabled", profile.convolver.enabled)
                    .put("impulseResponseId", profile.convolver.impulseResponseId)
                    .put("wet", profile.convolver.wet),
            )
            .put(
                "multiband",
                JSONObject()
                    .put("enabled", profile.multiband.enabled)
                    .put("lowMidCrossoverHz", profile.multiband.lowMidCrossoverHz)
                    .put("midHighCrossoverHz", profile.multiband.midHighCrossoverHz)
                    .put("low", profile.multiband.low.toJsonObject())
                    .put("mid", profile.multiband.mid.toJsonObject())
                    .put("high", profile.multiband.high.toJsonObject()),
            )
            .put(
                "limiter",
                JSONObject()
                    .put("enabled", profile.limiter.enabled)
                    .put("thresholdDb", profile.limiter.thresholdDb)
                    .put("releaseMs", profile.limiter.releaseMs),
            )
    }

    private fun isValidProfileId(id: String): Boolean = ID_PATTERN.matches(id) &&
        !id.contains("..") && !id.contains('/') && !id.contains('\\') && !id.contains('\u0000')

    private fun JSONObject.readFiniteDouble(key: String, default: Double): Double {
        val value = optDouble(key, default)
        require(value.isFinite())
        return value
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 2
        private const val LEGACY_SCHEMA_VERSION = 0
        private const val PROFILE_DIRECTORY = "dsp"
        private const val PROFILE_EXTENSION = "json"
        private const val KEY_SCHEMA_VERSION = "schemaVersion"
        private const val KEY_ID = "id"
        private const val KEY_NAME = "name"
        private val ID_PATTERN = Regex("^[A-Za-z0-9_-]{1,64}$")
        private val CUSTOM_PROFILE_IDS = setOf("custom1", "custom2")
        private val DEFAULT_BAND_CENTERS_HZ = listOf(
            31, 63, 125, 250, 500, 1_000, 2_000, 3_000, 4_000, 6_000, 8_000, 10_000, 12_000, 14_000, 16_000,
        )
    }
}

private fun DspCompressorConfig.toJsonObject(): JSONObject = JSONObject()
    .put("enabled", enabled)
    .put("thresholdDb", thresholdDb)
    .put("ratio", ratio)
    .put("attackMs", attackMs)
    .put("releaseMs", releaseMs)
    .put("kneeDb", kneeDb)
    .put("makeupDb", makeupDb)

private fun writeProfileAtomically(file: File, contents: ByteArray) {
    val atomicFile = AtomicFile(file)
    var stream: java.io.FileOutputStream? = null
    try {
        val output = atomicFile.startWrite()
        stream = output
        output.write(contents)
        atomicFile.finishWrite(output)
    } catch (exception: Exception) {
        stream?.let(atomicFile::failWrite)
        throw exception
    }
}
