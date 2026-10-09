package com.shilapi.xcertplay.dsp

import android.util.JsonReader
import android.util.JsonToken
import com.shilapi.xcertplay.media.dsp.DspCabinCorrectionConfig
import com.shilapi.xcertplay.media.dsp.DspCabinCut
import com.shilapi.xcertplay.media.dsp.DspCabinCutSource
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import org.json.JSONArray
import org.json.JSONObject

data class DspCabinMeasurementPoint(
    val frequencyHz: Double,
    val magnitudeDb: Double,
) {
    init {
        require(frequencyHz.isFinite() && frequencyHz >= MIN_FREQUENCY_HZ)
        require(magnitudeDb.isFinite() && magnitudeDb in MIN_MAGNITUDE_DB..MAX_MAGNITUDE_DB)
    }

    companion object {
        private const val MIN_FREQUENCY_HZ = 20.0
        private const val MIN_MAGNITUDE_DB = -200.0
        private const val MAX_MAGNITUDE_DB = 200.0
    }
}

data class DspCabinCalibrationData(
    val id: String,
    val formatVersion: Int,
    val sampleRate: Int,
    val position: String,
    val measuredAt: String,
    val microphoneCorrectionApplied: Boolean,
    val points: List<DspCabinMeasurementPoint>,
) {
    init {
        require(ID_PATTERN.matches(id))
        require(formatVersion == FORMAT_VERSION)
        require(sampleRate in MIN_SAMPLE_RATE..MAX_SAMPLE_RATE)
        require(position.isNotBlank() && position.length <= MAX_POSITION_LENGTH && position.none(Char::isISOControl))
        require(measuredAt.isNotBlank() && measuredAt.length <= MAX_MEASURED_AT_LENGTH)
        require(points.size in MIN_POINTS..MAX_POINTS)
        require(points.all { it.frequencyHz <= sampleRate * 0.5 })
        require(points.zipWithNext().all { (left, right) -> left.frequencyHz < right.frequencyHz })
    }

    companion object {
        const val FORMAT_VERSION = 1
        const val MAX_INPUT_BYTES = 512 * 1024
        const val MAX_POINTS = 8_000
        private const val MIN_POINTS = 5
        private const val MIN_SAMPLE_RATE = 8_000
        private const val MAX_SAMPLE_RATE = 192_000
        private const val MAX_POSITION_LENGTH = 80
        private const val MAX_MEASURED_AT_LENGTH = 64
        private val ID_PATTERN = Regex("^[A-Za-z0-9_-]{1,64}$")
    }
}

object DspCabinCalibrationParser {
    fun parse(id: String, input: InputStream): DspCabinCalibrationData {
        val bytes = readBounded(input)
        val text = decodeUtf8(bytes).removePrefix("\uFEFF").trim()
        require(text.isNotEmpty()) { "Measurement file is empty." }
        return if (text.startsWith("{")) parseJson(id, text) else parseCsv(id, text)
    }

    fun encodeJson(data: DspCabinCalibrationData): ByteArray {
        val points = JSONArray()
        data.points.forEach { point ->
            points.put(JSONArray().put(point.frequencyHz).put(point.magnitudeDb))
        }
        return JSONObject()
            .put("format", "diplay-cabin-calibration-v1")
            .put("formatVersion", data.formatVersion)
            .put("sampleRate", data.sampleRate)
            .put("position", data.position)
            .put("measuredAt", data.measuredAt)
            .put("microphoneCorrectionApplied", data.microphoneCorrectionApplied)
            .put("points", points)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
    }

