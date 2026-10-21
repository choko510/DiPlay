package com.shilapi.xcertplay.step4

import com.shilapi.xcertplay.transport.Ntb16Codec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class Step4TestFixturesTest {
    @Test
    fun ntbFixturesCoverSingleSplitCoalescedPaddedAndMalformedInputs() {
        val firstFrame = Step4TestFixtures.ethernetFrame(seed = 0x10)
        val secondFrame = Step4TestFixtures.ethernetFrame(seed = 0x30)
        val firstBlock = Step4TestFixtures.ntb16(firstFrame, sequence = 7)
        val secondBlock = Step4TestFixtures.ntb16(secondFrame, sequence = 8)

        assertArrayEquals(firstFrame, Ntb16Codec.parse(firstBlock).single())
        val splitBlock = Step4TestFixtures.split(firstBlock, 1, 12, 29)
            .fold(ByteArray(0)) { output, part -> output + part }
        assertArrayEquals(firstBlock, splitBlock)
        assertArrayEquals(firstBlock + secondBlock, Step4TestFixtures.coalesced(firstBlock, secondBlock))

        val paddedBoundary = Step4TestFixtures.paddedBoundaryNtb16()
        val unpaddedBoundary = Step4TestFixtures.unpaddedBoundaryNtb16()
        assertEquals(Step4TestFixtures.USB_PACKET_SIZE + 1, paddedBoundary.size)
        assertEquals(Step4TestFixtures.USB_PACKET_SIZE, unpaddedBoundary.size)
        assertArrayEquals(Step4TestFixtures.boundaryFrame(), Ntb16Codec.parse(paddedBoundary).single())
        assertArrayEquals(Step4TestFixtures.boundaryFrame(), Ntb16Codec.parse(unpaddedBoundary).single())
        assertTrue(Ntb16Codec.parse(Step4TestFixtures.corruptNdpOffset(firstBlock)).isEmpty())
        assertTrue(Ntb16Codec.parse(Step4TestFixtures.truncatedNtb16Header()).isEmpty())
        assertEquals(1_048_577, Step4TestFixtures.oversizedGarbage().size)
    }

    @Test
    fun startupAudioVideoAndUsbFixturesRetainTheirExplicitInputs() {
        assertTrue(Step4TestFixtures.endpointDescriptors.any { it.address == 0x81 && it.maxPacketSize == 512 })
        assertTrue(Step4TestFixtures.readWaitOutcomes.any { it is RequestWaitOutcome.Complete })
        assertTrue(Step4TestFixtures.readWaitOutcomes.contains(RequestWaitOutcome.Timeout))
        assertTrue(Step4TestFixtures.readWaitOutcomes.contains(RequestWaitOutcome.NullResult))
        assertTrue(Step4TestFixtures.readWaitOutcomes.contains(RequestWaitOutcome.UnexpectedCompletion))
        assertTrue(Step4TestFixtures.readWaitOutcomes.contains(RequestWaitOutcome.BlockUntilConnectionClose))
        assertTrue(Step4TestFixtures.wiredStartupScenarios.any { it.name == "start-session-no-syn" })
        assertTrue(Step4TestFixtures.wiredStartupScenarios.any { it.name == "ipv4-syn-only" })
        assertTrue(Step4TestFixtures.wiredStartupScenarios.any { it.name == "syn-ack-not-ready" })
        assertTrue(Step4TestFixtures.wiredStartupScenarios.any { it.name == "tcp-accept-only" })
        assertTrue(Step4TestFixtures.wiredStartupScenarios.any { it.name == "rtsp-active-only" })
        assertTrue(Step4TestFixtures.wiredStartupScenarios.any { it.name == "alt-only" })
        assertTrue(Step4TestFixtures.wiredStartupScenarios.any { it.name == "recovered-then-second-loss" })
        Step4TestFixtures.wiredStartupScenarios.forEach { fixture ->
            val timestamps = fixture.events.map { it.elapsedMillis }
            assertEquals(fixture.name, timestamps.sorted(), timestamps)
        }
        assertEquals(
            listOf(WiredStartupEventType.START_SESSION_SENT, WiredStartupEventType.ALT_SETUP),
            Step4TestFixtures.wiredStartupScenarios.single { it.name == "alt-only" }.events.map { it.type },
        )

        val deniedInput = Step4TestFixtures.microphoneRequests.first { !it.isInput }
        assertEquals("EXPLICIT_FALSE", deniedInput.name)
        assertTrue("fixture must supply its explicitly configured data port", deniedInput.dataPort != null)
        assertTrue(Step4TestFixtures.microphoneRequests.any { it.name == "VOICE_MESSAGE" && it.isInput })
        assertEquals(0.55f, Step4TestFixtures.localSplit55.carPlayFraction, 0.0f)

        val pcm = Step4TestFixtures.pcm16(sampleRate = 48_000, channels = 2, frames = 5)
        assertEquals(48_000, pcm.sampleRate)
        assertEquals(2, pcm.channels)
        assertEquals(5, pcm.frames)
        assertEquals(5 * 2 * Short.SIZE_BYTES, pcm.bytes.size)

        val video = Step4TestFixtures.videoFrame()
        assertEquals("H264", video.codec)
        assertEquals(1920, video.width)
        assertEquals(1080, video.height)
        assertEquals(33_333L, video.presentationTimeUs)
        assertTrue(video.payload.isNotEmpty())
    }

    @Test
    fun tunFixtureRequiresEachWriteAndReplyOutcomeToBeConfigured() {
        val tun = SimulatedTunFixture(writeOutcomes = emptyList(), kernelReplies = emptyList())

        assertThrows(IllegalStateException::class.java) {
            tun.writeToKernel(Step4TestFixtures.ipv6TcpSyn())
        }
        assertThrows(IllegalStateException::class.java) { tun.readKernelReply() }
    }

    @Test
    fun virtualSchedulerRunsDeadlinesInOrderAndHonorsCancellation() {
        val clock = VirtualClock(startAtMillis = 100L)
        val scheduler = VirtualScheduler(clock)
        val events = mutableListOf<String>()
        val cancelled = scheduler.schedule(20L) { events += "cancelled" }
        scheduler.schedule(10L) { events += "first" }
        scheduler.schedule(30L) { events += "last" }
        cancelled()

        scheduler.advanceBy(30L)

        assertEquals(listOf("first", "last"), events)
        assertEquals(130L, clock.nowMillis)
    }

    @Test
    fun tunCopiesPacketsSoFixtureCallersCannotMutateRecordedTraffic() {
        val tun = SimulatedTunFixture(
            writeOutcomes = listOf(TunWriteOutcome.WRITTEN),
            kernelReplies = listOf(Step4TestFixtures.ipv6TcpSynAck()),
        )
        val packet = Step4TestFixtures.ipv6TcpSyn()
        tun.writeToKernel(packet)
        packet.fill(0)

        assertFalse(tun.packetsWrittenToKernel().single().all { it == 0.toByte() })
        assertTrue(tun.readKernelReply()?.isNotEmpty() == true)
    }
}
