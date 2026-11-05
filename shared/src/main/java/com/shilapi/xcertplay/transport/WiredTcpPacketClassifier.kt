package com.shilapi.xcertplay.transport

@JvmInline
internal value class WiredPacketClassification(val bits: Long) {
    val disposition: Int get() = ((bits ushr DISPOSITION_SHIFT) and DISPOSITION_MASK).toInt()
    val family: Int get() = ((bits ushr FAMILY_SHIFT) and FAMILY_MASK).toInt()
    val protocol: Int get() = ((bits ushr PROTOCOL_SHIFT) and PROTOCOL_MASK).toInt()
    val tcpFlags: Int get() = ((bits ushr TCP_FLAGS_SHIFT) and TCP_FLAGS_MASK).toInt()
    val sourcePort: Int get() = ((bits ushr SOURCE_PORT_SHIFT) and PORT_MASK).toInt()
    val destinationPort: Int get() = ((bits ushr DESTINATION_PORT_SHIFT) and PORT_MASK).toInt()
    val addressChecked: Boolean get() = bits and ADDRESS_CHECKED_MASK != 0L
    val addressMatches: Boolean get() = bits and ADDRESS_MATCHED_MASK != 0L
    val portChecked: Boolean get() = bits and PORT_CHECKED_MASK != 0L
    val portMatches: Boolean get() = bits and PORT_MATCHED_MASK != 0L
    val fragmented: Boolean get() = bits and FRAGMENTED_MASK != 0L
    val vlanTagged: Boolean get() = bits and VLAN_TAGGED_MASK != 0L

    val tcpSyn: Boolean
        get() = disposition == DISPOSITION_VALID && protocol == PROTOCOL_TCP && !fragmented &&
            tcpFlags and TCP_SYN != 0 && tcpFlags and TCP_ACK == 0

    val tcpSynAck: Boolean
        get() = disposition == DISPOSITION_VALID && protocol == PROTOCOL_TCP && !fragmented &&
            tcpFlags and TCP_SYN != 0 && tcpFlags and TCP_ACK != 0

    val tcpRst: Boolean
        get() = disposition == DISPOSITION_VALID && protocol == PROTOCOL_TCP && !fragmented && tcpFlags and TCP_RST != 0

    companion object {
        const val DISPOSITION_UNKNOWN = 0
        const val DISPOSITION_VALID = 1
        const val DISPOSITION_INVALID = 2
        const val DISPOSITION_UNSUPPORTED = 3

        const val FAMILY_UNKNOWN = 0
        const val FAMILY_IPV4 = 1
        const val FAMILY_IPV6 = 2

        const val PROTOCOL_UNKNOWN = 0
        const val PROTOCOL_TCP = 1
        const val PROTOCOL_UDP = 2
        const val PROTOCOL_ICMPV4 = 3
        const val PROTOCOL_ICMPV6 = 4

        private const val DISPOSITION_SHIFT = 0
        private const val FAMILY_SHIFT = 2
        private const val PROTOCOL_SHIFT = 4
        private const val TCP_FLAGS_SHIFT = 7
        private const val SOURCE_PORT_SHIFT = 15
        private const val DESTINATION_PORT_SHIFT = 31
        private const val DISPOSITION_MASK = 0x3L
        private const val FAMILY_MASK = 0x3L
        private const val PROTOCOL_MASK = 0x7L
        private const val TCP_FLAGS_MASK = 0xffL
        private const val PORT_MASK = 0xffffL
        private const val ADDRESS_CHECKED_MASK = 1L shl 47
        private const val ADDRESS_MATCHED_MASK = 1L shl 48
        private const val PORT_CHECKED_MASK = 1L shl 49
        private const val PORT_MATCHED_MASK = 1L shl 50
        private const val FRAGMENTED_MASK = 1L shl 51
        private const val VLAN_TAGGED_MASK = 1L shl 52
        private const val TCP_SYN = 0x02
        private const val TCP_RST = 0x04
        private const val TCP_ACK = 0x10

        internal fun create(
            disposition: Int,
            family: Int = FAMILY_UNKNOWN,
            protocol: Int = PROTOCOL_UNKNOWN,
            tcpFlags: Int = 0,
            sourcePort: Int = 0,
            destinationPort: Int = 0,
            addressChecked: Boolean = false,
            addressMatches: Boolean = false,
            portChecked: Boolean = false,
            portMatches: Boolean = false,
            fragmented: Boolean = false,
            vlanTagged: Boolean = false,
        ): WiredPacketClassification {
            var value =
                (disposition.toLong() shl DISPOSITION_SHIFT) or
                    (family.toLong() shl FAMILY_SHIFT) or
                    (protocol.toLong() shl PROTOCOL_SHIFT) or
                    ((tcpFlags and 0xff).toLong() shl TCP_FLAGS_SHIFT) or
                    ((sourcePort and 0xffff).toLong() shl SOURCE_PORT_SHIFT) or
                    ((destinationPort and 0xffff).toLong() shl DESTINATION_PORT_SHIFT)
            if (addressChecked) value = value or ADDRESS_CHECKED_MASK
            if (addressMatches) value = value or ADDRESS_MATCHED_MASK
            if (portChecked) value = value or PORT_CHECKED_MASK
            if (portMatches) value = value or PORT_MATCHED_MASK
            if (fragmented) value = value or FRAGMENTED_MASK
            if (vlanTagged) value = value or VLAN_TAGGED_MASK
            return WiredPacketClassification(value)
        }
    }
}

