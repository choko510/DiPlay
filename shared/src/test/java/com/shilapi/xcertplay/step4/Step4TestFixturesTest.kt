package com.shilapi.xcertplay.step4

import android.hardware.usb.UsbConstants
import com.shilapi.xcertplay.transport.NcmDiagnosticProfile
import com.shilapi.xcertplay.transport.NcmSendResult
import com.shilapi.xcertplay.transport.Ntb16Codec
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [28],
    shadows = [
        Step4UsbDeviceConnectionShadow::class,
        Step4UsbEndpointShadow::class,
        Step4UsbRequestShadow::class,
    ],
)
class Step4TestFixturesTest {
    @Test
    fun usbEndpointDescriptorsAreExplicitAndTyped() {
        val endpoints = Step4TestFixtures.ncmEndpoints()

        assertEquals(1, endpoints.bulkIn.descriptor.interfaceNumber)
        assertEquals(1, endpoints.bulkIn.descriptor.alternateSetting)
        assertEquals(0x81, endpoints.bulkIn.endpoint.address)
        assertEquals(512, endpoints.bulkIn.endpoint.maxPacketSize)
        assertEquals(UsbConstants.USB_ENDPOINT_XFER_BULK, endpoints.bulkOut.endpoint.type)
        assertEquals(UsbConstants.USB_ENDPOINT_XFER_INT, endpoints.statusIn.endpoint.type)
    }

    @Test
    fun ncmBulkOutHasNoSuccessfulDefaultAndPreservesTypedResults() {
        val defaultFixture = Step4TestFixtures.ncmBridge()
        try {
            assertSame(NcmSendResult.NotReady, defaultFixture.bridge.send(Step4TestFixtures.ethernetIpv6Tcp(), 100))
            assertEquals(1, defaultFixture.bridge.diagnosticSnapshot().writeNotReady)
            assertEquals(1, defaultFixture.connection.bulkTransferCallCount)
        } finally {
            defaultFixture.bridge.close()
        }

        val zeroResultFixture = Step4TestFixtures.ncmBridge()
        try {
            zeroResultFixture.connection.enqueueBulkTransferResult(
                zeroResultFixture.endpoints.bulkOut.endpoint,
                0,
            )

            assertSame(NcmSendResult.NotReady, zeroResultFixture.bridge.send(Step4TestFixtures.ethernetIpv6Tcp(), 100))
            assertEquals(1, zeroResultFixture.bridge.diagnosticSnapshot().writeNotReady)
            assertEquals(1, zeroResultFixture.connection.bulkTransferCallCount)
        } finally {
            zeroResultFixture.bridge.close()
        }

        val sentFixture = Step4TestFixtures.ncmBridge()
        try {
            val frame = Step4TestFixtures.ethernetIpv6Tcp()
            val expectedBlock = Ntb16Codec.build(frame, 0)
            sentFixture.connection.enqueueBulkTransferResult(sentFixture.endpoints.bulkOut.endpoint, expectedBlock.size)

            assertSame(NcmSendResult.Sent, sentFixture.bridge.send(frame, 100))
            assertArrayEquals(expectedBlock, sentFixture.connection.bulkTransferCalls.single().buffer)
            assertEquals(1, sentFixture.bridge.diagnosticSnapshot().writeSuccesses)
        } finally {
            sentFixture.bridge.close()
        }

        val partialFixture = Step4TestFixtures.ncmBridge()
        try {
            val frame = Step4TestFixtures.ethernetIpv6Tcp()
            val expectedBlock = Ntb16Codec.build(frame, 0)
            partialFixture.connection.enqueueBulkTransferResult(
                partialFixture.endpoints.bulkOut.endpoint,
                expectedBlock.size - 1,
            )

            assertTrue(partialFixture.bridge.send(frame, 100) is NcmSendResult.Failed)
            assertEquals(1, partialFixture.bridge.diagnosticSnapshot().writePartialFailures)
        } finally {
            partialFixture.bridge.close()
        }
    }

