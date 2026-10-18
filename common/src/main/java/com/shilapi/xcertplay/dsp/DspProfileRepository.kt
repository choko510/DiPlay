package com.shilapi.xcertplay.dsp

import android.util.AtomicFile
import com.shilapi.xcertplay.media.dsp.DspBassConfig
import com.shilapi.xcertplay.media.dsp.DspAdvancedMsConfig
import com.shilapi.xcertplay.media.dsp.DspCabinCorrectionConfig
import com.shilapi.xcertplay.media.dsp.DspCabinCut
import com.shilapi.xcertplay.media.dsp.DspCabinCutSource
import com.shilapi.xcertplay.media.dsp.DspCompressorConfig
import com.shilapi.xcertplay.media.dsp.DspConvolverConfig
import com.shilapi.xcertplay.media.dsp.DspDynamicEqBandConfig
import com.shilapi.xcertplay.media.dsp.DspDynamicEqConfig
import com.shilapi.xcertplay.media.dsp.DspDynamicEqMode
import com.shilapi.xcertplay.media.dsp.DspEqBand
import com.shilapi.xcertplay.media.dsp.DspEqType
import com.shilapi.xcertplay.media.dsp.DspHarmonicEngineConfig
import com.shilapi.xcertplay.media.dsp.DspHarmonicMode
import com.shilapi.xcertplay.media.dsp.DspHarmonicQuality
import com.shilapi.xcertplay.media.dsp.DspHighExciterConfig
import com.shilapi.xcertplay.media.dsp.DspMonoBassConfig
import com.shilapi.xcertplay.media.dsp.DspMultibandConfig
import com.shilapi.xcertplay.media.dsp.DspSafetyLimiterConfig
import com.shilapi.xcertplay.media.dsp.DspSeatFocusConfig
import com.shilapi.xcertplay.media.dsp.DspSeatPreset
import com.shilapi.xcertplay.media.dsp.DspVirtualBassConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.charset.CodingErrorAction
import org.json.JSONArray
import org.json.JSONObject

private val ROOT_KNOWN_FIELDS = setOf(
    "schemaVersion", "id", "name", "enabled", "preampDb", "preamp", "autoHeadroom", "autoHeadroomMarginDb",
    "peq", "eq", "bass", "compressor", "stereo", "convolver", "multiband", "dynamicEq", "limiter",
    "seatFocus", "advancedMs", "cabinCorrection", "harmonicEngine",
)
private val STEREO_KNOWN_FIELDS = setOf("width", "monoBass")
private val MONO_BASS_KNOWN_FIELDS = setOf("enabled", "cutoffHz")
private val BASS_KNOWN_FIELDS = setOf("enabled", "gainDb", "frequencyHz")
private val COMPRESSOR_KNOWN_FIELDS = setOf("enabled", "thresholdDb", "ratio", "attackMs", "releaseMs", "kneeDb", "makeupDb")
private val CONVOLVER_KNOWN_FIELDS = setOf("enabled", "impulseResponseId", "wet")
private val MULTIBAND_KNOWN_FIELDS = setOf("enabled", "lowMidCrossoverHz", "midHighCrossoverHz", "low", "mid", "high")
private val DYNAMIC_EQ_KNOWN_FIELDS = setOf("enabled", "bands")
private val DYNAMIC_EQ_BAND_KNOWN_FIELDS = setOf(
    "enabled", "frequencyHz", "q", "thresholdDb", "ratio", "attackMs", "releaseMs", "maxBoostDb", "maxCutDb", "mode",
)
private val LIMITER_KNOWN_FIELDS = setOf("enabled", "thresholdDb", "releaseMs")
private val EQ_BAND_KNOWN_FIELDS = setOf("enabled", "type", "frequencyHz", "gainDb", "q")
private val SEAT_FOCUS_KNOWN_FIELDS = setOf(
    "enabled", "bypassed", "preset", "leftDelayMs", "rightDelayMs", "leftTrimDb", "rightTrimDb", "invertLeft",
    "invertRight", "distanceMode", "leftDistanceCm", "rightDistanceCm",
)
private val ADVANCED_MS_KNOWN_FIELDS = setOf(
    "enabled", "monitorMode", "midGainDb", "sideGainDb", "midEqBands", "sideEqBands", "lowWidth", "midWidth", "highWidth",
    "lowMidCrossoverHz", "midHighCrossoverHz", "centerFocusDb",
)
private val CABIN_KNOWN_FIELDS = setOf(
    "enabled", "bypassed", "calibrationId", "calibrationVersion", "cuts", "dynamicControlEnabled", "maxDynamicCutDb",
)
private val CABIN_CUT_KNOWN_FIELDS = setOf(
    "frequencyHz", "q", "reductionDb", "enabled", "source", "prominenceDb", "confidence",
)
private val HARMONIC_KNOWN_FIELDS = setOf("enabled", "bypassed", "quality", "highExciter", "virtualBass")
private val HIGH_EXCITER_KNOWN_FIELDS = setOf(
    "enabled", "frequencyHz", "drive", "amount", "mode", "wet", "outputCompensationDb",
)
private val VIRTUAL_BASS_KNOWN_FIELDS = setOf(
    "enabled", "fundamentalLowHz", "fundamentalHighHz", "drive", "amount", "secondHarmonicAmount",
    "thirdHarmonicAmount", "wet",
)