    fun parseCsv(id: String, text: String): DspCabinCalibrationData {
        require(text.toByteArray(StandardCharsets.UTF_8).size <= DspCabinCalibrationData.MAX_INPUT_BYTES) {
            "Measurement file exceeds the supported size."
        }
        val metadata = linkedMapOf<String, String>()
        val points = ArrayList<DspCabinMeasurementPoint>()
        var headerSeen = false
        var dataStarted = false
        for (rawLine in text.removePrefix("\uFEFF").lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#")) {
                require(!dataStarted) { "Metadata must appear before measurement rows." }
                val entry = line.removePrefix("#").trim()
                val separator = entry.indexOf('=')
                require(separator > 0) { "Invalid measurement metadata." }
                val key = entry.substring(0, separator).trim()
                val value = entry.substring(separator + 1).trim()
                require(key in REQUIRED_METADATA_KEYS) { "Unsupported measurement metadata: $key" }
                require(metadata.putIfAbsent(key, value) == null) { "Duplicate measurement metadata: $key" }
                continue
            }
            if (!headerSeen) {
                require(line.equals("frequencyHz,magnitudeDb", ignoreCase = true)) {
                    "Expected the frequencyHz,magnitudeDb CSV header."
                }
                headerSeen = true
                continue
            }
            dataStarted = true
            require(points.size < DspCabinCalibrationData.MAX_POINTS) { "Measurement has too many rows." }
            val columns = line.split(',')
            require(columns.size == 2) { "Each measurement row must have frequencyHz,magnitudeDb." }
            val frequencyHz = columns[0].trim().toDoubleOrNull()
                ?: throw IllegalArgumentException("Invalid measurement frequency.")
            val magnitudeDb = columns[1].trim().toDoubleOrNull()
                ?: throw IllegalArgumentException("Invalid measurement magnitude.")
            points += DspCabinMeasurementPoint(frequencyHz, magnitudeDb)
        }
        require(headerSeen && dataStarted) { "Measurement CSV is missing its header or data rows." }
        return makeData(id, metadata, points)
    }

    fun parseJson(id: String, text: String): DspCabinCalibrationData {
        require(text.toByteArray(StandardCharsets.UTF_8).size <= DspCabinCalibrationData.MAX_INPUT_BYTES) {
            "Measurement file exceeds the supported size."
        }
        val metadata = HashMap<String, Any>()
        var points: List<DspCabinMeasurementPoint>? = null
        JsonReader(StringReader(text)).use { reader ->
            reader.isLenient = false
            reader.beginObject()
            val names = HashSet<String>()
            while (reader.hasNext()) {
                val name = reader.nextName()
                require(names.add(name)) { "Duplicate measurement property: $name" }
                when (name) {
                    "format" -> metadata[name] = readString(reader)
                    "formatVersion" -> metadata[name] = readInt(reader)
                    "sampleRate" -> metadata[name] = readInt(reader)
                    "position" -> metadata[name] = readString(reader)
                    "measuredAt" -> metadata[name] = readString(reader)
                    "microphoneCorrectionApplied" -> metadata[name] = readBoolean(reader)
                    "points" -> points = readPoints(reader)
                    else -> throw IllegalArgumentException("Unsupported measurement property: $name")
                }
            }
            reader.endObject()
            require(reader.peek() == JsonToken.END_DOCUMENT) { "Unexpected data after measurement JSON." }
        }
        val jsonMetadata = mapOf(
            "format" to (metadata["format"] ?: throw IllegalArgumentException("Missing format.")),
            "formatVersion" to (metadata["formatVersion"] ?: throw IllegalArgumentException("Missing formatVersion.")),
            "sampleRate" to (metadata["sampleRate"] ?: throw IllegalArgumentException("Missing sampleRate.")),
            "position" to (metadata["position"] ?: throw IllegalArgumentException("Missing position.")),
            "measuredAt" to (metadata["measuredAt"] ?: throw IllegalArgumentException("Missing measuredAt.")),
            "microphoneCorrectionApplied" to (
                metadata["microphoneCorrectionApplied"]
                    ?: throw IllegalArgumentException("Missing microphoneCorrectionApplied.")
            ),
        )
        return makeData(id, jsonMetadata.mapValues { it.value.toString() }, requireNotNull(points))
    }

    private fun readPoints(reader: JsonReader): List<DspCabinMeasurementPoint> {
        val points = ArrayList<DspCabinMeasurementPoint>()
        reader.beginArray()
        while (reader.hasNext()) {
            require(points.size < DspCabinCalibrationData.MAX_POINTS) { "Measurement has too many rows." }
            val point = when (reader.peek()) {
                JsonToken.BEGIN_ARRAY -> {
                    reader.beginArray()
                    val frequency = readDouble(reader)
                    val magnitude = readDouble(reader)
                    require(reader.peek() == JsonToken.END_ARRAY) { "Each point must contain exactly two values." }
                    reader.endArray()
                    DspCabinMeasurementPoint(frequency, magnitude)
                }
                JsonToken.BEGIN_OBJECT -> {
                    reader.beginObject()
                    val names = HashSet<String>()
                    var frequency: Double? = null
                    var magnitude: Double? = null
                    while (reader.hasNext()) {
                        val name = reader.nextName()
                        require(names.add(name)) { "Duplicate point property: $name" }
                        when (name) {
                            "frequencyHz" -> frequency = readDouble(reader)
                            "magnitudeDb" -> magnitude = readDouble(reader)
                            else -> throw IllegalArgumentException("Unsupported point property: $name")
                        }
                    }
                    reader.endObject()
                    DspCabinMeasurementPoint(
                        frequencyHz = requireNotNull(frequency) { "Missing frequencyHz." },
                        magnitudeDb = requireNotNull(magnitude) { "Missing magnitudeDb." },
                    )
                }
                else -> throw IllegalArgumentException("Each point must be an object or a pair of numbers.")
            }
            points += point
        }
        reader.endArray()
        return points
    }

    private fun readInt(reader: JsonReader): Int {
        require(reader.peek() == JsonToken.NUMBER) { "Expected an integer number." }
        val value = reader.nextDouble()
        require(value.isFinite() && value % 1.0 == 0.0 && value in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) {
            "Invalid integer number."
        }
        return value.toInt()
    }

    private fun readDouble(reader: JsonReader): Double {
        require(reader.peek() == JsonToken.NUMBER) { "Expected a numeric value." }
        return reader.nextDouble().also { require(it.isFinite()) { "Measurement values must be finite." } }
    }

    private fun readString(reader: JsonReader): String {
        require(reader.peek() == JsonToken.STRING) { "Expected a string value." }
        return reader.nextString()
    }

    private fun readBoolean(reader: JsonReader): Boolean {
        require(reader.peek() == JsonToken.BOOLEAN) { "Expected a boolean value." }
        return reader.nextBoolean()
    }

    private fun makeData(
        id: String,
        metadata: Map<String, String>,
        points: List<DspCabinMeasurementPoint>,
    ): DspCabinCalibrationData {
        require(metadata["format"] == "diplay-cabin-calibration-v1") {
            "Unsupported measurement format."
        }
        val formatVersion = metadata.required("formatVersion").toIntOrNull()
            ?: throw IllegalArgumentException("Invalid formatVersion.")
        val sampleRate = metadata.required("sampleRate").toIntOrNull()
            ?: throw IllegalArgumentException("Invalid sampleRate.")
        val microphoneCorrection = when (metadata.required("microphoneCorrectionApplied")) {
            "true" -> true
            "false" -> false
            else -> throw IllegalArgumentException("microphoneCorrectionApplied must be true or false.")
        }
        return DspCabinCalibrationData(
            id = id,
            formatVersion = formatVersion,
            sampleRate = sampleRate,
            position = metadata.required("position"),
            measuredAt = metadata.required("measuredAt"),
            microphoneCorrectionApplied = microphoneCorrection,
            points = points,
        )
    }

    private fun Map<String, String>.required(key: String): String =
        get(key)?.takeIf(String::isNotBlank) ?: throw IllegalArgumentException("Missing $key metadata.")

    private fun readBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > DspCabinCalibrationData.MAX_INPUT_BYTES) throw DspCabinMeasurementTooLargeException()
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun decodeUtf8(bytes: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        throw IllegalArgumentException("Measurement must be UTF-8 text.")
    }

    private val REQUIRED_METADATA_KEYS = setOf(
        "format",
        "formatVersion",
        "sampleRate",
        "position",
        "measuredAt",
        "microphoneCorrectionApplied",
    )
}