    @Test
    fun synchronousBulkInReassemblesSplitAndCoalescedNtbBlocks() {
        val fixture = Step4TestFixtures.ncmBridge(NcmDiagnosticProfile.SYNC_BULK_IN)
        val firstFrame = Step4TestFixtures.ethernetIpv6Tcp(sourcePort = 49_152)
        val secondFrame = Step4TestFixtures.ethernetIpv6Tcp(sourcePort = 49_153)
        val firstBlock = Ntb16Codec.build(firstFrame, 1)
        val secondBlock = Ntb16Codec.build(secondFrame, 2)
        try {
            fixture.connection.enqueueBulkTransferInput(
                fixture.endpoints.bulkIn.endpoint,
                firstBlock.copyOfRange(0, 11),
            )
            fixture.connection.enqueueBulkTransferInput(
                fixture.endpoints.bulkIn.endpoint,
                firstBlock.copyOfRange(11, firstBlock.size) + secondBlock,
            )

            assertArrayEquals(firstFrame, fixture.bridge.recv(100))
            assertArrayEquals(secondFrame, fixture.bridge.recv(100))
            assertEquals(2, fixture.bridge.diagnosticSnapshot().ethernetRxFrames)
        } finally {
            fixture.bridge.close()
        }
    }

    @Test
    fun paddedExactPacketBoundaryIsDeliveredAndMissingPadIsCharacterized() {
        val frame = ByteArray(484) { it.toByte() }
        val block = Ntb16Codec.build(frame, 7)
        val declaredBlockLength = (block[8].toInt() and 0xff) or ((block[9].toInt() and 0xff) shl 8)
        assertEquals(512, declaredBlockLength)
        assertEquals(513, block.size)

        val paddedFixture = Step4TestFixtures.ncmBridge(NcmDiagnosticProfile.SYNC_BULK_IN)
        try {
            paddedFixture.connection.enqueueBulkTransferInput(
                paddedFixture.endpoints.bulkIn.endpoint,
                block,
            )
            assertArrayEquals(frame, paddedFixture.bridge.recv(100))
        } finally {
            paddedFixture.bridge.close()
        }

        val unpaddedFixture = Step4TestFixtures.ncmBridge(NcmDiagnosticProfile.SYNC_BULK_IN)
        try {
            unpaddedFixture.connection.enqueueBulkTransferInput(
                unpaddedFixture.endpoints.bulkIn.endpoint,
                block.copyOf(declaredBlockLength),
            )

            assertNull(unpaddedFixture.bridge.recv(2))
            assertEquals(0, unpaddedFixture.bridge.diagnosticSnapshot().ethernetRxFrames)
        } finally {
            unpaddedFixture.bridge.close()
        }
    }

    @Test
    fun asyncRequestWaitTimeoutKeepsOne32KiBRequestUntilCompletion() {
        val fixture = Step4TestFixtures.ncmBridge()
        val frame = Step4TestFixtures.ethernetIpv6Tcp()
        val block = Ntb16Codec.build(frame, 3)
        try {
            fixture.connection.setRequestInitializationResult(true)
            fixture.connection.enqueueRequestQueueResult(true)
            fixture.connection.enqueueRequestWaitTimeout()
            fixture.connection.enqueueRequestWaitCompletion(block)

            assertArrayEquals(frame, fixture.bridge.recv(100))
            assertEquals(1, fixture.connection.requestInitializationCount)
            assertSame(fixture.endpoints.bulkIn.endpoint, fixture.connection.initializedRequestEndpoints.single())
            assertEquals(1, fixture.connection.requestQueueCallCount)
            assertEquals(listOf(32 * 1024), fixture.connection.queuedBufferCapacities)
            assertEquals(2, fixture.connection.requestWaitCallCount)
            assertEquals(1, fixture.bridge.diagnosticSnapshot().readTimeouts)
            assertEquals(1, fixture.bridge.diagnosticSnapshot().readCompletions)
        } finally {
            fixture.bridge.close()
        }
        assertEquals(0, fixture.connection.requestCancelCount)
        assertEquals(1, fixture.connection.requestCloseCount)
    }

