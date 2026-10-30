package com.shilapi.xcertplay.airplay

/**
 * ChaCha20-Poly1305 framing for the CarPlay control channel.
 *
 * Each frame is [2-byte little-endian ciphertext length][ciphertext][16-byte tag]; the length
 * header is the AEAD associated data and each direction uses an 8-byte little-endian nonce
 * counter plus its own key.
 */
class ControlCipher(private val readKey: ByteArray, private val writeKey: ByteArray) {
    data class Decrypted(val data: ByteArray, val rest: ByteArray)

    class StreamDecoder internal constructor(private val cipher: ControlCipher) {
        private val pending = ByteArray(MAX_FRAME_BYTES)
        private var pendingSize = 0
        private var expectedFrameBytes = 0

        fun decrypt(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): ByteArray {
            require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
            val output = ArrayList<ByteArray>()
            var inputOffset = offset
            val inputEnd = offset + length
            while (inputOffset < inputEnd) {
                if (pendingSize == HEADER_SIZE && expectedFrameBytes == 0) {
                    val payloadBytes = readU16Le(pending, 0)
                    expectedFrameBytes = HEADER_SIZE + payloadBytes + TAG_SIZE
                }
                val targetSize = when {
                    pendingSize < HEADER_SIZE -> HEADER_SIZE
                    expectedFrameBytes > 0 -> expectedFrameBytes
                    else -> MAX_FRAME_BYTES
                }
                val copyBytes = minOf(targetSize - pendingSize, inputEnd - inputOffset)
                bytes.copyInto(pending, pendingSize, inputOffset, inputOffset + copyBytes)
                pendingSize += copyBytes
                inputOffset += copyBytes

                if (pendingSize == HEADER_SIZE && expectedFrameBytes == 0) {
                    val payloadBytes = readU16Le(pending, 0)
                    expectedFrameBytes = HEADER_SIZE + payloadBytes + TAG_SIZE
                }
                if (expectedFrameBytes > 0 && pendingSize == expectedFrameBytes) {
                    val decrypted = cipher.decrypt(pending.copyOf(expectedFrameBytes))
                    check(decrypted.rest.isEmpty()) { "Incomplete AirPlay control frame" }
                    if (decrypted.data.isNotEmpty()) output.add(decrypted.data)
                    pendingSize = 0
                    expectedFrameBytes = 0
                }
            }
            return concatBytes(*output.toTypedArray())
        }

        companion object {
            private const val HEADER_SIZE = 2
            private const val TAG_SIZE = 16
            private const val MAX_FRAME_BYTES = HEADER_SIZE + 0xffff + TAG_SIZE

            private fun readU16Le(source: ByteArray, offset: Int): Int =
                (source[offset].toInt() and 0xff) or ((source[offset + 1].toInt() and 0xff) shl 8)
        }
    }

    private var readCounter = 0L
    private var writeCounter = 0L

    fun newStreamDecoder(): StreamDecoder = StreamDecoder(this)

    fun decrypt(buffer: ByteArray): Decrypted {
        val output = ArrayList<ByteArray>()
        var offset = 0
        while (buffer.size - offset >= HEADER_SIZE) {
            val length = readU16Le(buffer, offset)
            val frameEnd = offset + HEADER_SIZE + length + TAG_SIZE
            if (buffer.size < frameEnd) break
            val aad = buffer.copyOfRange(offset, offset + HEADER_SIZE)
            val ciphertextAndTag = buffer.copyOfRange(offset + HEADER_SIZE, frameEnd)
            output.add(AirPlayCrypto.chachaOpen(readKey, AirPlayCrypto.nonce64(readCounter), ciphertextAndTag, aad))
            readCounter++
            offset = frameEnd
        }
        return Decrypted(concatBytes(*output.toTypedArray()), buffer.copyOfRange(offset, buffer.size))
    }

    fun encrypt(plaintext: ByteArray): ByteArray {
        val output = ArrayList<ByteArray>()
        var offset = 0
        do {
            val chunk = plaintext.copyOfRange(offset, minOf(offset + MAX_PAYLOAD, plaintext.size))
            val header = byteArrayOf(chunk.size.toByte(), (chunk.size ushr 8).toByte())
            val ciphertextAndTag = AirPlayCrypto.chachaSeal(writeKey, AirPlayCrypto.nonce64(writeCounter), chunk, header)
            output.add(header)
            output.add(ciphertextAndTag)
            writeCounter++
            offset += MAX_PAYLOAD
        } while (offset < plaintext.size)
        return concatBytes(*output.toTypedArray())
    }

    private fun readU16Le(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xff) or ((source[offset + 1].toInt() and 0xff) shl 8)

    private companion object {
        const val HEADER_SIZE = 2
        const val TAG_SIZE = 16
        const val MAX_PAYLOAD = 0x4000
    }
}
