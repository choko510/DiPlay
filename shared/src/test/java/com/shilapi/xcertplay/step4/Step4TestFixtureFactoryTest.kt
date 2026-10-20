package com.shilapi.xcertplay.step4

import android.hardware.usb.UsbRequest
import com.shilapi.xcertplay.transport.Ntb16Codec
import com.shilapi.xcertplay.transport.IphoneUsbException
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [Step4UsbDeviceConnectionShadow::class, Step4UsbRequestShadow::class])
class Step4TestFixtureFactoryTest {
    @Test
    fun scriptedUsbInTimeoutKeepsOneQueuedRequestForTheNextCompletion() {
        val frame = Step4TestFixtureFactory.ethernetFrame(marker = 9)
        val ntb = Step4TestFixtureFactory.ntb16(frame, sequence = 7)
        val usb = Step4TestFixtureFactory.robolectricUsbInHarness(
            incomingPackets = listOf(ntb),
            queueOutcomes = listOf(true),
            waitOutcomes = listOf(RequestWaitOutcome.TIMEOUT, RequestWaitOutcome.COMPLETE_CURRENT),
        )
        val bridge = com.shilapi.xcertplay.transport.NcmUsbBridge(
            connection = usb.connection,
            outEndpoint = Step4TestFixtureFactory.bulkOutEndpoint().materialize(),
            inEndpoint = Step4TestFixtureFactory.bulkInEndpoint().materialize(),
            statusEndpoint = null,
            descriptorHostMac = null,
            selectionDiagnostics = "test fixture only",
            onDiagnosticEvent = {},
            diagnosticProfile = com.shilapi.xcertplay.transport.NcmDiagnosticProfile.AUTO,
            diagnosticAttempt = 1,
            diagnosticsEnabled = false,
            onDiagnosticLog = {},
        )
        try {
            assertArrayEquals(frame, bridge.recv(timeoutMillis = 1_000) ?: error("USB fixture returned no frame"))
            assertEquals(listOf(32_768), usb.queuedBufferCapacities())
            assertEquals(0, usb.incomingPacketCount())
        } finally {
            bridge.close()
        }
    }

    @Test
    fun fakeUsbCanModelA32KiBQueueRejectionAndConfiguredSmallerRetry() {
        val usb = Step4TestFixtureFactory.robolectricUsbInHarness(
            incomingPackets = emptyList(),
            queueOutcomes = listOf(false, true),
            waitOutcomes = emptyList(),
        )
        val request = UsbRequest()
        val endpoint = Step4TestFixtureFactory.bulkInEndpoint().materialize()
        assertTrue(request.initialize(usb.connection, endpoint))

        assertFalse(request.queue(ByteBuffer.allocateDirect(32_768)))
        assertTrue(request.queue(ByteBuffer.allocateDirect(16_384)))
        assertEquals(listOf(32_768, 16_384), usb.queuedBufferCapacities())
        request.close()
        usb.connection.close()
    }

    @Test
    fun requestWaitDispatcherRequiresExplicitOutcomesAndExhaustsWithoutInventingCompletion() {
        val dispatcher = Step4TestFixtureFactory.requestWaitDispatcher(
            listOf(
                RequestWaitOutcome.TIMEOUT,
                RequestWaitOutcome.NULL_RESULT,
                RequestWaitOutcome.COMPLETE_CURRENT,
                RequestWaitOutcome.COMPLETE_UNEXPECTED,
            ),
        )

        assertEquals(RequestWaitOutcome.TIMEOUT, dispatcher.next())
        assertEquals(RequestWaitOutcome.NULL_RESULT, dispatcher.next())
        assertEquals(RequestWaitOutcome.COMPLETE_CURRENT, dispatcher.next())
        assertEquals(RequestWaitOutcome.COMPLETE_UNEXPECTED, dispatcher.next())
        try {
            dispatcher.next()
            throw AssertionError("an unconfigured wait must not imply completion")
        } catch (_: IllegalStateException) {
            // Exhaustion is an explicit fixture failure, not a successful USB completion.
        }
    }

    @Test
    fun unexpectedUsbCompletionIsNotMistakenForTheQueuedNcmRequest() {
        val usb = Step4TestFixtureFactory.robolectricUsbInHarness(
            incomingPackets = emptyList(),
            queueOutcomes = listOf(true),
            waitOutcomes = listOf(RequestWaitOutcome.COMPLETE_UNEXPECTED),
        )
        val bridge = com.shilapi.xcertplay.transport.NcmUsbBridge(
            connection = usb.connection,
            outEndpoint = Step4TestFixtureFactory.bulkOutEndpoint().materialize(),
            inEndpoint = Step4TestFixtureFactory.bulkInEndpoint().materialize(),
            statusEndpoint = null,
            descriptorHostMac = null,
            selectionDiagnostics = "test fixture only",
            onDiagnosticEvent = {},
            diagnosticProfile = com.shilapi.xcertplay.transport.NcmDiagnosticProfile.AUTO,
            diagnosticAttempt = 1,
            diagnosticsEnabled = false,
            onDiagnosticLog = {},
        )

        try {
            org.junit.Assert.assertThrows(IphoneUsbException::class.java) { bridge.recv(timeoutMillis = 1_000) }
        } finally {
            bridge.close()
        }
    }

