package com.shilapi.xcertplay.step4

import com.shilapi.xcertplay.transport.EthernetIpv6Codec
import com.shilapi.xcertplay.transport.Ntb16Codec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Step4TestFixturesTest {
    @Test
    fun ntbFixturesCoverSingleSplitCoalescedPadAndMalformedCases() {
        val firstFrame = Step4TestFixtures.ethernetFrame(seed = 0x10)
        val secondFrame = Step4TestFixtures.ethernetFrame(seed = 0x30)
        val firstBlock = Step4TestFixtures.ntb16(firstFrame, sequence = 7)
        val secondBlock = Step4TestFixtures.ntb16(secondFrame, sequence = 8)

        assertArrayEquals(firstFrame, Ntb16Codec.parse(firstBlock).single())
        assertArrayEquals(firstBlock, Step4TestFixtures.split(firstBlock, 1, 12, 29).flatMap(ByteArray::asIterable).toByteArray())
        assertArrayEquals(Step4TestFixtures.coalesced(firstBlock, secondBlock), firstBlock + secondBlock)

        val boundaryFrame = Step4TestFixtures.ethernetFrame(length = Step4TestFixtures.USB_PACKET_SIZE - 28)
        val paddedBoundary = Step4TestFixtures.padded512ByteNtb()
        val unpaddedBoundary = Step4TestFixtures.unpadded512ByteNtb()
        assertEquals(Step4TestFixtures.USB_PACKET_SIZE + 1, paddedBoundary.size)
        assertEquals(Step4TestFixtures.USB_PACKET_SIZE, unpaddedBoundary.size)
        assertArrayEquals(boundaryFrame, Ntb16Codec.parse(paddedBoundary, 0, Step4TestFixtures.USB_PACKET_SIZE).single())
        assertArrayEquals(boundaryFrame, Ntb16Codec.parse(unpaddedBoundary).single())
        assertTrue(Ntb16Codec.parse(Step4TestFixtures.corruptNdpOffset(firstBlock)).isEmpty())
        assertTrue(Ntb16Codec.parse(Step4TestFixtures.ntb32Candidate()).isEmpty())
        assertTrue(Ntb16Codec.parse(Step4TestFixtures.truncatedNtb16Block()).isEmpty())
        assertEquals(1_048_577, Step4TestFixtures.oversizedGarbage().size)

        assertTrue(Step4TestFixtures.readWaitOutcomes.any { it is UsbRequestWaitFixture.Completed })
        assertTrue(Step4TestFixtures.readWaitOutcomes.contains(UsbRequestWaitFixture.Timeout))
        assertTrue(Step4TestFixtures.readWaitOutcomes.contains(UsbRequestWaitFixture.NullResult))
        assertTrue(Step4TestFixtures.readWaitOutcomes.contains(UsbRequestWaitFixture.UnexpectedCompletion))
        assertTrue(Step4TestFixtures.endpointDescriptors.any { it.maxPacketSize == 512 })
    }

    @Test
    fun packetFixturesDistinguishIpv6SynAndSynAckFromIpv4() {
        val syn = Step4TestFixtures.ipv6TcpSyn()
        val synAck = Step4TestFixtures.ipv6TcpSynAck()
        val synIpv6 = EthernetIpv6Codec.parseIpv6View(syn)
        val synAckIpv6 = EthernetIpv6Codec.parseIpv6View(synAck)

        assertNotNull(synIpv6)
        assertNotNull(synAckIpv6)
        assertEquals("TCP", EthernetIpv6Codec.packetType(syn, synIpv6!!.payloadOffset, synIpv6.payloadLength))
        assertEquals("TCP", EthernetIpv6Codec.packetType(synAck, synAckIpv6!!.payloadOffset, synAckIpv6.payloadLength))
        assertEquals(0x02, syn[67].toInt() and 0xff)
        assertEquals(0x12, synAck[67].toInt() and 0xff)
        assertNull(EthernetIpv6Codec.parseIpv6View(Step4TestFixtures.ipv4TcpSyn()))
        val startupFixtures = listOf(
            Step4TestFixtures.mainOnlyStartup,
            Step4TestFixtures.alternateOnlyStartup,
            Step4TestFixtures.noSynStartup,
            Step4TestFixtures.ipv4OnlyStartup,
            Step4TestFixtures.tunnelReplyMissingStartup,
            Step4TestFixtures.synAckNotReadyStartup,
            Step4TestFixtures.tcpAcceptOnlyStartup,
            Step4TestFixtures.rtspActiveOnlyStartup,
        )
        startupFixtures.forEach { fixture ->
            val elapsed = fixture.events.map { it.elapsedMillis }
            assertEquals(fixture.name, elapsed.sorted(), elapsed)
        }
        assertEquals("NO_SYN", Step4TestFixtures.noSynStartup.events.last().stage)
        assertEquals("NO_IPV6_SYN", Step4TestFixtures.ipv4OnlyStartup.events.last().stage)
        assertEquals("NO_KERNEL_SYN_ACK", Step4TestFixtures.tunnelReplyMissingStartup.events.last().stage)
        assertEquals("NCM_OUT_NOT_READY", Step4TestFixtures.synAckNotReadyStartup.events.last().stage)
        assertEquals("TCP_ACCEPTED", Step4TestFixtures.tcpAcceptOnlyStartup.events.last().stage)
        assertEquals("RTSP_ACTIVE", Step4TestFixtures.rtspActiveOnlyStartup.events.last().stage)
        assertEquals("MAIN_FIRST_FRAME", Step4TestFixtures.mainOnlyStartup.events.last().stage)
        assertEquals("ALT_SETUP", Step4TestFixtures.alternateOnlyStartup.events[1].stage)
        assertTrue(Step4TestFixtures.synAckNotReadyStartup.events.any { it.ethernetPacket?.contentEquals(synAck) == true })
    }

    @Test
    fun fakeTunAndVirtualSchedulerModelPacketAndRecoveredRetryFlows() {
        val tun = SimulatedTunFixture()
        val inbound = Step4TestFixtures.ipv6TcpSyn()
        val outbound = Step4TestFixtures.ipv6TcpSynAck()
        tun.inject(inbound)
        assertArrayEquals(inbound, tun.read())
        tun.write(outbound)
        assertArrayEquals(outbound, tun.writtenPackets.single())
        tun.close()
        assertTrue(tun.closed)

        val scheduler = Step4VirtualScheduler()
        val events = ArrayList<String>()
        val staleRetry = scheduler.schedule(15_000) { events += "stale-retry" }
        scheduler.schedule(8_000) {
            events += "session-recovered"
            staleRetry()
        }
        scheduler.advanceBy(15_000)
        assertEquals(listOf("session-recovered"), events)

        scheduler.schedule(15_000) { events += "second-loss-retry" }
        scheduler.advanceBy(15_000)
        assertEquals(listOf("session-recovered", "second-loss-retry"), events)
    }

    @Test
    fun audioAndVideoFixturesIncludeTimingFormatAndExplicitInputDenial() {
        val pcm = Step4TestFixtures.pcm16()
        assertEquals(48_000, pcm.sampleRate)
        assertEquals(2, pcm.channels)
        assertEquals(pcm.samples.size * Short.SIZE_BYTES, pcm.bytes().size)

        val deniedInput = Step4TestFixtures.microphoneRequests.first { !it.inputRequested }
        assertEquals("EXPLICIT_FALSE", deniedInput.scenario)
        assertEquals("default", deniedInput.audioType)
        assertNotNull(deniedInput.dataPort)
        assertTrue(Step4TestFixtures.microphoneRequests.any { it.scenario == "VOICE_MESSAGE" && it.inputRequested })

        val mainVideo = Step4TestFixtures.videoFrame()
        assertEquals("H264", mainVideo.codec)
        assertTrue(mainVideo.keyFrame)
        assertEquals(33_333L, mainVideo.presentationTimeMicros)
        assertTrue(mainVideo.payload.isNotEmpty())
        assertEquals(0.55f, Step4TestFixtures.localSplitView.carPlayFraction, 0.0f)
        assertEquals(
            listOf("START_SENT", "ALT_SETUP", "RTSP_ACTIVE"),
            Step4TestFixtures.alternateOnlyStartup.events.map { it.stage },
        )
    }
}
