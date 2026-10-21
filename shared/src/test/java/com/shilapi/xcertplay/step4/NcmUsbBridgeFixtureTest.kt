package com.shilapi.xcertplay.step4

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbRequest
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import com.shilapi.xcertplay.transport.IphoneUsbException
import com.shilapi.xcertplay.transport.NcmDiagnosticEvent
import com.shilapi.xcertplay.transport.NcmDiagnosticProfile
import com.shilapi.xcertplay.transport.NcmSendResult
import com.shilapi.xcertplay.transport.NcmUsbBridge
import com.shilapi.xcertplay.transport.Ntb16Codec

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [28],
    manifest = Config.NONE,
    shadows = [Step4UsbDeviceConnectionShadow::class, Step4UsbEndpointShadow::class, Step4UsbRequestShadow::class],
)
class NcmUsbBridgeFixtureTest {
    @Test
    fun requestWaitTimeoutKeepsTheSameConfiguredRequestForTheNextCompletion() {
        val frame = Step4TestFixtures.ethernetFrame(seed = 0x30)
        val block = Ntb16Codec.build(frame, sequence = 7)
        val events = mutableListOf<NcmDiagnosticEvent>()
        val (bridge, script) = newBridge(
            queueOutcomes = listOf(UsbQueueOutcome.Queued),
            waitOutcomes = listOf(RequestWaitOutcome.Timeout, RequestWaitOutcome.Complete(block)),
            onDiagnosticEvent = { events += it },
        )

        bridge.use {
            assertArrayEquals(frame, it.recv(timeoutMillis = 1_000) ?: error("scripted USB read returned no frame"))
            assertEquals(1, script.queueCalls)
            assertEquals(listOf(32_768), script.queuedBufferCapacities)
            assertEquals(2, script.waitCalls)
            assertEquals(1L, it.diagnosticSnapshot().readQueues)
            assertEquals(1L, it.diagnosticSnapshot().readTimeouts)
            assertEquals(1L, it.diagnosticSnapshot().readCompletions)
        }

        assertEquals(
            listOf(
                NcmDiagnosticEvent.READ_QUEUED,
                NcmDiagnosticEvent.FIRST_USB_COMPLETION,
                NcmDiagnosticEvent.FIRST_ETHERNET_RX,
            ),
            events,
        )
        assertTrue(script.connectionClosed)
        assertEquals(1, script.requestCloseCount)
    }

    @Test
    fun queueRejectionIsExplicitAndDoesNotPretendAnInboundPacketArrived() {
        val (bridge, script) = newBridge(queueOutcomes = listOf(UsbQueueOutcome.Rejected))
        val error = runCatching { bridge.recv(timeoutMillis = 10) }.exceptionOrNull()

        bridge.close()

        assertTrue(error is IphoneUsbException.DeviceUnavailable)
        assertEquals(1, script.queueCalls)
        assertEquals(listOf(32_768), script.queuedBufferCapacities)
        assertEquals(0, script.waitCalls)
        assertEquals(1L, bridge.diagnosticSnapshot().readQueueFailures)
    }

    @Test
    fun unconfiguredUsbOutcomesCannotSilentlyBecomeSuccessfulTraffic() {
        val (bridge, script) = newBridge()
        val error = runCatching { bridge.recv(timeoutMillis = 10) }.exceptionOrNull()

        bridge.close()

        assertTrue(error is IphoneUsbException.DeviceUnavailable)
        assertEquals(0, script.queueCalls)
        assertEquals(0, script.waitCalls)
    }

