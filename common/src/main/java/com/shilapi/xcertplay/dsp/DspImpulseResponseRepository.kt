package com.shilapi.xcertplay.dsp

import android.util.AtomicFile
import com.shilapi.xcertplay.media.dsp.DspImpulseResponse
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

internal sealed interface DspImpulseImportResult {
    data class Imported(val impulseResponse: DspImpulseResponse) : DspImpulseImportResult
    data object InvalidId : DspImpulseImportResult
    data object InvalidWav : DspImpulseImportResult
    data object TooLarge : DspImpulseImportResult
    data object WriteFailed : DspImpulseImportResult
}

internal class DspImpulseResponseRepository(
    filesDirectory: File,
    private val atomicWriter: (File, ByteArray) -> Unit = ::writeImpulseAtomically,
) {
    internal val impulseDirectory = File(File(filesDirectory, "dsp"), "irs")

    fun import(id: String, input: InputStream): DspImpulseImportResult {
        val file = impulseFile(id) ?: run {
            runCatching { input.close() }
            return DspImpulseImportResult.InvalidId
        }
        val contents = when (val read = readBounded(input)) {
            is WavReadResult.Bytes -> read.value
            WavReadResult.TooLarge -> return DspImpulseImportResult.TooLarge
            WavReadResult.Invalid -> return DspImpulseImportResult.InvalidWav
        }
        val impulse = parseWav(id, contents) ?: return DspImpulseImportResult.InvalidWav
        if (!impulseDirectory.exists() && !impulseDirectory.mkdirs()) return DspImpulseImportResult.WriteFailed
        return try {
            atomicWriter(file, contents)
            DspImpulseImportResult.Imported(impulse)
        } catch (_: Exception) {
            DspImpulseImportResult.WriteFailed
        }
    }

    fun load(id: String): DspImpulseResponse? {
        val file = impulseFile(id) ?: return null
        if (!file.isFile || file.length() > MAX_FILE_BYTES) return null
        val contents = try {
            readBounded(AtomicFile(file).openRead()).let { result ->
                (result as? WavReadResult.Bytes)?.value ?: return null
            }
        } catch (_: Exception) {
            return null
        }
        return parseWav(id, contents)
    }

    fun ids(): List<String> = impulseDirectory.listFiles()
        .orEmpty()
        .asSequence()
        .filter { it.isFile && it.extension.equals(WAV_EXTENSION, ignoreCase = true) }
        .map { it.nameWithoutExtension }
        .filter(::isValidId)
        .distinct()
        .sorted()
        .toList()

    internal fun impulseFileForTesting(id: String): File? = impulseFile(id)

    private fun impulseFile(id: String): File? {
        if (!isValidId(id)) return null
        val directory = impulseDirectory.canonicalFile
        val file = File(directory, "$id.$WAV_EXTENSION").canonicalFile
        return file.takeIf { it.parentFile == directory }
    }

    private fun readBounded(input: InputStream): WavReadResult {
        return try {
            input.use { stream ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(READ_BUFFER_BYTES)
                var total = 0
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_FILE_BYTES) return WavReadResult.TooLarge
                    output.write(buffer, 0, read)
                }
                WavReadResult.Bytes(output.toByteArray())
            }
        } catch (_: Exception) {
            WavReadResult.Invalid
        }
    }

    private fun parseWav(id: String, data: ByteArray): DspImpulseResponse? {
        return try {
            if (data.size < WAV_HEADER_BYTES || fourCc(data, 0) != "RIFF" || fourCc(data, 8) != "WAVE") return null
            val riffEnd = uint32(data, 4) + 8L
            if (riffEnd < WAV_HEADER_BYTES || riffEnd > data.size) return null

            var offset = WAV_HEADER_BYTES
            var format: WavFormat? = null
            var audioDataOffset = -1
            var audioDataBytes = -1
            while (offset + CHUNK_HEADER_BYTES <= riffEnd) {
                val chunkId = fourCc(data, offset)
                val chunkSize = uint32(data, offset + 4)
                val chunkStart = offset + CHUNK_HEADER_BYTES
                val chunkEnd = chunkStart.toLong() + chunkSize
                if (chunkEnd > riffEnd) return null
                when (chunkId) {
                    "fmt " -> {
                        if (format != null || chunkSize < FMT_MIN_BYTES) return null
                        format = parseFormat(data, chunkStart)
                    }
                    "data" -> {
                        if (audioDataOffset >= 0) return null
                        audioDataOffset = chunkStart
                        audioDataBytes = chunkSize.toInt()
                    }
                }
                val paddedEnd = chunkEnd + (chunkSize and 1L)
                if (paddedEnd > riffEnd) return null
                offset = paddedEnd.toInt()
            }

            val wavFormat = format ?: return null
            if (audioDataOffset < 0 || audioDataBytes <= 0 || audioDataBytes % wavFormat.blockAlign != 0) return null
            val frames = audioDataBytes / wavFormat.blockAlign
            if (frames !in 1..DspImpulseResponse.MAX_FRAMES) return null
            val sampleCount = frames * wavFormat.channels
            val samples = FloatArray(sampleCount)
            val bytesPerSample = wavFormat.bitsPerSample / 8
            for (index in 0 until sampleCount) {
                samples[index] = readSample(data, audioDataOffset + index * bytesPerSample, wavFormat)
            }
            DspImpulseResponse(id, wavFormat.sampleRate, wavFormat.channels, samples)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseFormat(data: ByteArray, offset: Int): WavFormat? {
        val formatCode = uint16(data, offset)
        val channels = uint16(data, offset + 2)
        val sampleRate = uint32(data, offset + 4).toInt()
        val byteRate = uint32(data, offset + 8)
        val blockAlign = uint16(data, offset + 12)
        val bitsPerSample = uint16(data, offset + 14)
        if (channels !in 1..2 || sampleRate !in DspImpulseResponse.SUPPORTED_SAMPLE_RATES) return null
        val encoding = when {
            formatCode == PCM_FORMAT && bitsPerSample in PCM_BIT_DEPTHS -> WavEncoding.PCM
            formatCode == FLOAT_FORMAT && bitsPerSample == 32 -> WavEncoding.FLOAT
            else -> return null
        }
        val expectedAlign = channels * (bitsPerSample / 8)
        if (blockAlign != expectedAlign || byteRate != sampleRate.toLong() * expectedAlign) return null
        return WavFormat(encoding, channels, sampleRate, bitsPerSample, blockAlign)
    }

    private fun readSample(data: ByteArray, offset: Int, format: WavFormat): Float {
        if (format.encoding == WavEncoding.FLOAT) {
            val sample = Float.fromBits(int32(data, offset))
            require(sample.isFinite())
            return sample
        }
        return when (format.bitsPerSample) {
            16 -> (signed16(data, offset) / 32768.0).toFloat()
            24 -> (signed24(data, offset) / 8_388_608.0).toFloat()
            32 -> (int32(data, offset) / 2_147_483_648.0).toFloat()
            else -> error("Unsupported PCM depth")
        }
    }

    private fun signed16(data: ByteArray, offset: Int): Int {
        var value = (data[offset].toInt() and 0xff) or ((data[offset + 1].toInt() and 0xff) shl 8)
        if ((value and 0x8000) != 0) value = value or -0x10000
        return value
    }

    private fun signed24(data: ByteArray, offset: Int): Int {
        var value = (data[offset].toInt() and 0xff) or
            ((data[offset + 1].toInt() and 0xff) shl 8) or
            ((data[offset + 2].toInt() and 0xff) shl 16)
        if ((value and 0x800000) != 0) value = value or -0x1000000
        return value
    }

    private fun uint16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xff) or ((data[offset + 1].toInt() and 0xff) shl 8)

    private fun int32(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xff) or
            ((data[offset + 1].toInt() and 0xff) shl 8) or
            ((data[offset + 2].toInt() and 0xff) shl 16) or
            (data[offset + 3].toInt() shl 24)

    private fun uint32(data: ByteArray, offset: Int): Long =
        (data[offset].toLong() and 0xff) or
            ((data[offset + 1].toLong() and 0xff) shl 8) or
            ((data[offset + 2].toLong() and 0xff) shl 16) or
            ((data[offset + 3].toLong() and 0xff) shl 24)

    private fun fourCc(data: ByteArray, offset: Int): String =
        String(data, offset, 4, Charsets.US_ASCII)

    private fun isValidId(id: String): Boolean = ID_PATTERN.matches(id) &&
        !id.contains("..") && !id.contains('/') && !id.contains('\\') && !id.contains('\u0000')

    private data class WavFormat(
        val encoding: WavEncoding,
        val channels: Int,
        val sampleRate: Int,
        val bitsPerSample: Int,
        val blockAlign: Int,
    )

    private enum class WavEncoding { PCM, FLOAT }

    private sealed interface WavReadResult {
        data class Bytes(val value: ByteArray) : WavReadResult
        data object TooLarge : WavReadResult
        data object Invalid : WavReadResult
    }

    companion object {
        const val MAX_FILE_BYTES = 8 * 1024 * 1024
        private const val READ_BUFFER_BYTES = 8 * 1024
        private const val WAV_HEADER_BYTES = 12
        private const val CHUNK_HEADER_BYTES = 8
        private const val FMT_MIN_BYTES = 16
        private const val PCM_FORMAT = 1
        private const val FLOAT_FORMAT = 3
        private const val WAV_EXTENSION = "wav"
        private val ID_PATTERN = Regex("^[A-Za-z0-9_-]{1,64}$")
        private val PCM_BIT_DEPTHS = setOf(16, 24, 32)
    }
}

private fun writeImpulseAtomically(file: File, contents: ByteArray) {
    val atomicFile = AtomicFile(file)
    var stream: FileOutputStream? = null
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