/** Allocation-free packet-header classification for opt-in wired diagnostics. */
internal object WiredTcpPacketClassifier {
    fun classifyEthernet(
        frame: ByteArray,
        offset: Int = 0,
        length: Int = frame.size - offset,
        expectedAddress: ByteArray? = null,
        matchSourceAddress: Boolean = false,
        expectedPort: Int = -1,
        matchSourcePort: Boolean = false,
    ): WiredPacketClassification {
        if (!isRangeValid(frame.size, offset, length)) return invalid()
        val frameEnd = offset + length
        if (length < ETHERNET_HEADER_BYTES) return invalid(vlanTagged = false)
        var etherType = readU16(frame, offset + ETHER_TYPE_OFFSET)
        var packetOffset = offset + ETHERNET_HEADER_BYTES
        var vlanDepth = 0
        while (etherType == ETHERTYPE_VLAN || etherType == ETHERTYPE_QINQ) {
            if (vlanDepth == MAX_VLAN_TAGS) return unsupported(vlanTagged = true)
            if (frameEnd - packetOffset < VLAN_HEADER_BYTES) return invalid(vlanTagged = true)
            etherType = readU16(frame, packetOffset + VLAN_INNER_ETHERTYPE_OFFSET)
            packetOffset += VLAN_HEADER_BYTES
            vlanDepth++
        }
        val vlanTagged = vlanDepth > 0
        return when (etherType) {
            ETHERTYPE_IPV4 -> classifyIpv4(
                packet = frame,
                offset = packetOffset,
                availableLength = frameEnd - packetOffset,
                expectedAddress = expectedAddress,
                matchSourceAddress = matchSourceAddress,
                expectedPort = expectedPort,
                matchSourcePort = matchSourcePort,
                vlanTagged = vlanTagged,
            )
            ETHERTYPE_IPV6 -> classifyIpv6(
                packet = frame,
                offset = packetOffset,
                availableLength = frameEnd - packetOffset,
                expectedAddress = expectedAddress,
                matchSourceAddress = matchSourceAddress,
                expectedPort = expectedPort,
                matchSourcePort = matchSourcePort,
                vlanTagged = vlanTagged,
            )
            else -> unknown(vlanTagged = vlanTagged)
        }
    }

