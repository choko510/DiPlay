package com.shilapi.xcertplay.step4

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbRequest
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers

internal data class Step4BulkTransferCall(
    val endpoint: UsbEndpoint,
    val requestedBytes: Int,
    val timeoutMillis: Int,
    val transferredBytes: Int,
    val buffer: ByteArray,
)

internal sealed interface Step4BulkTransferScript {
    val endpoint: UsbEndpoint

    data class Result(
        override val endpoint: UsbEndpoint,
        val transferredBytes: Int,
        val incomingBytes: ByteArray? = null,
    ) : Step4BulkTransferScript

    data class Throw(
        override val endpoint: UsbEndpoint,
        val error: RuntimeException,
    ) : Step4BulkTransferScript
}

internal sealed interface Step4RequestWaitScript {
    data class Complete(val bytes: ByteArray) : Step4RequestWaitScript
    data object Timeout : Step4RequestWaitScript
    data object NullResult : Step4RequestWaitScript
    data object UnexpectedRequest : Step4RequestWaitScript
    data object BlockUntilConnectionClose : Step4RequestWaitScript
}

internal class Step4ScriptedUsbConnection private constructor(val androidConnection: UsbDeviceConnection) {
    private val shadow: Step4UsbDeviceConnectionShadow
        get() = Shadow.extract(androidConnection)

    val bulkTransferCallCount: Int get() = shadow.bulkTransferCallCount
    val bulkTransferCalls: List<Step4BulkTransferCall> get() = shadow.bulkTransferCalls.toList()
    val requestInitializationCount: Int get() = shadow.requestInitializationCount
    val requestQueueCallCount: Int get() = shadow.requestQueueCallCount
    val queuedBufferCapacities: List<Int> get() = shadow.queuedBufferCapacities.toList()
    val initializedRequestEndpoints: List<UsbEndpoint> get() = shadow.initializedRequestEndpoints.toList()
    val requestWaitTimeouts: List<Long> get() = shadow.requestWaitTimeouts.toList()
    val requestWaitCallCount: Int get() = shadow.requestWaitCallCount
    val requestCancelCount: Int get() = shadow.requestCancelCount
    val requestCloseCount: Int get() = shadow.requestCloseCount
    val connectionCloseCount: Int get() = shadow.connectionCloseCount

    fun setRequestInitializationResult(success: Boolean) {
        shadow.requestInitializationResult = success
    }

    fun enqueueBulkTransferResult(endpoint: UsbEndpoint, transferredBytes: Int) {
        shadow.bulkTransferScripts.addLast(
            Step4BulkTransferScript.Result(endpoint, transferredBytes),
        )
    }

    fun enqueueBulkTransferInput(endpoint: UsbEndpoint, bytes: ByteArray) {
        shadow.bulkTransferScripts.addLast(
            Step4BulkTransferScript.Result(endpoint, bytes.size, bytes.copyOf()),
        )
    }

    fun enqueueBulkTransferError(endpoint: UsbEndpoint, error: RuntimeException) {
        shadow.bulkTransferScripts.addLast(Step4BulkTransferScript.Throw(endpoint, error))
    }

    fun enqueueRequestQueueResult(success: Boolean) {
        shadow.requestQueueResults.addLast(success)
    }

    fun enqueueRequestWaitTimeout() {
        shadow.requestWaitScripts.addLast(Step4RequestWaitScript.Timeout)
    }

    fun enqueueRequestWaitNull() {
        shadow.requestWaitScripts.addLast(Step4RequestWaitScript.NullResult)
    }

    fun enqueueRequestWaitCompletion(bytes: ByteArray) {
        shadow.requestWaitScripts.addLast(Step4RequestWaitScript.Complete(bytes.copyOf()))
    }

    fun enqueueUnexpectedRequestWaitCompletion() {
        shadow.requestWaitScripts.addLast(Step4RequestWaitScript.UnexpectedRequest)
    }

    fun enqueueRequestWaitBlockedUntilClose() {
        shadow.requestWaitScripts.addLast(Step4RequestWaitScript.BlockUntilConnectionClose)
    }

    fun awaitRequestWaitStarted(timeoutMillis: Long): Boolean =
        shadow.requestWaitEntered.await(timeoutMillis, TimeUnit.MILLISECONDS)

    companion object {
        fun create(): Step4ScriptedUsbConnection = Step4ScriptedUsbConnection(
            ReflectionHelpers.callConstructor(UsbDeviceConnection::class.java),
        )
    }
}

@Implements(UsbDeviceConnection::class)
class Step4UsbDeviceConnectionShadow {
    internal val bulkTransferScripts = ArrayDeque<Step4BulkTransferScript>()
    internal val bulkTransferCalls = mutableListOf<Step4BulkTransferCall>()
    internal val requestQueueResults = ArrayDeque<Boolean>()
    internal val requestWaitScripts = ArrayDeque<Step4RequestWaitScript>()
    private val pendingRequests = ArrayDeque<UsbRequest>()
    internal val requestWaitEntered = CountDownLatch(1)
    private val connectionClosed = CountDownLatch(1)
    internal var requestInitializationResult = false
    internal var bulkTransferCallCount = 0
        private set
    internal var requestInitializationCount = 0
        private set
    internal var requestQueueCallCount = 0
        private set
    internal var requestWaitCallCount = 0
        private set
    internal var requestCancelCount = 0
        private set
    internal var requestCloseCount = 0
        private set
    internal var connectionCloseCount = 0
        private set
    internal val queuedBufferCapacities = mutableListOf<Int>()
    internal val initializedRequestEndpoints = mutableListOf<UsbEndpoint>()
    internal val requestWaitTimeouts = mutableListOf<Long>()