    @Test
    fun nullAndUnexpectedWaitResultsRemainDistinctConfiguredFailures() {
        val nullWait = newBridge(
            queueOutcomes = listOf(UsbQueueOutcome.Queued),
            waitOutcomes = listOf(RequestWaitOutcome.NullResult),
        )
        val nullError = runCatching { nullWait.first.recv(timeoutMillis = 10) }.exceptionOrNull()
        val nullSnapshot = nullWait.first.diagnosticSnapshot()
        nullWait.first.close()

        val unexpectedWait = newBridge(
            queueOutcomes = listOf(UsbQueueOutcome.Queued),
            waitOutcomes = listOf(RequestWaitOutcome.UnexpectedCompletion),
        )
        val unexpectedError = runCatching { unexpectedWait.first.recv(timeoutMillis = 10) }.exceptionOrNull()
        val unexpectedSnapshot = unexpectedWait.first.diagnosticSnapshot()
        unexpectedWait.first.close()

        assertTrue(nullError is IphoneUsbException.DeviceUnavailable)
        assertEquals(1L, nullSnapshot.readNullErrors)
        assertEquals(0L, nullSnapshot.readUnexpectedCompletions)
        assertTrue(unexpectedError is IphoneUsbException.DeviceUnavailable)
        assertEquals(0L, unexpectedSnapshot.readNullErrors)
        assertEquals(1L, unexpectedSnapshot.readUnexpectedCompletions)
    }

    @Test
    fun closeDuringQueuedRequestWaitCancelsAndClosesTheSingleRequest() {
        val (bridge, script) = newBridge(
            queueOutcomes = listOf(UsbQueueOutcome.Queued),
            waitOutcomes = listOf(RequestWaitOutcome.BlockUntilConnectionClose),
        )
        val readResult = AtomicReference<ByteArray?>(null)
        val readError = AtomicReference<Throwable?>(null)
        val reader = Thread {
            try {
                readResult.set(bridge.recv(timeoutMillis = 60_000))
            } catch (error: Throwable) {
                readError.set(error)
            }
        }.apply { start() }

        try {
            assertTrue(script.awaitRequestWait(2, TimeUnit.SECONDS))
            val closeError = AtomicReference<Throwable?>(null)
            val closeDone = CountDownLatch(1)
            Thread {
                try {
                    bridge.close()
                } catch (error: Throwable) {
                    closeError.set(error)
                } finally {
                    closeDone.countDown()
                }
            }.start()

            assertTrue(closeDone.await(2, TimeUnit.SECONDS))
            reader.join(2_000)
            assertFalse(reader.isAlive)
            assertNull(readError.get())
            assertNull(closeError.get())
            assertNull(readResult.get())
            assertEquals(1, script.queueCalls)
            assertEquals(1, script.waitCalls)
            assertEquals(1, script.requestCancelCount)
            assertEquals(1, script.requestCloseCount)
        } finally {
            bridge.close()
            reader.join(2_000)
        }
    }

    @Test
    fun configuredBulkOutResultsPreserveNotReadyAndFullTransferOutcomes() {
        val frame = Step4TestFixtures.ipv6TcpSynAck()
        val (bridge, script) = newBridge(
            bulkOutcomes = listOf(BulkTransferOutcome.NotReady, BulkTransferOutcome.FullTransfer),
        )

        bridge.use {
            assertEquals(NcmSendResult.NotReady, it.send(frame, timeoutMillis = 100))
            assertEquals(NcmSendResult.Sent, it.send(frame, timeoutMillis = 100))
            assertEquals(1L, it.diagnosticSnapshot().writeNotReady)
            assertEquals(1L, it.diagnosticSnapshot().writeSuccesses)
            assertEquals(2L, it.diagnosticSnapshot().ipv6TxAttempts)
            assertEquals(1L, it.diagnosticSnapshot().ipv6TxSuccesses)
        }

        assertEquals(2, script.bulkOutCalls)
        assertEquals(1, script.outgoingBlocks.size)
        assertArrayEquals(frame, Ntb16Codec.parse(script.outgoingBlocks.single()).single())

        val partialWrite = newBridge(bulkOutcomes = listOf(BulkTransferOutcome.Partial(1)))
        val partialResult = partialWrite.first.send(frame, timeoutMillis = 100)
        assertTrue(partialResult is NcmSendResult.Failed)
        assertEquals(1L, partialWrite.first.diagnosticSnapshot().writePartialFailures)
        assertEquals(1, partialWrite.second.outgoingBlocks.size)
        partialWrite.first.close()
    }

