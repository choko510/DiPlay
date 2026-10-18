package com.shilapi.xcertplay.network

@JvmInline
internal value class WiredTcpPacketClassification(val encoded: Long) {
    val status: Int get() = (encoded and STATUS_MASK).toInt()
    val ipVersion: Int get() = ((encoded ushr IP_VERSION_SHIFT) and IP_VERSION_MASK).toInt()
    val destinationMatch: Int get() = ((encoded ushr DEST_MATCH_SHIFT) and DEST_MATCH_MASK).toInt()
    val tcpFlags: Int get() = ((encoded ushr TCP_FLAGS_SHIFT) and TCP_FLAGS_MASK).toInt()
    val sourcePort: Int get() = ((encoded ushr SOURCE_PORT_SHIFT) and PORT_MASK).toInt()
    val destinationPort: Int get() = ((encoded ushr DESTINATION_PORT_SHIFT) and PORT_MASK).toInt()
    val isTcp: Boolean get() = encoded and TCP_PRESENT_MASK != 0L

    fun isAirPlaySyn(listenerPort: Int): Boolean =
        isTcp && destinationPort == listenerPort && tcpFlags and SYN_FLAG != 0 && tcpFlags and ACK_FLAG == 0

    fun isAirPlaySynAck(listenerPort: Int): Boolean =
        isTcp && sourcePort == listenerPort &&
            tcpFlags and (SYN_FLAG or ACK_FLAG) == (SYN_FLAG or ACK_FLAG)

    fun isAirPlayRst(listenerPort: Int): Boolean =
        isTcp && sourcePort == listenerPort && tcpFlags and RST_FLAG != 0

    companion object {
        const val STATUS_TCP = 1
        const val STATUS_NON_TCP = 2
        const val STATUS_INVALID = 3
        const val STATUS_TRUNCATED = 4
        const val STATUS_UNSUPPORTED = 5
        const val STATUS_FRAGMENTED = 6
        const val DESTINATION_UNVERIFIED = 0
        const val DESTINATION_MATCH = 1
        const val DESTINATION_MISMATCH = 2
        val EMPTY = result(STATUS_UNSUPPORTED)

        private const val STATUS_MASK = 0x7L
        private const val IP_VERSION_SHIFT = 3
        private const val IP_VERSION_MASK = 0x7L
        private const val DEST_MATCH_SHIFT = 6
        private const val DEST_MATCH_MASK = 0x3L
        private const val TCP_FLAGS_SHIFT = 8
        private const val TCP_FLAGS_MASK = 0xffL
        private const val SOURCE_PORT_SHIFT = 16
        private const val DESTINATION_PORT_SHIFT = 32
        private const val PORT_MASK = 0xffffL
        private const val TCP_PRESENT_MASK = 1L shl 48
        private const val SYN_FLAG = 0x02
        private const val ACK_FLAG = 0x10
        private const val RST_FLAG = 0x04

        internal fun result(
            status: Int,
            ipVersion: Int = 0,
            destinationMatch: Int = DESTINATION_UNVERIFIED,
            sourcePort: Int = 0,
            destinationPort: Int = 0,
            tcpFlags: Int = 0,
            isTcp: Boolean = false,
        ): WiredTcpPacketClassification {
            val encoded = status.toLong() or
                (ipVersion.toLong() shl IP_VERSION_SHIFT) or
                (destinationMatch.toLong() shl DEST_MATCH_SHIFT) or
                (tcpFlags.toLong() shl TCP_FLAGS_SHIFT) or
                (sourcePort.toLong() shl SOURCE_PORT_SHIFT) or
                (destinationPort.toLong() shl DESTINATION_PORT_SHIFT) or
                if (isTcp) TCP_PRESENT_MASK else 0L
            return WiredTcpPacketClassification(encoded)
        }
    }
}

internal object WiredTcpPacketClassifier {
    private const val ETHERNET_HEADER_BYTES = 14
    private const val IPV4_HEADER_MIN_BYTES = 20
    private const val IPV6_HEADER_BYTES = 40
    private const val TCP_HEADER_MIN_BYTES = 20
    private const val IPV4_ETHERTYPE = 0x0800
    private const val IPV6_ETHERTYPE = 0x86dd
    private const val VLAN_ETHERTYPE = 0x8100
    private const val QINQ_ETHERTYPE = 0x88a8
    private const val TCP_PROTOCOL = 6
    private const val IPV6_HOP_BY_HOP = 0
    private const val IPV6_ROUTING = 43
    private const val IPV6_FRAGMENT = 44
    private const val IPV6_AH = 51
    private const val IPV6_ESP = 50
    private const val IPV6_DESTINATION_OPTIONS = 60
    private const val IPV6_NO_NEXT_HEADER = 59
    private const val IPV6_UDP = 17
    private const val IPV6_ICMP = 58
    private const val MAX_IPV6_EXTENSION_HEADERS = 8