    @Test
    fun syncAndAsyncBulkInProfilesDeliverTheSameFrame() {
        val frame = Step4TestFixtures.ethernetIpv6Tcp()
        val block = Ntb16Codec.build(frame, 5)
        val syncFixture = Step4TestFixtures.ncmBridge(NcmDiagnosticProfile.SYNC_BULK_IN)
        val asyncFixture = Step4TestFixtures.ncmBridge(NcmDiagnosticProfile.AUTO)
        try {
            syncFixture.connection.enqueueBulkTransferInput(syncFixture.endpoints.bulkIn.endpoint, block)
            asyncFixture.connection.setRequestInitializationResult(true)
            asyncFixture.connection.enqueueRequestQueueResult(true)
            asyncFixture.connection.enqueueRequestWaitCompletion(block)

            val syncFrame = syncFixture.bridge.recv(100)
            val asyncFrame = asyncFixture.bridge.recv(100)

            assertArrayEquals(frame, syncFrame)
            assertArrayEquals(syncFrame, asyncFrame)
        } finally {
            syncFixture.bridge.close()
            asyncFixture.bridge.close()
        }
    }

    @Test
    fun requestQueueFailureAndWaitErrorsFailClosed() {
        val queueFailure = Step4TestFixtures.ncmBridge()
        try {
            queueFailure.connection.setRequestInitializationResult(true)
            queueFailure.connection.enqueueRequestQueueResult(false)

            assertTransportFailure { queueFailure.bridge.recv(20) }
            assertEquals(1, queueFailure.bridge.diagnosticSnapshot().readQueueFailures)
            assertEquals(0, queueFailure.connection.requestWaitCallCount)
        } finally {
            queueFailure.bridge.close()
        }

        val nullResult = Step4TestFixtures.ncmBridge()
        try {
            nullResult.connection.setRequestInitializationResult(true)
            nullResult.connection.enqueueRequestQueueResult(true)
            nullResult.connection.enqueueRequestWaitNull()

            assertTransportFailure { nullResult.bridge.recv(20) }
            assertEquals(1, nullResult.bridge.diagnosticSnapshot().readNullErrors)
        } finally {
            nullResult.bridge.close()
        }
        assertEquals(1, nullResult.connection.requestCancelCount)
        assertEquals(1, nullResult.connection.requestCloseCount)

        val unexpected = Step4TestFixtures.ncmBridge()
        try {
            unexpected.connection.setRequestInitializationResult(true)
            unexpected.connection.enqueueRequestQueueResult(true)
            unexpected.connection.enqueueUnexpectedRequestWaitCompletion()

            assertTransportFailure { unexpected.bridge.recv(20) }
            assertEquals(1, unexpected.bridge.diagnosticSnapshot().readUnexpectedCompletions)
        } finally {
            unexpected.bridge.close()
        }
        assertEquals(1, unexpected.connection.requestCancelCount)
        assertEquals(1, unexpected.connection.requestCloseCount)
    }

