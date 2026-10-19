package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbRequest
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.TimeoutException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers
import com.shilapi.xcertplay.step4.Step4TestFixtures
import com.shilapi.xcertplay.step4.UsbRequestWaitFixture

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [28],
    manifest = Config.NONE,
    shadows = [ScriptedUsbEndpointShadow::class, ScriptedUsbDeviceConnectionShadow::class, ScriptedUsbRequestShadow::class],
)
class NcmUsbBridgeFixtureTest {
    @Test
    fun scriptedUsbRequestReadsSplitAndCoalescedNtb16Blocks() {
        val firstFrame = Step4TestFixtures.ethernetFrame(length = 60, seed = 0x30)
        val secondFrame = Step4TestFixtures.ethernetFrame(length = 72, seed = 0x50)
        val firstBlock = Ntb16Codec.build(firstFrame, sequence = 7)
        val secondBlock = Ntb16Codec.build(secondFrame, sequence = 8)
        val (bridge, script) = newBridge()
        script.enqueueRead(UsbRequestWaitFixture.Completed(Step4TestFixtures.split(firstBlock, 3, 19).first()))
        script.enqueueRead(UsbRequestWaitFixture.Completed(Step4TestFixtures.split(firstBlock, 3, 19)[1]))
        script.enqueueRead(UsbRequestWaitFixture.Completed(Step4TestFixtures.coalesced(
            Step4TestFixtures.split(firstBlock, 3, 19)[2],
            secondBlock,
        )))

        bridge.use {
            assertArrayEquals(firstFrame, it.recv(100))
            assertArrayEquals(secondFrame, it.recv(100))
            val snapshot = it.diagnosticSnapshot()
            assertEquals(3L, snapshot.readCompletions)
            assertEquals(3L, snapshot.readQueues)
        }

        assertTrue(script.connectionClosed)
        assertEquals(1, script.requestCloseCount)
    }

    @Test
    fun paddedExactPacketBoundaryBlockIsDelivered() {
        val frame = Step4TestFixtures.ethernetFrame(
            length = Step4TestFixtures.USB_PACKET_SIZE - 28,
        )
        val block = Step4TestFixtures.padded512ByteNtb()
        assertArrayEquals(frame, Ntb16Codec.parse(block, 0, Step4TestFixtures.USB_PACKET_SIZE).single())
        val (bridge, script) = newBridge()
        script.enqueueRead(UsbRequestWaitFixture.Completed(block))

        bridge.use {
            assertArrayEquals(frame, it.recv(100))
        }
    }

    @Test
    fun missingExactPacketBoundaryPadIsCharacterizedForPr01() {
        val frame = Step4TestFixtures.ethernetFrame(
            length = Step4TestFixtures.USB_PACKET_SIZE - 28,
        )
        val unpaddedBlock = Step4TestFixtures.unpadded512ByteNtb()
        assertArrayEquals(frame, Ntb16Codec.parse(unpaddedBlock).single())
        val (bridge, script) = newBridge()
        script.enqueueRead(UsbRequestWaitFixture.Completed(unpaddedBlock))

        bridge.use {
            assertNull(it.recv(3))
            val snapshot = it.diagnosticSnapshot()
            assertEquals(1L, snapshot.readCompletions)
            assertTrue(snapshot.readTimeouts > 0L)
        }
    }

    @Test
    fun timeoutKeepsThePersistentRequestQueuedForItsNextCompletion() {
        val frame = Step4TestFixtures.ethernetFrame(length = 60)
        val block = Ntb16Codec.build(frame, sequence = 11)
        val (bridge, script) = newBridge()
        script.enqueueRead(UsbRequestWaitFixture.Timeout)
        script.enqueueRead(UsbRequestWaitFixture.Completed(block))

        bridge.use {
            assertNull(it.recv(2))
            val timedOut = it.diagnosticSnapshot()
            assertEquals(PersistentUsbReadRequestState.State.QUEUED, timedOut.readState)
            assertEquals(1L, timedOut.readQueues)
            assertArrayEquals(frame, it.recv(100))
            assertEquals(1L, it.diagnosticSnapshot().readQueues)
        }
    }

