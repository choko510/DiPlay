package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.step4.Step4PacketFixtures
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WiredTcpPacketClassifierTest {
    @Test
    fun classifiesIpv4SynByDestinationPortAndChecksOptionalAddress() {
        val frame = Step4PacketFixtures.ethernetIpv4Tcp()
        val expected = byteArrayOf(192.toByte(), 0, 2, 2)
        val matching = WiredTcpPacketClassifier.classifyEthernet(
            frame,
            frame.size,
            expectedIpv4 = expected,
        )
        val mismatching = WiredTcpPacketClassifier.classifyEthernet(
            frame,
            frame.size,
            expectedIpv4 = byteArrayOf(192.toByte(), 0, 2, 9),
        )

        assertEquals(WiredTcpPacketClassification.STATUS_TCP, matching.status)
        assertEquals(4, matching.ipVersion)
        assertTrue(matching.isAirPlaySyn(7_000))
        assertEquals(WiredTcpPacketClassification.DESTINATION_MATCH, matching.destinationMatch)
        assertEquals(WiredTcpPacketClassification.DESTINATION_MISMATCH, mismatching.destinationMatch)
    }

    @Test
    fun classifiesIpv6SynAfterDestinationOptionsAndQinQ() {
        val base = Step4PacketFixtures.ethernetIpv6Tcp()
        val extended = Step4PacketFixtures.ipv6DestinationOptions(base)
        val expected = extended.copyOfRange(14 + 24, 14 + 40)
        val classified = WiredTcpPacketClassifier.classifyEthernet(
            Step4PacketFixtures.vlanTagged(extended, tagCount = 2),
            extended.size + 8,
            expectedIpv6 = expected,
        )

        assertEquals(WiredTcpPacketClassification.STATUS_TCP, classified.status)
        assertEquals(6, classified.ipVersion)
        assertTrue(classified.isAirPlaySyn(7_000))
        assertEquals(WiredTcpPacketClassification.DESTINATION_MATCH, classified.destinationMatch)
    }

    @Test
    fun recognizesOnlyAirPlayPortSynAndTunSourcePortReplies() {
        val wrongPort = Step4PacketFixtures.ethernetIpv4Tcp(destinationPort = 7_001)
        val wrongFlags = Step4PacketFixtures.ethernetIpv4Tcp(flags = 0x12)
        assertFalse(WiredTcpPacketClassifier.classifyEthernet(wrongPort, wrongPort.size).isAirPlaySyn(7_000))
        assertFalse(WiredTcpPacketClassifier.classifyEthernet(wrongFlags, wrongFlags.size).isAirPlaySyn(7_000))

        val synAck = Step4PacketFixtures.ethernetIpv6Tcp(
            sourcePort = 7_000,
            destinationPort = 49_152,
            flags = 0x12,
        ).copyOfRange(14, 14 + 40 + 20)
        val rst = Step4PacketFixtures.ethernetIpv6Tcp(
            sourcePort = 7_000,
            destinationPort = 49_152,
            flags = 0x04,
        ).copyOfRange(14, 14 + 40 + 20)
        val classifiedSynAck = WiredTcpPacketClassifier.classifyTunPacket(synAck, synAck.size)
        val classifiedRst = WiredTcpPacketClassifier.classifyTunPacket(rst, rst.size)

        assertTrue(classifiedSynAck.isAirPlaySynAck(7_000))
        assertTrue(classifiedRst.isAirPlayRst(7_000))
        assertFalse(classifiedSynAck.isAirPlaySyn(7_000))
    }

    @Test
    fun fragmentsAndMalformedOrTruncatedPacketsAreNotConfirmedTcp() {
        val ipv4 = Step4PacketFixtures.ethernetIpv4Tcp()
        val ipv6 = Step4PacketFixtures.ethernetIpv6Tcp()
        val badIhl = ipv4.copyOf().also { it[14] = 0x44 }
        val badTcpOffset = ipv4.copyOf().also { it[14 + 20 + 12] = 0x40 }
        val badVersion = ipv6.copyOf().also { it[14] = 0x70 }
        val jumbo = ipv6.copyOf().also { it[14 + 4] = 0; it[14 + 5] = 0 }
        val unknownNextHeader = ipv6.copyOf().also { it[14 + 6] = 254.toByte() }
        val udpNextHeader = ipv6.copyOf().also { it[14 + 6] = 17 }
        val tooManyExtensions = Step4PacketFixtures.ipv6DestinationOptions(ipv6, headerCount = 9)
        val truncatedVlan = Step4PacketFixtures.vlanTagged(ipv4, tagCount = 1)

        assertEquals(
            WiredTcpPacketClassification.STATUS_FRAGMENTED,
            WiredTcpPacketClassifier.classifyEthernet(Step4PacketFixtures.ipv4Fragment(ipv4), ipv4.size).status,
        )
        val ipv6Fragment = Step4PacketFixtures.ipv6Fragment(ipv6)
        assertEquals(
            WiredTcpPacketClassification.STATUS_FRAGMENTED,
            WiredTcpPacketClassifier.classifyEthernet(ipv6Fragment, ipv6Fragment.size).status,
        )
        assertEquals(
            WiredTcpPacketClassification.STATUS_TRUNCATED,
            WiredTcpPacketClassifier.classifyEthernet(ipv4, ipv4.size - 1).status,
        )
        assertEquals(
            WiredTcpPacketClassification.STATUS_TRUNCATED,
            WiredTcpPacketClassifier.classifyEthernet(truncatedVlan, 15).status,
        )
        assertEquals(
            WiredTcpPacketClassification.STATUS_INVALID,
            WiredTcpPacketClassifier.classifyEthernet(badIhl, badIhl.size).status,
        )
        assertEquals(
            WiredTcpPacketClassification.STATUS_INVALID,
            WiredTcpPacketClassifier.classifyEthernet(badTcpOffset, badTcpOffset.size).status,
        )
        assertEquals(
            WiredTcpPacketClassification.STATUS_INVALID,
            WiredTcpPacketClassifier.classifyEthernet(badVersion, badVersion.size).status,
        )
        assertEquals(
            WiredTcpPacketClassification.STATUS_UNSUPPORTED,
            WiredTcpPacketClassifier.classifyEthernet(jumbo, jumbo.size).status,
        )
        assertEquals(
            WiredTcpPacketClassification.STATUS_UNSUPPORTED,
            WiredTcpPacketClassifier.classifyEthernet(tooManyExtensions, tooManyExtensions.size).status,
        )
        assertEquals(
            WiredTcpPacketClassification.STATUS_UNSUPPORTED,
            WiredTcpPacketClassifier.classifyEthernet(unknownNextHeader, unknownNextHeader.size).status,
        )
        assertEquals(
            WiredTcpPacketClassification.STATUS_NON_TCP,
            WiredTcpPacketClassifier.classifyEthernet(udpNextHeader, udpNextHeader.size).status,
        )
    }

    @Test
    fun seededRandomInputNeverEscapesParserBounds() {
        val random = Random(0x51e7)
        val packet = ByteArray(256)
        repeat(100_000) {
            random.nextBytes(packet)
            val length = random.nextInt(packet.size + 1)
            val ethernetResult = WiredTcpPacketClassifier.classifyEthernet(packet, length)
            val tunResult = WiredTcpPacketClassifier.classifyTunPacket(packet, length)
            assertTrue(ethernetResult.status in WiredTcpPacketClassification.STATUS_TCP..WiredTcpPacketClassification.STATUS_FRAGMENTED)
            assertTrue(tunResult.status in WiredTcpPacketClassification.STATUS_TCP..WiredTcpPacketClassification.STATUS_FRAGMENTED)
        }
    }
}
