package com.shilapi.xcertplay.step4

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbRequest
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.TimeoutException
import org.robolectric.Shadows
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadows.ShadowUsbDeviceConnection
import org.robolectric.shadows.ShadowUsbRequest
import org.robolectric.util.ReflectionHelpers

internal sealed interface UsbQueueResult {
    data object Accept : UsbQueueResult
    data object Reject : UsbQueueResult
    data class Throw(val error: RuntimeException) : UsbQueueResult
}

internal sealed interface UsbWaitResult {
    data class Complete(val bytes: ByteArray) : UsbWaitResult
    data object Timeout : UsbWaitResult
    data object NullResult : UsbWaitResult
    data object UnexpectedRequest : UsbWaitResult
}

internal sealed interface BulkInResult {
    data class Bytes(val bytes: ByteArray) : BulkInResult
    data class Count(val value: Int) : BulkInResult
    data class Throw(val error: RuntimeException) : BulkInResult
}

internal sealed interface BulkOutResult {
    data class Count(val value: Int) : BulkOutResult
    data class Throw(val error: RuntimeException) : BulkOutResult
}

@Implements(UsbDeviceConnection::class)
internal class ScriptedUsbDeviceConnectionShadow : ShadowUsbDeviceConnection() {
    private val queueScript = ArrayDeque<UsbQueueResult>()
    private val waitScript = ArrayDeque<UsbWaitResult>()
    private val bulkInScript = ArrayDeque<BulkInResult>()
    private val bulkOutScript = ArrayDeque<BulkOutResult>()
    private val sentBytes = ByteArrayOutputStream()
    private var repeatingBulkInResult: BulkInResult? = null
    private var repeatingWaitResult: UsbWaitResult? = null
    private var pendingRequest: UsbRequest? = null
    private var pendingReadBuffer: ByteBuffer? = null

    var queueAttempts: Int = 0
        private set
    var waitCalls: Int = 0
        private set
    var lastQueuedRequest: UsbRequest? = null
        private set
    var lastQueueCapacity: Int? = null
        private set

    fun scriptQueue(vararg results: UsbQueueResult) {
        queueScript.addAll(results)
    }

    fun scriptWait(vararg results: UsbWaitResult) {
        waitScript.addAll(results)
    }

    fun repeatWait(result: UsbWaitResult) {
        repeatingWaitResult = result
    }

    fun clearRepeatedWait() {
        repeatingWaitResult = null
    }

    fun scriptBulkIn(vararg results: BulkInResult) {
        bulkInScript.addAll(results)
    }

    fun repeatBulkIn(result: BulkInResult) {
        repeatingBulkInResult = result
    }

    fun clearRepeatedBulkIn() {
        repeatingBulkInResult = null
    }

    fun scriptBulkOut(vararg results: BulkOutResult) {
        bulkOutScript.addAll(results)
    }

    fun nextQueueResult(): UsbQueueResult =
        queueScript.pollFirst() ?: error("USB queue result was not scripted")

    fun recordQueueAttempt(request: UsbRequest, buffer: ByteBuffer, queued: Boolean) {
        queueAttempts++
        lastQueueCapacity = buffer.remaining()
        if (queued) {
            pendingRequest = request
            pendingReadBuffer = buffer
            lastQueuedRequest = request
        }
    }

    fun outgoingBytes(): ByteArray = sentBytes.toByteArray()

    @Implementation
    override fun bulkTransfer(
        endpoint: UsbEndpoint,
        buffer: ByteArray,
        length: Int,
        timeout: Int,
    ): Int = scriptedBulkTransfer(endpoint, buffer, 0, length, timeout)

    @Implementation
    override fun bulkTransfer(
        endpoint: UsbEndpoint,
        buffer: ByteArray,
        offset: Int,
        length: Int,
        timeout: Int,
    ): Int = scriptedBulkTransfer(endpoint, buffer, offset, length, timeout)

