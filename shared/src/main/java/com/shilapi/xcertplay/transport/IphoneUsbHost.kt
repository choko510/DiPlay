package com.shilapi.xcertplay.transport

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import android.os.Build
import android.util.Log
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Exact Apple USB identities allowed by the deployment configuration. */
class IphoneUsbMatcher private constructor(
    private val allowedDevices: Set<UsbDeviceId>?,
    private val allowAnyAppleProduct: Boolean,
) {
    constructor(allowedDevices: Collection<UsbDeviceId>) : this(
        allowedDevices.toSet(),
        allowAnyAppleProduct = false,
    )

    init {
        require(allowedDevices == null || allowedDevices.isNotEmpty()) {
            "At least one iPhone USB identity is required when not using Apple-vendor discovery"
        }
        require(allowedDevices == null || allowedDevices.all { it.vendorId == APPLE_VENDOR_ID }) {
            "iPhone USB identities must use Apple vendor ID 0x${APPLE_VENDOR_ID.toString(16)}"
        }
    }

    fun matches(vendorId: Int, productId: Int): Boolean =
        if (allowAnyAppleProduct) vendorId == APPLE_VENDOR_ID
        else UsbDeviceId(vendorId, productId) in allowedDevices.orEmpty()

    companion object {
        /** Apple VID used by LIVI commit 0a3dcaa0bf30d5319506d0e47c7b0d46bc942ec3. */
        const val APPLE_VENDOR_ID = 0x05ac

        /** Discovers every Apple device, matching only the vendor ID confirmed by LIVI. */
        fun appleVendor(): IphoneUsbMatcher = IphoneUsbMatcher(null, allowAnyAppleProduct = true)
    }
}

/**
 * Android USB Host bring-up boundary for a configured iPhone identity.
 *
 * LIVI's fixed commit uses Apple vendor request `0x52`, value `0`, index `4`, and then selects
 * configuration `6`. The vendor request can make the iPhone re-enumerate. Android does not offer
 * Linux sysfs configuration control or a synchronous re-enumeration primitive, so this class
 * closes the first connection and requires the caller to receive, re-authorize, and pass the new
 * [UsbDevice] to [selectCarPlayConfigurationAsync]. All opens run on the supplied executor.
 */
