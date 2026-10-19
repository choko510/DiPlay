package com.shilapi.xcertplay.transport

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WiredTcpPacketClassifierTest {
    @Test
    fun ipv4SynChecksIhlDestinationAndPortWithoutChangingInput() {
        val packet = ipv4Packet(ihlWords = 6, totalLength = 44)
        val frame = ethernetFrame(packet, ETHERTYPE_IPV4)
        val wrapped = ByteArray(frame.size + 9).apply { frame.copyInto(this, 4) }
        val before = wrapped.copyOf()

        val result = WiredTcpPacketClassifier.classifyEthernet(
            wrapped,
            offset = 4,
            length = frame.size,
            expectedAddress = IPV4_DESTINATION,
            expectedPort = AIRPLAY_PORT,
        )

        assertEquals(WiredPacketClassification.DISPOSITION_VALID, result.disposition)
        assertEquals(WiredPacketClassification.FAMILY_IPV4, result.family)
        assertTrue(result.tcpSyn)
        assertTrue(result.addressChecked)
        assertTrue(result.addressMatches)
        assertTrue(result.portChecked)
        assertTrue(result.portMatches)
        assertEquals(50_000, result.sourcePort)
        assertEquals(AIRPLAY_PORT, result.destinationPort)
        assertEquals(true, wrapped.contentEquals(before))
    }

    @Test
    fun ipv4FragmentsAndMalformedLengthsCannotBecomeSyns() {
        val fragmented = WiredTcpPacketClassifier.classifyRawIp(
            ipv4Packet(fragmentField = 0x2000),
            expectedPort = AIRPLAY_PORT,
        )
        val shortTotalLength = WiredTcpPacketClassifier.classifyRawIp(
            ipv4Packet(totalLength = 19),
            expectedPort = AIRPLAY_PORT,
        )
        val reservedFlag = WiredTcpPacketClassifier.classifyRawIp(
            ipv4Packet(fragmentField = 0x8000),
            expectedPort = AIRPLAY_PORT,
        )
        val shortTcpHeader = WiredTcpPacketClassifier.classifyRawIp(
            ipv4Packet(tcpDataOffsetWords = 4),
            expectedPort = AIRPLAY_PORT,
        )

        assertTrue(fragmented.fragmented)
        assertFalse(fragmented.tcpSyn)
        assertEquals(WiredPacketClassification.DISPOSITION_INVALID, shortTotalLength.disposition)
        assertEquals(WiredPacketClassification.DISPOSITION_INVALID, reservedFlag.disposition)
        assertEquals(WiredPacketClassification.DISPOSITION_INVALID, shortTcpHeader.disposition)
    }

    @Test
    fun ipv6SynAckMatchesSourcePortAndAddress() {
        val packet = ipv6Packet(tcpFlags = TCP_SYN or TCP_ACK, tcpSourcePort = AIRPLAY_PORT)
        val sourceAddress = packet.copyOfRange(IPV6_SOURCE_OFFSET, IPV6_SOURCE_OFFSET + 16)

        val result = WiredTcpPacketClassifier.classifyRawIp(
            packet,
            expectedAddress = sourceAddress,
            matchSourceAddress = true,
            expectedPort = AIRPLAY_PORT,
            matchSourcePort = true,
        )

        assertEquals(WiredPacketClassification.DISPOSITION_VALID, result.disposition)
        assertEquals(WiredPacketClassification.FAMILY_IPV6, result.family)
        assertTrue(result.tcpSynAck)
        assertFalse(result.tcpSyn)
        assertTrue(result.addressChecked)
        assertTrue(result.addressMatches)
        assertTrue(result.portMatches)
    }

    @Test
    fun ipv6ExtensionWalkIsBoundedAndAcceptsEightHeaders() {
        val supported = WiredTcpPacketClassifier.classifyRawIp(
            ipv6Packet(extensions = List(8) { IPV6_HOP_BY_HOP }),
            expectedPort = AIRPLAY_PORT,
        )
        val tooMany = WiredTcpPacketClassifier.classifyRawIp(
            ipv6Packet(extensions = List(9) { IPV6_HOP_BY_HOP }),
            expectedPort = AIRPLAY_PORT,
        )

        assertTrue(supported.tcpSyn)
        assertEquals(WiredPacketClassification.DISPOSITION_UNSUPPORTED, tooMany.disposition)
    }

    @Test
    fun ipv6FragmentJumboAndUnsupportedProtocolAreNotTreatedAsNoSyn() {
        val fragmented = WiredTcpPacketClassifier.classifyRawIp(
            ipv6Packet(extensions = listOf(IPV6_FRAGMENT), fragmentField = 1),
            expectedPort = AIRPLAY_PORT,
        )
        val atomicFragment = WiredTcpPacketClassifier.classifyRawIp(
            ipv6Packet(extensions = listOf(IPV6_FRAGMENT)),
            expectedPort = AIRPLAY_PORT,
        )
        val jumbogram = WiredTcpPacketClassifier.classifyRawIp(
            ipv6Packet(payloadLength = 0),
            expectedPort = AIRPLAY_PORT,
        )
        val unknownProtocol = WiredTcpPacketClassifier.classifyRawIp(
            ipv4Packet(protocol = 99),
            expectedPort = AIRPLAY_PORT,
        )

        assertTrue(fragmented.fragmented)
        assertFalse(fragmented.tcpSyn)
        assertFalse(atomicFragment.fragmented)
        assertTrue(atomicFragment.tcpSyn)
        assertEquals(WiredPacketClassification.DISPOSITION_UNSUPPORTED, jumbogram.disposition)
        assertEquals(WiredPacketClassification.DISPOSITION_UNSUPPORTED, unknownProtocol.disposition)
    }

    @Test
    fun ethernetAcceptsVlanAndQinQAndSeparatesUnknownFromInvalid() {
        val packet = ipv6Packet()
        val tagged = WiredTcpPacketClassifier.classifyEthernet(
            ethernetFrame(packet, ETHERTYPE_IPV6, listOf(ETHERTYPE_QINQ, ETHERTYPE_VLAN)),
            expectedPort = AIRPLAY_PORT,
        )
        val tooManyTags = WiredTcpPacketClassifier.classifyEthernet(
            ethernetFrame(packet, ETHERTYPE_IPV6, List(3) { ETHERTYPE_VLAN }),
        )
        val unknown = WiredTcpPacketClassifier.classifyEthernet(
            ethernetFrame(ByteArray(8), ethertype = 0x0806),
        )
        val truncatedTag = ByteArray(16).apply { writeU16(this, 12, ETHERTYPE_VLAN) }

        assertTrue(tagged.vlanTagged)
        assertTrue(tagged.tcpSyn)
        assertEquals(WiredPacketClassification.DISPOSITION_UNSUPPORTED, tooManyTags.disposition)
        assertEquals(WiredPacketClassification.DISPOSITION_UNKNOWN, unknown.disposition)
        assertEquals(WiredPacketClassification.DISPOSITION_INVALID,
            WiredTcpPacketClassifier.classifyEthernet(truncatedTag).disposition)
    }

    @Test
    fun parserHandlesOneHundredThousandSeededArbitraryBuffers() {
        val random = Random(0x57_49_52_45_44L)
        repeat(100_000) {
            val bytes = ByteArray(random.nextInt(257)).also(random::nextBytes)
            val offset = if (bytes.isEmpty()) 0 else random.nextInt(bytes.size + 1)
            val length = random.nextInt(bytes.size - offset + 1)
            val ethernet = WiredTcpPacketClassifier.classifyEthernet(bytes, offset, length)
            val raw = WiredTcpPacketClassifier.classifyRawIp(bytes, offset, length)

            assertTrue(ethernet.disposition in 0..3)
            assertTrue(raw.disposition in 0..3)
            if (ethernet.tcpSyn || ethernet.tcpSynAck || ethernet.tcpRst) {
                assertEquals(WiredPacketClassification.DISPOSITION_VALID, ethernet.disposition)
                assertEquals(WiredPacketClassification.PROTOCOL_TCP, ethernet.protocol)
            }
            if (raw.tcpSyn || raw.tcpSynAck || raw.tcpRst) {
                assertEquals(WiredPacketClassification.DISPOSITION_VALID, raw.disposition)
                assertEquals(WiredPacketClassification.PROTOCOL_TCP, raw.protocol)
            }
        }
    }

    private fun ethernetFrame(
        packet: ByteArray,
        ethertype: Int,
        tags: List<Int> = emptyList(),
    ): ByteArray {
        val frame = ByteArray(ETHERNET_HEADER_BYTES + tags.size * VLAN_HEADER_BYTES + packet.size)
        writeU16(frame, ETHER_TYPE_OFFSET, tags.firstOrNull() ?: ethertype)
        var tagOffset = ETHERNET_HEADER_BYTES
        tags.forEachIndexed { index, _ ->
            writeU16(frame, tagOffset + VLAN_INNER_ETHERTYPE_OFFSET, tags.getOrNull(index + 1) ?: ethertype)
            tagOffset += VLAN_HEADER_BYTES
        }
        packet.copyInto(frame, tagOffset)
        return frame
    }

    private fun ipv4Packet(
        ihlWords: Int = 5,
        totalLength: Int? = null,
        fragmentField: Int = 0,
        tcpDataOffsetWords: Int = 5,
        protocol: Int = IP_TCP,
    ): ByteArray {
        val headerBytes = ihlWords * 4
        val tcp = tcpSegment(dataOffsetWords = tcpDataOffsetWords)
        val packet = ByteArray(headerBytes + tcp.size)
        packet[0] = ((IPV4_VERSION shl 4) or ihlWords).toByte()
        writeU16(packet, 2, totalLength ?: packet.size)
        writeU16(packet, 6, fragmentField)
        packet[9] = protocol.toByte()
        IPV4_SOURCE.copyInto(packet, 12)
        IPV4_DESTINATION.copyInto(packet, 16)
        tcp.copyInto(packet, headerBytes)
        return packet
    }

    private fun ipv6Packet(
        tcpFlags: Int = TCP_SYN,
        tcpSourcePort: Int = 50_000,
        extensions: List<Int> = emptyList(),
        fragmentField: Int = 0,
        payloadLength: Int? = null,
    ): ByteArray {
        val tcp = tcpSegment(sourcePort = tcpSourcePort, flags = tcpFlags)
        val payloadBytes = extensions.size * IPV6_EXTENSION_BYTES + tcp.size
        val packet = ByteArray(IPV6_HEADER_BYTES + payloadBytes)
        packet[0] = 0x60
        writeU16(packet, 4, payloadLength ?: payloadBytes)
        packet[6] = (extensions.firstOrNull() ?: IP_TCP).toByte()
        IPV6_SOURCE.copyInto(packet, IPV6_SOURCE_OFFSET)
        IPV6_DESTINATION.copyInto(packet, IPV6_DESTINATION_OFFSET)
        var cursor = IPV6_HEADER_BYTES
        extensions.forEachIndexed { index, extension ->
            packet[cursor] = (extensions.getOrNull(index + 1) ?: IP_TCP).toByte()
            if (extension == IPV6_FRAGMENT) writeU16(packet, cursor + 2, fragmentField)
            cursor += IPV6_EXTENSION_BYTES
        }
        tcp.copyInto(packet, cursor)
        return packet
    }

    private fun tcpSegment(
        sourcePort: Int = 50_000,
        destinationPort: Int = AIRPLAY_PORT,
        flags: Int = TCP_SYN,
        dataOffsetWords: Int = 5,
    ): ByteArray = ByteArray(TCP_HEADER_BYTES).apply {
        writeU16(this, 0, sourcePort)
        writeU16(this, 2, destinationPort)
        this[12] = (dataOffsetWords shl 4).toByte()
        this[13] = flags.toByte()
    }

    private fun writeU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }

    private companion object {
        const val AIRPLAY_PORT = 7_000
        const val ETHERNET_HEADER_BYTES = 14
        const val ETHER_TYPE_OFFSET = 12
        const val VLAN_HEADER_BYTES = 4
        const val VLAN_INNER_ETHERTYPE_OFFSET = 2
        const val IPV4_VERSION = 4
        const val IPV6_HEADER_BYTES = 40
        const val IPV6_SOURCE_OFFSET = 8
        const val IPV6_DESTINATION_OFFSET = 24
        const val IPV6_EXTENSION_BYTES = 8
        const val TCP_HEADER_BYTES = 20
        const val TCP_SYN = 0x02
        const val TCP_ACK = 0x10
        const val IP_TCP = 6
        const val ETHERTYPE_IPV4 = 0x0800
        const val ETHERTYPE_IPV6 = 0x86dd
        const val ETHERTYPE_VLAN = 0x8100
        const val ETHERTYPE_QINQ = 0x88a8
        const val IPV6_HOP_BY_HOP = 0
        const val IPV6_FRAGMENT = 44
        val IPV4_SOURCE = byteArrayOf(192.toByte(), 0, 2, 10)
        val IPV4_DESTINATION = byteArrayOf(192.toByte(), 0, 2, 1)
        val IPV6_SOURCE = ByteArray(16).apply { this[0] = 0xfe.toByte(); this[1] = 0x80.toByte(); this[15] = 2 }
        val IPV6_DESTINATION = ByteArray(16).apply { this[0] = 0xfe.toByte(); this[1] = 0x80.toByte(); this[15] = 1 }
    }
}
