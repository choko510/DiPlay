package com.shilapi.xcertplay.testing.step4

import android.hardware.usb.UsbConstants
import com.shilapi.xcertplay.transport.Ntb16Codec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class Step4TestFactoryTest {
    @Test
    fun usbConnectionFixtureUsesRobolectricAndDeliversOnlyConfiguredBytes() {
        val fixture = Step4TestFactory.usbConnection()
        val incoming = byteArrayOf(0x01, 0x02, 0x03, 0x04)

        assertEquals(3, fixture.endpointDescriptors.size)
        assertEquals(UsbConstants.USB_DIR_IN or 1, fixture.endpointDescriptors[0].address)
        assertEquals(UsbConstants.USB_ENDPOINT_XFER_BULK, fixture.endpointDescriptors[0].attributes)
        assertEquals(UsbConstants.USB_DIR_OUT or 2, fixture.endpointDescriptors[1].address)
        assertEquals(512, fixture.endpointDescriptors[0].maxPacketSize)
        assertEquals(16, fixture.endpointDescriptors[2].maxPacketSize)
        assertArrayEquals(incoming, fixture.readIncoming(incoming))
    }

    @Test
    fun ntbFactoryCreates512ByteBoundaryPadVariantsAndReadShapes() {
        val exactBoundaryFrame = ByteArray(484) { it.toByte() }
        val secondFrame = byteArrayOf(0x33, 0x33, 0x86.toByte(), 0xdd.toByte())
        val unpadded = Step4TestFactory.ncmReads(listOf(exactBoundaryFrame))
        val padded = Step4TestFactory.ncmReads(listOf(exactBoundaryFrame), optionalPadAfterBlocks = setOf(0))
        val split = Step4TestFactory.ncmReads(listOf(exactBoundaryFrame), readChunkSizes = listOf(1, 511, 13))
        val coalesced = Step4TestFactory.ncmReads(listOf(exactBoundaryFrame, secondFrame))

        assertEquals(512, unpadded.blocks.single().size)
        assertEquals(512, unpadded.reads.sumOf(ByteArray::size))
        assertEquals(513, padded.reads.sumOf(ByteArray::size))
        assertTrue(split.reads.size > 1)
        assertEquals(2, coalesced.blocks.size)
        assertEquals(1, coalesced.reads.size)
        assertArrayEquals(exactBoundaryFrame, unpadded.expectedDatagrams.single())
        assertArrayEquals(exactBoundaryFrame, Ntb16Codec.parse(unpadded.blocks.single()).single())
        assertArrayEquals(exactBoundaryFrame, Ntb16Codec.parse(padded.reads.single()).single())
    }

    @Test
    fun malformedNcmFactoryPreservesMissingShortAndCorruptInputs() {
        val scenarios = Step4TestFactory.malformedNcmReads().associateBy(NamedNcmFailureFixture::name)

        assertTrue(scenarios.getValue("no-read").reads.isEmpty())
        assertEquals(0, scenarios.getValue("zero-byte-read").reads.single().size)
        val shortNth = scenarios.getValue("short-nth").reads.single()
        assertTrue(shortNth.size < 12)
        assertTrue(Ntb16Codec.parse(shortNth).isEmpty())
        val badNdp = scenarios.getValue("invalid-ndp-offset").reads.single()
        assertTrue(Ntb16Codec.parse(badNdp).isEmpty())
        assertTrue(Ntb16Codec.parse(scenarios.getValue("corrupt-garbage").reads.single()).isEmpty())
    }

    @Test
    fun requestWaitDispatcherDoesNotInventSuccessWhenOutcomesAreMissing() {
        val request = Step4TestFactory.requestWaitDispatcher(
            UsbRequestWaitOutcome.Timeout,
            UsbRequestWaitOutcome.NullResult,
            UsbRequestWaitOutcome.Completed("bulk-in", 512),
            UsbRequestWaitOutcome.UnexpectedCompletion("status-in"),
        )

        assertSame(UsbRequestWaitOutcome.Timeout, request.requestWait())
        assertSame(UsbRequestWaitOutcome.NullResult, request.requestWait())
        assertEquals(UsbRequestWaitOutcome.Completed("bulk-in", 512), request.requestWait())
        assertEquals(UsbRequestWaitOutcome.UnexpectedCompletion("status-in"), request.requestWait())
        assertEquals(0, request.pendingCount())
        try {
            request.requestWait()
            throw AssertionError("an unspecified wait must not report success")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("No requestWait outcome"))
        }
    }

    @Test
    fun usbReadScenariosKeepQueueAndWaitOutcomesDistinct() {
        val scenarios = Step4TestFactory.usbReadScenarios().associateBy(UsbReadScenario::name)

        assertEquals(32 * 1024, scenarios.getValue("queue32k-complete").bufferBytes)
        assertTrue(scenarios.getValue("queue32k-complete").queueSucceeded)
        assertFalse(scenarios.getValue("queue32k-false").queueSucceeded)
        assertEquals(null, scenarios.getValue("queue32k-false").waitOutcome)
        assertSame(UsbRequestWaitOutcome.Timeout, scenarios.getValue("queued-timeout").waitOutcome)
        assertSame(UsbRequestWaitOutcome.NullResult, scenarios.getValue("queued-null").waitOutcome)
    }

    @Test
    fun fakeTunRequiresAnExplicitWriteOutcomeAndKeepsFailureSeparate() {
        val firstPacket = byteArrayOf(1, 2, 3)
        val secondPacket = byteArrayOf(4, 5, 6)
        val tun = Step4TestFactory.tun(
            inboundPackets = listOf(firstPacket),
            writeOutcomes = listOf(TunWriteOutcome.Failure("synthetic failure"), TunWriteOutcome.Success),
        )

        assertArrayEquals(firstPacket, tun.read())
        assertEquals(null, tun.read())
        assertEquals(TunWriteOutcome.Failure("synthetic failure"), tun.write(firstPacket))
        assertTrue(tun.sentPackets().isEmpty())
        assertEquals(TunWriteOutcome.Success, tun.write(secondPacket))
        assertArrayEquals(secondPacket, tun.sentPackets().single())
    }

    @Test
    fun virtualSchedulerPreservesDeadlineOrderAndCancellation() {
        val scheduler = Step4TestFactory.virtualScheduler(startMillis = 100)
        val events = mutableListOf<String>()
        scheduler.schedule(20) { events += "later" }
        val cancel = scheduler.schedule(10) { events += "cancelled" }
        scheduler.schedule(10) { events += "first" }
        cancel()

        scheduler.advanceBy(9)
        assertTrue(events.isEmpty())
        scheduler.advanceBy(1)
        assertEquals(listOf("first"), events)
        scheduler.advanceBy(10)
        assertEquals(listOf("first", "later"), events)
        assertEquals(120, scheduler.clock.nowMillis)
        assertEquals(0, scheduler.pendingCount())
    }

    @Test
    fun mediaFixturesAreDeterministicAndCarryExplicitFormatMetadata() {
        val pcm = Step4TestFactory.pcm16(shortArrayOf(1, -2, 0x1234), sampleRate = 44_100, channelCount = 1)
        val video = Step4TestFactory.videoPayload(width = 4, height = 2, presentationTimeUs = 33_333, seed = 7)

        assertEquals(44_100, pcm.sampleRate)
        assertEquals(1, pcm.channelCount)
        assertArrayEquals(byteArrayOf(1, 0, -2, -1, 0x34, 0x12), pcm.littleEndianBytes)
        assertEquals("opaque-test-payload", video.codec)
        assertEquals(33_333, video.presentationTimeUs)
        assertArrayEquals(ByteArray(8) { (it + 7).toByte() }, video.bytes)
    }

    @Test
    fun attemptFixturesKeepNetworkAndAirPlayMilestonesDistinct() {
        val attempt = Step4TestFactory.wiredAttempt(
            WiredAttemptEvent.START_SESSION_SENT,
            WiredAttemptEvent.IPV6_SYN,
            WiredAttemptEvent.TUN_WRITE_SUCCESS,
            WiredAttemptEvent.SYN_ACK_NOT_READY,
            WiredAttemptEvent.RTSP_ACTIVE,
            WiredAttemptEvent.ALT_SETUP,
        )

        assertEquals(WiredAttemptEvent.IPV6_SYN, attempt.events[1])
        assertEquals(WiredAttemptEvent.RTSP_ACTIVE, attempt.events[4])
        assertFalse(attempt.events.contains(WiredAttemptEvent.MAIN_FIRST_FRAME))
    }

    @Test
    fun minimumWiredAndMicScenariosKeepIncompleteSignalsExplicit() {
        val attempts = Step4TestFactory.minimumWiredAttempts().associateBy(NamedWiredAttemptFixture::name)
        val microphones = Step4TestFactory.microphoneRequests().associateBy(MicrophoneSetupFixture::name)
        val noSyn = attempts.getValue("start-session-no-syn").attempt.events
        val ipv4Only = attempts.getValue("ipv4-syn-only").attempt.events
        val notReady = attempts.getValue("ipv6-tun-synack-not-ready").attempt.events
        val tunReplyNone = attempts.getValue("tun-reply-none").attempt.events
        val controlOnly = attempts.getValue("airplay-control-accepted-only").attempt.events
        val activeOnly = attempts.getValue("rtsp-active-only").attempt.events

        assertEquals(listOf(WiredAttemptEvent.START_SESSION_SENT), noSyn)
        assertEquals(
            listOf(WiredAttemptEvent.START_SESSION_SENT, WiredAttemptEvent.IPV4_SYN),
            ipv4Only,
        )
        assertEquals(
            listOf(
                WiredAttemptEvent.START_SESSION_SENT,
                WiredAttemptEvent.IPV6_SYN,
                WiredAttemptEvent.TUN_WRITE_SUCCESS,
                WiredAttemptEvent.SYN_ACK_NOT_READY,
            ),
            notReady,
        )
        assertTrue(tunReplyNone.contains(WiredAttemptEvent.TUN_REPLY_NONE))
        assertFalse(tunReplyNone.contains(WiredAttemptEvent.TUN_WRITE_SUCCESS))
        assertEquals(listOf(WiredAttemptEvent.AIRPLAY_CONTROL_ACCEPTED), controlOnly)
        assertEquals(listOf(WiredAttemptEvent.RTSP_ACTIVE), activeOnly)
        assertFalse(attempts.getValue("tcp-accept-only").attempt.events.contains(WiredAttemptEvent.MAIN_FIRST_FRAME))
        assertEquals(listOf(WiredAttemptEvent.ALT_SETUP), attempts.getValue("alt-setup-only").attempt.events)
        assertEquals(
            listOf(WiredAttemptEvent.MAIN_SETUP, WiredAttemptEvent.MAIN_FIRST_FRAME),
            attempts.getValue("main-first-frame").attempt.events,
        )
        assertEquals(
            listOf(
                WiredAttemptEvent.HOST_RETRY_SCHEDULED,
                WiredAttemptEvent.SESSION_ACTIVE,
                WiredAttemptEvent.SESSION_LOST_AGAIN,
                WiredAttemptEvent.HOST_RETRY_SCHEDULED,
            ),
            attempts.getValue("recovered-then-second-loss").attempt.events,
        )
        assertEquals(AudioRequestKind.SIRI, microphones.getValue("siri").kind)
        assertEquals(AudioRequestKind.PHONE, microphones.getValue("phone").kind)
        assertEquals(AudioRequestKind.VOICE_MESSAGE, microphones.getValue("voice-message").kind)
        assertFalse(microphones.getValue("explicit-false-with-port").input!!)
        assertEquals(49_103, microphones.getValue("explicit-false-with-port").dataPort)
    }

    @Test
    fun localSplitFixtureFreezesMainBaselineRatio() {
        val viewport = Step4TestFactory.localSplitViewport()

        assertEquals(1_920, viewport.width)
        assertEquals(1_080, viewport.height)
        assertEquals(0.55f, viewport.carPlayFraction)
    }
}