class IphoneUsbHost(
    context: Context,
    private val usbManager: UsbManager,
    private val matcher: IphoneUsbMatcher,
    private val permissionAction: String = "${context.packageName}.IPHONE_USB_PERMISSION",
) {
    private val appContext = context.applicationContext
    private val permissionRequestSequence = AtomicInteger(0)

    sealed class PermissionRequest {
        data class AlreadyGranted(val device: UsbDevice, val requestId: Int) : PermissionRequest()
        data class Requested(val device: UsbDevice, val requestId: Int) : PermissionRequest()
    }

    sealed class PermissionResult {
        abstract val device: UsbDevice
        abstract val requestId: Int

        data class Granted(
            override val device: UsbDevice,
            override val requestId: Int,
        ) : PermissionResult()

        data class Denied(
            override val device: UsbDevice,
            override val requestId: Int,
        ) : PermissionResult()
    }

    sealed class TransitionResult {
        /** The connection was closed; wait for a new matching attached device before continuing. */
        data object ReenumerationRequested : TransitionResult()

        data class Failed(val error: IphoneUsbException) : TransitionResult()
    }

    sealed class Iap2SessionResult {
        data class Connected(val session: Iap2UsbSession) : Iap2SessionResult()
        data class Failed(val error: IphoneUsbException) : Iap2SessionResult()
    }

    data class AppleUsbModeDiagnostics(
        val guessedMode: Int?,
        val configurationCount: Int,
        val configurationIds: List<Int>,
        val getModeResponse: String,
    ) {
        fun summary(): String =
            "appleModeGuessed=${guessedMode ?: "unknown"} configurationCount=$configurationCount " +
                "configurationIds=${configurationIds.joinToString(",")} getModeResponse=$getModeResponse"
    }

    fun discover(): List<UsbDevice> =
        usbManager.deviceList.values.filter { matcher.matches(it.vendorId, it.productId) }

    /** Reads usbmuxd's diagnostic mode request without changing the device's current mode. */
    fun queryAppleUsbMode(
        device: UsbDevice,
        connection: UsbDeviceConnection,
        queryDevice: Boolean,
    ): AppleUsbModeDiagnostics {
        requireConfiguredDevice(device)
        val configurations = (0 until device.configurationCount).map(device::getConfiguration)
        val response = if (!queryDevice) {
            "not-queried"
        } else {
            val bytes = ByteArray(APPLE_GET_MODE_RESPONSE_LENGTH)
            val transferred = runCatching {
                connection.controlTransfer(
                    USB_VENDOR_DEVICE_IN,
                    APPLE_GET_MODE_REQUEST,
                    0,
                    0,
                    bytes,
                    bytes.size,
                    APPLE_GET_MODE_TIMEOUT_MILLIS,
                )
            }.getOrDefault(-1)
            if (transferred == bytes.size) {
                bytes.joinToString(":") { "%02x".format(it.toInt() and 0xff) }
            } else {
                "unavailable($transferred/${bytes.size})"
            }
        }
        return AppleUsbModeDiagnostics(
            guessedMode = guessAppleUsbMode(configurations),
            configurationCount = device.configurationCount,
            configurationIds = configurations.map(UsbConfiguration::getId),
            getModeResponse = response,
        )
    }

    private fun guessAppleUsbMode(configurations: List<UsbConfiguration>): Int? = when {
        configurations.size == 1 -> 5
        configurations.size <= 4 -> 1
        configurations.size == 6 -> 4
        configurations.size != 5 -> null
        else -> {
            val fifth = configurations.firstOrNull { it.id == 5 } ?: return null
            val interfaces = (0 until fifth.interfaceCount).map(fifth::getInterface)
            val hasValeria = interfaces.any {
                it.interfaceClass == 0xff && it.interfaceSubclass == 42 && it.interfaceProtocol == 255
            }
            val hasNcm = interfaces.any {
                it.interfaceClass == NCM_CONTROL_CLASS && it.interfaceSubclass == NCM_CONTROL_SUBCLASS
            }
            val hasUsbMux = interfaces.any {
                it.interfaceClass == USBMUX_CLASS && it.interfaceSubclass == USBMUX_SUBCLASS &&
                    it.interfaceProtocol == USBMUX_PROTOCOL
            }
            when {
                hasValeria && hasUsbMux -> 2
                hasNcm && hasUsbMux -> 3
                else -> null
            }
        }
    }

    @Throws(IphoneUsbException::class)
    fun requestPermission(device: UsbDevice): PermissionRequest {
        requireConfiguredDevice(device)
        val requestId = nextPermissionRequestId()
        if (usbManager.hasPermission(device)) return PermissionRequest.AlreadyGranted(device, requestId)

        usbManager.requestPermission(device, permissionPendingIntent(requestId))
        return PermissionRequest.Requested(device, requestId)
    }

    /** Returns null for unrelated broadcasts, malformed results, or non-configured devices. */
    fun parsePermissionResult(intent: Intent): PermissionResult? {
        if (intent.action != permissionAction) return null
        val device = intent.usbDevice() ?: return null
        if (!matcher.matches(device.vendorId, device.productId)) return null
        val requestId = intent.getIntExtra(EXTRA_PERMISSION_REQUEST_ID, 0)
        if (requestId <= 0) return null
        return if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
            PermissionResult.Granted(device, requestId)
        } else {
            PermissionResult.Denied(device, requestId)
        }
    }

    /** Returns the new matching device after the vendor request caused Android USB re-enumeration. */
    fun parseAttachedDevice(intent: Intent): UsbDevice? {
        if (intent.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return null
        val device = intent.usbDevice() ?: return null
        return device.takeIf { matcher.matches(it.vendorId, it.productId) }
    }

    /** Register once for this host instance and close the returned handle to unregister it. */
    fun registerPermissionReceiver(onResult: (PermissionResult) -> Unit): Closeable =
        registerReceiver(IntentFilter(permissionAction)) { parsePermissionResult(it)?.let(onResult) }

    /** Register once for this host instance and close the returned handle to unregister it. */
    fun registerAttachReceiver(onAttached: (UsbDevice) -> Unit): Closeable =
        registerReceiver(IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED)) {
            parseAttachedDevice(it)?.let(onAttached)
        }

    /**
     * Sends the LIVI-evidenced vendor request then closes the connection before re-enumeration.
     * The callback is invoked from [executor].
     */
    fun requestCarPlayReenumerationAsync(
        device: UsbDevice,
        executor: Executor,
        callback: (TransitionResult) -> Unit,
    ) {
        executor.execute {
            callback(runTransition(device) { connection ->
                val response = ByteArray(VENDOR_RESPONSE_LENGTH)
                val transferred = connection.controlTransfer(
                    USB_VENDOR_DEVICE_IN,
                    CARPLAY_CONFIGURATION_REQUEST,
                    0,
                    CARPLAY_CONFIGURATION_INDEX,
                    response,
                    response.size,
                    CONTROL_TRANSFER_TIMEOUT_MILLIS,
                )
                if (transferred != response.size) {
                    throw IphoneUsbException.Protocol(
                        "CarPlay configuration request transferred $transferred of ${response.size} bytes",
                    )
                }
                TransitionResult.ReenumerationRequested
            })
        }
    }

    /**
     * Opens LIVI's USBMUX bulk pipe on the re-enumerated iPhone.
     *
     * This repeats CarPlay configuration selection on the newly opened Android connection and
     * claims the Apple USB Multiplexor interface, preferring the LIVI bulk pair 0x04/0x85.
     * After a successful callback, it owns the returned session and must close it. If the callback
     * throws, this method closes the session before propagating the callback failure.
     */
    fun openIap2UsbSessionAsync(
        device: UsbDevice,
        executor: Executor,
        callback: (Iap2SessionResult) -> Unit,
    ) {
        executor.execute {
            val result = try {
                Iap2SessionResult.Connected(openIap2UsbSession(device))
            } catch (error: IphoneUsbException) {
                Iap2SessionResult.Failed(error)
            } catch (error: SecurityException) {
                Iap2SessionResult.Failed(
                    IphoneUsbException.PermissionDenied("USB permission was denied", error),
                )
            } catch (error: RuntimeException) {
                Iap2SessionResult.Failed(
                    IphoneUsbException.DeviceUnavailable("iPhone USBMUX operation failed", error),
                )
            }
            try {
                callback(result)
            } catch (error: Throwable) {
                if (result is Iap2SessionResult.Connected) {
                    try {
                        result.session.close()
                    } catch (closeError: Throwable) {
                        error.addSuppressed(closeError)
                    }
                }
                throw error
            }
        }
    }

    private fun runTransition(
        device: UsbDevice,
        operation: (UsbDeviceConnection) -> TransitionResult,
    ): TransitionResult = try {
        requireConfiguredDevice(device)
        if (!usbManager.hasPermission(device)) {
            throw IphoneUsbException.PermissionDenied("USB permission has not been granted")
        }
        val connection = usbManager.openDevice(device)
            ?: throw IphoneUsbException.DeviceUnavailable("UsbManager could not open the iPhone")
        try {
            operation(connection)
        } finally {
            connection.close()
        }
    } catch (error: IphoneUsbException) {
        TransitionResult.Failed(error)
    } catch (error: SecurityException) {
        TransitionResult.Failed(IphoneUsbException.PermissionDenied("USB permission was denied", error))
    } catch (error: RuntimeException) {
        TransitionResult.Failed(IphoneUsbException.DeviceUnavailable("iPhone USB operation failed", error))
    }

    private fun openIap2UsbSession(device: UsbDevice): Iap2UsbSession {
        requireConfiguredDevice(device)
        if (!usbManager.hasPermission(device)) {
            throw IphoneUsbException.PermissionDenied("USB permission has not been granted")
        }
        val connection = usbManager.openDevice(device)
            ?: throw IphoneUsbException.DeviceUnavailable("UsbManager could not open the iPhone")
        var claimedInterface: UsbInterface? = null
        try {
            val configuration = IphoneCarPlayConfiguration.find(device)
                ?: throw IphoneUsbException.Protocol(
                    "Re-enumerated iPhone exposes no USBMUX CarPlay configuration",
                )
            if (!connection.setConfiguration(configuration)) {
                Log.w(
                    IphoneCarPlayConfiguration.TAG,
                    "setConfiguration ${configuration.id} reported failure; claiming anyway",
                )
            }
            val usbMux = IphoneCarPlayConfiguration.usbMuxInterface(configuration)
                ?: throw IphoneUsbException.Protocol("CarPlay configuration exposes no USBMUX interface")
            val endpoints = IphoneCarPlayConfiguration.usbMuxEndpoints(usbMux)
                ?: throw IphoneUsbException.Protocol("USBMUX interface exposes no bulk endpoint pair")
            Log.i(
                IphoneCarPlayConfiguration.TAG,
                "usbmux config=${configuration.id} iface=${usbMux.id} alt=${usbMux.alternateSetting} " +
                    "class=${usbMux.interfaceClass}/${usbMux.interfaceSubclass}/${usbMux.interfaceProtocol} " +
                    "endpoints=${usbMux.endpointCount} " +
                    "out=${describeUsbEndpoint(endpoints.first)} " +
                    "in=${describeUsbEndpoint(endpoints.second)}",
            )
            if (!connection.claimInterface(usbMux, true)) {
                throw IphoneUsbException.DeviceUnavailable("Android could not claim USBMUX interface 1")
            }
            claimedInterface = usbMux
            return Iap2UsbSession(connection, endpoints.first, endpoints.second)
        } catch (error: Throwable) {
            claimedInterface?.let { claimed -> runCatching { connection.releaseInterface(claimed) } }
            runCatching { connection.close() }
            throw error
        }
    }

    private fun requireConfiguredDevice(device: UsbDevice) {
        if (!matcher.matches(device.vendorId, device.productId)) {
            throw IphoneUsbException.DeviceUnavailable("USB device is not a configured iPhone identity")
        }
    }

    @Synchronized
    private fun nextPermissionRequestId(): Int {
        val next = permissionRequestSequence.incrementAndGet()
        if (next > 0) return next
        permissionRequestSequence.set(1)
        return 1
    }

    private fun permissionPendingIntent(requestId: Int): PendingIntent {
        val intent = Intent(permissionAction)
            .setPackage(appContext.packageName)
            .putExtra(EXTRA_PERMISSION_REQUEST_ID, requestId)
        return PendingIntent.getBroadcast(
            appContext,
            requestId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun registerReceiver(filter: IntentFilter, onReceive: (Intent) -> Unit): Closeable {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = onReceive(intent)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            appContext.registerReceiver(receiver, filter)
        }
        val registered = AtomicBoolean(true)
        return Closeable {
            if (registered.compareAndSet(true, false)) appContext.unregisterReceiver(receiver)
        }
    }

    private fun Intent.usbDevice(): UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(UsbManager.EXTRA_DEVICE)
    }

    companion object {
        const val EXTRA_PERMISSION_REQUEST_ID = "com.shilapi.xcertplay.extra.IPHONE_PERMISSION_REQUEST_ID"
        private const val USB_VENDOR_DEVICE_IN = 0xc0
        private const val CARPLAY_CONFIGURATION_REQUEST = 0x52
        private const val CARPLAY_CONFIGURATION_INDEX = 0x0004
        private const val VENDOR_RESPONSE_LENGTH = 1
        private const val CONTROL_TRANSFER_TIMEOUT_MILLIS = 1_000
        private const val APPLE_GET_MODE_REQUEST = 0x45
        private const val APPLE_GET_MODE_RESPONSE_LENGTH = 4
        private const val APPLE_GET_MODE_TIMEOUT_MILLIS = 250
        private const val USBMUX_CLASS = 0xff
        private const val USBMUX_SUBCLASS = 0xfe
        private const val USBMUX_PROTOCOL = 0x02
        private const val NCM_CONTROL_CLASS = 0x02
        private const val NCM_CONTROL_SUBCLASS = 0x0d
    }

}