    @Test
    fun queueFalseNullAndUnexpectedCompletionRemainDistinctFixtureOutcomes() {
        val queueFailed = newBridge()
        queueFailed.second.enqueueQueueResult(false)
        val queueError = runCatching { queueFailed.first.recv(2) }.exceptionOrNull()
        assertTrue(queueError is IphoneUsbException.DeviceUnavailable)
        assertEquals(1L, queueFailed.first.diagnosticSnapshot().readQueueFailures)
        queueFailed.first.close()

        val nullWait = newBridge()
        nullWait.second.enqueueRead(UsbRequestWaitFixture.NullResult)
        val nullError = runCatching { nullWait.first.recv(2) }.exceptionOrNull()
        assertTrue(nullError is IphoneUsbException.DeviceUnavailable)
        assertEquals(1L, nullWait.first.diagnosticSnapshot().readNullErrors)
        nullWait.first.close()

        val unexpected = newBridge()
        unexpected.second.enqueueRead(UsbRequestWaitFixture.UnexpectedCompletion)
        val unexpectedError = runCatching { unexpected.first.recv(2) }.exceptionOrNull()
        assertTrue(unexpectedError is IphoneUsbException.DeviceUnavailable)
        assertEquals(1L, unexpected.first.diagnosticSnapshot().readUnexpectedCompletions)
        unexpected.first.close()
    }

    @Test
    fun fakeCapturesTheExistingThirtyTwoKiBReadQueueContract() {
        val (bridge, script) = newBridge()
        script.enqueueQueueResult(false)

        bridge.use {
            val error = runCatching { it.recv(2) }.exceptionOrNull()
            assertTrue(error is IphoneUsbException.DeviceUnavailable)
            assertEquals(32_768, script.lastQueuedBufferCapacity)
            assertEquals(1L, it.diagnosticSnapshot().readQueueFailures)
        }
    }

    @Test
    fun fakeBulkOutDistinguishesNotReadySentAndPartialFailure() {
        val frame = Step4TestFixtures.ethernetFrame(length = 60)
        val (bridge, script) = newBridge()
        script.enqueueBulkOutResult(0)

        bridge.use {
            assertEquals(NcmSendResult.NotReady, it.send(frame, timeoutMillis = 100))
            assertEquals(NcmSendResult.Sent, it.send(frame, timeoutMillis = 100))
            assertEquals(1L, it.diagnosticSnapshot().writeNotReady)
            assertEquals(1L, it.diagnosticSnapshot().writeSuccesses)
            assertArrayEquals(frame, Ntb16Codec.parse(script.outgoingBlocks.single()).single())
        }

        val partialWrite = newBridge()
        partialWrite.second.enqueueBulkOutResult(1)
        val result = partialWrite.first.send(frame, timeoutMillis = 100)
        assertTrue(result is NcmSendResult.Failed)
        assertEquals(1L, partialWrite.first.diagnosticSnapshot().writePartialFailures)
        partialWrite.first.close()
    }

    @Test
    fun wiredPathDiagnosticsDoNotChangeBulkOutResults() {
        val frame = Step4TestFixtures.ethernetFrame(length = 60)
        val disabled = WiredPathDiagnostics("disabled", 1, enabled = false)
        val enabled = WiredPathDiagnostics("run-fixture", 1, enabled = true)
        val (withoutDiagnostics, disabledScript) = newBridge(disabled)
        val (withDiagnostics, enabledScript) = newBridge(enabled)
        disabledScript.enqueueBulkOutResult(0)
        enabledScript.enqueueBulkOutResult(0)

        val disabledResults = listOf(
            withoutDiagnostics.send(frame, timeoutMillis = 100),
            withoutDiagnostics.send(frame, timeoutMillis = 100),
        )
        val enabledResults = listOf(
            withDiagnostics.send(frame, timeoutMillis = 100),
            withDiagnostics.send(frame, timeoutMillis = 100),
        )
        withoutDiagnostics.close()
        withDiagnostics.close()

        assertEquals(disabledResults, enabledResults)
        assertEquals(1, disabledScript.outgoingBlocks.size)
        assertEquals(1, enabledScript.outgoingBlocks.size)
        assertEquals(0L, disabled.snapshot(WiredPathReportReason.FINAL)
            .counter(WiredPathEvent.FIRST_BULK_OUT_FULL_SENT).count)
        assertEquals(1L, enabled.snapshot(WiredPathReportReason.FINAL)
            .counter(WiredPathEvent.FIRST_BULK_OUT_FULL_SENT).count)
    }

    private fun newBridge(
        wiredPathDiagnostics: WiredPathDiagnostics? = null,
    ): Pair<NcmUsbBridge, ScriptedUsbDeviceConnectionShadow> {
        val connection = ReflectionHelpers.callConstructor(UsbDeviceConnection::class.java)
        val script = Shadow.extract<ScriptedUsbDeviceConnectionShadow>(connection)
        val input = endpoint(address = 0x81, maxPacketSize = 512, direction = 0x80)
        val output = endpoint(address = 0x02, maxPacketSize = 512, direction = 0x00)
        val bridge = NcmUsbBridge(
            connection = connection,
            outEndpoint = output,
            inEndpoint = input,
            statusEndpoint = null,
            descriptorHostMac = null,
            selectionDiagnostics = "fixture",
            onDiagnosticEvent = {},
            diagnosticProfile = NcmDiagnosticProfile.AUTO,
            diagnosticAttempt = 1,
            diagnosticsEnabled = false,
            onDiagnosticLog = {},
            wiredPathDiagnostics = wiredPathDiagnostics,
        )
        return bridge to script
    }