class DspCabinMeasurementTooLargeException : IllegalArgumentException("Measurement file exceeds the supported size.")

data class DspCabinPeakCandidate(
    val frequencyHz: Double,
    val q: Double,
    val prominenceDb: Double,
    val confidence: Double,
    val reductionDb: Double,
) {
    fun toCut(enabled: Boolean = false): DspCabinCut = DspCabinCut(
        frequencyHz = frequencyHz,
        q = q,
        reductionDb = reductionDb,
        enabled = enabled,
        source = DspCabinCutSource.MEASUREMENT,
        prominenceDb = prominenceDb,
        confidence = confidence,
    )
}

object DspCabinPeakDetector {
    private const val LOW_FREQUENCY_HZ = 40.0
    private const val HIGH_FREQUENCY_HZ = 250.0
    private const val GRID_STEPS_PER_OCTAVE = 24
    private const val SMOOTHING_RADIUS = 1
    private const val BASELINE_RADIUS = 12
    private const val PROMINENCE_RADIUS = 6
    private const val MIN_PROMINENCE_DB = 3.0
    private const val MIN_Q = 0.5
    private const val MAX_Q = 8.0
    private const val MIN_PEAK_GAP_OCTAVES = 1.0 / 12.0

    fun detect(points: List<DspCabinMeasurementPoint>): List<DspCabinPeakCandidate> {
        require(points.size >= 5)
        require(points.zipWithNext().all { (left, right) -> left.frequencyHz < right.frequencyHz })
        require(points.all { it.frequencyHz.isFinite() && it.magnitudeDb.isFinite() })
        val firstGridIndex = ceil(log2(LOW_FREQUENCY_HZ / LOW_FREQUENCY_HZ) * GRID_STEPS_PER_OCTAVE).toInt()
        val lastGridIndex = floor(log2(HIGH_FREQUENCY_HZ / LOW_FREQUENCY_HZ) * GRID_STEPS_PER_OCTAVE).toInt()
        val grid = (firstGridIndex..lastGridIndex).map { index ->
            LOW_FREQUENCY_HZ * 2.0.pow(index.toDouble() / GRID_STEPS_PER_OCTAVE)
        }.filter { it in points.first().frequencyHz..points.last().frequencyHz }
        if (grid.size < 2 * (BASELINE_RADIUS + SMOOTHING_RADIUS) + 3) return emptyList()

        val sampled = grid.map { frequency -> interpolateLogFrequency(points, frequency) }
        val smoothed = sampled.indices.map { index ->
            val start = max(0, index - SMOOTHING_RADIUS)
            val end = min(sampled.lastIndex, index + SMOOTHING_RADIUS)
            sampled.subList(start, end + 1).average()
        }
        val candidates = ArrayList<DspCabinPeakCandidate>()
        for (index in 1 until grid.lastIndex) {
            if (index < BASELINE_RADIUS || index > grid.lastIndex - BASELINE_RADIUS) continue
            if (smoothed[index] <= smoothed[index - 1] || smoothed[index] < smoothed[index + 1]) continue
            val baseline = localBaseline(smoothed, index) ?: continue
            val prominence = smoothed[index] - baseline
            if (prominence < MIN_PROMINENCE_DB) continue
            val logStep = ln(2.0) / GRID_STEPS_PER_OCTAVE
            val curvature = (smoothed[index - 1] - 2.0 * smoothed[index] + smoothed[index + 1]) /
                (logStep * logStep)
            if (curvature >= 0.0) continue
            val sigma = sqrt(prominence / -curvature)
            val q = 1.0 / (2.0 * sqrt(2.0 * ln(2.0)) * sigma)
            if (q !in MIN_Q..MAX_Q) continue
            candidates += DspCabinPeakCandidate(
                frequencyHz = grid[index],
                q = q,
                prominenceDb = prominence.coerceAtMost(24.0),
                confidence = (prominence / 6.0).coerceIn(0.0, 1.0),
                reductionDb = -min(6.0, prominence),
            )
        }
        val separated = ArrayList<DspCabinPeakCandidate>()
        for (candidate in candidates.sortedByDescending(DspCabinPeakCandidate::prominenceDb)) {
            val tooClose = separated.any { existing ->
                abs(log2(existing.frequencyHz / candidate.frequencyHz)) < MIN_PEAK_GAP_OCTAVES
            }
            if (!tooClose) separated += candidate
            if (separated.size == DspCabinCorrectionConfig.MAX_CUTS) break
        }
        return separated.sortedBy(DspCabinPeakCandidate::frequencyHz)
    }