    fun classifyEthernet(
        frame: ByteArray,
        length: Int,
        expectedIpv4: ByteArray? = null,
        expectedIpv6: ByteArray? = null,
    ): WiredTcpPacketClassification {
        if (length < 0 || length > frame.size) return result(WiredTcpPacketClassification.STATUS_INVALID)
        if (length < ETHERNET_HEADER_BYTES) return result(WiredTcpPacketClassification.STATUS_TRUNCATED)

        var etherType = readU16Be(frame, 12)
        var ipOffset = ETHERNET_HEADER_BYTES
        var vlanCount = 0
        while (etherType == VLAN_ETHERTYPE || etherType == QINQ_ETHERTYPE) {
            if (vlanCount == 2) return result(WiredTcpPacketClassification.STATUS_UNSUPPORTED)
            if (ipOffset > length - 4) return result(WiredTcpPacketClassification.STATUS_TRUNCATED)
            etherType = readU16Be(frame, ipOffset + 2)
            ipOffset += 4
            vlanCount++
        }

        return when (etherType) {
            IPV4_ETHERTYPE -> parseIpv4(frame, ipOffset, length, expectedIpv4)
            IPV6_ETHERTYPE -> parseIpv6(frame, ipOffset, length, expectedIpv6)
            else -> result(WiredTcpPacketClassification.STATUS_UNSUPPORTED)
        }
    }

    fun classifyTunPacket(
        packet: ByteArray,
        length: Int,
        expectedIpv4: ByteArray? = null,
        expectedIpv6: ByteArray? = null,
    ): WiredTcpPacketClassification {
        if (length < 0 || length > packet.size) return result(WiredTcpPacketClassification.STATUS_INVALID)
        if (length == 0) return result(WiredTcpPacketClassification.STATUS_TRUNCATED)
        return when ((packet[0].toInt() ushr 4) and 0x0f) {
            4 -> parseIpv4(packet, 0, length, expectedIpv4)
            6 -> parseIpv6(packet, 0, length, expectedIpv6)
            else -> result(WiredTcpPacketClassification.STATUS_INVALID)
        }
    }

    private fun parseIpv4(
        source: ByteArray,
        offset: Int,
        end: Int,
        expectedDestination: ByteArray?,
    ): WiredTcpPacketClassification {
        if (offset > end - IPV4_HEADER_MIN_BYTES) return result(WiredTcpPacketClassification.STATUS_TRUNCATED, 4)
        val versionAndLength = source[offset].toInt() and 0xff
        if (versionAndLength ushr 4 != 4) return result(WiredTcpPacketClassification.STATUS_INVALID, 4)
        val headerLength = (versionAndLength and 0x0f) * 4
        if (headerLength < IPV4_HEADER_MIN_BYTES) return result(WiredTcpPacketClassification.STATUS_INVALID, 4)
        if (offset > end - headerLength) return result(WiredTcpPacketClassification.STATUS_TRUNCATED, 4)
        val totalLength = readU16Be(source, offset + 2)
        if (totalLength < headerLength) return result(WiredTcpPacketClassification.STATUS_INVALID, 4)
        if (offset > end - totalLength) return result(WiredTcpPacketClassification.STATUS_TRUNCATED, 4)
        val fragment = readU16Be(source, offset + 6)
        if (fragment and 0x3fff != 0) return result(WiredTcpPacketClassification.STATUS_FRAGMENTED, 4)
        val match = compareAddress(source, offset + 16, expectedDestination, 4)
        if ((source[offset + 9].toInt() and 0xff) != TCP_PROTOCOL) {
            return result(WiredTcpPacketClassification.STATUS_NON_TCP, 4, match)
        }
        return parseTcp(source, offset + headerLength, offset + totalLength, 4, match)
    }