    fun classifyRawIp(
        packet: ByteArray,
        offset: Int = 0,
        length: Int = packet.size - offset,
        expectedAddress: ByteArray? = null,
        matchSourceAddress: Boolean = false,
        expectedPort: Int = -1,
        matchSourcePort: Boolean = false,
    ): WiredPacketClassification {
        if (!isRangeValid(packet.size, offset, length) || length < 1) return invalid()
        return when ((packet[offset].toInt() ushr 4) and 0x0f) {
            IPV4_VERSION -> classifyIpv4(
                packet,
                offset,
                length,
                expectedAddress,
                matchSourceAddress,
                expectedPort,
                matchSourcePort,
                vlanTagged = false,
            )
            IPV6_VERSION -> classifyIpv6(
                packet,
                offset,
                length,
                expectedAddress,
                matchSourceAddress,
                expectedPort,
                matchSourcePort,
                vlanTagged = false,
            )
            else -> unknown()
        }
    }

    private fun classifyIpv4(
        packet: ByteArray,
        offset: Int,
        availableLength: Int,
        expectedAddress: ByteArray?,
        matchSourceAddress: Boolean,
        expectedPort: Int,
        matchSourcePort: Boolean,
        vlanTagged: Boolean,
    ): WiredPacketClassification {
        if (availableLength < IPV4_MIN_HEADER_BYTES || packet[offset].toInt() ushr 4 and 0x0f != IPV4_VERSION) {
            return invalid(vlanTagged = vlanTagged)
        }
        val headerLength = (packet[offset].toInt() and IPV4_IHL_MASK) * 4
        val totalLength = readU16(packet, offset + IPV4_TOTAL_LENGTH_OFFSET)
        if (headerLength < IPV4_MIN_HEADER_BYTES || headerLength > availableLength ||
            totalLength < headerLength || totalLength > availableLength
        ) {
            return invalid(WiredPacketClassification.FAMILY_IPV4, vlanTagged)
        }
        val protocol = transportProtocol(packet[offset + IPV4_PROTOCOL_OFFSET].toInt() and 0xff, ipv6 = false)
        val fragment = readU16(packet, offset + IPV4_FRAGMENT_OFFSET)
        if (fragment and IPV4_RESERVED_FLAG_MASK != 0) {
            return invalid(WiredPacketClassification.FAMILY_IPV4, vlanTagged)
        }
        val fragmented = fragment and IPV4_FRAGMENT_MASK != 0
        val addressOffset = offset + if (matchSourceAddress) IPV4_SOURCE_OFFSET else IPV4_DESTINATION_OFFSET
        val addressChecked = expectedAddress?.size == IPV4_ADDRESS_BYTES
        val addressMatched = expectedAddress != null && expectedAddress.size == IPV4_ADDRESS_BYTES &&
            bytesEqual(packet, addressOffset, expectedAddress, 0, IPV4_ADDRESS_BYTES)
        if (protocol == WiredPacketClassification.PROTOCOL_UNKNOWN) {
            return unsupported(
                WiredPacketClassification.FAMILY_IPV4,
                vlanTagged,
                addressChecked,
                addressMatched,
            )
        }
        if (fragmented || protocol != WiredPacketClassification.PROTOCOL_TCP) {
            return WiredPacketClassification.create(
                disposition = WiredPacketClassification.DISPOSITION_VALID,
                family = WiredPacketClassification.FAMILY_IPV4,
                protocol = protocol,
                addressChecked = addressChecked,
                addressMatches = addressMatched,
                fragmented = fragmented,
                vlanTagged = vlanTagged,
            )
        }
        return classifyTcp(
            packet = packet,
            tcpOffset = offset + headerLength,
            packetEnd = offset + totalLength,
            family = WiredPacketClassification.FAMILY_IPV4,
            addressChecked = addressChecked,
            expectedAddressMatches = addressMatched,
            expectedPort = expectedPort,
            matchSourcePort = matchSourcePort,
            vlanTagged = vlanTagged,
        )
    }

