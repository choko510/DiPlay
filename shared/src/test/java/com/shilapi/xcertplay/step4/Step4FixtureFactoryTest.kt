package com.shilapi.xcertplay.step4

import android.hardware.usb.FakeUsbDeviceConnection
import android.hardware.usb.FakeUsbDeviceConnection.FakeUsbRequest
import com.shilapi.xcertplay.airplay.VideoCodec
import com.shilapi.xcertplay.diagnostics.WiredPathDiagnostics
import com.shilapi.xcertplay.diagnostics.WiredPathEvent
import com.shilapi.xcertplay.diagnostics.WiredPathReportReason
import com.shilapi.xcertplay.media.MediaCodecSupport
import com.shilapi.xcertplay.transport.IphoneUsbException
import com.shilapi.xcertplay.transport.NcmDiagnosticEvent
import com.shilapi.xcertplay.transport.NcmDiagnosticProfile
import com.shilapi.xcertplay.transport.NcmSendResult
import com.shilapi.xcertplay.transport.NcmUsbBridge
import com.shilapi.xcertplay.transport.Ntb16Codec
import java.nio.ByteBuffer
import java.util.concurrent.TimeoutException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class Step4FixtureFactoryTest {
    @Test
    fun ntbFixtureCreatesExactBoundaryWithOptionalPad() {
        val frame = ByteArray(484) { it.toByte() }
        val withoutPad = Step4PacketFixtures.ntb16(frame)
        val withPad = Step4PacketFixtures.ntb16(frame, shortPad = true)

        assertEquals(512, withoutPad.size)
        assertEquals(513, withPad.size)
        assertEquals(0, withPad.last().toInt())
        assertArrayEquals(frame, Ntb16Codec.parse(withoutPad, 0, withoutPad.size).single())
        val frames = listOf(byteArrayOf(1, 2), byteArrayOf(3, 4, 5))
        val multiDatagram = Ntb16Codec.parse(Step4PacketFixtures.ntb16(frames))
        assertEquals(frames.size, multiDatagram.size)
        frames.forEachIndexed { index, expected -> assertArrayEquals(expected, multiDatagram[index]) }
    }

    @Test
    fun usbFixtureRequiresScriptedQueueAndTransferSuccess() {
        val connection = FakeUsbDeviceConnection()
        val endpoint = FakeUsbDeviceConnection.bulkOut(2, 512)
        val request = FakeUsbRequest(true)

        assertEquals(-1, connection.bulkTransfer(endpoint, byteArrayOf(1, 2), 2, 100))
        assertEquals(512, endpoint.maxPacketSize)
        assertTrue(request.initialize(connection, FakeUsbDeviceConnection.bulkIn(1, 512)))
        assertFalse(request.queue(ByteBuffer.allocateDirect(32 * 1024)))
        request.enqueueQueueResult(true)
        assertTrue(request.queue(ByteBuffer.allocateDirect(32 * 1024)))
        connection.enqueueWaitCompletion(byteArrayOf(1, 2, 3))
        assertSame(request, connection.requestWait(100))
        assertEquals(1, connection.getRequestWaitCalls())
    }

    @Test
    fun usbFixtureDistinguishesTimeoutNullAndUnexpectedCompletion() {
        val connection = FakeUsbDeviceConnection()
        connection.enqueueWaitTimeout()
        val timeout = runCatching { connection.requestWait(100) }.exceptionOrNull()
        assertTrue(timeout is TimeoutException)
        connection.enqueueWaitNull()
        assertNull(connection.requestWait(100))
        val unexpected = FakeUsbRequest(true)
        connection.enqueueUnexpectedCompletion(unexpected)
        assertSame(unexpected, connection.requestWait(100))
    }

    @Test
    fun ncmBridgeUsesScriptedUsbOutResults() {
        val frame = Step4PacketFixtures.ethernetIpv4Tcp()
        val expectedLength = Ntb16Codec.build(frame, 0).size

        val notReadyConnection = FakeUsbDeviceConnection()
        val notReadyBridge = bridge(notReadyConnection)
        assertSame(NcmSendResult.NotReady, notReadyBridge.send(frame, 100))
        notReadyBridge.close()

        val partialConnection = FakeUsbDeviceConnection().apply {
            enqueueBulkOutResult(expectedLength - 1)
        }
        val partialBridge = bridge(partialConnection)
        assertTrue(partialBridge.send(frame, 100) is NcmSendResult.Failed)
        partialBridge.close()

        val sentConnection = FakeUsbDeviceConnection().apply {
            enqueueBulkOutResult(expectedLength)
        }
        val sentBridge = bridge(sentConnection)
        assertSame(NcmSendResult.Sent, sentBridge.send(frame, 100))
        assertEquals(1, sentConnection.getBulkOutTransfers().size)
        sentBridge.close()
    }

    @Test
    fun ncmReceiveDiagnosticsCountAssembledAndDeliveredEthernetFrames() {
        val frame = Step4PacketFixtures.ethernetIpv6Tcp()
        val diagnostics = WiredPathDiagnostics(
            enabled = true,
            controllerRunId = "rx-test-run",
            attempt = 1,
            listenerPort = 7_000,
        )
        val request = FakeUsbRequest(true).apply { enqueueQueueResult(true) }
        val connection = FakeUsbDeviceConnection().apply {
            enqueueWaitCompletion(Step4PacketFixtures.ntb16(frame))
        }
        val bridge = bridge(connection, request, wiredPathDiagnostics = diagnostics)

        try {
            assertArrayEquals(frame, bridge.recv(1_000))
            assertEquals(1L, diagnostics.count(WiredPathEvent.NCM_ETHERNET_COMPLETE))
            assertEquals(1L, diagnostics.count(WiredPathEvent.NCM_ETHERNET_DELIVERED))
        } finally {
            bridge.close()
        }
    }

    @Test
    fun wiredDiagnosticsOnAndOffKeepNcmTransferBytesAndResultEqual() {
        val frame = Step4PacketFixtures.ethernetIpv4Tcp()
        val blockLength = Ntb16Codec.build(frame, 0).size
        val disabled = WiredPathDiagnostics(
            enabled = false,
            controllerRunId = "disabled-run",
            attempt = 1,
            listenerPort = 7_000,
        )
        val enabled = WiredPathDiagnostics(
            enabled = true,
            controllerRunId = "enabled-run",
            attempt = 1,
            listenerPort = 7_000,
        )
        val offConnection = FakeUsbDeviceConnection().apply { enqueueBulkOutResult(blockLength) }
        val onConnection = FakeUsbDeviceConnection().apply { enqueueBulkOutResult(blockLength) }
        val offBridge = bridge(offConnection, wiredPathDiagnostics = disabled)
        val onBridge = bridge(onConnection, wiredPathDiagnostics = enabled)

        val offResult = offBridge.send(frame, 100)
        val onResult = onBridge.send(frame, 100)
        val offBytes = offConnection.getBulkOutTransfers().single()
        val onBytes = onConnection.getBulkOutTransfers().single()
        offBridge.close()
        onBridge.close()

        assertSame(NcmSendResult.Sent, offResult)
        assertSame(NcmSendResult.Sent, onResult)
        assertArrayEquals(offBytes, onBytes)
        assertEquals(0, disabled.count(WiredPathEvent.FIRST_BULK_OUT_FULL_SENT))
        assertEquals(1, enabled.count(WiredPathEvent.FIRST_BULK_OUT_FULL_SENT))
        assertEquals(1, enabled.count(WiredPathEvent.NCM_WRITE_SENT))
        assertNotNull(enabled.snapshot(WiredPathReportReason.FINAL))
    }

    @Test
    fun ncmAsyncReadReusesQueuedRequestAfterTimeout() {
        val frame = Step4PacketFixtures.ethernetIpv6Tcp()
        val request = FakeUsbRequest(true).apply { enqueueQueueResult(true) }
        val connection = FakeUsbDeviceConnection().apply {
            enqueueWaitTimeout()
            enqueueWaitCompletion(Step4PacketFixtures.ntb16(frame))
        }
        val bridge = bridge(connection, request)

        try {
            assertArrayEquals(frame, bridge.recv(1_000))
            assertEquals(1, request.getQueueCalls())
            assertEquals(2, connection.getRequestWaitCalls())
            assertEquals(32 * 1024, request.queuedBufferCapacity)
            assertEquals(1, bridge.diagnosticSnapshot().readTimeouts)
            assertEquals(1, bridge.diagnosticSnapshot().readCompletions)
        } finally {
            bridge.close()
        }
        assertTrue(request.isClosed())
    }

    @Test
    fun ncmAsyncReadDeliversAlignedBlockWithoutOptionalShortPad() {
        val frame = ByteArray(484) { it.toByte() }
        val block = Step4PacketFixtures.ntb16(frame)
        val request = FakeUsbRequest(true).apply {
            enqueueQueueResult(true)
            enqueueQueueResult(true)
        }
        val connection = FakeUsbDeviceConnection().apply {
            enqueueWaitCompletion(byteArrayOf())
            enqueueWaitCompletion(block)
        }
        val bridge = bridge(connection, request)

        try {
            assertArrayEquals(frame, bridge.recv(1_000))
            assertEquals(2, request.queueCalls)
        } finally {
            bridge.close()
        }
    }

    @Test
    fun ncmSyncReadReassemblesSplitAndCoalescedBlocks() {
        val frame1 = Step4PacketFixtures.ethernetIpv4Tcp()
        val frame2 = Step4PacketFixtures.ethernetIpv6Tcp()
        val combined = Step4PacketFixtures.ntb16(frame1) + Step4PacketFixtures.ntb16(frame2, sequence = 1)
        val connection = FakeUsbDeviceConnection().apply {
            enqueueBulkIn(combined.copyOfRange(0, 13))
            enqueueBulkIn(combined.copyOfRange(13, 85))
            enqueueBulkIn(combined.copyOfRange(85, combined.size))
        }
        val bridge = bridge(connection, profile = NcmDiagnosticProfile.SYNC_BULK_IN)

        try {
            assertArrayEquals(frame1, bridge.recv(1_000))
            assertArrayEquals(frame2, bridge.recv(1_000))
            assertEquals(3, bridge.diagnosticSnapshot().syncReadCalls)
        } finally {
            bridge.close()
        }
    }

    @Test
    fun ncmSyncReadDeliversAlignedBlockWithoutOptionalShortPad() {
        val frame = ByteArray(484) { it.toByte() }
        val block = Step4PacketFixtures.ntb16(frame)
        assertEquals(512, block.size)
        val bridge = bridge(
            FakeUsbDeviceConnection().apply {
                enqueueBulkIn(byteArrayOf())
                block.forEach { enqueueBulkIn(byteArrayOf(it)) }
            },
            profile = NcmDiagnosticProfile.SYNC_BULK_IN,
        )

        try {
            assertArrayEquals(frame, bridge.recv(1_000))
            assertEquals(1, bridge.diagnosticSnapshot().syncReadNoData)
        } finally {
            bridge.close()
        }
    }

    @Test
    fun ncmSyncReadDoesNotAssumePadAroundPacketSizeBoundary() {
        val frames = (510..514).map { blockLength ->
            ByteArray(blockLength - 28) { (blockLength + it).toByte() }
        }
        val combined = frames.foldIndexed(ByteArray(0)) { index, data, frame ->
            data + Step4PacketFixtures.ntb16(frame, sequence = index)
        }
        val bridge = bridge(
            FakeUsbDeviceConnection().apply { enqueueBulkIn(combined) },
            profile = NcmDiagnosticProfile.SYNC_BULK_IN,
        )

        try {
            frames.forEach { frame -> assertArrayEquals(frame, bridge.recv(1_000)) }
        } finally {
            bridge.close()
        }
    }

    @Test
    fun ncmSyncReadSkipsOptionalPadAndPreservesNextAlignedNtb() {
        val frame1 = ByteArray(484) { it.toByte() }
        val frame2 = ByteArray(484) { (it xor 0x55).toByte() }
        val padded = Step4PacketFixtures.ntb16(frame1, shortPad = true)
        val unpadded = Step4PacketFixtures.ntb16(frame2, sequence = 1)
        val bridge = bridge(
            FakeUsbDeviceConnection().apply { enqueueBulkIn(padded + unpadded) },
            profile = NcmDiagnosticProfile.SYNC_BULK_IN,
        )

        try {
            assertArrayEquals(frame1, bridge.recv(1_000))
            assertArrayEquals(frame2, bridge.recv(1_000))
        } finally {
            bridge.close()
        }
    }

    @Test
    fun ncmSyncReadUsesActualEndpointPacketSizeForOptionalPad() {
        val frame64 = ByteArray(36) { it.toByte() }
        val block64 = Step4PacketFixtures.ntb16(frame64)
        assertEquals(64, block64.size)
        val bridge64 = bridge(
            FakeUsbDeviceConnection().apply { enqueueBulkIn(block64 + byteArrayOf(0)) },
            profile = NcmDiagnosticProfile.SYNC_BULK_IN,
            inMaxPacketSize = 64,
        )
        val frame1024 = ByteArray(484) { (it xor 0x33).toByte() }
        val block512 = Step4PacketFixtures.ntb16(frame1024)
        val bridge1024 = bridge(
            FakeUsbDeviceConnection().apply { enqueueBulkIn(block512) },
            profile = NcmDiagnosticProfile.SYNC_BULK_IN,
            inMaxPacketSize = 1_024,
        )

        try {
            assertArrayEquals(frame64, bridge64.recv(1_000))
            assertArrayEquals(frame1024, bridge1024.recv(1_000))
        } finally {
            bridge64.close()
            bridge1024.close()
        }
    }

    @Test
    fun ncmSyncReadResynchronizesDamagedNthsAndNdpBeforeNextValidBlocks() {
        val damagedNth = Step4PacketFixtures.ntb16(byteArrayOf(1, 2, 3)).copyOf().also {
            it[4] = 0
        }
        val damagedLength = Step4PacketFixtures.ntb16(byteArrayOf(19, 20, 21)).copyOf().also {
            it[8] = 1
            it[9] = 0
        }
        val damagedNdpHeader = Step4PacketFixtures.ntb16(byteArrayOf(4, 5, 6)).copyOf().also {
            it[10] = 0xff.toByte()
            it[11] = 0xff.toByte()
        }
        val damagedNdpLength = Step4PacketFixtures.ntb16(byteArrayOf(22, 23, 24)).copyOf().also {
            it[16] = 4
            it[17] = 0
        }
        val damagedNdpSignature = Step4PacketFixtures.ntb16(byteArrayOf(7, 8, 9)).copyOf().also {
            it[12] = 0
            it[13] = 0
            it[14] = 0
            it[15] = 0
        }
        val valid1 = byteArrayOf(10, 11, 12)
        val valid2 = byteArrayOf(13, 14, 15)
        val valid3 = byteArrayOf(16, 17, 18)
        val connection = FakeUsbDeviceConnection().apply {
            enqueueBulkIn(
                damagedNth + damagedLength + Step4PacketFixtures.ntb16(valid1, sequence = 1) +
                    damagedNdpHeader + damagedNdpLength + Step4PacketFixtures.ntb16(valid2, sequence = 2) +
                    damagedNdpSignature + Step4PacketFixtures.ntb16(valid3, sequence = 3),
            )
        }
        val bridge = bridge(connection, profile = NcmDiagnosticProfile.SYNC_BULK_IN)

        try {
            assertArrayEquals(valid1, bridge.recv(1_000))
            assertArrayEquals(valid2, bridge.recv(1_000))
            assertArrayEquals(valid3, bridge.recv(1_000))
        } finally {
            bridge.close()
        }
    }

    @Test
    fun ncmSyncReadResynchronizesGarbageToValidNtbAndBoundsLongCorruptRuns() {
        val expected = byteArrayOf(21, 22, 23)
        val validBlock = Step4PacketFixtures.ntb16(expected)
        val falseNtbHeader = ByteArray(28).also {
            it[0] = 0x4e
            it[1] = 0x43
            it[2] = 0x4d
            it[3] = 0x48
            it[4] = 12
            it[8] = 28
            it[10] = 12
        }
        val recoverable = bridge(
            FakeUsbDeviceConnection().apply {
                enqueueBulkIn(ByteArray(1_024) { 0x55.toByte() } + falseNtbHeader + validBlock)
            },
            profile = NcmDiagnosticProfile.SYNC_BULK_IN,
        )
        val corruptRun = bridge(
            FakeUsbDeviceConnection().apply {
                enqueueBulkIn(ByteArray((1 shl 20) + 1) { 0x55.toByte() })
            },
            profile = NcmDiagnosticProfile.SYNC_BULK_IN,
        )

        try {
            assertArrayEquals(expected, recoverable.recv(1_000))
            val error = runCatching { corruptRun.recv(1_000) }.exceptionOrNull()
            assertTrue(error is com.shilapi.xcertplay.transport.IphoneUsbException.DeviceUnavailable)
            assertTrue(error?.message.orEmpty().contains("NCM_RESYNC_ERROR"))
        } finally {
            recoverable.close()
            corruptRun.close()
        }
    }

    @Test
    fun ncmRxRecoveryRetainsTheExistingFrameQueueLimit() {
        val output = java.io.ByteArrayOutputStream()
        repeat(257) { sequence ->
            output.write(Step4PacketFixtures.ntb16(byteArrayOf(sequence.toByte()), sequence))
        }
        val bridge = bridge(
            FakeUsbDeviceConnection().apply { enqueueBulkIn(output.toByteArray()) },
            profile = NcmDiagnosticProfile.SYNC_BULK_IN,
        )

        try {
            val error = runCatching { bridge.recv(1_000) }.exceptionOrNull()
            assertTrue(error is IphoneUsbException.DeviceUnavailable)
            assertTrue(error?.message.orEmpty().contains("frame queue exceeded its bounds"))
        } finally {
            bridge.close()
        }
    }

    @Test
    fun ncmAsyncReadTreatsNullCompletionAsTerminal() {
        val request = FakeUsbRequest(true).apply { enqueueQueueResult(true) }
        val connection = FakeUsbDeviceConnection().apply { enqueueWaitNull() }
        val bridge = bridge(connection, request)

        try {
            val failure = runCatching { bridge.recv(1_000) }.exceptionOrNull()
            assertNotNull(failure)
            assertTrue(failure?.message.orEmpty().contains("NCM_REQUEST_WAIT_ERROR"))
            assertEquals(1, bridge.diagnosticSnapshot().readNullErrors)
        } finally {
            bridge.close()
        }
    }

    @Test
    fun ncmAsyncReadRejectsUnexpectedCompletionAndCancelsOwnedRequest() {
        val request = FakeUsbRequest(true).apply { enqueueQueueResult(true) }
        val connection = FakeUsbDeviceConnection().apply {
            enqueueUnexpectedCompletion(FakeUsbRequest(true))
        }
        val bridge = bridge(connection, request)

        try {
            val failure = runCatching { bridge.recv(1_000) }.exceptionOrNull()
            assertNotNull(failure)
            assertTrue(failure?.message.orEmpty().contains("NCM_REQUEST_WAIT_ERROR"))
            assertEquals(1, bridge.diagnosticSnapshot().readUnexpectedCompletions)
        } finally {
            bridge.close()
        }
        assertEquals(1, request.getCancelCalls())
    }

    @Test
    fun virtualSchedulerRunsDueTasksInOrderAndHonorsCancellation() {
        val clock = VirtualClock()
        val scheduler = VirtualScheduler(clock)
        val events = mutableListOf<String>()
        scheduler.schedule(10) { events.add("late") }
        val cancelled = scheduler.schedule(5) { events.add("cancelled") }
        scheduler.schedule(5) { events.add("early") }
        cancelled.cancel()

        assertEquals(0, scheduler.advanceByMillis(4))
        assertEquals(1, scheduler.advanceByMillis(1))
        assertEquals(listOf("early"), events)
        assertEquals(1, scheduler.advanceByMillis(5))
        assertEquals(listOf("early", "late"), events)
        assertEquals(10_000_000L, clock.nowNanos)
    }

    @Test
    fun wiredFixturesKeepNoSynAndPartialStartupStagesDistinct() {
        val noSyn = WiredScenarioFixtures.startSessionNoSyn()
        assertTrue(noSyn.events.any { it.kind == WiredFixtureEventKind.START_SESSION_SENT })
        assertFalse(noSyn.events.any { it.kind == WiredFixtureEventKind.IPV4_SYN || it.kind == WiredFixtureEventKind.IPV6_SYN })

        val ipv4Only = WiredScenarioFixtures.ipv4SynOnly()
        assertTrue(ipv4Only.events.any { it.kind == WiredFixtureEventKind.IPV4_SYN })
        assertFalse(ipv4Only.events.any { it.kind == WiredFixtureEventKind.IPV6_SYN })

        val notReady = WiredScenarioFixtures.ipv6SynAckNotReady()
        assertTrue(notReady.events.map { it.kind }.containsAll(
            listOf(
                WiredFixtureEventKind.IPV6_SYN,
                WiredFixtureEventKind.TUN_WRITE_SUCCESS,
                WiredFixtureEventKind.SYN_ACK_OBSERVED,
                WiredFixtureEventKind.NCM_NOT_READY,
            ),
        ))
        assertFalse(WiredScenarioFixtures.tunReplyNone().events.any { it.kind == WiredFixtureEventKind.SYN_ACK_OBSERVED })
        assertEquals(WiredFixtureEventKind.TCP_ACCEPTED, WiredScenarioFixtures.tcpAcceptOnly().events.last().kind)
        assertEquals(WiredFixtureEventKind.RTSP_ACTIVE, WiredScenarioFixtures.rtspActiveOnly().events.last().kind)
        assertEquals(WiredFixtureEventKind.ALT_SETUP, WiredScenarioFixtures.altOnly().events.last().kind)
        val splitStreams = WiredScenarioFixtures.mainAndAltSeparate().events.map { it.kind }
        assertTrue(splitStreams.contains(WiredFixtureEventKind.MAIN_SETUP))
        assertTrue(splitStreams.contains(WiredFixtureEventKind.ALT_SETUP))
        assertTrue(splitStreams.contains(WiredFixtureEventKind.MAIN_FRAME_SUBMITTED))
        val recovered = WiredScenarioFixtures.recoveredThenSecondLoss().events.map { it.kind }
        assertTrue(recovered.indexOf(WiredFixtureEventKind.RECONNECT_FAILURE) < recovered.indexOf(WiredFixtureEventKind.RETRY_PENDING))
        assertTrue(recovered.indexOf(WiredFixtureEventKind.RETRY_PENDING) < recovered.indexOf(WiredFixtureEventKind.SESSION_ACTIVE))
        assertEquals(WiredFixtureEventKind.SECOND_LOSS, recovered.last())
        assertTrue(MicrophoneFixtures.siri().inputEnabled)
        assertTrue(MicrophoneFixtures.phone().inputEnabled)
        assertTrue(MicrophoneFixtures.voiceMessage().inputEnabled)
        assertFalse(MicrophoneFixtures.explicitlyDisabled().inputEnabled)
    }

    @Test
    fun tunAndMediaFixturesRequireExplicitInputsAndAreDeterministic() {
        val tun = FakeTunDevice()
        val packet = Step4PacketFixtures.ethernetIpv4Tcp()
        assertFalse(tun.write(packet))
        tun.enqueueWriteOutcome(true)
        assertTrue(tun.write(packet))
        tun.enqueueWriteException(IllegalStateException("scripted TUN failure"))
        assertTrue(runCatching { tun.write(packet) }.exceptionOrNull() is IllegalStateException)
        packet[0] = 0
        assertArrayEquals(Step4PacketFixtures.ethernetIpv4Tcp(), tun.writtenPackets.single())
        tun.enqueueIncoming(byteArrayOf(1, 2, 3))
        assertArrayEquals(byteArrayOf(1, 2, 3), tun.read())
        assertNull(tun.read())

        val impulse = PcmFixtures.impulse(frameCount = 4)
        assertEquals(1f, impulse[0])
        assertEquals(0f, PcmFixtures.silence(4).sum(), 0f)
        assertArrayEquals(
            PcmFixtures.sine(48_000, 1_000.0, 16),
            PcmFixtures.sine(48_000, 1_000.0, 16),
            0f,
        )
        val video = VideoFixtures.frame(presentationTimeUs = 1_000)
        assertEquals(VideoCodec.H264, video.codec)
        assertTrue(video.keyFrame)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x65, 0x80.toByte()), video.nalus)
        assertTrue(MediaCodecSupport.isRandomAccess(video.nalus, video.codec))
        assertEquals(1_000L, video.presentationTimeUs)
        val predicted = VideoFixtures.frame(codec = VideoCodec.H265, keyFrame = false)
        assertFalse(predicted.keyFrame)
        assertFalse(MediaCodecSupport.isRandomAccess(predicted.nalus, predicted.codec))
        assertTrue(MediaCodecSupport.isRandomAccess(VideoFixtures.frame(codec = VideoCodec.H265).nalus, VideoCodec.H265))
    }

    private fun bridge(
        connection: FakeUsbDeviceConnection,
        request: FakeUsbRequest = FakeUsbRequest(false),
        profile: NcmDiagnosticProfile = NcmDiagnosticProfile.AUTO,
        wiredPathDiagnostics: WiredPathDiagnostics? = null,
        inMaxPacketSize: Int = 512,
    ): NcmUsbBridge = NcmUsbBridge(
        connection = connection,
        outEndpoint = FakeUsbDeviceConnection.bulkOut(2, 512),
        inEndpoint = FakeUsbDeviceConnection.bulkIn(1, inMaxPacketSize),
        statusEndpoint = null,
        descriptorHostMac = null,
        selectionDiagnostics = "step4-fixture",
        onDiagnosticEvent = { _: NcmDiagnosticEvent -> },
        diagnosticProfile = profile,
        diagnosticAttempt = 1,
        diagnosticsEnabled = false,
        onDiagnosticLog = {},
        wiredPathDiagnostics = wiredPathDiagnostics,
        usbRequestFactory = { request },
    )
}