    private fun parseIpv6(
        source: ByteArray,
        offset: Int,
        end: Int,
        expectedDestination: ByteArray?,
    ): WiredTcpPacketClassification {
        if (offset > end - IPV6_HEADER_BYTES) return result(WiredTcpPacketClassification.STATUS_TRUNCATED, 6)
        if ((source[offset].toInt() ushr 4) and 0x0f != 6) {
            return result(WiredTcpPacketClassification.STATUS_INVALID, 6)
        }
        val payloadLength = readU16Be(source, offset + 4)
        if (payloadLength == 0) return result(WiredTcpPacketClassification.STATUS_UNSUPPORTED, 6)
        val packetEnd = offset + IPV6_HEADER_BYTES + payloadLength
        if (packetEnd < offset || packetEnd > end) {
            return result(WiredTcpPacketClassification.STATUS_TRUNCATED, 6)
        }
        val match = compareAddress(source, offset + 24, expectedDestination, 16)
        var nextHeader = source[offset + 6].toInt() and 0xff
        var cursor = offset + IPV6_HEADER_BYTES
        var extensionCount = 0
        while (true) {
            if (nextHeader == TCP_PROTOCOL) return parseTcp(source, cursor, packetEnd, 6, match)
            if (nextHeader == IPV6_NO_NEXT_HEADER) {
                return result(WiredTcpPacketClassification.STATUS_NON_TCP, 6, match)
            }
            if (nextHeader == IPV6_FRAGMENT) {
                if (cursor > packetEnd - 8) return result(WiredTcpPacketClassification.STATUS_TRUNCATED, 6, match)
                return result(WiredTcpPacketClassification.STATUS_FRAGMENTED, 6, match)
            }
            val extensionBytes = when (nextHeader) {
                IPV6_HOP_BY_HOP, IPV6_ROUTING, IPV6_DESTINATION_OPTIONS -> {
                    if (cursor > packetEnd - 2) return result(WiredTcpPacketClassification.STATUS_TRUNCATED, 6, match)
                    ((source[cursor + 1].toInt() and 0xff) + 1) * 8
                }
                IPV6_AH -> {
                    if (cursor > packetEnd - 2) return result(WiredTcpPacketClassification.STATUS_TRUNCATED, 6, match)
                    ((source[cursor + 1].toInt() and 0xff) + 2) * 4
                }
                IPV6_ESP -> return result(WiredTcpPacketClassification.STATUS_UNSUPPORTED, 6, match)
                IPV6_UDP, IPV6_ICMP ->
                    return result(WiredTcpPacketClassification.STATUS_NON_TCP, 6, match)
                else -> return result(WiredTcpPacketClassification.STATUS_UNSUPPORTED, 6, match)
            }
            if (extensionCount >= MAX_IPV6_EXTENSION_HEADERS) {
                return result(WiredTcpPacketClassification.STATUS_UNSUPPORTED, 6, match)
            }
            if (extensionBytes < 8 || cursor > packetEnd - extensionBytes) {
                return result(WiredTcpPacketClassification.STATUS_TRUNCATED, 6, match)
            }
            nextHeader = source[cursor].toInt() and 0xff
            cursor += extensionBytes
            extensionCount++
        }
    }

    private fun parseTcp(
        source: ByteArray,
        offset: Int,
        packetEnd: Int,
        ipVersion: Int,
        destinationMatch: Int,
    ): WiredTcpPacketClassification {
        if (offset > packetEnd - TCP_HEADER_MIN_BYTES) {
            return result(WiredTcpPacketClassification.STATUS_TRUNCATED, ipVersion, destinationMatch)
        }
        val headerBytes = ((source[offset + 12].toInt() ushr 4) and 0x0f) * 4
        if (headerBytes < TCP_HEADER_MIN_BYTES) {
            return result(WiredTcpPacketClassification.STATUS_INVALID, ipVersion, destinationMatch)
        }
        if (offset > packetEnd - headerBytes) {
            return result(WiredTcpPacketClassification.STATUS_TRUNCATED, ipVersion, destinationMatch)
        }
        return WiredTcpPacketClassification.result(
            status = WiredTcpPacketClassification.STATUS_TCP,
            ipVersion = ipVersion,
            destinationMatch = destinationMatch,
            sourcePort = readU16Be(source, offset),
            destinationPort = readU16Be(source, offset + 2),
            tcpFlags = source[offset + 13].toInt() and 0xff,
            isTcp = true,
        )
    }

    private fun compareAddress(
        source: ByteArray,
        offset: Int,
        expected: ByteArray?,
        length: Int,
    ): Int {
        if (expected == null || expected.size != length) {
            return WiredTcpPacketClassification.DESTINATION_UNVERIFIED
        }
        for (index in 0 until length) {
            if (source[offset + index] != expected[index]) {
                return WiredTcpPacketClassification.DESTINATION_MISMATCH
            }
        }
        return WiredTcpPacketClassification.DESTINATION_MATCH
    }

    private fun readU16Be(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 8) or (source[offset + 1].toInt() and 0xff)

    private fun result(
        status: Int,
        ipVersion: Int = 0,
        destinationMatch: Int = WiredTcpPacketClassification.DESTINATION_UNVERIFIED,
    ): WiredTcpPacketClassification = WiredTcpPacketClassification.result(
        status = status,
        ipVersion = ipVersion,
        destinationMatch = destinationMatch,
    )
}
