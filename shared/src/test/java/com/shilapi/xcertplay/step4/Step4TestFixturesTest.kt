package com.shilapi.xcertplay.step4

import android.hardware.usb.ScriptedUsbDeviceConnection
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbRequest
import com.shilapi.xcertplay.transport.EthernetIpv6Codec
import com.shilapi.xcertplay.transport.IphoneUsbException
import com.shilapi.xcertplay.transport.NcmFunctionDiscovery
import com.shilapi.xcertplay.transport.NcmSendResult
import com.shilapi.xcertplay.transport.Ntb16Codec
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeoutException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Step4TestFixturesTest {
    @Test
    fun usbBulkTransferCannotSucceedWithoutAnExplicitOutcome() {
        val connection = ScriptedUsbDeviceConnection()
        val endpoint = bulkInEndpoint()
        val request = UsbRequest()

        assertThrows(AssertionError::class.java) {
            connection.bulkTransfer(endpoint, ByteArray(64), 64, 10)
        }
        assertThrows(AssertionError::class.java) {
            connection.queueRequest(request, ByteBuffer.allocateDirect(32 * 1024))
        }
        assertThrows(AssertionError::class.java) {
            connection.requestWait(10)
        }
        connection.enqueueBulkOutcome(endpoint, ScriptedUsbDeviceConnection.BulkOutcome.result(1))
        assertThrows(AssertionError::class.java) {
            connection.bulkTransfer(endpoint, ByteArray(64), 64, 10)
        }
        connection.enqueueControlOutcome(ScriptedUsbDeviceConnection.BulkOutcome.result(1))
        assertThrows(AssertionError::class.java) {
            connection.controlTransfer(UsbConstants.USB_DIR_IN, 0, 0, 0, ByteArray(8), 8, 10)
        }
    }

    @Test
    fun cdcUnionDescriptorFixturePairsItsDeclaredInterfaces() {
        val rawDescriptors = Step4TestFixtures.cdcNcmDescriptors(controlId = 3, dataId = 4)
        val descriptors = NcmFunctionDiscovery.parseFunctionalDescriptors(
            rawDescriptors,
            configurationId = 4,
        )
        val candidate = NcmFunctionDiscovery.pairCandidates(
            interfaces = listOf(
                controlInterface(3),
                dataInterface(4),
            ),
            descriptors = descriptors,
        ).single()

        assertEquals(72, rawDescriptors.size)
        assertFalse(descriptors.malformed)
        assertTrue(candidate.hasUnion)
        assertTrue(candidate.eligible)
        assertEquals(3, candidate.controlId)
        assertEquals(4, candidate.dataId)
    }

    @Test
    fun ncmBridgeReassemblesSplitAndCoalescedNtbReads() {
        val fixture = Step4TestFixtures.ncmBridge()
        val first = Step4TestFixtures.ethernetFrame(seed = 1)
        val second = Step4TestFixtures.ethernetFrame(seed = 2)
        val coalesced = Step4TestFixtures.ntb(first, 1) + Step4TestFixtures.ntb(second, 2)
        fixture.connection.enqueueBulkOutcome(
            fixture.bulkIn,
            ScriptedUsbDeviceConnection.BulkOutcome.data(coalesced.copyOfRange(0, 5)),
        )
        fixture.connection.enqueueBulkOutcome(
            fixture.bulkIn,
            ScriptedUsbDeviceConnection.BulkOutcome.data(coalesced.copyOfRange(5, coalesced.size)),
        )
        fixture.connection.setBulkFallback(
            fixture.bulkIn,
            ScriptedUsbDeviceConnection.BulkOutcome.noData(1),
        )

        try {
            assertArrayEquals(first, fixture.bridge.recv(100))
            assertArrayEquals(second, fixture.bridge.recv(100))
            assertEquals(2, fixture.bridge.diagnosticSnapshot().ethernetRxFrames)
        } finally {
            fixture.bridge.close()
        }
    }

    @Test
    fun mainBaselineBlocksOnA512ByteNtbWithoutTheOptionalPad() {
        val fixture = Step4TestFixtures.ncmBridge()
        val frame = Step4TestFixtures.ethernetFrame(size = 484)
        val paddedBlock = Step4TestFixtures.ntb(frame)
        assertEquals(513, paddedBlock.size)
        val unpaddedBlock = paddedBlock.copyOf(512)
        fixture.connection.enqueueBulkOutcome(
            fixture.bulkIn,
            ScriptedUsbDeviceConnection.BulkOutcome.data(unpaddedBlock),
        )
        fixture.connection.setBulkFallback(
            fixture.bulkIn,
            ScriptedUsbDeviceConnection.BulkOutcome.noData(1),
        )

        try {
            assertNull(fixture.bridge.recv(10))
            assertEquals(512L, fixture.bridge.diagnosticSnapshot().readBytes)
            assertEquals(0L, fixture.bridge.diagnosticSnapshot().ethernetRxFrames)
        } finally {
            fixture.bridge.close()
        }
    }

    @Test
    fun mainBaselineAcceptsA512ByteNtbWithTheOptionalPad() {
        val fixture = Step4TestFixtures.ncmBridge()
        val frame = Step4TestFixtures.ethernetFrame(size = 484)
        val paddedBlock = Step4TestFixtures.ntb(frame)
        assertEquals(513, paddedBlock.size)
        fixture.connection.enqueueBulkOutcome(
            fixture.bulkIn,
            ScriptedUsbDeviceConnection.BulkOutcome.data(paddedBlock),
        )

        try {
            assertArrayEquals(frame, fixture.bridge.recv(100))
            assertEquals(1L, fixture.bridge.diagnosticSnapshot().ethernetRxFrames)
        } finally {
            fixture.bridge.close()
        }
    }

    @Test
    fun mainBaselineConsumesOnlyTheOptionalPadBeforeTheFollowingNtb() {
        val fixture = Step4TestFixtures.ncmBridge()
        val first = Step4TestFixtures.ethernetFrame(size = 484, seed = 1)
        val second = Step4TestFixtures.ethernetFrame(seed = 2)
        val firstNtbWithPad = Step4TestFixtures.ntb(first)
        val secondNtb = Step4TestFixtures.ntb(second, 2)
        fixture.connection.enqueueBulkOutcome(
            fixture.bulkIn,
            ScriptedUsbDeviceConnection.BulkOutcome.data(firstNtbWithPad + secondNtb),
        )

        try {
            assertArrayEquals(first, fixture.bridge.recv(100))
            assertArrayEquals(second, fixture.bridge.recv(100))
        } finally {
            fixture.bridge.close()
        }
    }

    @Test
    fun mainBaselineConsumesTheNextNtbHeaderAsARequiredPad() {
        val fixture = Step4TestFixtures.ncmBridge()
        val first = Step4TestFixtures.ntb(Step4TestFixtures.ethernetFrame(size = 484), 1).copyOf(512)
        val second = Step4TestFixtures.ntb(Step4TestFixtures.ethernetFrame(seed = 2), 2)
        fixture.connection.enqueueBulkOutcome(
            fixture.bulkIn,
            ScriptedUsbDeviceConnection.BulkOutcome.data(first + second),
        )

        try {
            assertThrows(IphoneUsbException.DeviceUnavailable::class.java) {
                fixture.bridge.recv(100)
            }
        } finally {
            fixture.bridge.close()
        }
    }

    @Test
    fun ncmSendCapturesTheExactNtbAndSeparatesFullPartialAndNotReadyOutcomes() {
        val frame = Step4TestFixtures.ethernetFrame(seed = 11)
        val sentFixture = Step4TestFixtures.ncmBridge()
        sentFixture.connection.enqueueBulkOutcome(
            sentFixture.bulkOut,
            ScriptedUsbDeviceConnection.BulkOutcome.result(Ntb16Codec.build(frame, 0).size),
        )
        try {
            assertEquals(NcmSendResult.Sent, sentFixture.bridge.send(frame, 100))
            val outgoing = sentFixture.connection.transferRecords().single().bytes
            assertArrayEquals(frame, Ntb16Codec.parse(outgoing).single())
        } finally {
            sentFixture.bridge.close()
        }

        val partialFixture = Step4TestFixtures.ncmBridge()
        partialFixture.connection.enqueueBulkOutcome(
            partialFixture.bulkOut,
            ScriptedUsbDeviceConnection.BulkOutcome.result(Ntb16Codec.build(frame, 0).size - 1),
        )
        try {
            assertTrue(partialFixture.bridge.send(frame, 100) is NcmSendResult.Failed)
            assertEquals(1L, partialFixture.bridge.diagnosticSnapshot().writePartialFailures)
        } finally {
            partialFixture.bridge.close()
        }

        val notReadyFixture = Step4TestFixtures.ncmBridge()
        notReadyFixture.connection.enqueueBulkOutcome(
            notReadyFixture.bulkOut,
            ScriptedUsbDeviceConnection.BulkOutcome.result(-1),
        )
        try {
            assertEquals(NcmSendResult.NotReady, notReadyFixture.bridge.send(frame, 100))
            assertEquals(1L, notReadyFixture.bridge.diagnosticSnapshot().writeNotReady)
        } finally {
            notReadyFixture.bridge.close()
        }
    }

    @Test
    fun requestWaitTimeoutAndCompletionUseTheSameExplicitlyQueuedRequest() {
        val connection = ScriptedUsbDeviceConnection()
        val endpoint = bulkInEndpoint()
        val request = UsbRequest()
        val buffer = ByteBuffer.allocateDirect(32 * 1024)
        val bytes = Step4TestFixtures.ntb(Step4TestFixtures.ethernetFrame(seed = 5))
        assertTrue(connection.initializeRequest(request, endpoint))
        connection.enqueueQueueOutcome(true)
        assertTrue(connection.queueRequest(request, buffer))

        connection.enqueueWaitOutcome(ScriptedUsbDeviceConnection.WaitOutcome.timeout())
        assertThrows(TimeoutException::class.java) { connection.requestWait(1) }
        assertSame(request, connection.queuedRequest())
        assertSame(buffer, connection.queuedBuffer())

        connection.enqueueWaitOutcome(ScriptedUsbDeviceConnection.WaitOutcome.completion(bytes))
        assertSame(request, connection.requestWait(100))
        assertEquals(1, connection.queueAttempts())
        assertEquals(bytes.size, buffer.position())
    }

    @Test
    fun explicitQueueFailureRecordsTheAttemptWithoutInventingACompletion() {
        val connection = ScriptedUsbDeviceConnection()
        val request = UsbRequest()
        val buffer = ByteBuffer.allocateDirect(32 * 1024)
        connection.enqueueQueueOutcome(false)

        assertFalse(connection.queueRequest(request, buffer))
        assertEquals(1, connection.queueAttempts())
        assertEquals(32 * 1024, connection.lastQueueCapacity())
        assertNull(connection.queuedRequest())
    }

    @Test
    fun nullAndUnexpectedRequestWaitResultsAreExplicitFailures() {
        val connection = ScriptedUsbDeviceConnection()
        val request = UsbRequest()
        val unexpectedRequest = UsbRequest()
        connection.enqueueQueueOutcome(true)
        assertTrue(connection.queueRequest(request, ByteBuffer.allocateDirect(32 * 1024)))

        connection.enqueueWaitOutcome(ScriptedUsbDeviceConnection.WaitOutcome.nullResult())
        assertNull(connection.requestWait(1))
        connection.enqueueWaitOutcome(ScriptedUsbDeviceConnection.WaitOutcome.unexpected(unexpectedRequest))
        assertSame(unexpectedRequest, connection.requestWait(1))
    }

    @Test
    fun blockingRequestWaitIsReleasedByConnectionClose() {
        val connection = ScriptedUsbDeviceConnection()
        val request = UsbRequest()
        connection.enqueueQueueOutcome(true)
        assertTrue(connection.queueRequest(request, ByteBuffer.allocateDirect(32 * 1024)))
        connection.enqueueWaitOutcome(ScriptedUsbDeviceConnection.WaitOutcome.blockUntilClose())
        val result = AtomicReference<UsbRequest?>()
        val failure = AtomicReference<Throwable?>()
        val waiter = Thread {
            try {
                result.set(connection.requestWait(5_000))
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        waiter.start()

        assertTrue(connection.awaitWaitStarted(1_000))
        connection.cancelRequest(request)
        connection.close()
        waiter.join(1_000)

        assertFalse(waiter.isAlive)
        assertNull(failure.get())
        assertNull(result.get())
        assertEquals(1, connection.cancelCalls())
        assertEquals(1, connection.connectionCloseCalls())
    }

    @Test
    fun remainingForkFixturesPreserveTheirInputsAndSeparateMainFromAlt() {
        val recorder = WiredStartupEventRecorder(attempt = 7, startedAtNanos = 100)
        recorder.record("MAIN_SETUP", timestampNanos = 200, streamSlot = WiredStreamSlot.MAIN)
        recorder.record("ALT_SETUP", timestampNanos = 300, streamSlot = WiredStreamSlot.ALT)
        assertEquals(listOf(WiredStreamSlot.MAIN, WiredStreamSlot.ALT), recorder.snapshot().map { it.streamSlot })
        assertEquals(listOf(100L, 200L), recorder.snapshot().map { it.elapsedNanos })

        val scheduler = VirtualClockScheduler()
        val fired = mutableListOf<String>()
        scheduler.schedule(10) { fired += "later" }
        val cancel = scheduler.schedule(5) { fired += "cancelled" }
        cancel()
        scheduler.schedule(5) { fired += "first" }
        scheduler.advanceBy(4)
        assertTrue(fired.isEmpty())
        scheduler.advanceBy(1)
        assertEquals(listOf("first"), fired)
        scheduler.advanceBy(5)
        assertEquals(listOf("first", "later"), fired)

        val microphone = Step4TestFixtures.MicrophoneRequest(
            audioType = 100,
            input = false,
            dataPort = 49_152,
            sampleRate = 48_000,
            channels = 1,
        )
        assertFalse(microphone.input!!)
        assertEquals(49_152, microphone.dataPort)

        val split = Step4TestFixtures.splitGeometry(width = 1_000, height = 600)
        assertEquals(550, split.carPlayWidth)
        assertEquals(450, split.youtubeWidth)
        assertEquals(600, split.displayHeight)

        val pcm = Step4TestFixtures.pcmBlock(frameCount = 128, channels = 2)
        assertEquals(256, pcm.samples.size)
        assertTrue(pcm.samples.all(Float::isFinite))

        val video = Step4TestFixtures.videoFrame(width = 640, height = 480, byteCount = 64)
        assertEquals("video/avc", video.mimeType)
        assertEquals(640, video.width)
        assertEquals(480, video.height)
        assertEquals(64, video.bytes.size)
    }

    @Test
    fun ipv4AndIpv6TcpFixturesKeepTheirFamiliesAndFlagsDistinct() {
        val ipv4Syn = Step4TestFixtures.ipv4TcpFrame(flags = 0x02)
        val ipv6Syn = Step4TestFixtures.ipv6TcpFrame(flags = 0x02)
        val ipv6SynAck = Step4TestFixtures.ipv6TcpFrame(flags = 0x12)
        assertEquals(0x0800, ((ipv4Syn[12].toInt() and 0xff) shl 8) or (ipv4Syn[13].toInt() and 0xff))
        assertEquals(0x86dd, ((ipv6Syn[12].toInt() and 0xff) shl 8) or (ipv6Syn[13].toInt() and 0xff))
        val ipv6 = EthernetIpv6Codec.parseIpv6View(ipv6Syn)!!
        val synAck = EthernetIpv6Codec.parseIpv6View(ipv6SynAck)!!
        assertEquals(14, ipv6.payloadOffset)
        assertEquals(60, ipv6.payloadLength)
        assertEquals("TCP", EthernetIpv6Codec.packetType(ipv6Syn, ipv6.payloadOffset, ipv6.payloadLength))
        assertEquals(0x02, ipv6Syn[67].toInt() and 0xff)
        assertEquals(0x12, ipv6SynAck[67].toInt() and 0xff)
        assertEquals(60, synAck.payloadLength)
    }

    @Test
    fun simulatedTunFixturesRequireExplicitReadAndWriteOutcomes() {
        val input = ScriptedTunInputStream()
        val output = ScriptedTunOutputStream()
        val target = ByteArray(3)
        val packet = byteArrayOf(1, 2, 3, 4)
        input.enqueue(TunReadOutcome.Bytes(packet))
        assertEquals(3, input.read(target))
        assertArrayEquals(byteArrayOf(1, 2, 3), target)
        assertEquals(1, input.read(target))
        assertEquals(4, target[0].toInt())
        input.enqueue(TunReadOutcome.ZeroBytes)
        assertEquals(0, input.read(target))
        input.enqueue(TunReadOutcome.EndOfStream)
        assertEquals(-1, input.read(target))
        input.enqueue(TunReadOutcome.Failure(IOException("scripted TUN read failure")))
        assertThrows(IOException::class.java) { input.read(target) }

        val reply = byteArrayOf(5, 6, 7)
        output.enqueue(TunWriteOutcome.Accept)
        output.write(reply)
        assertArrayEquals(reply, output.writes().single())
        output.enqueue(TunWriteOutcome.Failure(IOException("scripted TUN write failure")))
        assertThrows(IOException::class.java) { output.write(reply) }
    }

    private fun bulkInEndpoint() = ScriptedUsbDeviceConnection.Endpoint(
        0x81,
        UsbConstants.USB_ENDPOINT_XFER_BULK,
        512,
        0,
    )

    private fun controlInterface(id: Int) = NcmFunctionDiscovery.InterfaceRecord(
        id = id,
        alternateSetting = 0,
        interfaceClass = 2,
        interfaceSubclass = 0x0d,
        interfaceProtocol = 0,
        hasStatusIn = true,
        hasBulkIn = false,
        hasBulkOut = false,
    )

    private fun dataInterface(id: Int) = NcmFunctionDiscovery.InterfaceRecord(
        id = id,
        alternateSetting = 1,
        interfaceClass = 0x0a,
        interfaceSubclass = 0,
        interfaceProtocol = 0,
        hasStatusIn = false,
        hasBulkIn = true,
        hasBulkOut = true,
    )
}