    private fun newBridge(
        queueOutcomes: List<UsbQueueOutcome> = emptyList(),
        waitOutcomes: List<RequestWaitOutcome> = emptyList(),
        bulkOutcomes: List<BulkTransferOutcome> = emptyList(),
        onDiagnosticEvent: (NcmDiagnosticEvent) -> Unit = {},
    ): Pair<NcmUsbBridge, Step4UsbDeviceConnectionShadow> {
        val connection = ReflectionHelpers.callConstructor(UsbDeviceConnection::class.java)
        val script = Shadow.extract<Step4UsbDeviceConnectionShadow>(connection)
        script.configure(queueOutcomes, waitOutcomes, bulkOutcomes)
        val inEndpoint = Step4TestFixtures.endpointDescriptors[0].materialize()
        val outEndpoint = Step4TestFixtures.endpointDescriptors[1].materialize()
        return NcmUsbBridge(
            connection = connection,
            outEndpoint = outEndpoint,
            inEndpoint = inEndpoint,
            statusEndpoint = null,
            descriptorHostMac = null,
            selectionDiagnostics = "test fixture only",
            onDiagnosticEvent = onDiagnosticEvent,
            diagnosticProfile = NcmDiagnosticProfile.AUTO,
            diagnosticAttempt = 1,
            diagnosticsEnabled = false,
            onDiagnosticLog = {},
        ) to script
    }
}

@Implements(UsbEndpoint::class)
internal class Step4UsbEndpointShadow {
    private var descriptor: UsbEndpointFixture? = null

    fun configure(descriptor: UsbEndpointFixture) {
        this.descriptor = descriptor
    }

    @Implementation
    protected fun getAddress(): Int = configuredDescriptor().address

    @Implementation
    protected fun getDirection(): Int = configuredDescriptor().direction

    @Implementation
    protected fun getType(): Int = configuredDescriptor().transferType

    @Implementation
    protected fun getMaxPacketSize(): Int = configuredDescriptor().maxPacketSize

    private fun configuredDescriptor(): UsbEndpointFixture =
        requireNotNull(descriptor) { "USB endpoint descriptor must be configured explicitly" }
}

private fun UsbEndpointFixture.materialize(): UsbEndpoint =
    ReflectionHelpers.callConstructor(UsbEndpoint::class.java).also { endpoint ->
        Shadow.extract<Step4UsbEndpointShadow>(endpoint).configure(this)
    }

@Implements(UsbDeviceConnection::class)
internal class Step4UsbDeviceConnectionShadow {
    private val queueOutcomes = ArrayDeque<UsbQueueOutcome>()
    private val waitOutcomes = ArrayDeque<RequestWaitOutcome>()
    private val bulkOutcomes = ArrayDeque<BulkTransferOutcome>()
    private var pendingRequest: UsbRequest? = null
    private var pendingBuffer: ByteBuffer? = null
    private val waitEntered = CountDownLatch(1)
    private val connectionClosedSignal = CountDownLatch(1)
    var queueCalls: Int = 0
        private set
    val queuedBufferCapacities = mutableListOf<Int>()
    var waitCalls: Int = 0
        private set
    var bulkOutCalls: Int = 0
        private set
    var requestCancelCount: Int = 0
        private set
    var requestCloseCount: Int = 0
        private set
    @Volatile
    var connectionClosed: Boolean = false
        private set
    val outgoingBlocks = mutableListOf<ByteArray>()

    fun configure(
        queueResults: List<UsbQueueOutcome>,
        waitResults: List<RequestWaitOutcome>,
        bulkResults: List<BulkTransferOutcome>,
    ) {
        queueOutcomes.clear()
        queueOutcomes.addAll(queueResults)
        waitOutcomes.clear()
        waitOutcomes.addAll(waitResults)
        bulkOutcomes.clear()
        bulkOutcomes.addAll(bulkResults)
    }

    fun queueRequest(request: UsbRequest, buffer: ByteBuffer): Boolean {
        check(queueOutcomes.isNotEmpty()) { "USB queue outcome must be configured explicitly" }
        queueCalls++
        queuedBufferCapacities += buffer.remaining()
        return when (queueOutcomes.removeFirst()) {
            UsbQueueOutcome.Queued -> {
                pendingRequest = request
                pendingBuffer = buffer
                true
            }
            UsbQueueOutcome.Rejected -> false
        }
    }

    fun cancelRequest(request: UsbRequest) {
        if (pendingRequest === request) {
            pendingRequest = null
            pendingBuffer = null
        }
        requestCancelCount++
    }