    private fun endpoint(address: Int, maxPacketSize: Int, direction: Int): UsbEndpoint =
        ReflectionHelpers.callConstructor(UsbEndpoint::class.java).also { endpoint ->
            Shadow.extract<ScriptedUsbEndpointShadow>(endpoint).apply {
                endpointAddress = address
                endpointMaxPacketSize = maxPacketSize
                endpointDirection = direction
            }
        }
}

@Implements(UsbEndpoint::class)
internal class ScriptedUsbEndpointShadow {
    var endpointAddress: Int = 0
    var endpointMaxPacketSize: Int = 0
    var endpointDirection: Int = 0

    @Implementation
    protected fun getAddress(): Int = endpointAddress

    @Implementation
    protected fun getMaxPacketSize(): Int = endpointMaxPacketSize

    @Implementation
    protected fun getDirection(): Int = endpointDirection
}

@Implements(UsbDeviceConnection::class)
internal class ScriptedUsbDeviceConnectionShadow {
    private val waitOutcomes = ArrayDeque<UsbRequestWaitFixture>()
    private val queueResults = ArrayDeque<Boolean>()
    private val bulkOutResults = ArrayDeque<Int>()
    private var queuedRequest: UsbRequest? = null
    private var queuedBuffer: ByteBuffer? = null
    var connectionClosed: Boolean = false
        private set
    var requestCloseCount: Int = 0
        private set
    var lastQueuedBufferCapacity: Int = 0
        private set
    val outgoingBlocks = ArrayList<ByteArray>()

    fun enqueueRead(outcome: UsbRequestWaitFixture) {
        waitOutcomes.addLast(outcome)
    }

    fun enqueueQueueResult(result: Boolean) {
        queueResults.addLast(result)
    }

    fun enqueueBulkOutResult(result: Int) {
        bulkOutResults.addLast(result)
    }

    internal fun queueRequest(request: UsbRequest, buffer: ByteBuffer): Boolean {
        lastQueuedBufferCapacity = buffer.capacity()
        val result = if (queueResults.isEmpty()) true else queueResults.removeFirst()
        if (result) {
            queuedRequest = request
            queuedBuffer = buffer
        }
        return result
    }

    internal fun deliverRead(bytes: ByteArray) {
        val buffer = requireNotNull(queuedBuffer) { "No queued USB read buffer" }
        buffer.put(bytes)
    }

    internal fun recordRequestClose() {
        requestCloseCount++
    }

    @Implementation
    protected fun bulkTransfer(endpoint: UsbEndpoint, buffer: ByteArray, length: Int, timeoutMillis: Int): Int {
        val result = if (bulkOutResults.isEmpty()) length else bulkOutResults.removeFirst()
        if (endpoint.direction == 0x00 && result > 0) {
            outgoingBlocks += buffer.copyOf(minOf(length, result))
        }
        return result
    }

    @Implementation
    protected fun requestWait(timeoutMillis: Long): UsbRequest? {
        val outcome = if (waitOutcomes.isEmpty()) UsbRequestWaitFixture.Timeout else waitOutcomes.removeFirst()
        return when (outcome) {
            is UsbRequestWaitFixture.Completed -> {
                deliverRead(outcome.bytes)
                queuedRequest
            }
            UsbRequestWaitFixture.Timeout -> {
                Thread.sleep(timeoutMillis.coerceAtLeast(1L).coerceAtMost(2L))
                throw TimeoutException("fixture timeout")
            }
            UsbRequestWaitFixture.NullResult -> null
            UsbRequestWaitFixture.UnexpectedCompletion -> ReflectionHelpers.callConstructor(UsbRequest::class.java)
        }
    }

    @Implementation
    protected fun close() {
        connectionClosed = true
    }
}

@Implements(UsbRequest::class)
internal class ScriptedUsbRequestShadow {
    @field:RealObject
    lateinit var request: UsbRequest

    private var initializedConnection: UsbDeviceConnection? = null

    @Implementation
    protected fun initialize(connection: UsbDeviceConnection, endpoint: UsbEndpoint): Boolean {
        initializedConnection = connection
        return true
    }

    @Implementation
    protected fun queue(buffer: ByteBuffer): Boolean =
        initializedConnection?.let { Shadow.extract<ScriptedUsbDeviceConnectionShadow>(it).queueRequest(request, buffer) }
            ?: false

    @Implementation
    protected fun cancel(): Boolean = true

    @Implementation
    protected fun close() {
        initializedConnection?.let { Shadow.extract<ScriptedUsbDeviceConnectionShadow>(it).recordRequestClose() }
    }
}
