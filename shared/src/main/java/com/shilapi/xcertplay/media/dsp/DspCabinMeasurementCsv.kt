package com.shilapi.xcertplay.media.dsp

import kotlin.math.ln

object DspCabinMeasurementCsv {
    private const val MAX_CHARACTERS = 256 * 1024
    private const val MAX_SOURCE_POINTS = 4_096
    private const val MIN_NOISE_MARGIN_DB = 10.0
    private const val MAX_OCTAVE_STEP = 1.122462048309373
    private const val MIN_PEAK_PROMINENCE_DB = 4.0
    private const val MIN_PEAK_SPACING_OCTAVES = 1.0 / 6.0
    private const val MAX_SOURCE_FREQUENCY_HZ = 20_000.0

    fun parse(csv: String): DspCabinMeasurementImportResult {
        if (csv.length > MAX_CHARACTERS) return DspCabinMeasurementImportResult.TooLarge
        var noiseFloorDb: Double? = null
        var clippedSamples: Int? = null
        var headerSeen = false
        val points = ArrayList<MeasurementPoint>()

        for (rawLine in csv.removePrefix("\uFEFF").lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (line.startsWith('#')) {
                val metadata = line.drop(1).split('=', limit = 2)
                if (metadata.size != 2) continue
                when (metadata[0].trim().lowercase()) {
                    "noise_floor_db" -> {
                        val value = metadata[1].trim().toDoubleOrNull() ?: return DspCabinMeasurementImportResult.InvalidCsv
                        if (noiseFloorDb != null && noiseFloorDb != value) return DspCabinMeasurementImportResult.InvalidCsv
                        noiseFloorDb = value
                    }
                    "clipped_samples" -> {
                        val value = metadata[1].trim().toIntOrNull() ?: return DspCabinMeasurementImportResult.InvalidCsv
                        if (clippedSamples != null && clippedSamples != value) return DspCabinMeasurementImportResult.InvalidCsv
                        clippedSamples = value
                    }
                }
                continue
            }

            if (!headerSeen) {
                val header = line.split(',').map { it.trim().lowercase() }
                if (header !in SUPPORTED_HEADERS) return DspCabinMeasurementImportResult.InvalidCsv
                headerSeen = true
                continue
            }

            if (points.size >= MAX_SOURCE_POINTS) return DspCabinMeasurementImportResult.TooLarge
            val columns = line.split(',')
            if (columns.size != 2) return DspCabinMeasurementImportResult.InvalidCsv
            val frequencyHz = columns[0].trim().toDoubleOrNull() ?: return DspCabinMeasurementImportResult.InvalidCsv
            val levelDb = columns[1].trim().toDoubleOrNull() ?: return DspCabinMeasurementImportResult.InvalidCsv
            if (!frequencyHz.isFinite() || frequencyHz !in 1.0..MAX_SOURCE_FREQUENCY_HZ ||
                !levelDb.isFinite() || levelDb !in -120.0..140.0) {
                return DspCabinMeasurementImportResult.InvalidCsv
            }
            if (points.lastOrNull()?.frequencyHz?.let { frequencyHz <= it } == true) {
                return DspCabinMeasurementImportResult.InvalidCsv
            }
            points += MeasurementPoint(frequencyHz, levelDb)
        }

        if (!headerSeen || points.isEmpty()) return DspCabinMeasurementImportResult.InvalidCsv
        val noiseFloor = noiseFloorDb
            ?: return DspCabinMeasurementImportResult.CalibrationUnavailable(
                DspCabinUnavailableReason.MISSING_QUALITY_METADATA,
            )
        val clipped = clippedSamples
            ?: return DspCabinMeasurementImportResult.CalibrationUnavailable(
                DspCabinUnavailableReason.MISSING_QUALITY_METADATA,
            )
        if (!noiseFloor.isFinite() || noiseFloor !in DspCabinMeasurementSummary.MIN_NOISE_FLOOR_DB..
            DspCabinMeasurementSummary.MAX_NOISE_FLOOR_DB || clipped < 0) {
            return DspCabinMeasurementImportResult.CalibrationUnavailable(
                DspCabinUnavailableReason.MISSING_QUALITY_METADATA,
            )
        }
        if (clipped > 0) {
            return DspCabinMeasurementImportResult.CalibrationUnavailable(DspCabinUnavailableReason.INPUT_CLIPPED)
        }

        val lowPoints = points.filter { it.frequencyHz in DspCabinMeasurementSummary.MIN_FREQUENCY_HZ..
            DspCabinMeasurementSummary.MAX_FREQUENCY_HZ }
        if (lowPoints.size < DspCabinMeasurementSummary.MIN_POINT_COUNT) {
            return DspCabinMeasurementImportResult.CalibrationUnavailable(DspCabinUnavailableReason.TOO_FEW_POINTS)
        }
        if (lowPoints.first().frequencyHz > MIN_COVERED_FREQUENCY_HZ ||
            lowPoints.last().frequencyHz < MAX_COVERED_FREQUENCY_HZ) {
            return DspCabinMeasurementImportResult.CalibrationUnavailable(
                DspCabinUnavailableReason.INSUFFICIENT_FREQUENCY_SPAN,
            )
        }
        if (lowPoints.zipWithNext().any { (left, right) -> right.frequencyHz / left.frequencyHz > MAX_OCTAVE_STEP }) {
            return DspCabinMeasurementImportResult.CalibrationUnavailable(
                DspCabinUnavailableReason.INSUFFICIENT_FREQUENCY_RESOLUTION,
            )
        }
        if (lowPoints.any { it.levelDb - noiseFloor < MIN_NOISE_MARGIN_DB }) {
            return DspCabinMeasurementImportResult.CalibrationUnavailable(DspCabinUnavailableReason.NOISE_TOO_HIGH)
        }

        val summary = DspCabinMeasurementSummary(
            noiseFloorDb = noiseFloor,
            pointCount = lowPoints.size,
            minimumFrequencyHz = lowPoints.first().frequencyHz,
            maximumFrequencyHz = lowPoints.last().frequencyHz,
            clippedSamples = clipped,
        )
        val notches = findPeaks(lowPoints)
        if (notches.isEmpty()) {
            return DspCabinMeasurementImportResult.CalibrationUnavailable(
                DspCabinUnavailableReason.NO_SIGNIFICANT_PEAKS,
            )
        }
        return DspCabinMeasurementImportResult.Ready(summary, notches)
    }