    fun recordRequestClose() {
        requestCloseCount++
    }

    fun awaitRequestWait(timeout: Long, unit: TimeUnit): Boolean = waitEntered.await(timeout, unit)

    @Implementation
    protected fun requestWait(timeoutMillis: Long): UsbRequest? {
        require(timeoutMillis > 0L)
        check(waitOutcomes.isNotEmpty()) { "requestWait outcome must be configured explicitly" }
        waitCalls++
        return when (val outcome = waitOutcomes.removeFirst()) {
            is RequestWaitOutcome.Complete -> {
                val request = requireNotNull(pendingRequest) { "completion needs a queued USB request" }
                val buffer = requireNotNull(pendingBuffer) { "completion needs a queued USB buffer" }
                check(outcome.bytes.size <= buffer.remaining()) { "inbound packet exceeds the queued buffer" }
                buffer.put(outcome.bytes)
                request
            }
            RequestWaitOutcome.Timeout -> throw TimeoutException("scripted requestWait timeout")
            RequestWaitOutcome.NullResult -> null
            RequestWaitOutcome.UnexpectedCompletion -> ReflectionHelpers.callConstructor(UsbRequest::class.java)
            RequestWaitOutcome.BlockUntilConnectionClose -> {
                waitEntered.countDown()
                if (!connectionClosedSignal.await(timeoutMillis.coerceAtMost(5_000L), TimeUnit.MILLISECONDS)) {
                    throw TimeoutException("fixture requestWait was not released by connection.close()")
                }
                null
            }
        }
    }

    @Implementation
    protected fun bulkTransfer(endpoint: UsbEndpoint, buffer: ByteArray, length: Int, timeoutMillis: Int): Int {
        require(length in 0..buffer.size)
        require(timeoutMillis > 0)
        require(endpoint.direction == 0x00 && endpoint.type == 2) { "expected the configured bulk-OUT endpoint" }
        check(bulkOutcomes.isNotEmpty()) { "USB bulk-OUT outcome must be configured explicitly" }
        bulkOutCalls++
        return when (val outcome = bulkOutcomes.removeFirst()) {
            BulkTransferOutcome.FullTransfer -> {
                outgoingBlocks += buffer.copyOf(length)
                length
            }
            BulkTransferOutcome.NotReady -> 0
            is BulkTransferOutcome.Partial -> outcome.bytes.also {
                require(it in 0 until length)
                outgoingBlocks += buffer.copyOf(it)
            }
            is BulkTransferOutcome.Failure -> throw IllegalStateException(outcome.message)
        }
    }

    @Implementation
    protected fun close() {
        connectionClosed = true
        pendingRequest = null
        pendingBuffer = null
        connectionClosedSignal.countDown()
    }
}

@Implements(UsbRequest::class)
internal class Step4UsbRequestShadow {
    @field:RealObject
    lateinit var request: UsbRequest

    private var connection: UsbDeviceConnection? = null

    @Implementation
    protected fun initialize(connection: UsbDeviceConnection, endpoint: UsbEndpoint): Boolean {
        require(endpoint.direction == 0x80 && endpoint.type == 2) { "expected the configured bulk-IN endpoint" }
        require(endpoint.maxPacketSize > 0)
        this.connection = connection
        return true
    }

    @Implementation
    protected fun queue(buffer: ByteBuffer): Boolean = owner().queueRequest(request, buffer)

    @Implementation(minSdk = 26)
    protected fun queue(buffer: ByteBuffer, length: Int): Boolean {
        require(length in 0..buffer.remaining())
        val originalLimit = buffer.limit()
        return try {
            buffer.limit(buffer.position() + length)
            owner().queueRequest(request, buffer)
        } finally {
            buffer.limit(originalLimit)
        }
    }

    @Implementation
    protected fun cancel(): Boolean {
        owner().cancelRequest(request)
        return true
    }

    @Implementation
    protected fun close() {
        owner().recordRequestClose()
        connection = null
    }

    private fun owner(): Step4UsbDeviceConnectionShadow =
        Shadow.extract(requireNotNull(connection) { "USB request is not initialized" })
}