internal sealed interface DspProfileReadResult {
    data class Loaded(val profile: DspAudioProfile, val migratedFromVersion: Int? = null) : DspProfileReadResult
    data class FutureSchema(val schemaVersion: Int) : DspProfileReadResult
    data object NotFound : DspProfileReadResult
    data object Invalid : DspProfileReadResult
}

internal enum class DspProfileSaveResult {
    SAVED,
    INVALID_PROFILE_ID,
    INVALID_EXISTING_PROFILE,
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
            val json = readProfileJson(file)
            val schemaVersion = schemaVersion(json)
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
        val existing = if (file.isFile) {
            try {
                readProfileJson(file)
            } catch (_: Exception) {
                return DspProfileSaveResult.INVALID_EXISTING_PROFILE
            }
        } else {
            null
        }
        val existingSchemaVersion = try {
            existing?.let(::schemaVersion)
        } catch (_: Exception) {
            return DspProfileSaveResult.INVALID_EXISTING_PROFILE
        }
        if (existingSchemaVersion != null && existingSchemaVersion > CURRENT_SCHEMA_VERSION) {
            return DspProfileSaveResult.FUTURE_SCHEMA_PRESERVED
        }
        if (existingSchemaVersion != null && existingSchemaVersion < LEGACY_SCHEMA_VERSION) {
            return DspProfileSaveResult.INVALID_EXISTING_PROFILE
        }
        if (existing != null && existingSchemaVersion != null) {
            try {
                decode(profile.id, existing, existingSchemaVersion)
            } catch (_: Exception) {
                return DspProfileSaveResult.INVALID_EXISTING_PROFILE
            }
        }
        if (!profilesDirectory.exists() && !profilesDirectory.mkdirs()) return DspProfileSaveResult.WRITE_FAILED

        return try {
            val contents = encode(profile, existing).toString().toByteArray(StandardCharsets.UTF_8)
            if (contents.size > MAX_PROFILE_BYTES) return DspProfileSaveResult.WRITE_FAILED
            atomicWriter(file, contents)
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
        require(serialized.toByteArray(StandardCharsets.UTF_8).size <= MAX_PROFILE_BYTES)
        val json = parseProfileJson(serialized)
        val profileId = json.readString(KEY_ID, "")
        if (!isValidProfileId(profileId)) null else decode(profileId, json, CURRENT_SCHEMA_VERSION)
    } catch (_: Exception) {
        null
    }

    private fun profileFile(profileId: String): File? {
        if (!isValidProfileId(profileId)) return null
        val directory = profilesDirectory.canonicalFile
        val file = File(directory, "$profileId.$PROFILE_EXTENSION").canonicalFile
        return file.takeIf { it.parentFile == directory }
    }

    private fun readProfileJson(file: File): JSONObject {
        val bytes = AtomicFile(file).openRead().use(::readBounded)
        val text = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
        return parseProfileJson(text)
    }

    private fun parseProfileJson(text: String): JSONObject {
        require(text.toByteArray(StandardCharsets.UTF_8).size <= MAX_PROFILE_BYTES)
        require(JsonObjectKeyValidator.hasUniqueKeys(text))
        return JSONObject(text)
    }

