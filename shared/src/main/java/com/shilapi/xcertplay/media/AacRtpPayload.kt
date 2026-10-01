package com.shilapi.xcertplay.media

internal enum class AacPayloadMode { RAW, RFC3640 }

internal data class ParsedAacPayload(
    val mode: AacPayloadMode,
    val accessUnits: List<ByteArray>,
    val possibleFragmentation: Boolean = false,
)

/** Parses a single RFC 3640 AU-header section, preserving unrecognized payloads as raw AUs. */
internal object AacRtpPayloadParser {
    fun parse(payload: ByteArray): ParsedAacPayload? {
        if (payload.isEmpty()) return null
        val parsed = parseRfc3640(payload)
        return parsed ?: ParsedAacPayload(
            mode = AacPayloadMode.RAW,
            accessUnits = listOf(payload),
            possibleFragmentation = hasIncompleteRfc3640AccessUnit(payload),
        )
    }

    private fun hasIncompleteRfc3640AccessUnit(payload: ByteArray): Boolean {
        if (payload.size < 4) return false
        val headerBits = readU16(payload, 0)
        if (headerBits == 0 || headerBits % 16 != 0) return false
        val headerCount = headerBits / 16
        if (headerCount !in 1..MAX_AU_HEADERS) return false
        val dataOffset = 2 + headerBits / 8
        if (dataOffset > payload.size) return false

        var totalDataBytes = 0L
        repeat(headerCount) { index ->
            val header = readU16(payload, 2 + index * 2)
            val size = header ushr 3
            val indexValue = header and 0x7
            if (size == 0 || (index == 0 && indexValue != 0)) return false
            totalDataBytes += size
        }
        return totalDataBytes > payload.size - dataOffset
    }

    private fun parseRfc3640(payload: ByteArray): ParsedAacPayload? {
        if (payload.size < 4) return null
        val headerBits = readU16(payload, 0)
        if (headerBits == 0 || headerBits % 16 != 0) return null
        val headerCount = headerBits / 16
        if (headerCount !in 1..MAX_AU_HEADERS) return null
        val dataOffset = 2 + headerBits / 8
        if (dataOffset > payload.size) return null

        val sizes = IntArray(headerCount)
        var totalDataBytes = 0L
        repeat(headerCount) { index ->
            val headerOffset = 2 + index * 2
            val header = readU16(payload, headerOffset)
            val size = header ushr 3
            val indexValue = header and 0x7
            if (size == 0 || (index == 0 && indexValue != 0)) return null
            sizes[index] = size
            totalDataBytes += size
        }
        if (totalDataBytes != (payload.size - dataOffset).toLong()) return null

        val units = ArrayList<ByteArray>(headerCount)
        var cursor = dataOffset
        for (size in sizes) {
            units.add(payload.copyOfRange(cursor, cursor + size))
            cursor += size
        }
        return ParsedAacPayload(AacPayloadMode.RFC3640, units)
    }

    private fun readU16(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 8) or (source[offset + 1].toInt() and 0xff)

    private const val MAX_AU_HEADERS = 32
}