    @Test
    fun closeWhileRequestWaitIsBlockedCancelsAndClosesTheOwnedRequest() {
        val fixture = Step4TestFixtures.ncmBridge()
        fixture.connection.setRequestInitializationResult(true)
        fixture.connection.enqueueRequestQueueResult(true)
        fixture.connection.enqueueRequestWaitBlockedUntilClose()
        val result = AtomicReference<ByteArray?>()
        val failure = AtomicReference<Throwable?>()
        val receiver = Thread {
            try {
                result.set(fixture.bridge.recv(5_000))
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        receiver.start()

        try {
            assertTrue(fixture.connection.awaitRequestWaitStarted(2_000))
            fixture.bridge.close()
            receiver.join(2_000)

            assertFalse(receiver.isAlive)
            assertNull(result.get())
            assertNull(failure.get())
            assertEquals(1, fixture.connection.requestCancelCount)
            assertEquals(1, fixture.connection.requestCloseCount)
            assertEquals(1, fixture.connection.connectionCloseCount)
        } finally {
            fixture.bridge.close()
            if (receiver.isAlive) {
                receiver.interrupt()
                receiver.join(2_000)
            }
        }
    }

    @Test
    fun syntheticWiredAttemptsKeepNetworkMilestonesSeparate() {
        val noSyn = Step4TestFixtures.startSessionNoSyn()
        assertEquals(listOf(Step4WiredMilestone.START_SESSION_SENT), noSyn.milestones)
        assertTrue(noSyn.receivedEthernetFrames.isEmpty())

        val ipv4Only = Step4TestFixtures.ipv4SynOnly()
        assertEquals(Step4WiredMilestone.IPV4_SYN_RECEIVED, ipv4Only.milestones.last())
        assertEquals(0x0800, ((ipv4Only.receivedEthernetFrames.single()[12].toInt() and 0xff) shl 8) or
            (ipv4Only.receivedEthernetFrames.single()[13].toInt() and 0xff))

        val ipv6NotReady = Step4TestFixtures.ipv6SynAckNotReady()
        assertEquals(
            listOf(
                Step4WiredMilestone.START_SESSION_SENT,
                Step4WiredMilestone.IPV6_SYN_RECEIVED,
                Step4WiredMilestone.TUN_WRITE_SUCCEEDED,
                Step4WiredMilestone.KERNEL_SYN_ACK_OBSERVED,
                Step4WiredMilestone.NCM_OUT_NOT_READY,
            ),
            ipv6NotReady.milestones,
        )
        assertEquals(listOf(NcmSendResult.NotReady), ipv6NotReady.ncmSendResults)
        assertEquals(60, ipv6NotReady.tunReplies.single().size)
        assertEquals(0x60, ipv6NotReady.tunReplies.single()[0].toInt() and 0xff)

        val rtspAltOnly = Step4TestFixtures.rtspAltWithoutMain()
        assertTrue(Step4WiredMilestone.RTSP_ACTIVE in rtspAltOnly.milestones)
        assertFalse(Step4WiredMilestone.MAIN_SETUP in rtspAltOnly.milestones)
        assertTrue(Step4WiredMilestone.ALT_SETUP in rtspAltOnly.milestones)

        val secondLoss = Step4TestFixtures.recoveredThenSecondLoss()
        assertEquals(Step4WiredMilestone.SESSION_ACTIVE, secondLoss.milestones[2])
        assertEquals(Step4WiredMilestone.SECOND_LOSS, secondLoss.milestones[3])
    }

    @Test
    fun simulatedTunAndMediaInputFixturesAreDeterministic() {
        Step4SimulatedTun().use { tun ->
            val inbound = Step4TestFixtures.ipv6TcpPacket()
            val outbound = Step4TestFixtures.ipv6TcpPacket(
                sourcePort = 7_000,
                destinationPort = 49_152,
                tcpFlags = 0x12,
            )
            tun.peerWrite(inbound)
            assertArrayEquals(inbound, tun.bridgeRead(inbound.size))
            tun.bridgeWrite(outbound)
            assertArrayEquals(outbound, tun.peerRead(outbound.size))
        }

        val pcm = Step4TestFixtures.pcm16(sampleRate = 48_000, channels = 2, frameCount = 4, sample = -1)
        assertEquals(16, pcm.pcm16Le.size)
        assertEquals(0xff.toByte(), pcm.pcm16Le[0])
        assertEquals(0xff.toByte(), pcm.pcm16Le[1])

        val sourcePayload = byteArrayOf(1, 2, 3)
        val video = Step4TestFixtures.videoFrameInput("video/avc", 16, 9, 1_000, sourcePayload)
        sourcePayload[0] = 9
        assertArrayEquals(byteArrayOf(1, 2, 3), video.encodedPayload)
    }

    @Test
    fun virtualSchedulerRunsDueTasksInOrderAndHonorsCancellation() {
        val clock = Step4VirtualClock()
        val scheduler = Step4ManualScheduler(clock)
        val events = mutableListOf<String>()
        scheduler.schedule(20) { events += "later" }
        val cancelled = scheduler.schedule(10) { events += "cancelled" }
        scheduler.schedule(10) { events += "first" }
        assertTrue(cancelled.cancel())
        assertFalse(cancelled.cancel())

        assertEquals(0, scheduler.advanceBy(9))
        assertEquals(1, scheduler.advanceBy(1))
        assertEquals(listOf("first"), events)
        assertEquals(1, scheduler.advanceBy(10))
        assertEquals(listOf("first", "later"), events)
        assertEquals(0, scheduler.pendingTaskCount())
    }

    private fun assertTransportFailure(action: () -> Unit) {
        try {
            action()
            throw AssertionError("transport failure was not reported")
        } catch (_: com.shilapi.xcertplay.transport.IphoneUsbException.DeviceUnavailable) {
        }
    }
}