    private fun scriptedBulkTransfer(
        endpoint: UsbEndpoint,
        buffer: ByteArray,
        offset: Int,
        length: Int,
        timeout: Int,
    ): Int = if (endpoint.direction == UsbConstants.USB_DIR_IN) {
        when (val result = bulkInScript.pollFirst() ?: repeatingBulkInResult
            ?: error("USB bulk-IN result was not scripted")) {
            is BulkInResult.Bytes -> {
                require(result.bytes.size <= length) { "scripted USB bulk-IN chunk exceeds the requested length" }
                result.bytes.copyInto(buffer, offset)
                result.bytes.size
            }
            is BulkInResult.Count -> {
                require(result.value <= 0) { "positive bulk-IN results must include scripted bytes" }
                result.value
            }
            is BulkInResult.Throw -> throw result.error
        }
    } else {
        when (val result = bulkOutScript.pollFirst() ?: error("USB bulk-OUT result was not scripted")) {
            is BulkOutResult.Count -> {
                if (result.value > 0) {
                    sentBytes.write(buffer, offset, minOf(length, result.value))
                }
                result.value
            }
            is BulkOutResult.Throw -> throw result.error
        }
    }

    @Implementation
    override fun requestWait(timeout: Long): UsbRequest? {
        waitCalls++
        return when (val result = waitScript.pollFirst() ?: repeatingWaitResult
            ?: error("USB requestWait result was not scripted")) {
            is UsbWaitResult.Complete -> {
                val buffer = pendingReadBuffer ?: error("No USB request buffer is queued")
                require(result.bytes.size <= buffer.remaining()) { "scripted USB data exceeds request capacity" }
                buffer.put(result.bytes)
                pendingRequest ?: error("No USB request is queued")
            }
            UsbWaitResult.Timeout -> throw TimeoutException("Scripted USB requestWait timeout")
            UsbWaitResult.NullResult -> null
            UsbWaitResult.UnexpectedRequest -> UsbRequest()
        }
    }

    @Implementation
    fun close() {
        pendingRequest = null
        pendingReadBuffer = null
    }
}

@Implements(UsbRequest::class)
internal class ScriptedUsbRequestShadow : ShadowUsbRequest() {
    @RealObject
    private lateinit var request: UsbRequest

    private var connection: UsbDeviceConnection? = null

    @Implementation
    override fun initialize(connection: UsbDeviceConnection, endpoint: UsbEndpoint): Boolean {
        this.connection = connection
        return super.initialize(connection, endpoint)
    }

    @Implementation
    override fun queue(buffer: java.nio.ByteBuffer): Boolean {
        val usbConnection = checkNotNull(connection) { "UsbRequest must be initialized before queue" }
        val shadow = Shadows.shadowOf(usbConnection) as ScriptedUsbDeviceConnectionShadow
        val result = shadow.nextQueueResult()
        shadow.recordQueueAttempt(request, buffer, queued = result == UsbQueueResult.Accept)
        return when (result) {
            UsbQueueResult.Accept -> true
            UsbQueueResult.Reject -> false
            is UsbQueueResult.Throw -> throw result.error
        }
    }
}

internal data class UsbEndpointDescriptorFixture(
    val address: Int,
    val transferType: Int = UsbConstants.USB_ENDPOINT_XFER_BULK,
    val maxPacketSize: Int = 512,
    val interval: Int = 0,
) {
    init {
        require(address in 0..0xff)
        require(maxPacketSize > 0)
        require(interval >= 0)
    }

    fun create(): UsbEndpoint = ReflectionHelpers.callConstructor(UsbEndpoint::class.java).also { endpoint ->
        ReflectionHelpers.setField(endpoint, "mAddress", address)
        ReflectionHelpers.setField(endpoint, "mAttributes", transferType)
        ReflectionHelpers.setField(endpoint, "mMaxPacketSize", maxPacketSize)
        ReflectionHelpers.setField(endpoint, "mInterval", interval)
    }
}

internal class RobolectricUsbPipeFixture {
    val connection: UsbDeviceConnection = ReflectionHelpers.callConstructor(UsbDeviceConnection::class.java)
    val shadow: ScriptedUsbDeviceConnectionShadow =
        Shadows.shadowOf(connection) as ScriptedUsbDeviceConnectionShadow
    val bulkOut: UsbEndpoint = UsbEndpointDescriptorFixture(0x01).create()
    val bulkIn: UsbEndpoint = UsbEndpointDescriptorFixture(UsbConstants.USB_DIR_IN or 0x81).create()
}
