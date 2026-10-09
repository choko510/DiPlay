package com.shilapi.xcertplay.dsp

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

data class DspCabinCalibrationSummary(
    val id: String,
    val sampleRate: Int,
    val position: String,
    val measuredAt: String,
    val microphoneCorrectionApplied: Boolean,
    val pointCount: Int,
)

sealed interface DspCabinCalibrationImportResult {
    data class Imported(
        val summary: DspCabinCalibrationSummary,
        val candidates: List<DspCabinPeakCandidate>,
    ) : DspCabinCalibrationImportResult

    data object InvalidMeasurement : DspCabinCalibrationImportResult
    data object TooLarge : DspCabinCalibrationImportResult
    data object WriteFailed : DspCabinCalibrationImportResult
    data object AlreadyExists : DspCabinCalibrationImportResult
}

class DspCabinCalibrationRepository(private val directory: File) {
    constructor(context: Context) : this(File(context.filesDir, CALIBRATION_DIRECTORY))
    private val validatedIds = ConcurrentHashMap.newKeySet<String>()

    fun import(id: String, openInput: () -> InputStream?): DspCabinCalibrationImportResult {
        if (!ID_PATTERN.matches(id)) return DspCabinCalibrationImportResult.InvalidMeasurement
        if (fileFor(id).exists() || backupFor(id).exists()) return DspCabinCalibrationImportResult.AlreadyExists
        val data = try {
            openInput()?.use { DspCabinCalibrationParser.parse(id, it) }
                ?: return DspCabinCalibrationImportResult.InvalidMeasurement
        } catch (_: DspCabinMeasurementTooLargeException) {
            return DspCabinCalibrationImportResult.TooLarge
        } catch (_: Exception) {
            return DspCabinCalibrationImportResult.InvalidMeasurement
        }
        val candidates = runCatching { DspCabinPeakDetector.detect(data.points) }.getOrElse {
            return DspCabinCalibrationImportResult.InvalidMeasurement
        }
        if (!write(data)) return DspCabinCalibrationImportResult.WriteFailed
        validatedIds += id
        return DspCabinCalibrationImportResult.Imported(
            summary = data.toSummary(),
            candidates = candidates,
        )
    }

    fun load(id: String): DspCabinCalibrationData? {
        if (!ID_PATTERN.matches(id)) return null
        val file = fileFor(id)
        if (file.length() > DspCabinCalibrationData.MAX_INPUT_BYTES) return null
        return runCatching {
            AtomicFile(file).openRead().use { DspCabinCalibrationParser.parse(id, it) }
        }.getOrNull()
    }

    fun contains(id: String): Boolean {
        if (!ID_PATTERN.matches(id)) return false
        if (id in validatedIds) return true
        val valid = load(id) != null
        if (valid) validatedIds += id
        return valid
    }

    fun available(): List<DspCabinCalibrationSummary> = directory.listFiles()
        .orEmpty()
        .asSequence()
        .filter { it.isFile && (it.name.endsWith(FILE_SUFFIX) || it.name.endsWith(FILE_BACKUP_SUFFIX)) }
        .mapNotNull { file ->
            val id = if (file.name.endsWith(FILE_BACKUP_SUFFIX)) {
                file.name.removeSuffix(FILE_BACKUP_SUFFIX)
            } else {
                file.name.removeSuffix(FILE_SUFFIX)
            }
            load(id)?.toSummary()
        }
        .sortedByDescending(DspCabinCalibrationSummary::measuredAt)
        .toList()

    fun delete(id: String): Boolean {
        if (!ID_PATTERN.matches(id)) return false
        val file = fileFor(id)
        if (!file.exists() && !backupFor(id).exists()) return false
        return runCatching {
            AtomicFile(file).delete()
            validatedIds -= id
            true
        }.getOrDefault(false)
    }

    private fun write(data: DspCabinCalibrationData): Boolean {
        if (!directory.exists() && !directory.mkdirs()) return false
        val bytes = runCatching { DspCabinCalibrationParser.encodeJson(data) }.getOrNull() ?: return false
        if (bytes.size > DspCabinCalibrationData.MAX_INPUT_BYTES) return false
        val atomicFile = AtomicFile(fileFor(data.id))
        var stream: java.io.FileOutputStream? = null
        return try {
            stream = atomicFile.startWrite()
            stream.write(bytes)
            atomicFile.finishWrite(stream)
            true
        } catch (_: Exception) {
            stream?.let(atomicFile::failWrite)
            false
        }
    }

    private fun fileFor(id: String): File = File(directory, "$id$FILE_SUFFIX")

    private fun backupFor(id: String): File = File(directory, "$id$FILE_BACKUP_SUFFIX")

    private fun DspCabinCalibrationData.toSummary(): DspCabinCalibrationSummary = DspCabinCalibrationSummary(
        id = id,
        sampleRate = sampleRate,
        position = position,
        measuredAt = measuredAt,
        microphoneCorrectionApplied = microphoneCorrectionApplied,
        pointCount = points.size,
    )

    companion object {
        private const val CALIBRATION_DIRECTORY = "dsp/cabin-calibrations"
        private const val FILE_SUFFIX = ".json"
        private const val FILE_BACKUP_SUFFIX = ".json.bak"
        private val ID_PATTERN = Regex("^[A-Za-z0-9_-]{1,64}$")
    }
}
