package com.shilapi.xcertplay.dsp

import com.shilapi.xcertplay.media.dsp.DspImpulseResponse
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DspImpulseResponseRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun importsAndReloadsPcm16WithoutNormalization() {
        val repository = repository()
        val wav = wav(
            formatCode = 1,
            channels = 1,
            sampleRate = 48_000,
            bitsPerSample = 16,
            samples = byteArrayOf(0, 0, 0, 0x40, 0, 0x80.toByte()),
        )

        val imported = repository.import("room1", wav.inputStream()) as DspImpulseImportResult.Imported
        val reloaded = repository.load("room1")

        assertEquals(48_000, imported.impulseResponse.sampleRate)
        assertEquals(1, imported.impulseResponse.channels)
        assertEquals(3, imported.impulseResponse.frameCount)
        assertEquals(0.0f, imported.impulseResponse.sampleAt(0, 0), 0f)
        assertEquals(0.5f, imported.impulseResponse.sampleAt(1, 0), 0f)
        assertEquals(-1.0f, imported.impulseResponse.sampleAt(2, 0), 0f)
        assertEquals(0.5f, reloaded?.sampleAt(1, 0) ?: 0.0f, 0f)
    }

    @Test
    fun acceptsPcm24Pcm32AndFloat32AndPreservesStereoChannels() {
        val repository = repository()
        val pcm24 = wav(
            formatCode = 1,
            channels = 2,
            sampleRate = 44_100,
            bitsPerSample = 24,
            samples = byteArrayOf(0, 0, 0x80.toByte(), 0, 0, 0x40),
        )
        val pcm24Response = repository.import("pcm24", pcm24.inputStream()) as DspImpulseImportResult.Imported
        assertEquals(-1.0f, pcm24Response.impulseResponse.sampleAt(0, 0), 0f)
        assertEquals(0.5f, pcm24Response.impulseResponse.sampleAt(0, 1), 0f)

        val pcm32 = wav(
            formatCode = 1,
            channels = 1,
            sampleRate = 96_000,
            bitsPerSample = 32,
            samples = littleEndianInt(Int.MIN_VALUE),
        )
        val pcm32Response = repository.import("pcm32", pcm32.inputStream()) as DspImpulseImportResult.Imported
        assertEquals(-1.0f, pcm32Response.impulseResponse.sampleAt(0, 0), 0f)

        val float32 = wav(
            formatCode = 3,
            channels = 1,
            sampleRate = 48_000,
            bitsPerSample = 32,
            samples = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(0.25f).array(),
        )
        val floatResponse = repository.import("float32", float32.inputStream()) as DspImpulseImportResult.Imported
        assertEquals(0.25f, floatResponse.impulseResponse.sampleAt(0, 0), 0f)
    }

    @Test
    fun rejectsMalformedRiffBadFormatUnsupportedChannelsAndNonFiniteFloatSamples() {
        val repository = repository()
        val valid = wav(1, 1, 48_000, 16, byteArrayOf(0, 0))
        val wrongRiff = valid.copyOf().apply { this[0] = 'X'.code.toByte() }
        assertEquals(DspImpulseImportResult.InvalidWav, repository.import("bad_riff", wrongRiff.inputStream()))

        val unsupportedEncoding = wav(1, 1, 48_000, 8, byteArrayOf(0))
        assertEquals(DspImpulseImportResult.InvalidWav, repository.import("bad_fmt", unsupportedEncoding.inputStream()))

        val unsupportedChannels = wav(1, 3, 48_000, 16, ByteArray(6))
        assertEquals(DspImpulseImportResult.InvalidWav, repository.import("bad_channels", unsupportedChannels.inputStream()))

        val unsupportedRate = wav(1, 1, 32_000, 16, byteArrayOf(0, 0))
        assertEquals(DspImpulseImportResult.InvalidWav, repository.import("bad_rate", unsupportedRate.inputStream()))

        val nonFinite = wav(
            3,
            1,
            48_000,
            32,
            ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(Float.NaN).array(),
        )
        assertEquals(DspImpulseImportResult.InvalidWav, repository.import("bad_float", nonFinite.inputStream()))
    }

    @Test
    fun enforcesImpulseFrameAndFileSizeLimits() {
        val repository = repository()
        val tooManyFrames = wav(1, 1, 48_000, 16, ByteArray((DspImpulseResponse.MAX_FRAMES + 1) * 2))
        assertEquals(DspImpulseImportResult.InvalidWav, repository.import("too_many", tooManyFrames.inputStream()))
        assertEquals(
            DspImpulseImportResult.TooLarge,
            repository.import("oversized", ByteArray(DspImpulseResponseRepository.MAX_FILE_BYTES + 1).inputStream()),
        )
    }

    @Test
    fun rejectsUnsafeIdsAndStoresAcceptedIrUnderPrivateDirectory() {
        val repository = repository()
        val valid = wav(1, 1, 48_000, 16, byteArrayOf(0, 0))
        assertEquals(DspImpulseImportResult.InvalidId, repository.import("../outside", valid.inputStream()))

        val imported = repository.import("saved_ir", valid.inputStream()) as DspImpulseImportResult.Imported

        assertEquals(listOf("saved_ir"), repository.ids())
        assertTrue(requireNotNull(repository.impulseFileForTesting("saved_ir")).isFile)
        assertEquals(imported.impulseResponse.sampleAt(0, 0), repository.load("saved_ir")?.sampleAt(0, 0) ?: 1.0f, 0f)
    }

    private fun repository() = DspImpulseResponseRepository(temporaryFolder.newFolder("files"))

    private fun wav(
        formatCode: Int,
        channels: Int,
        sampleRate: Int,
        bitsPerSample: Int,
        samples: ByteArray,
    ): ByteArray {
        val blockAlign = channels * bitsPerSample / 8
        val format = ByteArrayOutputStream().apply {
            writeU16(formatCode)
            writeU16(channels)
            writeU32(sampleRate)
            writeU32(sampleRate * blockAlign)
            writeU16(blockAlign)
            writeU16(bitsPerSample)
        }.toByteArray()
        val body = ByteArrayOutputStream().apply {
            writeFourCc("WAVE")
            writeFourCc("fmt ")
            writeU32(format.size)
            write(format)
            writeFourCc("data")
            writeU32(samples.size)
            write(samples)
        }.toByteArray()
        return ByteArrayOutputStream().apply {
            writeFourCc("RIFF")
            writeU32(body.size)
            write(body)
        }.toByteArray()
    }

    private fun ByteArrayOutputStream.writeFourCc(value: String) {
        write(value.toByteArray(Charsets.US_ASCII))
    }

    private fun ByteArrayOutputStream.writeU16(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
    }

    private fun ByteArrayOutputStream.writeU32(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
        write((value ushr 16) and 0xff)
        write((value ushr 24) and 0xff)
    }

    private fun littleEndianInt(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
}