    private fun classifyIpv6(
        packet: ByteArray,
        offset: Int,
        availableLength: Int,
        expectedAddress: ByteArray?,
        matchSourceAddress: Boolean,
        expectedPort: Int,
        matchSourcePort: Boolean,
        vlanTagged: Boolean,
    ): WiredPacketClassification {
        if (availableLength < IPV6_HEADER_BYTES || packet[offset].toInt() ushr 4 and 0x0f != IPV6_VERSION) {
            return invalid(WiredPacketClassification.FAMILY_IPV6, vlanTagged)
        }
        val payloadLength = readU16(packet, offset + IPV6_PAYLOAD_LENGTH_OFFSET)
        if (payloadLength == 0) return unsupported(WiredPacketClassification.FAMILY_IPV6, vlanTagged)
        if (payloadLength > availableLength - IPV6_HEADER_BYTES) {
            return invalid(WiredPacketClassification.FAMILY_IPV6, vlanTagged)
        }
        val packetEnd = offset + IPV6_HEADER_BYTES + payloadLength
        val addressOffset = offset + if (matchSourceAddress) IPV6_SOURCE_OFFSET else IPV6_DESTINATION_OFFSET
        val addressChecked = expectedAddress?.size == IPV6_ADDRESS_BYTES
        val addressMatched = expectedAddress != null && expectedAddress.size == IPV6_ADDRESS_BYTES &&
            bytesEqual(packet, addressOffset, expectedAddress, 0, IPV6_ADDRESS_BYTES)
        var nextHeader = packet[offset + IPV6_NEXT_HEADER_OFFSET].toInt() and 0xff
        var cursor = offset + IPV6_HEADER_BYTES
        var extensionCount = 0
        while (isExtensionHeader(nextHeader)) {
            if (extensionCount++ >= MAX_IPV6_EXTENSION_HEADERS) {
                return unsupported(WiredPacketClassification.FAMILY_IPV6, vlanTagged, addressChecked, addressMatched)
            }
            if (nextHeader == IPV6_FRAGMENT_HEADER) {
                if (packetEnd - cursor < IPV6_FRAGMENT_HEADER_BYTES) {
                    return invalid(WiredPacketClassification.FAMILY_IPV6, vlanTagged)
                }
                val fragmentField = readU16(packet, cursor + IPV6_FRAGMENT_FIELD_OFFSET)
                if (fragmentField and IPV6_FRAGMENT_RESERVED_MASK != 0) {
                    return invalid(WiredPacketClassification.FAMILY_IPV6, vlanTagged)
                }
                val fragmented = fragmentField and IPV6_FRAGMENT_OFFSET_AND_MORE_MASK != 0
                nextHeader = packet[cursor].toInt() and 0xff
                cursor += IPV6_FRAGMENT_HEADER_BYTES
                if (fragmented) {
                    return WiredPacketClassification.create(
                        disposition = WiredPacketClassification.DISPOSITION_VALID,
                        family = WiredPacketClassification.FAMILY_IPV6,
                        protocol = transportProtocol(nextHeader, ipv6 = true),
                        addressChecked = addressChecked,
                        addressMatches = addressMatched,
                        fragmented = true,
                        vlanTagged = vlanTagged,
                    )
                }
                continue
            }
            if (packetEnd - cursor < IPV6_EXTENSION_PREFIX_BYTES) {
                return invalid(WiredPacketClassification.FAMILY_IPV6, vlanTagged)
            }
            val extensionBytes = if (nextHeader == IPV6_AUTHENTICATION_HEADER) {
                ((packet[cursor + 1].toInt() and 0xff) + 2) * 4
            } else {
                ((packet[cursor + 1].toInt() and 0xff) + 1) * 8
            }
            if (extensionBytes < IPV6_EXTENSION_PREFIX_BYTES || extensionBytes > packetEnd - cursor) {
                return invalid(WiredPacketClassification.FAMILY_IPV6, vlanTagged)
            }
            nextHeader = packet[cursor].toInt() and 0xff
            cursor += extensionBytes
        }
        val protocol = transportProtocol(nextHeader, ipv6 = true)
        if (protocol != WiredPacketClassification.PROTOCOL_TCP) {
            return when (protocol) {
                WiredPacketClassification.PROTOCOL_UNKNOWN -> unsupported(
                    WiredPacketClassification.FAMILY_IPV6,
                    vlanTagged,
                    addressChecked,
                    addressMatched,
                )
                else -> WiredPacketClassification.create(
                    disposition = WiredPacketClassification.DISPOSITION_VALID,
                    family = WiredPacketClassification.FAMILY_IPV6,
                    protocol = protocol,
                    addressChecked = addressChecked,
                    addressMatches = addressMatched,
                    vlanTagged = vlanTagged,
                )
            }
        }
        return classifyTcp(
            packet = packet,
            tcpOffset = cursor,
            packetEnd = packetEnd,
            family = WiredPacketClassification.FAMILY_IPV6,
            addressChecked = addressChecked,
            expectedAddressMatches = addressMatched,
            expectedPort = expectedPort,
            matchSourcePort = matchSourcePort,
            vlanTagged = vlanTagged,
        )
    }

