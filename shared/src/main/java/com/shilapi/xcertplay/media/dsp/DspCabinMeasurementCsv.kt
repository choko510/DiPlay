package com.shilapi.xcertplay.media.dsp

import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.log2

data class DspCabinMeasurementPoint(val frequencyHz: Double, val levelDb: Double)

sealed interface DspCabinMeasurementParseResult {
    data class Available(
        val correction: DspCabinCorrectionConfig,
        val suggestedNotchCount: Int,
    ) : DspCabinMeasurementParseResult

    data class CalibrationUnavailable(val reason: Reason) : DspCabinMeasurementParseResult {
        enum class Reason {
            INPUT_TOO_LARGE,
            INVALID_METADATA,
            CLIPPING_REPORTED,
            TOO_FEW_POINTS,
            TOO_MANY_POINTS,
            FREQUENCY_COVERAGE,
            RESOLUTION,
            NOISE_MARGIN,
            INVALID_DATA,
        }
    }
}

private typealias CabinMeasurementReason = DspCabinMeasurementParseResult.CalibrationUnavailable.Reason

object DspCabinMeasurementCsv {
    const val MAX_INPUT_CHARACTERS = 65_536
    private const val FREQUENCY_HEADER = "frequency_hz,level_db"
    private const val FORMAT_KEY = "format"
    private const val FORMAT_VALUE = "diplay-cabin-v1"
    private const val NOISE_FLOOR_KEY = "noise_floor_db"
    private const val CLIPPING_KEY = "clipped"
    private const val EXPECTED_OCTAVE_STEP = 1.0 / 6.0
    private const val OCTAVE_STEP_TOLERANCE = 0.035
    private const val MIN_SIGNAL_NOISE_MARGIN_DB = 12.0
    private const val MIN_PEAK_PROMINENCE_DB = 3.0
    private const val MAX_RECOMMENDED_NOTCH_GAIN_DB = 12.0
    private const val MIN_NOTCH_SEPARATION_OCTAVES = 1.0 / 3.0