    @Implementation
    fun bulkTransfer(endpoint: UsbEndpoint, buffer: ByteArray, length: Int, timeoutMillis: Int): Int {
        bulkTransferCallCount++
        val step = bulkTransferScripts.pollFirst() ?: return -1
        check(step.endpoint === endpoint) { "Bulk transfer used an unexpected endpoint" }
        when (step) {
            is Step4BulkTransferScript.Throw -> throw step.error
            is Step4BulkTransferScript.Result -> {
                val incoming = step.incomingBytes
                if (incoming != null) {
                    val copiedBytes = minOf(incoming.size, length)
                    incoming.copyInto(buffer, endIndex = copiedBytes)
                }
                bulkTransferCalls += Step4BulkTransferCall(
                    endpoint = endpoint,
                    requestedBytes = length,
                    timeoutMillis = timeoutMillis,
                    transferredBytes = step.transferredBytes,
                    buffer = buffer.copyOf(minOf(length, buffer.size)),
                )
                return step.transferredBytes
            }
        }
    }

    internal fun initializeRequest(endpoint: UsbEndpoint): Boolean {
        requestInitializationCount++
        initializedRequestEndpoints += endpoint
        return requestInitializationResult
    }

    internal fun queueRequest(request: UsbRequest, buffer: ByteBuffer): Boolean {
        requestQueueCallCount++
        queuedBufferCapacities += buffer.capacity()
        val success = requestQueueResults.pollFirst() ?: false
        if (success) pendingRequests.addLast(request)
        return success
    }

    internal fun cancelRequest(request: UsbRequest): Boolean {
        requestCancelCount++
        return pendingRequests.remove(request)
    }

    internal fun closeRequest() {
        requestCloseCount++
    }

    @Implementation
    fun requestWait(timeoutMillis: Long): UsbRequest? {
        requestWaitCallCount++
        requestWaitTimeouts += timeoutMillis
        return when (val step = requestWaitScripts.pollFirst() ?: Step4RequestWaitScript.Timeout) {
            is Step4RequestWaitScript.Complete -> {
                val request = pendingRequests.pollFirst()
                    ?: error("requestWait completed without a queued request")
                Shadow.extract<Step4UsbRequestShadow>(request).complete(step.bytes)
                request
            }
            Step4RequestWaitScript.Timeout -> throw TimeoutException()
            Step4RequestWaitScript.NullResult -> null
            Step4RequestWaitScript.UnexpectedRequest ->
                ReflectionHelpers.callConstructor(UsbRequest::class.java)
            Step4RequestWaitScript.BlockUntilConnectionClose -> {
                requestWaitEntered.countDown()
                if (!connectionClosed.await(timeoutMillis.coerceAtLeast(1), TimeUnit.MILLISECONDS)) {
                    throw TimeoutException()
                }
                null
            }
        }
    }

    @Implementation
    fun close() {
        connectionCloseCount++
        connectionClosed.countDown()
    }
}

@Implements(UsbRequest::class)
class Step4UsbRequestShadow {
    @RealObject
    private lateinit var realRequest: UsbRequest

    private var connection: UsbDeviceConnection? = null
    private var queuedBuffer: ByteBuffer? = null

    @Implementation
    fun initialize(connection: UsbDeviceConnection, endpoint: UsbEndpoint): Boolean {
        this.connection = connection
        return Shadow.extract<Step4UsbDeviceConnectionShadow>(connection).initializeRequest(endpoint)
    }

    @Implementation
    fun queue(buffer: ByteBuffer): Boolean {
        queuedBuffer = buffer
        val connection = connection ?: error("UsbRequest was not initialized")
        return Shadow.extract<Step4UsbDeviceConnectionShadow>(connection).queueRequest(realRequest, buffer)
    }

    @Implementation
    fun cancel(): Boolean {
        val connection = connection ?: return false
        return Shadow.extract<Step4UsbDeviceConnectionShadow>(connection).cancelRequest(realRequest)
    }

    @Implementation
    fun close() {
        connection?.let { Shadow.extract<Step4UsbDeviceConnectionShadow>(it).closeRequest() }
    }

    internal fun complete(bytes: ByteArray) {
        val buffer = queuedBuffer ?: error("UsbRequest completion has no queued buffer")
        buffer.clear()
        require(bytes.size <= buffer.remaining())
        buffer.put(bytes)
    }
}

@Implements(UsbEndpoint::class)
class Step4UsbEndpointShadow {
    internal lateinit var descriptor: Step4UsbEndpointDescriptor

    @Implementation
    fun getAddress(): Int = descriptor.address

    @Implementation
    fun getAttributes(): Int = descriptor.attributes

    @Implementation
    fun getDirection(): Int = descriptor.address and 0x80

    @Implementation
    fun getEndpointNumber(): Int = descriptor.address and 0x0f

    @Implementation
    fun getInterval(): Int = descriptor.interval

    @Implementation
    fun getMaxPacketSize(): Int = descriptor.maxPacketSize

    @Implementation
    fun getType(): Int = descriptor.attributes and 0x03
}