    private fun classifyTcp(
        packet: ByteArray,
        tcpOffset: Int,
        packetEnd: Int,
        family: Int,
        addressChecked: Boolean,
        expectedAddressMatches: Boolean,
        expectedPort: Int,
        matchSourcePort: Boolean,
        vlanTagged: Boolean,
    ): WiredPacketClassification {
        if (tcpOffset < 0 || packetEnd - tcpOffset < TCP_MIN_HEADER_BYTES) return invalid(family, vlanTagged)
        val tcpHeaderLength = ((packet[tcpOffset + TCP_DATA_OFFSET_OFFSET].toInt() ushr 4) and TCP_DATA_OFFSET_MASK) * 4
        if (tcpHeaderLength < TCP_MIN_HEADER_BYTES || tcpHeaderLength > packetEnd - tcpOffset) {
            return invalid(family, vlanTagged)
        }
        val sourcePort = readU16(packet, tcpOffset + TCP_SOURCE_PORT_OFFSET)
        val destinationPort = readU16(packet, tcpOffset + TCP_DESTINATION_PORT_OFFSET)
        val port = if (matchSourcePort) sourcePort else destinationPort
        return WiredPacketClassification.create(
            disposition = WiredPacketClassification.DISPOSITION_VALID,
            family = family,
            protocol = WiredPacketClassification.PROTOCOL_TCP,
            tcpFlags = packet[tcpOffset + TCP_FLAGS_OFFSET].toInt() and TCP_FLAGS_MASK,
            sourcePort = sourcePort,
            destinationPort = destinationPort,
            addressChecked = addressChecked,
            addressMatches = expectedAddressMatches,
            portChecked = expectedPort in MIN_PORT..MAX_PORT,
            portMatches = expectedPort in MIN_PORT..MAX_PORT && port == expectedPort,
            vlanTagged = vlanTagged,
        )
    }

    private fun transportProtocol(value: Int, ipv6: Boolean): Int = when (value) {
        TCP_NEXT_HEADER -> WiredPacketClassification.PROTOCOL_TCP
        UDP_NEXT_HEADER -> WiredPacketClassification.PROTOCOL_UDP
        if (ipv6) ICMPV6_NEXT_HEADER else ICMPV4_NEXT_HEADER ->
            if (ipv6) WiredPacketClassification.PROTOCOL_ICMPV6 else WiredPacketClassification.PROTOCOL_ICMPV4
        else -> WiredPacketClassification.PROTOCOL_UNKNOWN
    }

    private fun isExtensionHeader(nextHeader: Int): Boolean = when (nextHeader) {
        IPV6_HOP_BY_HOP_HEADER,
        IPV6_ROUTING_HEADER,
        IPV6_FRAGMENT_HEADER,
        IPV6_DESTINATION_HEADER,
        IPV6_AUTHENTICATION_HEADER,
        -> true
        else -> false
    }

    private fun isRangeValid(size: Int, offset: Int, length: Int): Boolean =
        offset >= 0 && length >= 0 && offset <= size - length

    private fun bytesEqual(source: ByteArray, sourceOffset: Int, target: ByteArray, targetOffset: Int, length: Int): Boolean {
        for (index in 0 until length) if (source[sourceOffset + index] != target[targetOffset + index]) return false
        return true
    }

    private fun readU16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    private fun invalid(
        family: Int = WiredPacketClassification.FAMILY_UNKNOWN,
        vlanTagged: Boolean = false,
    ): WiredPacketClassification = WiredPacketClassification.create(
        disposition = WiredPacketClassification.DISPOSITION_INVALID,
        family = family,
        vlanTagged = vlanTagged,
    )