    private fun findPeaks(points: List<MeasurementPoint>): List<DspCabinNotchConfig> {
        val candidates = ArrayList<PeakCandidate>()
        for (index in 2 until points.lastIndex - 1) {
            val peak = points[index]
            if (peak.levelDb < points[index - 1].levelDb || peak.levelDb < points[index + 1].levelDb) continue
            val leftBaseline = points.filter {
                it.frequencyHz in (peak.frequencyHz / 1.8)..(peak.frequencyHz / 1.25)
            }.map(MeasurementPoint::levelDb).takeIf { it.isNotEmpty() }?.let(::median) ?: continue
            val rightBaseline = points.filter {
                it.frequencyHz in (peak.frequencyHz * 1.25)..(peak.frequencyHz * 1.8)
            }.map(MeasurementPoint::levelDb).takeIf { it.isNotEmpty() }?.let(::median) ?: continue
            val prominenceDb = peak.levelDb - (leftBaseline + rightBaseline) * 0.5
            if (prominenceDb < MIN_PEAK_PROMINENCE_DB) continue

            var leftIndex = index
            while (leftIndex > 0 && points[leftIndex].levelDb > peak.levelDb - 3.0) leftIndex--
            var rightIndex = index
            while (rightIndex < points.lastIndex && points[rightIndex].levelDb > peak.levelDb - 3.0) rightIndex++
            if (leftIndex == index || rightIndex == index) continue
            val bandwidthHz = points[rightIndex].frequencyHz - points[leftIndex].frequencyHz
            if (bandwidthHz <= 0.0) continue

            candidates += PeakCandidate(
                frequencyHz = peak.frequencyHz,
                prominenceDb = prominenceDb,
                notch = DspCabinNotchConfig(
                    frequencyHz = peak.frequencyHz,
                    gainDb = (-prominenceDb * 0.5).coerceIn(-9.0, DspCabinNotchConfig.MAX_GAIN_DB),
                    q = (peak.frequencyHz / bandwidthHz).coerceIn(
                        DspCabinNotchConfig.MIN_Q,
                        DspCabinNotchConfig.MAX_Q,
                    ),
                ),
            )
        }

        val selected = ArrayList<PeakCandidate>()
        candidates.sortedByDescending(PeakCandidate::prominenceDb).forEach { candidate ->
            val tooClose = selected.any {
                kotlin.math.abs(ln(candidate.frequencyHz / it.frequencyHz)) / ln(2.0) < MIN_PEAK_SPACING_OCTAVES
            }
            if (!tooClose && selected.size < DspCabinCorrectionConfig.MAX_NOTCHES) selected += candidate
        }
        return selected.sortedBy(PeakCandidate::frequencyHz).map(PeakCandidate::notch)
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) * 0.5
        } else {
            sorted[middle]
        }
    }

    private data class MeasurementPoint(val frequencyHz: Double, val levelDb: Double)

    private data class PeakCandidate(val frequencyHz: Double, val prominenceDb: Double, val notch: DspCabinNotchConfig)

    private val SUPPORTED_HEADERS = setOf(
        listOf("frequency_hz", "level_db"),
        listOf("frequency", "spl"),
        listOf("freq", "spl"),
    )

    private const val MIN_COVERED_FREQUENCY_HZ = 25.0
    private const val MAX_COVERED_FREQUENCY_HZ = 280.0
}