    @Test
    fun ntbFixturesExposeBoundarySplitCoalescedAndCorruptionInputsWithoutCallingThemPasses() {
        val fixtures = Step4TestFixtureFactory.ntbFixtures()

        assertEquals(512, fixtures.boundaryBlock.size)
        assertEquals(513, fixtures.optionalZeroShortPad.size)
        assertArrayEquals(fixtures.boundaryBlock, fixtures.optionalZeroShortPad.copyOf(512))
        assertEquals(0, fixtures.optionalZeroShortPad.last().toInt())
        assertArrayEquals(fixtures.boundaryBlock, fixtures.splitReads.fold(ByteArray(0)) { all, chunk -> all + chunk })
        val secondBlock = Ntb16Codec.build(Step4TestFixtureFactory.ethernetFrame(marker = 1), 8)
        assertEquals(fixtures.boundaryBlock.size + secondBlock.size, fixtures.coalescedBlocks.size)
        assertEquals(8, fixtures.truncatedHeader.size)
        assertEquals(0xff, fixtures.corruptNdpOffset[10].toInt() and 0xff)
        assertEquals(1024 * 1024 + 1, Step4TestFixtureFactory.oversizedGarbage().size)
    }

    @Test
    fun fakeTunDoesNotAssumeWriteOrKernelReplySuccess() {
        val packet = byteArrayOf(1, 2, 3)
        val tun = Step4TestFixtureFactory.tun(
            writeOutcomes = listOf(TunWriteOutcome.NOT_READY, TunWriteOutcome.WRITTEN),
            kernelReplies = listOf(null, byteArrayOf(4, 5, 6)),
        )

        assertEquals(TunWriteOutcome.NOT_READY, tun.writeToKernel(packet))
        assertTrue(tun.packetsWrittenToKernel().isEmpty())
        assertEquals(TunWriteOutcome.WRITTEN, tun.writeToKernel(packet))
        assertArrayEquals(packet, tun.packetsWrittenToKernel().single())
        assertEquals(null, tun.readKernelReply())
        assertArrayEquals(byteArrayOf(4, 5, 6), tun.readKernelReply())
    }

    @Test
    fun virtualSchedulerRunsOnlyAdvancedTasksInStableDeadlineOrder() {
        val scheduler = Step4TestFixtureFactory.virtualScheduler()
        val observed = mutableListOf<String>()
        scheduler.schedule(20) { observed += "late" }
        val cancel = scheduler.schedule(10) { observed += "cancelled" }
        scheduler.schedule(10) { observed += "early" }
        cancel()

        scheduler.advanceBy(9)
        assertTrue(observed.isEmpty())
        scheduler.advanceBy(1)
        assertEquals(listOf("early"), observed)
        scheduler.advanceBy(10)
        assertEquals(listOf("early", "late"), observed)
        assertEquals(20L, scheduler.clock.nowMillis)
    }

    @Test
    fun startupAudioSplitPcmAndVideoFixturesKeepTheirEvidenceSeparate() {
        val names = Step4TestFixtureFactory.wiredStartupScenarios.associateBy(WiredStartupFixture::name)
        assertEquals(
            listOf(WiredStartupEvent.START_SESSION_SENT),
            names.getValue("start-session-no-syn").events,
        )
        assertEquals(
            listOf(WiredStartupEvent.START_SESSION_SENT, WiredStartupEvent.IPV4_SYN_TO_AIRPLAY),
            names.getValue("ipv4-syn-only").events,
        )
        assertEquals(
            listOf(
                WiredStartupEvent.START_SESSION_SENT,
                WiredStartupEvent.IPV6_SYN_TO_AIRPLAY,
                WiredStartupEvent.TUN_WRITE_SUCCEEDED,
                WiredStartupEvent.SYN_ACK_OBSERVED,
                WiredStartupEvent.USB_OUT_NOT_READY,
            ),
            names.getValue("ipv6-syn-synack-out-not-ready").events,
        )
        assertEquals(
            listOf(WiredStartupEvent.START_SESSION_SENT, WiredStartupEvent.TCP_ACCEPTED),
            names.getValue("tcp-accept-only").events,
        )
        assertEquals(
            listOf(WiredStartupEvent.START_SESSION_SENT, WiredStartupEvent.TCP_ACCEPTED, WiredStartupEvent.RTSP_ACTIVE),
            names.getValue("rtsp-active-only").events,
        )
        assertTrue(WiredStartupEvent.MAIN_SETUP !in names.getValue("alt-setup-only").events)
        assertTrue(WiredStartupEvent.ALT_SETUP !in names.getValue("main-setup").events)
        assertEquals(
            listOf(WiredStartupEvent.SESSION_RECOVERED, WiredStartupEvent.SECOND_LOSS),
            names.getValue("recovered-then-second-loss").events,
        )
        assertEquals(
            setOf("SIRI", "PHONE", "VOICE_MESSAGE"),
            Step4TestFixtureFactory.microphoneRequests.drop(1).map(MicrophoneRequestFixture::messageType).toSet(),
        )
        assertFalse(Step4TestFixtureFactory.microphoneRequests.first().isInput)
        assertEquals(0.55f, Step4TestFixtureFactory.localSplit55.carPlayFraction)
        assertEquals(1920, Step4TestFixtureFactory.localSplit55.widthPixels)

        val pcm = Step4TestFixtureFactory.pcm16(sampleRate = 48_000, channels = 2, frames = 3)
        val video = Step4TestFixtureFactory.videoFrame(width = 1280, height = 720, presentationTimeUs = 42)
        assertEquals(12, pcm.bytes.size)
        assertArrayEquals(pcm.bytes, Step4TestFixtureFactory.pcm16(48_000, 2, 3).bytes)
        assertEquals(42L, video.presentationTimeUs)
        assertArrayEquals(byteArrayOf(0x53, 0x54, 0x45, 0x50), video.opaquePayload)
    }
}