    private fun unsupported(
        family: Int = WiredPacketClassification.FAMILY_UNKNOWN,
        vlanTagged: Boolean = false,
        addressChecked: Boolean = false,
        addressMatches: Boolean = false,
    ): WiredPacketClassification = WiredPacketClassification.create(
        disposition = WiredPacketClassification.DISPOSITION_UNSUPPORTED,
        family = family,
        addressChecked = addressChecked,
        addressMatches = addressMatches,
        vlanTagged = vlanTagged,
    )

    private fun unknown(vlanTagged: Boolean = false): WiredPacketClassification = WiredPacketClassification.create(
        disposition = WiredPacketClassification.DISPOSITION_UNKNOWN,
        vlanTagged = vlanTagged,
    )

    private const val ETHERNET_HEADER_BYTES = 14
    private const val ETHER_TYPE_OFFSET = 12
    private const val VLAN_HEADER_BYTES = 4
    private const val VLAN_INNER_ETHERTYPE_OFFSET = 2
    private const val IPV4_VERSION = 4
    private const val IPV6_VERSION = 6
    private const val IPV4_MIN_HEADER_BYTES = 20
    private const val IPV4_IHL_MASK = 0x0f
    private const val IPV4_TOTAL_LENGTH_OFFSET = 2
    private const val IPV4_FRAGMENT_OFFSET = 6
    private const val IPV4_RESERVED_FLAG_MASK = 0x8000
    private const val IPV4_FRAGMENT_MASK = 0x3fff
    private const val IPV4_PROTOCOL_OFFSET = 9
    private const val IPV4_SOURCE_OFFSET = 12
    private const val IPV4_DESTINATION_OFFSET = 16
    private const val IPV4_ADDRESS_BYTES = 4
    private const val IPV6_HEADER_BYTES = 40
    private const val IPV6_PAYLOAD_LENGTH_OFFSET = 4
    private const val IPV6_NEXT_HEADER_OFFSET = 6
    private const val IPV6_SOURCE_OFFSET = 8
    private const val IPV6_DESTINATION_OFFSET = 24
    private const val IPV6_ADDRESS_BYTES = 16
    private const val IPV6_EXTENSION_PREFIX_BYTES = 2
    private const val IPV6_FRAGMENT_HEADER_BYTES = 8
    private const val IPV6_FRAGMENT_FIELD_OFFSET = 2
    private const val IPV6_FRAGMENT_RESERVED_MASK = 0x0006
    private const val IPV6_FRAGMENT_OFFSET_AND_MORE_MASK = 0xfff9
    private const val MAX_IPV6_EXTENSION_HEADERS = 8
    private const val TCP_MIN_HEADER_BYTES = 20
    private const val TCP_SOURCE_PORT_OFFSET = 0
    private const val TCP_DESTINATION_PORT_OFFSET = 2
    private const val TCP_DATA_OFFSET_OFFSET = 12
    private const val TCP_DATA_OFFSET_MASK = 0x0f
    private const val TCP_FLAGS_OFFSET = 13
    private const val TCP_FLAGS_MASK = 0xff
    private const val MIN_PORT = 1
    private const val MAX_PORT = 65535
    private const val ETHERTYPE_IPV4 = 0x0800
    private const val ETHERTYPE_IPV6 = 0x86dd
    private const val ETHERTYPE_VLAN = 0x8100
    private const val ETHERTYPE_QINQ = 0x88a8
    private const val TCP_NEXT_HEADER = 6
    private const val UDP_NEXT_HEADER = 17
    private const val ICMPV4_NEXT_HEADER = 1
    private const val ICMPV6_NEXT_HEADER = 58
    private const val IPV6_HOP_BY_HOP_HEADER = 0
    private const val IPV6_ROUTING_HEADER = 43
    private const val IPV6_FRAGMENT_HEADER = 44
    private const val IPV6_AUTHENTICATION_HEADER = 51
    private const val IPV6_DESTINATION_HEADER = 60
    private const val MAX_VLAN_TAGS = 2
}