/**
 * A blocking, full-duplex USBMUX pipe. A null [read] result means only that its timeout elapsed.
 *
 * All operations must run off the Android main thread. The session does not parse iAP2 frames.
 */
class Iap2UsbSession internal constructor(
    private val connection: UsbDeviceConnection,
    private val outEndpoint: UsbEndpoint,
    private val inEndpoint: UsbEndpoint,
) : Closeable {
    private val stateLock = Any()
    private val readLock = Any()
    private val writeLock = Any()
    private var closed = false
    private var failure: IphoneUsbException? = null
    private val readLifecycle = PersistentUsbReadRequestState<UsbRequest>()
    // Match NCM's proven 32 KiB direct buffer. USBMUX frames up to 64 KiB are reassembled above.
    private val directReadBuffer = ByteBuffer.allocateDirect(USBMUX_READ_CHUNK_BYTES)
    private var readCompletions = 0L
    private var readTimeouts = 0L
    private var readBytes = 0L
    private var readQueues = 0L
    private var readCancels = 0L
    private var writeFailures = 0L
    private var diagnosticsLogged = false

    fun write(data: ByteArray, timeoutMillis: Int) = synchronized(writeLock) {
        checkOpen()
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        if (data.isEmpty()) return@synchronized
        val transferred = try {
            connection.bulkTransfer(outEndpoint, data, data.size, timeoutMillis)
        } catch (error: RuntimeException) {
            synchronized(stateLock) { writeFailures++ }
            throw failSession("USBMUX write failed", error)
        }
        if (transferred != data.size) {
            synchronized(stateLock) { writeFailures++ }
            throw failSession(
                "USBMUX write transferred $transferred of ${data.size} bytes",
            )
        }
    }

    /** Returns null only when no completed USB request arrives before [timeoutMillis]. */
    fun read(timeoutMillis: Long): ByteArray? = synchronized(readLock) {
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        try {
            synchronized(stateLock) {
                checkOpenLocked()
                val current = readLifecycle.requestOrCreate {
                    UsbRequest().also {
                        val initialized = try {
                            it.initialize(connection, inEndpoint)
                        } catch (error: RuntimeException) {
                            runCatching { it.close() }
                            throw error
                        }
                        if (!initialized) {
                            runCatching { it.close() }
                            throw failSession(
                                "Android could not initialize USBMUX read request (${requestDiagnostics(timeoutMillis)})",
                            )
                        }
                    }
                }
                if (readLifecycle.needsQueue()) {
                    directReadBuffer.clear()
                    if (!current.queue(directReadBuffer)) {
                        readLifecycle.markQueueFailure()
                        throw failSession(
                            "Android could not queue USBMUX read request " +
                                "(${requestDiagnostics(timeoutMillis, directReadBuffer.capacity())})",
                        )
                    }
                    readLifecycle.markQueued()
                    readQueues++
                }
            }
            val completed = try {
                connection.requestWait(timeoutMillis)
            } catch (_: TimeoutException) {
                synchronized(stateLock) {
                    checkOpenLocked()
                    readLifecycle.onTimeout()
                    readTimeouts++
                }
                return@synchronized null
            }
            if (completed == null) {
                throw failSession("Android returned no USBMUX read request")
            }
            val transferred = synchronized(stateLock) {
                checkOpenLocked()
                if (!readLifecycle.onCompletion(completed)) {
                    throw failSession("Android completed an unexpected USB request")
                }
                val count = directReadBuffer.position()
                readCompletions++
                readBytes += count.toLong()
                count
            }
            return@synchronized ByteArray(transferred).also {
                directReadBuffer.flip()
                directReadBuffer.get(it)
            }
        } catch (error: IphoneUsbException) {
            throw error
        } catch (error: RuntimeException) {
            throw failSession("USBMUX read failed", error)
        }
    }

    override fun close() {
        val closePlan = synchronized(stateLock) {
            if (closed) return
            closed = true
            readLifecycle.beginClose().also {
                if (it.cancelQueuedRequest) readCancels++
            }
        }
        try {
            if (closePlan.cancelQueuedRequest) runCatching { closePlan.request?.cancel() }
            runCatching { connection.close() }
        } finally {
            // Closing the connection wakes requestWait(); wait for the sole reader to leave before
            // releasing the request, so close() never races a requestWait() or double-closes it.
            synchronized(readLock) { }
            synchronized(writeLock) { }
            val requestToClose = synchronized(stateLock) { readLifecycle.takeForClose() }
            runCatching { requestToClose?.close() }
            logFailureDiagnostics()
        }
    }

    internal fun diagnosticSummary(): String = synchronized(stateLock) {
        "readCompletions=$readCompletions readTimeouts=$readTimeouts readBytes=$readBytes " +
            "queues=$readQueues cancels=$readCancels writeFailures=$writeFailures"
    }

    private fun checkOpen() {
        synchronized(stateLock) { checkOpenLocked() }
    }

    private fun checkOpenLocked() {
        failure?.let { throw it }
        if (closed) throw IphoneUsbException.DeviceUnavailable("USBMUX session is closed")
    }

    private fun failSession(message: String, cause: Throwable? = null): IphoneUsbException.DeviceUnavailable {
        val error = IphoneUsbException.DeviceUnavailable(message, cause)
        return synchronized(stateLock) {
            readLifecycle.markFailure()
            val existing = failure
            if (existing is IphoneUsbException.DeviceUnavailable) existing
            else error.also { failure = it }
        }
    }

    private fun logFailureDiagnostics() {
        val failureMessage = synchronized(stateLock) {
            if (failure == null || diagnosticsLogged) return
            diagnosticsLogged = true
            failure?.message
        }
        Log.w(
            IphoneCarPlayConfiguration.TAG,
            "USBMUX transport summary ${diagnosticSummary()} failure=${failureMessage ?: "unknown"}",
        )
    }

    private fun requestDiagnostics(timeoutMillis: Long, bufferBytes: Int? = null): String = buildString {
        append("api=").append(Build.VERSION.SDK_INT)
        append(" endpoint=").append(describeUsbEndpoint(inEndpoint))
        append(" timeoutMs=").append(timeoutMillis)
        if (bufferBytes != null) append(" bufferBytes=").append(bufferBytes)
    }

    private companion object {
        const val USBMUX_READ_CHUNK_BYTES = 32 * 1024
    }
}

private fun describeUsbEndpoint(endpoint: UsbEndpoint): String =
    "0x${endpoint.address.toString(16)}(direction=${endpoint.direction}," +
        "type=${endpoint.type},maxPacket=${endpoint.maxPacketSize})"

/** USB bring-up failures that precede iAP2 and are distinct from MFi I2C failures. */
sealed class IphoneUsbException(message: String, cause: Throwable? = null) : IOException(message, cause) {
    class PermissionDenied(message: String, cause: Throwable? = null) : IphoneUsbException(message, cause)
    class DeviceUnavailable(message: String, cause: Throwable? = null) : IphoneUsbException(message, cause)
    class TimedOut(message: String, cause: Throwable? = null) : IphoneUsbException(message, cause)
    class Protocol(message: String) : IphoneUsbException(message)
    class LockdownRemoteError(val request: String, val code: String) :
        IphoneUsbException("Lockdown $request failed: $code")
}