    fun parse(contents: String): DspCabinMeasurementParseResult {
        if (contents.length > MAX_INPUT_CHARACTERS) return unavailable(CabinMeasurementReason.INPUT_TOO_LARGE)
        val metadata = LinkedHashMap<String, String>()
        val pointLines = ArrayList<String>()
        var hasHeader = false
        var firstLine = true
        for (rawLine in contents.lineSequence()) {
            val line = (if (firstLine) rawLine.removePrefix("\uFEFF") else rawLine).trim()
            firstLine = false
            if (line.isEmpty()) continue
            if (line.startsWith('#')) {
                val entry = line.removePrefix("#").trim().split('=', limit = 2)
                if (entry.size != 2 || entry[0].isBlank() || metadata.put(entry[0].trim(), entry[1].trim()) != null) {
                    return unavailable(CabinMeasurementReason.INVALID_METADATA)
                }
            } else if (!hasHeader && line.equals(FREQUENCY_HEADER, ignoreCase = true)) {
                hasHeader = true
            } else if (hasHeader) {
                pointLines += line
                if (pointLines.size > DspCabinMeasurementSummary.MAX_POINTS) return unavailable(CabinMeasurementReason.TOO_MANY_POINTS)
            } else {
                return unavailable(CabinMeasurementReason.INVALID_DATA)
            }
        }
        if (metadata[FORMAT_KEY] != FORMAT_VALUE) return unavailable(CabinMeasurementReason.INVALID_METADATA)
        val noiseFloorDb = metadata[NOISE_FLOOR_KEY]?.toDoubleOrNull()
            ?: return unavailable(CabinMeasurementReason.INVALID_METADATA)
        if (!noiseFloorDb.isFinite() || noiseFloorDb !in DspCabinMeasurementSummary.MIN_NOISE_FLOOR_DB..
            DspCabinMeasurementSummary.MAX_NOISE_FLOOR_DB) {
            return unavailable(CabinMeasurementReason.INVALID_METADATA)
        }
        val clipped = metadata[CLIPPING_KEY]?.toBooleanStrictOrNull()
            ?: return unavailable(CabinMeasurementReason.INVALID_METADATA)
        if (clipped) return unavailable(CabinMeasurementReason.CLIPPING_REPORTED)
        val points = pointLines.map { line ->
            val values = line.split(',')
            if (values.size != 2) return unavailable(CabinMeasurementReason.INVALID_DATA)
            val frequency = values[0].trim().toDoubleOrNull() ?: return unavailable(CabinMeasurementReason.INVALID_DATA)
            val level = values[1].trim().toDoubleOrNull() ?: return unavailable(CabinMeasurementReason.INVALID_DATA)
            if (!frequency.isFinite() || frequency <= 0.0 || !level.isFinite() || level !in -160.0..120.0) {
                return unavailable(CabinMeasurementReason.INVALID_DATA)
            }
            DspCabinMeasurementPoint(frequency, level)
        }
        if (points.size < DspCabinMeasurementSummary.MIN_POINTS) return unavailable(CabinMeasurementReason.TOO_FEW_POINTS)
        if (points.first().frequencyHz > DspCabinCorrectionNotch.MIN_FREQUENCY_HZ ||
            points.last().frequencyHz < DspCabinCorrectionNotch.MAX_FREQUENCY_HZ) {
            return unavailable(CabinMeasurementReason.FREQUENCY_COVERAGE)
        }
        if (points.zipWithNext().any { (first, second) ->
                second.frequencyHz <= first.frequencyHz ||
                    abs(log2(second.frequencyHz / first.frequencyHz) - EXPECTED_OCTAVE_STEP) > OCTAVE_STEP_TOLERANCE
            }) {
            return unavailable(CabinMeasurementReason.RESOLUTION)
        }
        if (points.minOf(DspCabinMeasurementPoint::levelDb) - noiseFloorDb < MIN_SIGNAL_NOISE_MARGIN_DB) {
            return unavailable(CabinMeasurementReason.NOISE_MARGIN)
        }

        val summary = DspCabinMeasurementSummary(
            sourceSha256 = sha256(contents),
            pointCount = points.size,
            minimumFrequencyHz = points.first().frequencyHz,
            maximumFrequencyHz = points.last().frequencyHz,
            noiseFloorDb = noiseFloorDb,
            clippingDetected = false,
        )
        val notches = suggestNotches(points)
        return DspCabinMeasurementParseResult.Available(
            correction = DspCabinCorrectionConfig(measurement = summary, notches = notches),
            suggestedNotchCount = notches.size,
        )
    }

    private fun suggestNotches(points: List<DspCabinMeasurementPoint>): List<DspCabinCorrectionNotch> {
        val candidates = (1 until points.lastIndex).mapNotNull { index ->
            val previous = points[index - 1]
            val peak = points[index]
            val next = points[index + 1]
            if (peak.frequencyHz !in DspCabinCorrectionNotch.MIN_FREQUENCY_HZ..DspCabinCorrectionNotch.MAX_FREQUENCY_HZ ||
                peak.levelDb < previous.levelDb || peak.levelDb < next.levelDb) {
                null
            } else {
                val prominenceDb = peak.levelDb - maxOf(previous.levelDb, next.levelDb)
                if (prominenceDb < MIN_PEAK_PROMINENCE_DB) null else peak to prominenceDb
            }
        }.sortedByDescending { it.second }
        val selected = ArrayList<DspCabinCorrectionNotch>(DspCabinCorrectionConfig.MAX_NOTCHES)
        for ((point, prominenceDb) in candidates) {
            if (selected.size == DspCabinCorrectionConfig.MAX_NOTCHES) break
            if (selected.any { abs(log2(point.frequencyHz / it.frequencyHz)) < MIN_NOTCH_SEPARATION_OCTAVES }) continue
            selected += DspCabinCorrectionNotch(
                enabled = false,
                frequencyHz = point.frequencyHz,
                gainDb = -minOf(MAX_RECOMMENDED_NOTCH_GAIN_DB, maxOf(3.0, prominenceDb * 0.75)),
                q = 4.0,
            )
        }
        return selected.sortedBy(DspCabinCorrectionNotch::frequencyHz)
    }

    private fun sha256(contents: String): String = MessageDigest.getInstance("SHA-256")
        .digest(contents.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun unavailable(reason: CabinMeasurementReason): DspCabinMeasurementParseResult.CalibrationUnavailable =
        DspCabinMeasurementParseResult.CalibrationUnavailable(reason)

}
