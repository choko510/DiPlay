package com.shilapi.xcertplay.step4

import com.shilapi.xcertplay.transport.EthernetIpv6Codec
import com.shilapi.xcertplay.transport.Ntb16Codec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Step4FixtureCatalogTest {
    @Test
    fun ntb16FixturesRepresentSingleSplitCoalescedAndBoundaryBlocks() {
        val first = ByteArray(60) { it.toByte() }
        val second = ByteArray(84) { (it + 1).toByte() }
        val firstBlock = Ntb16Fixture.single(first, sequence = 7)
        val secondBlock = Ntb16Fixture.single(second, sequence = 8)

        assertEquals(2, Ntb16Fixture.split(firstBlock, 9).size)
        assertArrayEquals(firstBlock + secondBlock, Ntb16Fixture.coalesced(firstBlock, secondBlock))

        val (boundaryFrame, paddedBlock) = Ntb16Fixture.exact512Boundary(padded = true)
        val (unpaddedFrame, unpaddedBlock) = Ntb16Fixture.exact512Boundary(padded = false)
        assertArrayEquals(boundaryFrame, unpaddedFrame)
        assertEquals(513, paddedBlock.size)
        assertEquals(512, unpaddedBlock.size)
    }

    @Test
    fun ntb16FixturesIncludeShortGarbageAndMalformedNdpInputs() {
        assertEquals(11, Ntb16Fixture.shortHeader().size)
        assertEquals(64, Ntb16Fixture.garbage(64).size)
        assertTrue(Ntb16Fixture.corruptSignature().isNotEmpty())
        assertTrue(Ntb16Codec.parse(Ntb16Fixture.invalidNdpOffset()).isEmpty())
    }

    @Test
    fun startupFixturesKeepNetworkAndAirPlayMilestonesSeparate() {
        assertEquals(listOf(StartupSignal.START_SESSION_SENT), StartupTraceFixtures.startSessionWithoutSyn.signals)
        assertEquals(StartupSignal.IPV4_SYN, StartupTraceFixtures.ipv4SynOnly.signals.last())
        assertEquals(StartupSignal.TUN_WRITE_FAILURE, StartupTraceFixtures.ipv6SynToTunFailure.signals.last())
        assertTrue(StartupTraceFixtures.ipv6SynTunReplyAbsent.signals.contains(StartupSignal.TUN_WRITE_SUCCESS))
        assertFalse(StartupTraceFixtures.ipv6SynTunReplyAbsent.signals.contains(StartupSignal.TUN_SYN_ACK))
        assertEquals(StartupSignal.NCM_OUT_NOT_READY, StartupTraceFixtures.ipv6SynAckNcmNotReady.signals.last())
        assertFalse(StartupTraceFixtures.tcpAcceptedWithoutRtsp.signals.contains(StartupSignal.RTSP_ACTIVE))
        assertFalse(StartupTraceFixtures.rtspActiveWithoutMainFrame.signals.contains(StartupSignal.MAIN_FRAME_SUBMITTED))
        assertTrue(StartupTraceFixtures.mainAndAltAreDistinct.signals.contains(StartupSignal.MAIN_SETUP))
        assertTrue(StartupTraceFixtures.mainAndAltAreDistinct.signals.contains(StartupSignal.ALT_SETUP))
        assertEquals(
            listOf(StartupSignal.SESSION_RECOVERED, StartupSignal.SESSION_LOST_AGAIN),
            StartupTraceFixtures.recoveredThenLostAgain.signals,
        )
    }

    @Test
    fun localSplitAndInputRequestFixturesKeepRequiredVariants() {
        assertEquals(0.55f, SplitGeometryFixture.LOCAL_55_45.carPlayFraction)
        assertEquals(0.45f, SplitGeometryFixture.LOCAL_55_45.youtubeFraction)
        assertFalse(CarPlayInputRequestFixtures.declined.inputRequested)
        assertTrue(CarPlayInputRequestFixtures.siri.inputRequested)
        assertTrue(CarPlayInputRequestFixtures.phone.inputRequested)
        assertTrue(CarPlayInputRequestFixtures.voiceMessage.inputRequested)
        assertEquals(3, listOf(CarPlayInputRequestFixtures.siri, CarPlayInputRequestFixtures.phone,
            CarPlayInputRequestFixtures.voiceMessage).map { it.inputPort }.distinct().size)
    }

    @Test
    fun ipv4AndIpv6TcpInputsRemainExplicitlyDifferentFixtureKinds() {
        assertTrue(EthernetIpv6Codec.parseIpv6View(EthernetPacketFixture.ipv6Tcp()) != null)
        assertNull(EthernetIpv6Codec.parseIpv6View(EthernetPacketFixture.ipv4Tcp()))
    }

    @Test
    fun virtualSchedulerOrdersAndCancelsWithoutWallClockSleep() {
        val clock = VirtualClock()
        val scheduler = ManualScheduler(clock)
        val observed = mutableListOf<String>()
        val cancelled = scheduler.scheduleAfter(5) { observed += "cancelled" }
        cancelled.cancel()
        scheduler.scheduleAfter(10) { observed += "first" }
        scheduler.scheduleAfter(10) { observed += "second" }

        scheduler.advanceBy(9)
        assertTrue(observed.isEmpty())
        scheduler.advanceBy(1)

        assertEquals(listOf("first", "second"), observed)
        assertTrue(cancelled.isCancelled)
    }

    @Test
    fun tunPcmAndVideoFixturesAreDeterministicCopies() {
        val tun = SimulatedTun()
        val outbound = byteArrayOf(1, 2, 3)
        tun.appSends(outbound)
        outbound[0] = 99
        assertArrayEquals(byteArrayOf(1, 2, 3), tun.kernelReceives())

        val inbound = byteArrayOf(4, 5, 6)
        tun.kernelSends(inbound)
        inbound[0] = 88
        assertArrayEquals(byteArrayOf(4, 5, 6), tun.appReceives())

        assertArrayEquals(byteArrayOf(0, 0, 0, 0), Pcm16Fixture.silence(frames = 2, channels = 1))
        val impulse = Pcm16Fixture.impulse(frames = 2, channels = 1, frame = 1, value = 0x1234)
        assertArrayEquals(byteArrayOf(0, 0, 0x34, 0x12), impulse)
        val tone = Pcm16Fixture.sine(frames = 4, channels = 1, sampleRate = 48_000, frequencyHz = 1_000.0)
        assertEquals(8, tone.size)
        assertArrayEquals(byteArrayOf(0, 0), tone.copyOfRange(0, 2))

        val frame = VideoFrameFixture.solid(width = 2, height = 1, presentationTimeUs = 42, rgba = 0x11223344)
        assertEquals(42L, frame.presentationTimeUs)
        assertArrayEquals(byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x11, 0x22, 0x33, 0x44), frame.rgba)
        assertTrue(frame.rgba.isNotEmpty())
    }
}
