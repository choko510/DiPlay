package com.shilapi.xcertplay.dsp

import android.util.AtomicFile
import com.shilapi.xcertplay.media.dsp.DspBassConfig
import com.shilapi.xcertplay.media.dsp.DspCompressorConfig
import com.shilapi.xcertplay.media.dsp.DspConvolverConfig
import com.shilapi.xcertplay.media.dsp.DspAdvancedMsConfig
import com.shilapi.xcertplay.media.dsp.DspCabinCorrectionConfig
import com.shilapi.xcertplay.media.dsp.DspCabinCorrectionNotch
import com.shilapi.xcertplay.media.dsp.DspCabinMeasurementSummary
import com.shilapi.xcertplay.media.dsp.DspHarmonicConfig
import com.shilapi.xcertplay.media.dsp.DspHarmonicMode
import com.shilapi.xcertplay.media.dsp.DspHarmonicQuality
import com.shilapi.xcertplay.media.dsp.DspDynamicEqBandConfig
import com.shilapi.xcertplay.media.dsp.DspDynamicEqConfig
import com.shilapi.xcertplay.media.dsp.DspDynamicEqMode
import com.shilapi.xcertplay.media.dsp.DspEqBand
import com.shilapi.xcertplay.media.dsp.DspEqType
import com.shilapi.xcertplay.media.dsp.DspMonoBassConfig
import com.shilapi.xcertplay.media.dsp.DspMsSideCompressorConfig
import com.shilapi.xcertplay.media.dsp.DspMultibandConfig
import com.shilapi.xcertplay.media.dsp.DspSafetyLimiterConfig
import com.shilapi.xcertplay.media.dsp.DspSeatAlignmentConfig
import com.shilapi.xcertplay.media.dsp.DspSeatNearSpeaker
import com.shilapi.xcertplay.media.dsp.DspSeatPreset
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
    INVALID_EQ_CAPACITY,
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
                if (!profile.hasValidEqCapacity()) {
                    DspProfileReadResult.Invalid
                } else {
                    DspProfileReadResult.Loaded(
                        profile = profile,
                        migratedFromVersion = if (schemaVersion < CURRENT_SCHEMA_VERSION) schemaVersion else null,
                    )
                }
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
        if (!profile.hasValidEqCapacity()) return DspProfileSaveResult.INVALID_EQ_CAPACITY
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

    internal fun encodeDraftForSavedState(profile: DspAudioProfile): String = encode(profile).toString()

    internal fun decodeDraftFromSavedState(serialized: String): DspAudioProfile? = try {
        val json = JSONObject(serialized)
        val profileId = json.optString(KEY_ID)
        if (!isValidProfileId(profileId)) {
            null
        } else {
            decode(profileId, json, CURRENT_SCHEMA_VERSION).takeIf(DspAudioProfile::hasValidEqCapacity)
        }
    } catch (_: Exception) {
        null
    }

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
        val seatJson = json.optJSONObject("seatAlignment") ?: JSONObject()
        val advancedMsJson = json.optJSONObject("advancedMs") ?: JSONObject()
        val sideCompressorJson = advancedMsJson.optJSONObject("sideCompressor") ?: JSONObject()
        val cabinJson = json.optJSONObject("cabinCorrection") ?: JSONObject()
        val cabinMeasurementJson = cabinJson.optJSONObject("measurement")
        val harmonicJson = json.optJSONObject("harmonic") ?: JSONObject()
        val multibandJson = json.optJSONObject("multiband") ?: JSONObject()
        val dynamicEqJson = json.optJSONObject("dynamicEq") ?: JSONObject()
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
            seatAlignment = DspSeatAlignmentConfig(
                enabled = seatJson.optBoolean("enabled", false),
                preset = DspSeatPreset.valueOf(seatJson.optString("preset", DspSeatPreset.CENTER.name)),
                nearSpeaker = DspSeatNearSpeaker.valueOf(
                    seatJson.optString("nearSpeaker", DspSeatNearSpeaker.CENTER.name),
                ),
                distanceDifferenceCm = seatJson.readFiniteDouble("distanceDifferenceCm", 0.0),
                leftTrimDb = seatJson.readFiniteDouble("leftTrimDb", 0.0),
                rightTrimDb = seatJson.readFiniteDouble("rightTrimDb", 0.0),
                invertLeft = seatJson.optBoolean("invertLeft", false),
                invertRight = seatJson.optBoolean("invertRight", false),
            ),
            advancedMs = if (schemaVersion >= 5) DspAdvancedMsConfig(
                enabled = advancedMsJson.optBoolean("enabled", false),
                crossoverHz = advancedMsJson.readFiniteDouble(
                    "crossoverHz",
                    DspAdvancedMsConfig.DEFAULT_CROSSOVER_HZ,
                ),
                centerFocusDb = advancedMsJson.readFiniteDouble("centerFocusDb", 0.0),
                midLowGainDb = advancedMsJson.readFiniteDouble("midLowGainDb", 0.0),
                midHighGainDb = advancedMsJson.readFiniteDouble("midHighGainDb", 0.0),
                sideLowGainDb = advancedMsJson.readFiniteDouble("sideLowGainDb", 0.0),
                sideHighGainDb = advancedMsJson.readFiniteDouble("sideHighGainDb", 0.0),
                lowBandWidth = advancedMsJson.readFiniteDouble("lowBandWidth", 1.0),
                highBandWidth = advancedMsJson.readFiniteDouble("highBandWidth", 1.0),
                sideCompressor = DspMsSideCompressorConfig(
                    enabled = sideCompressorJson.optBoolean("enabled", false),
                    thresholdDb = sideCompressorJson.readFiniteDouble("thresholdDb", -24.0),
                    ratio = sideCompressorJson.readFiniteDouble("ratio", 2.0),
                    attackMs = sideCompressorJson.readFiniteDouble("attackMs", 10.0),
                    releaseMs = sideCompressorJson.readFiniteDouble("releaseMs", 100.0),
                    kneeDb = sideCompressorJson.readFiniteDouble("kneeDb", 6.0),
                ),
            ) else DspAdvancedMsConfig(),
            cabinCorrection = if (schemaVersion >= 6) DspCabinCorrectionConfig(
                enabled = cabinJson.optBoolean("enabled", false),
                measurement = cabinMeasurementJson?.let { measurement ->
                    DspCabinMeasurementSummary(
                        sourceSha256 = measurement.optString("sourceSha256"),
                        pointCount = measurement.optInt("pointCount", 0),
                        minimumFrequencyHz = measurement.readFiniteDouble("minimumFrequencyHz", Double.NaN),
                        maximumFrequencyHz = measurement.readFiniteDouble("maximumFrequencyHz", Double.NaN),
                        noiseFloorDb = measurement.readFiniteDouble("noiseFloorDb", Double.NaN),
                        clippingDetected = measurement.optBoolean("clippingDetected", false),
                    )
                },
                notches = readCabinNotches(cabinJson.optJSONArray("notches")),
            ) else DspCabinCorrectionConfig(),
            harmonic = if (schemaVersion >= 7) DspHarmonicConfig(
                enabled = harmonicJson.optBoolean("enabled", false),
                mode = DspHarmonicMode.valueOf(harmonicJson.optString("mode", DspHarmonicMode.MIXED.name)),
                quality = DspHarmonicQuality.valueOf(harmonicJson.optString("quality", DspHarmonicQuality.MEDIUM.name)),
                exciterEnabled = harmonicJson.optBoolean("exciterEnabled", false),
                exciterAmount = harmonicJson.readFiniteDouble("exciterAmount", 0.2),
                exciterCutoffHz = harmonicJson.readFiniteDouble("exciterCutoffHz", 6_000.0),
                virtualBassEnabled = harmonicJson.optBoolean("virtualBassEnabled", false),
                virtualBassAmount = harmonicJson.readFiniteDouble("virtualBassAmount", 0.2),
                virtualBassCutoffHz = harmonicJson.readFiniteDouble("virtualBassCutoffHz", 120.0),
                levelMatchEnabled = harmonicJson.optBoolean("levelMatchEnabled", true),
            ) else DspHarmonicConfig(),
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
            dynamicEq = DspDynamicEqConfig(
                enabled = dynamicEqJson.optBoolean("enabled", false),
                bands = dynamicEqJson.optJSONArray("bands")?.let(::readDynamicEqBands)
                    ?: DspDynamicEqConfig.defaultBands(),
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

    private fun readDynamicEqBands(array: JSONArray): List<DspDynamicEqBandConfig> {
        require(array.length() <= DspDynamicEqConfig.MAX_BANDS)
        return List(array.length()) { index ->
            val values = requireNotNull(array.optJSONObject(index))
            DspDynamicEqBandConfig(
                enabled = values.optBoolean("enabled", false),
                frequencyHz = values.readFiniteDouble("frequencyHz", 1_000.0),
                q = values.readFiniteDouble("q", 1.0),
                thresholdDb = values.readFiniteDouble("thresholdDb", -24.0),
                ratio = values.readFiniteDouble("ratio", 2.0),
                attackMs = values.readFiniteDouble("attackMs", 10.0),
                releaseMs = values.readFiniteDouble("releaseMs", 100.0),
                maxBoostDb = values.readFiniteDouble("maxBoostDb", 6.0),
                maxCutDb = values.readFiniteDouble("maxCutDb", 6.0),
                mode = DspDynamicEqMode.valueOf(values.optString("mode", DspDynamicEqMode.CUT.name)),
            )
        }
    }

    private fun readCabinNotches(array: JSONArray?): List<DspCabinCorrectionNotch> {
        if (array == null) return emptyList()
        require(array.length() <= DspCabinCorrectionConfig.MAX_NOTCHES)
        return List(array.length()) { index ->
            val values = requireNotNull(array.optJSONObject(index))
            DspCabinCorrectionNotch(
                enabled = values.optBoolean("enabled", false),
                frequencyHz = values.readFiniteDouble("frequencyHz", 100.0),
                gainDb = values.readFiniteDouble("gainDb", -3.0),
                q = values.readFiniteDouble("q", 4.0),
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
                "seatAlignment",
                JSONObject()
                    .put("enabled", profile.seatAlignment.enabled)
                    .put("preset", profile.seatAlignment.preset.name)
                    .put("nearSpeaker", profile.seatAlignment.nearSpeaker.name)
                    .put("distanceDifferenceCm", profile.seatAlignment.distanceDifferenceCm)
                    .put("leftTrimDb", profile.seatAlignment.leftTrimDb)
                    .put("rightTrimDb", profile.seatAlignment.rightTrimDb)
                    .put("invertLeft", profile.seatAlignment.invertLeft)
                    .put("invertRight", profile.seatAlignment.invertRight),
            )
            .put(
                "advancedMs",
                JSONObject()
                    .put("enabled", profile.advancedMs.enabled)
                    .put("crossoverHz", profile.advancedMs.crossoverHz)
                    .put("centerFocusDb", profile.advancedMs.centerFocusDb)
                    .put("midLowGainDb", profile.advancedMs.midLowGainDb)
                    .put("midHighGainDb", profile.advancedMs.midHighGainDb)
                    .put("sideLowGainDb", profile.advancedMs.sideLowGainDb)
                    .put("sideHighGainDb", profile.advancedMs.sideHighGainDb)
                    .put("lowBandWidth", profile.advancedMs.lowBandWidth)
                    .put("highBandWidth", profile.advancedMs.highBandWidth)
                    .put(
                        "sideCompressor",
                        JSONObject()
                            .put("enabled", profile.advancedMs.sideCompressor.enabled)
                            .put("thresholdDb", profile.advancedMs.sideCompressor.thresholdDb)
                            .put("ratio", profile.advancedMs.sideCompressor.ratio)
                            .put("attackMs", profile.advancedMs.sideCompressor.attackMs)
                            .put("releaseMs", profile.advancedMs.sideCompressor.releaseMs)
                            .put("kneeDb", profile.advancedMs.sideCompressor.kneeDb),
                    ),
            )
            .put(
                "cabinCorrection",
                JSONObject()
                    .put("enabled", profile.cabinCorrection.enabled)
                    .put("measurement", profile.cabinCorrection.measurement?.let { measurement ->
                        JSONObject()
                            .put("sourceSha256", measurement.sourceSha256)
                            .put("pointCount", measurement.pointCount)
                            .put("minimumFrequencyHz", measurement.minimumFrequencyHz)
                            .put("maximumFrequencyHz", measurement.maximumFrequencyHz)
                            .put("noiseFloorDb", measurement.noiseFloorDb)
                            .put("clippingDetected", measurement.clippingDetected)
                    })
                    .put("notches", JSONArray().apply {
                        profile.cabinCorrection.notches.forEach { notch ->
                            put(
                                JSONObject()
                                    .put("enabled", notch.enabled)
                                    .put("frequencyHz", notch.frequencyHz)
                                    .put("gainDb", notch.gainDb)
                                    .put("q", notch.q),
                            )
                        }
                    }),
            )
            .put(
                "harmonic",
                JSONObject()
                    .put("enabled", profile.harmonic.enabled)
                    .put("mode", profile.harmonic.mode.name)
                    .put("quality", profile.harmonic.quality.name)
                    .put("exciterEnabled", profile.harmonic.exciterEnabled)
                    .put("exciterAmount", profile.harmonic.exciterAmount)
                    .put("exciterCutoffHz", profile.harmonic.exciterCutoffHz)
                    .put("virtualBassEnabled", profile.harmonic.virtualBassEnabled)
                    .put("virtualBassAmount", profile.harmonic.virtualBassAmount)
                    .put("virtualBassCutoffHz", profile.harmonic.virtualBassCutoffHz)
                    .put("levelMatchEnabled", profile.harmonic.levelMatchEnabled),
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
                "dynamicEq",
                JSONObject()
                    .put("enabled", profile.dynamicEq.enabled)
                    .put("bands", JSONArray().apply {
                        profile.dynamicEq.bands.forEach { put(it.toJsonObject()) }
                    }),
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
        const val CURRENT_SCHEMA_VERSION = 7
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

private fun DspDynamicEqBandConfig.toJsonObject(): JSONObject = JSONObject()
    .put("enabled", enabled)
    .put("frequencyHz", frequencyHz)
    .put("q", q)
    .put("thresholdDb", thresholdDb)
    .put("ratio", ratio)
    .put("attackMs", attackMs)
    .put("releaseMs", releaseMs)
    .put("maxBoostDb", maxBoostDb)
    .put("maxCutDb", maxCutDb)
    .put("mode", mode.name)

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