    private fun readBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            require(count <= MAX_PROFILE_BYTES - total)
            output.write(buffer, 0, count)
            total += count
        }
        return output.toByteArray()
    }

    private fun schemaVersion(json: JSONObject): Int {
        if (!json.has(KEY_SCHEMA_VERSION)) return LEGACY_SCHEMA_VERSION
        val raw = json.opt(KEY_SCHEMA_VERSION)
        require(raw is Number)
        val value = raw.toDouble()
        require(value.isFinite() && value % 1.0 == 0.0 && value in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble())
        return value.toInt()
    }

    private fun decode(profileId: String, json: JSONObject, schemaVersion: Int): DspAudioProfile {
        val storedId = json.readString(KEY_ID, profileId)
        require(storedId == profileId)
        val legacy = schemaVersion == LEGACY_SCHEMA_VERSION
        val bands = readBands(json.readOptionalArray(if (legacy) "eq" else "peq"))
        val bassJson = json.readOptionalObject("bass")
        val compressorJson = json.readOptionalObject("compressor")
        val stereoJson = json.readOptionalObject("stereo")
        val convolverJson = json.readOptionalObject("convolver")
        val limiterJson = json.readOptionalObject("limiter")
        val multibandJson = json.readOptionalObject("multiband")
        val dynamicEqJson = json.readOptionalObject("dynamicEq")
        val monoBassJson = stereoJson.readOptionalObject("monoBass")
        val seatFocusJson = json.readOptionalObject("seatFocus")
        val advancedMsJson = json.readOptionalObject("advancedMs")
        val cabinJson = json.readOptionalObject("cabinCorrection")
        val harmonicJson = json.readOptionalObject("harmonicEngine")
        val highExciterJson = harmonicJson.readOptionalObject("highExciter")
        val virtualBassJson = harmonicJson.readOptionalObject("virtualBass")
        fun readCompressor(values: JSONObject) = DspCompressorConfig(
            enabled = values.readBoolean("enabled", false),
            thresholdDb = values.readFiniteDouble("thresholdDb", -20.0),
            ratio = values.readFiniteDouble("ratio", 4.0),
            attackMs = values.readFiniteDouble("attackMs", 10.0),
            releaseMs = values.readFiniteDouble("releaseMs", 100.0),
            kneeDb = values.readFiniteDouble("kneeDb", 0.0),
            makeupDb = values.readFiniteDouble("makeupDb", 0.0),
        )
        return DspAudioProfile(
            id = json.readString(KEY_ID, profileId),
            name = json.readString(KEY_NAME, profileId),
            enabled = json.readBoolean("enabled", true),
            preampDb = json.readFiniteDouble(if (legacy) "preamp" else "preampDb", 0.0),
            autoHeadroomEnabled = json.readBoolean("autoHeadroom", true),
            autoHeadroomMarginDb = json.readFiniteDouble("autoHeadroomMarginDb", 1.0),
            eqBands = bands,
            bass = DspBassConfig(
                enabled = bassJson.readBoolean("enabled", false),
                gainDb = bassJson.readFiniteDouble("gainDb", 0.0),
                frequencyHz = bassJson.readFiniteDouble("frequencyHz", 80.0),
            ),
            compressor = readCompressor(compressorJson),
            stereoWidth = stereoJson.readFiniteDouble("width", 1.0),
            monoBass = DspMonoBassConfig(
                enabled = monoBassJson.readBoolean("enabled", false),
                cutoffHz = monoBassJson.readStrictInt("cutoffHz", 120),
            ),
            convolver = DspConvolverConfig(
                enabled = convolverJson.readBoolean("enabled", false),
                impulseResponseId = convolverJson.readOptionalString("impulseResponseId"),
                wet = convolverJson.readFiniteDouble("wet", 1.0),
            ),
            multiband = DspMultibandConfig(
                enabled = multibandJson.readBoolean("enabled", false),
                lowMidCrossoverHz = multibandJson.readFiniteDouble(
                    "lowMidCrossoverHz",
                    DspMultibandConfig.DEFAULT_LOW_MID_CROSSOVER_HZ,
                ),
                midHighCrossoverHz = multibandJson.readFiniteDouble(
                    "midHighCrossoverHz",
                    DspMultibandConfig.DEFAULT_MID_HIGH_CROSSOVER_HZ,
                ),
                low = readCompressor(multibandJson.readOptionalObject("low")),
                mid = readCompressor(multibandJson.readOptionalObject("mid")),
                high = readCompressor(multibandJson.readOptionalObject("high")),
            ),
            dynamicEq = DspDynamicEqConfig(
                enabled = dynamicEqJson.readBoolean("enabled", false),
                bands = dynamicEqJson.readOptionalArray("bands")?.let(::readDynamicEqBands)
                    ?: DspDynamicEqConfig.defaultBands(),
            ),
            limiter = DspSafetyLimiterConfig(
                enabled = limiterJson.readBoolean("enabled", true),
                thresholdDb = limiterJson.readFiniteDouble("thresholdDb", -1.0),
                releaseMs = limiterJson.readFiniteDouble("releaseMs", 60.0),
            ),
            seatFocus = DspSeatFocusConfig(
                enabled = seatFocusJson.readBoolean("enabled", false),
                preset = seatFocusJson.readEnum("preset", DspSeatPreset.CUSTOM),
                leftDelayMs = seatFocusJson.readStrictFiniteDouble("leftDelayMs", 0.0),
                rightDelayMs = seatFocusJson.readStrictFiniteDouble("rightDelayMs", 0.0),
                leftTrimDb = seatFocusJson.readStrictFiniteDouble("leftTrimDb", 0.0),
                rightTrimDb = seatFocusJson.readStrictFiniteDouble("rightTrimDb", 0.0),
                invertLeft = seatFocusJson.readBoolean("invertLeft", false),
                invertRight = seatFocusJson.readBoolean("invertRight", false),
                distanceMode = seatFocusJson.readBoolean("distanceMode", false),
                leftDistanceCm = seatFocusJson.readOptionalFiniteDouble("leftDistanceCm"),
                rightDistanceCm = seatFocusJson.readOptionalFiniteDouble("rightDistanceCm"),
            ),
            advancedMs = DspAdvancedMsConfig(
                enabled = advancedMsJson.readBoolean("enabled", false),
                midGainDb = advancedMsJson.readStrictFiniteDouble("midGainDb", 0.0),
                sideGainDb = advancedMsJson.readStrictFiniteDouble("sideGainDb", 0.0),
                midEqBands = readAdvancedBands(advancedMsJson.readOptionalArray("midEqBands")),
                sideEqBands = readAdvancedBands(advancedMsJson.readOptionalArray("sideEqBands")),
                lowWidth = advancedMsJson.readStrictFiniteDouble("lowWidth", 1.0),
                midWidth = advancedMsJson.readStrictFiniteDouble("midWidth", 1.0),
                highWidth = advancedMsJson.readStrictFiniteDouble("highWidth", 1.0),
                lowMidCrossoverHz = advancedMsJson.readStrictFiniteDouble(
                    "lowMidCrossoverHz",
                    DspAdvancedMsConfig.DEFAULT_LOW_MID_CROSSOVER_HZ,
                ),
                midHighCrossoverHz = advancedMsJson.readStrictFiniteDouble(
                    "midHighCrossoverHz",
                    DspAdvancedMsConfig.DEFAULT_MID_HIGH_CROSSOVER_HZ,
                ),
                centerFocusDb = advancedMsJson.readStrictFiniteDouble("centerFocusDb", 0.0),
            ),
            cabinCorrection = DspCabinCorrectionConfig(
                enabled = cabinJson.readBoolean("enabled", false),
                calibrationId = cabinJson.readOptionalString("calibrationId"),
                calibrationVersion = cabinJson.readStrictInt(
                    "calibrationVersion",
                    DspCabinCorrectionConfig.CALIBRATION_FORMAT_VERSION,
                ),
                cuts = readCabinCuts(cabinJson.readOptionalArray("cuts")),
                dynamicControlEnabled = cabinJson.readBoolean("dynamicControlEnabled", false),
                maxDynamicCutDb = cabinJson.readStrictFiniteDouble("maxDynamicCutDb", 3.0),
            ),
            harmonicEngine = DspHarmonicEngineConfig(
                enabled = harmonicJson.readBoolean("enabled", false),
                quality = harmonicJson.readEnum("quality", DspHarmonicQuality.TWO_X),
                highExciter = DspHighExciterConfig(
                    enabled = highExciterJson.readBoolean("enabled", false),
                    frequencyHz = highExciterJson.readStrictFiniteDouble(
                        "frequencyHz",
                        DspHighExciterConfig.DEFAULT_FREQUENCY_HZ,
                    ),
                    drive = highExciterJson.readStrictFiniteDouble("drive", 1.0),
                    amount = highExciterJson.readStrictFiniteDouble("amount", 0.5),
                    mode = highExciterJson.readEnum("mode", DspHarmonicMode.MIXED),
                    wet = highExciterJson.readStrictFiniteDouble("wet", 0.0),
                    outputCompensationDb = highExciterJson.readStrictFiniteDouble("outputCompensationDb", 0.0),
                ),
                virtualBass = DspVirtualBassConfig(
                    enabled = virtualBassJson.readBoolean("enabled", false),
                    fundamentalLowHz = virtualBassJson.readStrictFiniteDouble("fundamentalLowHz", 35.0),
                    fundamentalHighHz = virtualBassJson.readStrictFiniteDouble("fundamentalHighHz", 80.0),
                    drive = virtualBassJson.readStrictFiniteDouble("drive", 1.0),
                    amount = virtualBassJson.readStrictFiniteDouble("amount", 0.5),
                    secondHarmonicAmount = virtualBassJson.readStrictFiniteDouble("secondHarmonicAmount", 0.5),
                    thirdHarmonicAmount = virtualBassJson.readStrictFiniteDouble("thirdHarmonicAmount", 0.25),
                    wet = virtualBassJson.readStrictFiniteDouble("wet", 0.0),
                ),
            ),
        )
    }

    private fun readBands(array: JSONArray?): List<DspEqBand> {
        if (array == null) return DspProfilePresets.flatBands()
        require(array.length() <= DspAudioProfile.MAX_EQ_BANDS)
        return List(array.length()) { index ->
            val json = array.getJSONObject(index)
            DspEqBand(
                type = json.readEnum("type", DspEqType.PEAK),
                frequencyHz = json.readFiniteDouble("frequencyHz", DEFAULT_BAND_CENTERS_HZ[index].toDouble()),
                gainDb = json.readFiniteDouble("gainDb", 0.0),
                q = json.readFiniteDouble("q", 1.0),
                enabled = json.readBoolean("enabled", true),
            )
        }
    }

    private fun readDynamicEqBands(array: JSONArray): List<DspDynamicEqBandConfig> {
        require(array.length() <= DspDynamicEqConfig.MAX_BANDS)
        return List(array.length()) { index ->
            val values = requireNotNull(array.optJSONObject(index))
            DspDynamicEqBandConfig(
                enabled = values.readBoolean("enabled", false),
                frequencyHz = values.readFiniteDouble("frequencyHz", 1_000.0),
                q = values.readFiniteDouble("q", 1.0),
                thresholdDb = values.readFiniteDouble("thresholdDb", -24.0),
                ratio = values.readFiniteDouble("ratio", 2.0),
                attackMs = values.readFiniteDouble("attackMs", 10.0),
                releaseMs = values.readFiniteDouble("releaseMs", 100.0),
                maxBoostDb = values.readFiniteDouble("maxBoostDb", 6.0),
                maxCutDb = values.readFiniteDouble("maxCutDb", 6.0),
                mode = values.readEnum("mode", DspDynamicEqMode.CUT),
            )
        }
    }

    private fun readAdvancedBands(array: JSONArray?): List<DspEqBand> {
        if (array == null) return emptyList()
        require(array.length() <= DspAdvancedMsConfig.MAX_EQ_BANDS)
        return List(array.length()) { index ->
            val values = array.getJSONObject(index)
            DspEqBand(
                type = values.readEnum("type", DspEqType.PEAK),
                frequencyHz = values.readStrictFiniteDouble("frequencyHz", 1_000.0),
                gainDb = values.readStrictFiniteDouble("gainDb", 0.0),
                q = values.readStrictFiniteDouble("q", 1.0),
                enabled = values.readBoolean("enabled", false),
            )
        }
    }

    private fun readCabinCuts(array: JSONArray?): List<DspCabinCut> {
        if (array == null) return emptyList()
        require(array.length() <= DspCabinCorrectionConfig.MAX_CUTS)
        return List(array.length()) { index ->
            val values = array.getJSONObject(index)
            DspCabinCut(
                frequencyHz = values.readStrictFiniteDouble("frequencyHz", 80.0),
                q = values.readStrictFiniteDouble("q", 4.0),
                reductionDb = values.readStrictFiniteDouble("reductionDb", -3.0),
                enabled = values.readBoolean("enabled", false),
                source = values.readEnum("source", DspCabinCutSource.MANUAL),
                prominenceDb = values.readStrictFiniteDouble("prominenceDb", 0.0),
                confidence = values.readStrictFiniteDouble("confidence", 0.0),
            )
        }
    }

    private fun encode(profile: DspAudioProfile, existing: JSONObject? = null): JSONObject {
        val root = preservingUnknown(existing, ROOT_KNOWN_FIELDS)
        root.put(KEY_SCHEMA_VERSION, CURRENT_SCHEMA_VERSION)
            .put(KEY_ID, profile.id)
            .put(KEY_NAME, profile.name)
            .put("enabled", profile.enabled)
            .put("preampDb", profile.preampDb)
            .put("autoHeadroom", profile.autoHeadroomEnabled)
            .put("autoHeadroomMarginDb", profile.autoHeadroomMarginDb)
            .put("peq", encodeBands(profile.eqBands, existing?.optJSONArray("peq") ?: existing?.optJSONArray("eq")))

        root.put("bass", profile.bass.toJsonObject(existing?.optJSONObject("bass")))
        root.put("compressor", profile.compressor.toJsonObject(existing?.optJSONObject("compressor")))

        val previousStereo = existing?.optJSONObject("stereo")
        val stereo = preservingUnknown(previousStereo, STEREO_KNOWN_FIELDS)
        stereo.put("width", profile.stereoWidth)
            .put("monoBass", profile.monoBass.toJsonObject(previousStereo?.optJSONObject("monoBass")))
        root.put("stereo", stereo)

        root.put("convolver", preservingUnknown(existing?.optJSONObject("convolver"), CONVOLVER_KNOWN_FIELDS)
            .put("enabled", profile.convolver.enabled)
            .put("impulseResponseId", profile.convolver.impulseResponseId)
            .put("wet", profile.convolver.wet))

        val previousMultiband = existing?.optJSONObject("multiband")
        root.put("multiband", preservingUnknown(previousMultiband, MULTIBAND_KNOWN_FIELDS)
            .put("enabled", profile.multiband.enabled)
            .put("lowMidCrossoverHz", profile.multiband.lowMidCrossoverHz)
            .put("midHighCrossoverHz", profile.multiband.midHighCrossoverHz)
            .put("low", profile.multiband.low.toJsonObject(previousMultiband?.optJSONObject("low")))
            .put("mid", profile.multiband.mid.toJsonObject(previousMultiband?.optJSONObject("mid")))
            .put("high", profile.multiband.high.toJsonObject(previousMultiband?.optJSONObject("high"))))

        val previousDynamicEq = existing?.optJSONObject("dynamicEq")
        val dynamicEq = preservingUnknown(previousDynamicEq, DYNAMIC_EQ_KNOWN_FIELDS)
        dynamicEq.put("enabled", profile.dynamicEq.enabled)
            .put("bands", encodeDynamicEqBands(profile.dynamicEq.bands, previousDynamicEq?.optJSONArray("bands")))
        root.put("dynamicEq", dynamicEq)

        root.put("limiter", preservingUnknown(existing?.optJSONObject("limiter"), LIMITER_KNOWN_FIELDS)
            .put("enabled", profile.limiter.enabled)
            .put("thresholdDb", profile.limiter.thresholdDb)
            .put("releaseMs", profile.limiter.releaseMs))
        root.put("seatFocus", profile.seatFocus.toJsonObject(existing?.optJSONObject("seatFocus")))
        root.put("advancedMs", profile.advancedMs.toJsonObject(existing?.optJSONObject("advancedMs")))
        root.put("cabinCorrection", profile.cabinCorrection.toJsonObject(existing?.optJSONObject("cabinCorrection")))
        root.put("harmonicEngine", profile.harmonicEngine.toJsonObject(existing?.optJSONObject("harmonicEngine")))
        return root
    }

    private fun encodeBands(bands: List<DspEqBand>, previous: JSONArray?): JSONArray = JSONArray().apply {
        bands.forEachIndexed { index, band ->
            put(band.toJsonObject(previous?.optJSONObject(index)))
        }
    }

    private fun encodeDynamicEqBands(
        bands: List<DspDynamicEqBandConfig>,
        previous: JSONArray?,
    ): JSONArray = JSONArray().apply {
        bands.forEachIndexed { index, band ->
            put(band.toJsonObject(previous?.optJSONObject(index)))
        }
    }

    private fun preservingUnknown(existing: JSONObject?, knownFields: Set<String>): JSONObject =
        copyUnknownFields(existing, knownFields)

    private fun isValidProfileId(id: String): Boolean = ID_PATTERN.matches(id) &&
        !id.contains("..") && !id.contains('/') && !id.contains('\\') && !id.contains('\u0000')

    private fun JSONObject.readFiniteDouble(key: String, default: Double): Double = readStrictFiniteDouble(key, default)

    companion object {
        const val CURRENT_SCHEMA_VERSION = 4
        private const val MAX_PROFILE_BYTES = 512 * 1024
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

private fun copyUnknownFields(existing: JSONObject?, knownFields: Set<String>): JSONObject = JSONObject().apply {
    existing?.keys()?.forEach { key ->
        if (key !in knownFields) put(key, existing.get(key))
    }
}

private fun encodeEqBands(bands: List<DspEqBand>, previous: JSONArray?): JSONArray = JSONArray().apply {
    bands.forEachIndexed { index, band -> put(band.toJsonObject(previous?.optJSONObject(index))) }
}

private fun encodeDynamicEqBands(bands: List<DspDynamicEqBandConfig>, previous: JSONArray?): JSONArray = JSONArray().apply {
    bands.forEachIndexed { index, band -> put(band.toJsonObject(previous?.optJSONObject(index))) }
}

private fun DspEqBand.toJsonObject(previous: JSONObject? = null): JSONObject =
    copyUnknownFields(previous, EQ_BAND_KNOWN_FIELDS)
        .put("enabled", enabled)
        .put("type", type.name)
        .put("frequencyHz", frequencyHz)
        .put("gainDb", gainDb)
        .put("q", q)

private fun DspBassConfig.toJsonObject(previous: JSONObject?): JSONObject =
    copyUnknownFields(previous, BASS_KNOWN_FIELDS)
        .put("enabled", enabled)
        .put("gainDb", gainDb)
        .put("frequencyHz", frequencyHz)

private fun DspCompressorConfig.toJsonObject(previous: JSONObject? = null): JSONObject =
    copyUnknownFields(previous, COMPRESSOR_KNOWN_FIELDS)
        .put("enabled", enabled)
        .put("thresholdDb", thresholdDb)
        .put("ratio", ratio)
        .put("attackMs", attackMs)
        .put("releaseMs", releaseMs)
        .put("kneeDb", kneeDb)
        .put("makeupDb", makeupDb)

private fun DspMonoBassConfig.toJsonObject(previous: JSONObject?): JSONObject =
    copyUnknownFields(previous, MONO_BASS_KNOWN_FIELDS)
        .put("enabled", enabled)
        .put("cutoffHz", cutoffHz)

private fun DspDynamicEqBandConfig.toJsonObject(previous: JSONObject? = null): JSONObject =
    copyUnknownFields(previous, DYNAMIC_EQ_BAND_KNOWN_FIELDS)
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

private fun DspSeatFocusConfig.toJsonObject(previous: JSONObject?): JSONObject =
    copyUnknownFields(previous, SEAT_FOCUS_KNOWN_FIELDS)
        .put("enabled", enabled)
        .put("preset", preset.name)
        .put("leftDelayMs", leftDelayMs)
        .put("rightDelayMs", rightDelayMs)
        .put("leftTrimDb", leftTrimDb)
        .put("rightTrimDb", rightTrimDb)
        .put("invertLeft", invertLeft)
        .put("invertRight", invertRight)
        .put("distanceMode", distanceMode)
        .put("leftDistanceCm", leftDistanceCm ?: JSONObject.NULL)
        .put("rightDistanceCm", rightDistanceCm ?: JSONObject.NULL)

private fun DspAdvancedMsConfig.toJsonObject(previous: JSONObject?): JSONObject {
    val result = copyUnknownFields(previous, ADVANCED_MS_KNOWN_FIELDS)
    return result
        .put("enabled", enabled)
        .put("midGainDb", midGainDb)
        .put("sideGainDb", sideGainDb)
        .put("midEqBands", encodeEqBands(midEqBands, previous?.optJSONArray("midEqBands")))
        .put("sideEqBands", encodeEqBands(sideEqBands, previous?.optJSONArray("sideEqBands")))
        .put("lowWidth", lowWidth)
        .put("midWidth", midWidth)
        .put("highWidth", highWidth)
        .put("lowMidCrossoverHz", lowMidCrossoverHz)
        .put("midHighCrossoverHz", midHighCrossoverHz)
        .put("centerFocusDb", centerFocusDb)
}

private fun DspCabinCut.toJsonObject(previous: JSONObject? = null): JSONObject =
    copyUnknownFields(previous, CABIN_CUT_KNOWN_FIELDS)
        .put("frequencyHz", frequencyHz)
        .put("q", q)
        .put("reductionDb", reductionDb)
        .put("enabled", enabled)
        .put("source", source.name)
        .put("prominenceDb", prominenceDb)
        .put("confidence", confidence)

private fun DspCabinCorrectionConfig.toJsonObject(previous: JSONObject?): JSONObject {
    val cuts = JSONArray().apply {
        this@toJsonObject.cuts.forEachIndexed { index, cut ->
            put(cut.toJsonObject(previous?.optJSONArray("cuts")?.optJSONObject(index)))
        }
    }
    return copyUnknownFields(previous, CABIN_KNOWN_FIELDS)
        .put("enabled", enabled)
        .put("calibrationId", calibrationId ?: JSONObject.NULL)
        .put("calibrationVersion", calibrationVersion)
        .put("cuts", cuts)
        .put("dynamicControlEnabled", dynamicControlEnabled)
        .put("maxDynamicCutDb", maxDynamicCutDb)
}

private fun DspHighExciterConfig.toJsonObject(previous: JSONObject?): JSONObject =
    copyUnknownFields(previous, HIGH_EXCITER_KNOWN_FIELDS)
        .put("enabled", enabled)
        .put("frequencyHz", frequencyHz)
        .put("drive", drive)
        .put("amount", amount)
        .put("mode", mode.name)
        .put("wet", wet)
        .put("outputCompensationDb", outputCompensationDb)

private fun DspVirtualBassConfig.toJsonObject(previous: JSONObject?): JSONObject =
    copyUnknownFields(previous, VIRTUAL_BASS_KNOWN_FIELDS)
        .put("enabled", enabled)
        .put("fundamentalLowHz", fundamentalLowHz)
        .put("fundamentalHighHz", fundamentalHighHz)
        .put("drive", drive)
        .put("amount", amount)
        .put("secondHarmonicAmount", secondHarmonicAmount)
        .put("thirdHarmonicAmount", thirdHarmonicAmount)
        .put("wet", wet)

private fun DspHarmonicEngineConfig.toJsonObject(previous: JSONObject?): JSONObject =
    copyUnknownFields(previous, HARMONIC_KNOWN_FIELDS)
        .put("enabled", enabled)
        .put("quality", quality.name)
        .put("highExciter", highExciter.toJsonObject(previous?.optJSONObject("highExciter")))
        .put("virtualBass", virtualBass.toJsonObject(previous?.optJSONObject("virtualBass")))

private fun JSONObject.readStrictFiniteDouble(key: String, default: Double): Double {
    if (!has(key)) return default
    val raw = opt(key)
    require(raw is Number)
    return raw.toDouble().also { require(it.isFinite()) }
}

private fun JSONObject.readOptionalFiniteDouble(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    val raw = opt(key)
    require(raw is Number)
    return raw.toDouble().also { require(it.isFinite()) }
}

private fun JSONObject.readBoolean(key: String, default: Boolean): Boolean {
    if (!has(key)) return default
    return opt(key) as? Boolean ?: throw IllegalArgumentException("$key must be a boolean")
}

private fun JSONObject.readString(key: String, default: String): String {
    if (!has(key)) return default
    return opt(key) as? String ?: throw IllegalArgumentException("$key must be a string")
}

private fun JSONObject.readOptionalString(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return opt(key) as? String ?: throw IllegalArgumentException("$key must be a string")
}

private fun JSONObject.readOptionalObject(key: String): JSONObject {
    if (!has(key)) return JSONObject()
    return opt(key) as? JSONObject ?: throw IllegalArgumentException("$key must be an object")
}

private fun JSONObject.readOptionalArray(key: String): JSONArray? {
    if (!has(key)) return null
    return opt(key) as? JSONArray ?: throw IllegalArgumentException("$key must be an array")
}

private fun JSONObject.readStrictInt(key: String, default: Int): Int {
    val value = readStrictFiniteDouble(key, default.toDouble())
    require(value % 1.0 == 0.0 && value in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble())
    return value.toInt()
}

private inline fun <reified T : Enum<T>> JSONObject.readEnum(key: String, default: T): T {
    if (!has(key)) return default
    val value = opt(key) as? String ?: throw IllegalArgumentException("$key must be a string")
    return enumValueOf(value)
}

private object JsonObjectKeyValidator {
    fun hasUniqueKeys(source: String): Boolean = runCatching { Parser(source).parse() }.isSuccess

    private class Parser(private val source: String) {
        private var index = 0

        fun parse() {
            skipWhitespace()
            parseValue(0)
            skipWhitespace()
            require(index == source.length)
        }

        private fun parseValue(depth: Int) {
            require(depth <= MAX_DEPTH)
            skipWhitespace()
            require(index < source.length)
            when (source[index]) {
                '{' -> parseObject(depth + 1)
                '[' -> parseArray(depth + 1)
                '"' -> parseString()
                else -> parsePrimitive()
            }
        }

        private fun parseObject(depth: Int) {
            expect('{')
            skipWhitespace()
            if (consumeIf('}')) return
            val keys = HashSet<String>()
            while (true) {
                skipWhitespace()
                require(index < source.length && source[index] == '"')
                require(keys.add(parseString()))
                skipWhitespace()
                expect(':')
                parseValue(depth)
                skipWhitespace()
                if (consumeIf('}')) return
                expect(',')
            }
        }

        private fun parseArray(depth: Int) {
            expect('[')
            skipWhitespace()
            if (consumeIf(']')) return
            while (true) {
                parseValue(depth)
                skipWhitespace()
                if (consumeIf(']')) return
                expect(',')
            }
        }

        private fun parseString(): String {
            expect('"')
            val result = StringBuilder()
            while (index < source.length) {
                val character = source[index++]
                when {
                    character == '"' -> return result.toString()
                    character == '\\' -> {
                        require(index < source.length)
                        when (val escaped = source[index++]) {
                            '"', '\\', '/' -> result.append(escaped)
                            'b' -> result.append('\b')
                            'f' -> result.append('\u000c')
                            'n' -> result.append('\n')
                            'r' -> result.append('\r')
                            't' -> result.append('\t')
                            'u' -> {
                                require(index + 4 <= source.length)
                                val codeUnit = source.substring(index, index + 4).toIntOrNull(16)
                                require(codeUnit != null)
                                result.append(codeUnit.toChar())
                                index += 4
                            }
                            else -> throw IllegalArgumentException("Invalid JSON escape")
                        }
                    }
                    character.code < 0x20 -> throw IllegalArgumentException("Invalid JSON string character")
                    else -> result.append(character)
                }
            }
            throw IllegalArgumentException("Unterminated JSON string")
        }

        private fun parsePrimitive() {
            val start = index
            while (index < source.length && source[index] !in PRIMITIVE_DELIMITERS) index++
            require(index > start)
        }

        private fun skipWhitespace() {
            while (index < source.length && source[index] in JSON_WHITESPACE) index++
        }

        private fun expect(expected: Char) {
            require(index < source.length && source[index] == expected)
            index++
        }

        private fun consumeIf(expected: Char): Boolean {
            if (index >= source.length || source[index] != expected) return false
            index++
            return true
        }
    }

    private const val MAX_DEPTH = 64
    private const val JSON_WHITESPACE = " \t\r\n"
    private const val PRIMITIVE_DELIMITERS = " \t\r\n,]}"
}

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
