package com.shilapi.xcertplay.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbMuxFrameDecoderTest {
    @Test
    fun splitHeaderAndPayloadAreReassembled() {
        val decoder = UsbMuxFrameDecoder()
        val frame = frame(protocol = PROTOCOL_TCP, payload = ByteArray(23) { it.toByte() })

        decoder.append(frame.copyOfRange(0, 7))
        assertNull(decoder.takeFrame())
        decoder.append(frame.copyOfRange(7, 29))
        assertNull(decoder.takeFrame())
        decoder.append(frame.copyOfRange(29, frame.size))

        val decoded = decoder.takeFrame()!!
        assertEquals(PROTOCOL_TCP, decoded.protocol)
        assertEquals(frame.size, decoded.length)
        assertEquals(0xfeedfaceL, decoded.word8)
        assertEquals(0x1234, decoded.sequence)
        assertEquals(23, decoded.payload.size)
        assertNull(decoder.takeFrame())
    }

    @Test
    fun multipleFramesInOneReadAreReturnedInOrder() {
        val decoder = UsbMuxFrameDecoder()
        val version = frame(PROTOCOL_VERSION, ByteArray(4).also { it[3] = 2 })
        val setup = frame(PROTOCOL_SETUP, byteArrayOf(7))

        decoder.append(version + setup)

        assertEquals(PROTOCOL_VERSION, decoder.takeFrame()!!.protocol)
        assertEquals(PROTOCOL_SETUP, decoder.takeFrame()!!.protocol)
        assertNull(decoder.takeFrame())
    }

    @Test
    fun maximumFrameIsReassembledAcross32KiBReads() {
        val decoder = UsbMuxFrameDecoder()
        val frame = frame(PROTOCOL_TCP, ByteArray(65_520))

        frame.asList().chunked(32 * 1024).forEach { chunk ->
            decoder.append(chunk.toByteArray())
        }

        assertEquals(frame.size, decoder.takeFrame()!!.length)
    }

    @Test
    fun zeroByteReadDoesNotCreateOrCorruptAFrame() {
        val decoder = UsbMuxFrameDecoder()
        decoder.append(ByteArray(0))

        assertNull(decoder.takeFrame())
        assertEquals(0, decoder.previousReadBytes)
    }

    @Test
    fun unsignedInvalidLengthHasBoundedHeaderDiagnosticsAndDoesNotResynchronize() {
        val decoder = UsbMuxFrameDecoder()
        val invalid = byteArrayOf(
            0, 0, 0, PROTOCOL_TCP.toByte(),
            0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
            0xfe.toByte(), 0xed.toByte(), 0xfa.toByte(), 0xce.toByte(),
            0x12, 0x34, 0x56, 0x78,
        )
        decoder.append(invalid)

        val failure = try {
            decoder.takeFrame()
            throw AssertionError("invalid frame was accepted")
        } catch (error: IphoneUsbException.Protocol) {
            error
        }
        assertTrue(failure.message.orEmpty().contains("lengthUnsigned=4294967295"))
        assertTrue(failure.message.orEmpty().contains("rawHeader=00000006fffffffffeedface12345678"))
        assertTrue(failure.message.orEmpty().contains("previousReadBytes=16"))

        decoder.append(frame(PROTOCOL_VERSION, byteArrayOf(0, 0, 0, 2)))
        val stillInvalid = try {
            decoder.takeFrame()
            throw AssertionError("decoder resynchronized after a corrupt frame")
        } catch (error: IphoneUsbException.Protocol) {
            error
        }
        assertTrue(stillInvalid.message.orEmpty().contains("lengthUnsigned=4294967295"))
    }

    @Test
    fun unsupportedProtocolIsRejected() {
        val decoder = UsbMuxFrameDecoder()
        decoder.append(frame(protocol = 0x10203040, payload = byteArrayOf(1)))

        try {
            decoder.takeFrame()
            throw AssertionError("unsupported protocol was accepted")
        } catch (error: IphoneUsbException.Protocol) {
            assertTrue(error.message.orEmpty().contains("unsupported protocol"))
        }
    }

    @Test
    fun malformedKnownProtocolHeadersAreRejected() {
        val versionDecoder = UsbMuxFrameDecoder()
        versionDecoder.append(frame(PROTOCOL_VERSION, ByteArray(5)))
        try {
            versionDecoder.takeFrame()
            throw AssertionError("invalid version frame length was accepted")
        } catch (error: IphoneUsbException.Protocol) {
            assertTrue(error.message.orEmpty().contains("invalid version frame length"))
        }

        val tcpDecoder = UsbMuxFrameDecoder()
        tcpDecoder.append(frame(PROTOCOL_TCP, ByteArray(0)))
        try {
            tcpDecoder.takeFrame()
            throw AssertionError("TCP frame without a TCP header was accepted")
        } catch (error: IphoneUsbException.Protocol) {
            assertTrue(error.message.orEmpty().contains("TCP frame is shorter than its header"))
        }
    }

    private fun frame(protocol: Int, payload: ByteArray): ByteArray =
        ByteArray(16 + payload.size).also { bytes ->
            putU32(bytes, 0, protocol)
            putU32(bytes, 4, bytes.size)
            putU32(bytes, 8, 0xfeedface.toInt())
            putU16(bytes, 12, 0x1234)
            putU16(bytes, 14, 0x5678)
            payload.copyInto(bytes, 16)
        }

    private fun putU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }

    private fun putU32(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }

    private companion object {
        const val PROTOCOL_VERSION = 0
        const val PROTOCOL_SETUP = 2
        const val PROTOCOL_TCP = 6
    }
}