    private fun interpolateLogFrequency(points: List<DspCabinMeasurementPoint>, frequencyHz: Double): Double {
        var upper = points.binarySearch { it.frequencyHz.compareTo(frequencyHz) }
        if (upper >= 0) return points[upper].magnitudeDb
        upper = -upper - 1
        if (upper <= 0) return points.first().magnitudeDb
        if (upper >= points.size) return points.last().magnitudeDb
        val lowerPoint = points[upper - 1]
        val upperPoint = points[upper]
        val fraction = (ln(frequencyHz) - ln(lowerPoint.frequencyHz)) /
            (ln(upperPoint.frequencyHz) - ln(lowerPoint.frequencyHz))
        return lowerPoint.magnitudeDb + (upperPoint.magnitudeDb - lowerPoint.magnitudeDb) * fraction
    }

    private fun localBaseline(values: List<Double>, peakIndex: Int): Double? {
        val left = (peakIndex - BASELINE_RADIUS until peakIndex - PROMINENCE_RADIUS)
            .map { values[it] }
        val right = (peakIndex + PROMINENCE_RADIUS + 1..peakIndex + BASELINE_RADIUS)
            .map { values[it] }
        if (left.size < 2 || right.size < 2) return null
        val leftBaseline = median(left)
        val rightBaseline = median(right)
        val lowerBaseline = min(leftBaseline, rightBaseline)
        return lowerBaseline
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) * 0.5 else sorted[middle]
    }

}
