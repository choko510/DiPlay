package com.shilapi.xcertplay.transport

internal data class UsbMuxFrame(
    val protocol: Int,
    val length: Int,
    val word8: Long,
    val sequence: Int,
    val payload: ByteArray,
)

/** Incremental USBMUX framing; a malformed stream is terminal and is never scanned for resync. */
internal class UsbMuxFrameDecoder(
    private val maxFrameBytes: Int = MAX_FRAME_BYTES,
) {
    private var buffered = ByteArray(0)
    var previousReadBytes: Int = 0
        private set

    fun append(bytes: ByteArray) {
        previousReadBytes = bytes.size
        if (bytes.isNotEmpty()) buffered += bytes
    }

    fun takeFrame(): UsbMuxFrame? {
        if (buffered.size < HEADER_BYTES) return null

        val protocol = readUnsignedU32(buffered, 0)
        val unsignedLength = readUnsignedU32(buffered, 4)
        // Keep word8 observable, but do not require the host-to-phone magic on received TCP frames.
        // Observed iPhone replies omit 0xfeedface here; the version response is checked in begin().
        val word8 = readUnsignedU32(buffered, 8)
        if (unsignedLength < HEADER_BYTES || unsignedLength > maxFrameBytes.toLong()) {
            throw framingFailure(protocol, unsignedLength, word8, "length outside allowed range")
        }
        if (protocol !in SUPPORTED_PROTOCOLS) {
            throw framingFailure(protocol, unsignedLength, word8, "unsupported protocol")
        }
        if (protocol == PROTOCOL_VERSION && unsignedLength != VERSION_FRAME_BYTES.toLong()) {
            throw framingFailure(protocol, unsignedLength, word8, "invalid version frame length")
        }
        if (protocol == PROTOCOL_TCP && unsignedLength < (HEADER_BYTES + TCP_HEADER_BYTES).toLong()) {
            throw framingFailure(protocol, unsignedLength, word8, "TCP frame is shorter than its header")
        }
        val length = unsignedLength.toInt()
        if (buffered.size < length) return null

        val frame = UsbMuxFrame(
            protocol = protocol.toInt(),
            length = length,
            word8 = word8,
            sequence = readU16(buffered, 12),
            payload = buffered.copyOfRange(HEADER_BYTES, length),
        )
        buffered = buffered.copyOfRange(length, buffered.size)
        return frame
    }

    private fun framingFailure(
        protocol: Long,
        unsignedLength: Long,
        word8: Long,
        reason: String,
    ): IphoneUsbException.Protocol {
        val rawHeader = buffered.copyOfRange(0, HEADER_BYTES).joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        }
        return IphoneUsbException.Protocol(
            "USBMUX framing error: bufferedBytes=${buffered.size} rawHeader=$rawHeader " +
                "protocol=$protocol lengthUnsigned=$unsignedLength word8=$word8 " +
                "previousReadBytes=$previousReadBytes reason=$reason",
        )
    }

    private fun readUnsignedU32(source: ByteArray, offset: Int): Long =
        ((source[offset].toLong() and 0xff) shl 24) or
            ((source[offset + 1].toLong() and 0xff) shl 16) or
            ((source[offset + 2].toLong() and 0xff) shl 8) or
            (source[offset + 3].toLong() and 0xff)

    private fun readU16(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 8) or (source[offset + 1].toInt() and 0xff)

    private companion object {
        const val HEADER_BYTES = 16
        const val TCP_HEADER_BYTES = 20
        const val VERSION_FRAME_BYTES = 20
        const val MAX_FRAME_BYTES = 65_536
        const val PROTOCOL_VERSION = 0L
        const val PROTOCOL_SETUP = 2L
        const val PROTOCOL_TCP = 6L
        val SUPPORTED_PROTOCOLS = setOf(PROTOCOL_VERSION, PROTOCOL_SETUP, PROTOCOL_TCP)
    }
}
