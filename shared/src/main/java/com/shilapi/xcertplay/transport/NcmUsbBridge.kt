package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbRequest
import android.util.Log
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class NcmDiagnosticEvent {
    READ_QUEUED,
    FIRST_USB_COMPLETION,
    FIRST_ETHERNET_RX,
    FIRST_IPV6_RX,
    PEER_MAC_LEARNED,
    FIRST_IPV6_TX_ATTEMPT,
    FIRST_IPV6_TX_SUCCESS,
    TX_NOT_READY,
    LINK_READY,
}

sealed interface NcmSendResult {
    data object Sent : NcmSendResult
    data object NotReady : NcmSendResult
    data class Failed(val error: Throwable) : NcmSendResult
}

internal data class NcmDiagnosticsSnapshot(
    val selection: String,
    val profile: NcmDiagnosticProfile,
    val attempt: Int,
    val statusEndpoint: Int?,
    val linkPhase: NcmLinkPhase,
    val rxProven: Boolean,
    val txProven: Boolean,
    val readState: PersistentUsbReadRequestState.State,
    val statusPollingEnabled: Boolean,
    val peerMacLearned: Boolean,
    val failure: String?,
    val readQueues: Long,
    val readCompletions: Long,
    val readTimeouts: Long,
    val readNullErrors: Long,
    val readQueueFailures: Long,
    val readUnexpectedCompletions: Long,
    val readBytes: Long,
    val readCancels: Long,
    val ethernetRxFrames: Long,
    val writeAttempts: Long,
    val writeSuccesses: Long,
    val writeNotReady: Long,
    val writePartialFailures: Long,
    val writeBytes: Long,
    val ipv6RxPackets: Long,
    val ipv6TxAttempts: Long,
    val ipv6TxSuccesses: Long,
    val ndpRxPackets: Long,
    val ndpTxAttempts: Long,
    val ndpTxSuccesses: Long,
    val statusPollAttempts: Long,
    val statusPollNoData: Long,
    val statusPollFailures: Long,
    val syncReadCalls: Long,
    val syncReadNoData: Long,
) {
    val ncmOutcome: NcmLinkOutcome
        get() = ncmLinkOutcome(rxProven, txProven)

    fun report(): String =
        "ncmOutcome=${ncmOutcome.name} profile=${profile.name} attempt=$attempt " +
            "statusEndpoint=${statusEndpoint?.let { "0x${it.toString(16)}" } ?: "none"} " +
            "statusPolling=$statusPollingEnabled preReadyOutTimeoutMs=${profile.preReadyOutTimeoutMillis} " +
            "readMode=${if (profile.synchronousBulkIn) "SYNC_BULK_IN" else "ASYNC_USB_REQUEST"} " +
            "readinessPhase=$linkPhase rxProven=$rxProven txProven=$txProven readState=$readState " +
            "peerMacLearned=$peerMacLearned failure=${failure ?: "none"} " +
            "readQueues=$readQueues readCompletions=$readCompletions readTimeouts=$readTimeouts " +
            "readNullErrors=$readNullErrors readQueueFailures=$readQueueFailures " +
            "readUnexpectedCompletions=$readUnexpectedCompletions readBytes=$readBytes " +
            "readCancels=$readCancels ethernetRxFrames=$ethernetRxFrames " +
            "writeAttempts=$writeAttempts writeSuccesses=$writeSuccesses " +
            "writeNotReady=$writeNotReady writePartialFailures=$writePartialFailures writeBytes=$writeBytes " +
            "ipv6RxPackets=$ipv6RxPackets ipv6TxAttempts=$ipv6TxAttempts " +
            "ipv6TxSuccesses=$ipv6TxSuccesses ndpRxPackets=$ndpRxPackets " +
            "ndpTxAttempts=$ndpTxAttempts ndpTxSuccesses=$ndpTxSuccesses " +
            "statusPollAttempts=$statusPollAttempts statusPollNoData=$statusPollNoData " +
            "statusPollFailures=$statusPollFailures syncReadCalls=$syncReadCalls syncReadNoData=$syncReadNoData " +
            "selected={$selection}"
}

internal data class NcmNtbParameters(
    val supportedFormats: Int,
    val ntbInMaxSize: Long,
    val ndpInDivisor: Int,
    val ndpInRemainder: Int,
    val ndpInAlignment: Int,
    val ntbOutMaxSize: Long,
    val ndpOutDivisor: Int,
    val ndpOutRemainder: Int,
    val ndpOutAlignment: Int,
    val ntbOutMaxDatagrams: Int,
) {
    fun summary(): String =
        "formats=0x${supportedFormats.toString(16)} ntb16Supported=${supportedFormats and 1 != 0} " +
            "ntbInMaxSize=$ntbInMaxSize ndpInDivisor=$ndpInDivisor " +
            "ndpInRemainder=$ndpInRemainder ndpInAlignment=$ndpInAlignment " +
            "ntbOutMaxSize=$ntbOutMaxSize ndpOutDivisor=$ndpOutDivisor " +
            "ndpOutRemainder=$ndpOutRemainder ndpOutAlignment=$ndpOutAlignment " +
            "ntbOutMaxDatagrams=$ntbOutMaxDatagrams readChunkBytes=${NcmUsbBridge.readChunkBytesForDiagnostics()}"
}

/**
 * A blocking NCM data pipe that moves Ethernet frames as NTB16 blocks over bulk endpoints.
 *
 * The caller opens the USB connection while the CarPlay configuration is already active; this
 * bridge claims only the NCM control/data interfaces and owns the connection thereafter. All
 * calls may block and must run away from the Android main thread.
 */
class NcmUsbBridge internal constructor(
    private val connection: UsbDeviceConnection,
    private val outEndpoint: UsbEndpoint,
    private val inEndpoint: UsbEndpoint,
    private val statusEndpoint: UsbEndpoint?,
    descriptorHostMac: ByteArray?,
    private val selectionDiagnostics: String,
    onDiagnosticEvent: (NcmDiagnosticEvent) -> Unit,
    val diagnosticProfile: NcmDiagnosticProfile,
    private val diagnosticAttempt: Int,
    private val diagnosticsEnabled: Boolean,
    private val onDiagnosticLog: (String) -> Unit,
) : Closeable {
    private val descriptorMac = descriptorHostMac?.copyOf()
    val hostMac: ByteArray? get() = descriptorMac?.copyOf()
    private val stateLock = Any()
    private val readLock = Any()
    private val writeLock = Any()
    private var closed = false
    private var failure: IphoneUsbException? = null
    private val readLifecycle = PersistentUsbReadRequestState<UsbRequest>()
    private var sequence = 0
    private val frames = ArrayDeque<ByteArray>()
    private var queuedBytes = 0
    private var buffered = ByteArray(0)
    private var bufferedSize = 0
    private val readBuffer = ByteArray(READ_CHUNK_BYTES)
    private val statusPollingEnabled = diagnosticProfile.shouldPollStatusEndpoint(statusEndpoint != null)
    // Bulk IN uses one persistent async request: bulkTransfer() pins its byte[] in a JNI critical
    // section for the whole wait, which blocks ART's GC thread flip and, with it, every other USB
    // transfer (seen as ~0.8 s stalls of video and audio). A timed-out request stays queued, so no
    // data is lost between calls. This is the only requestWait() user on this connection.
    private val directReadBuffer = ByteBuffer.allocateDirect(READ_CHUNK_BYTES)
    private val statusRunning = AtomicBoolean(statusPollingEnabled && statusEndpoint != null)
    @Volatile
    private var diagnosticEventListener: (NcmDiagnosticEvent) -> Unit = onDiagnosticEvent
    private val firstEvents = mutableSetOf<NcmDiagnosticEvent>()
    private val peerMacLearned = AtomicBoolean(false)
    private val linkReadiness = NcmLinkReadiness()
    private val readQueues = AtomicLong()
    private val readCompletions = AtomicLong()
    private val readTimeouts = AtomicLong()
    private val readNullErrors = AtomicLong()
    private val readQueueFailures = AtomicLong()
    private val readUnexpectedCompletions = AtomicLong()
    private val readBytes = AtomicLong()
    private val readCancels = AtomicLong()
    private val ethernetRxFrames = AtomicLong()
    private val writeAttempts = AtomicLong()
    private val writeSuccesses = AtomicLong()
    private val writeNotReady = AtomicLong()
    private val writePartialFailures = AtomicLong()
    private val writeBytes = AtomicLong()
    private val ipv6RxPackets = AtomicLong()
    private val ipv6TxAttempts = AtomicLong()
    private val ipv6TxSuccesses = AtomicLong()
    private val ndpRxPackets = AtomicLong()
    private val ndpTxAttempts = AtomicLong()
    private val ndpTxSuccesses = AtomicLong()
    private val statusPollAttempts = AtomicLong()
    private val statusPollNoData = AtomicLong()
    private val statusPollFailures = AtomicLong()
    private val syncReadCalls = AtomicLong()
    private val syncReadNoData = AtomicLong()
    private val startupTxLogCount = AtomicLong()
    private val statusThread = statusEndpoint?.takeIf { statusPollingEnabled }?.let { endpoint ->
        Thread({ drainStatus(endpoint) }, "ncm-status-in").apply {
            isDaemon = true
            start()
        }
    }

    /** Wraps one Ethernet frame in one NTB16 block and writes it to bulk OUT. */
    fun send(frame: ByteArray, timeoutMillis: Int): NcmSendResult = synchronized(writeLock) {
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        val startedAtNanos = System.nanoTime()
        val phase = linkReadiness.phase()
        linkReadiness.markOutboundAttempt()
        val ipv6 = EthernetIpv6Codec.parseIpv6View(frame)
        val packetType = if (diagnosticsEnabled) {
            ipv6?.let { EthernetIpv6Codec.packetType(frame, it.payloadOffset, it.payloadLength) } ?: "OTHER"
        } else {
            "OTHER"
        }
        val ndp = ipv6?.let {
            EthernetIpv6Codec.isNeighborDiscovery(frame, it.payloadOffset, it.payloadLength)
        } == true
        val attempt = writeAttempts.incrementAndGet()
        fun finish(result: NcmSendResult, resultOverride: String? = null): NcmSendResult {
            logStartupTx(
                phase,
                packetType,
                timeoutMillis,
                frame.size,
                attempt,
                startedAtNanos,
                result,
                resultOverride,
            )
            return result
        }
        if (ipv6 != null) {
            ipv6TxAttempts.incrementAndGet()
            if (ndp) ndpTxAttempts.incrementAndGet()
            emitFirst(NcmDiagnosticEvent.FIRST_IPV6_TX_ATTEMPT)
        }
        val sequence = try {
            synchronized(stateLock) {
                checkOpenLocked()
                this.sequence.also { this.sequence = (this.sequence + 1) and 0xffff }
            }
        } catch (error: Exception) {
            return@synchronized finish(NcmSendResult.Failed(error))
        }
        val block = try {
            Ntb16Codec.build(frame, sequence)
        } catch (error: Exception) {
            return@synchronized finish(NcmSendResult.Failed(error))
        }
        val transferred = try {
            connection.bulkTransfer(outEndpoint, block, block.size, timeoutMillis)
        } catch (error: RuntimeException) {
            writePartialFailures.incrementAndGet()
            return@synchronized finish(NcmSendResult.Failed(
                failSession("NCM_WRITE_ERROR: NCM bulk OUT failed", error),
            ))
        }
        if (transferred <= 0) {
            writeNotReady.incrementAndGet()
            emitFirst(NcmDiagnosticEvent.TX_NOT_READY)
            return@synchronized finish(NcmSendResult.NotReady)
        }
        if (transferred != block.size) {
            writePartialFailures.incrementAndGet()
            return@synchronized finish(
                NcmSendResult.Failed(
                    failSession("NCM_WRITE_ERROR: NCM write transferred $transferred of ${block.size} bytes"),
                ),
                resultOverride = "PARTIAL",
            )
        }
        writeSuccesses.incrementAndGet()
        writeBytes.addAndGet(transferred.toLong())
        if (ipv6 != null) {
            ipv6TxSuccesses.incrementAndGet()
            if (ndp) ndpTxSuccesses.incrementAndGet()
            emitFirst(NcmDiagnosticEvent.FIRST_IPV6_TX_SUCCESS)
            if (linkReadiness.recordOutboundIpv6Success()) emitFirst(NcmDiagnosticEvent.LINK_READY)
        }
        finish(NcmSendResult.Sent)
    }

    internal fun diagnosticSnapshot(): NcmDiagnosticsSnapshot = NcmDiagnosticsSnapshot(
        selection = selectionDiagnostics,
        profile = diagnosticProfile,
        attempt = diagnosticAttempt,
        statusEndpoint = statusEndpoint?.address,
        linkPhase = linkReadiness.phase(),
        rxProven = linkReadiness.hasRxProof(),
        txProven = linkReadiness.hasTxProof(),
        readState = readLifecycle.state(),
        statusPollingEnabled = statusPollingEnabled,
        peerMacLearned = peerMacLearned.get(),
        failure = synchronized(stateLock) { failure?.message?.take(200) },
        readQueues = readQueues.get(),
        readCompletions = readCompletions.get(),
        readTimeouts = readTimeouts.get(),
        readNullErrors = readNullErrors.get(),
        readQueueFailures = readQueueFailures.get(),
        readUnexpectedCompletions = readUnexpectedCompletions.get(),
        readBytes = readBytes.get(),
        readCancels = readCancels.get(),
        ethernetRxFrames = ethernetRxFrames.get(),
        writeAttempts = writeAttempts.get(),
        writeSuccesses = writeSuccesses.get(),
        writeNotReady = writeNotReady.get(),
        writePartialFailures = writePartialFailures.get(),
        writeBytes = writeBytes.get(),
        ipv6RxPackets = ipv6RxPackets.get(),
        ipv6TxAttempts = ipv6TxAttempts.get(),
        ipv6TxSuccesses = ipv6TxSuccesses.get(),
        ndpRxPackets = ndpRxPackets.get(),
        ndpTxAttempts = ndpTxAttempts.get(),
        ndpTxSuccesses = ndpTxSuccesses.get(),
        statusPollAttempts = statusPollAttempts.get(),
        statusPollNoData = statusPollNoData.get(),
        statusPollFailures = statusPollFailures.get(),
        syncReadCalls = syncReadCalls.get(),
        syncReadNoData = syncReadNoData.get(),
    )

    internal fun recordPeerMacLearned() {
        if (peerMacLearned.compareAndSet(false, true)) emitFirst(NcmDiagnosticEvent.PEER_MAC_LEARNED)
    }

    internal fun setDiagnosticEventListener(listener: (NcmDiagnosticEvent) -> Unit) {
        diagnosticEventListener = listener
    }

    internal fun linkPhase(): NcmLinkPhase = linkReadiness.phase()

    internal fun markCarPlayStartSent() {
        linkReadiness.markCarPlayStartSent()
    }

    internal fun markLinkReady() {
        if (linkReadiness.markLinkReady()) emitFirst(NcmDiagnosticEvent.LINK_READY)
    }

    private fun emitFirst(event: NcmDiagnosticEvent) {
        val first = synchronized(firstEvents) { firstEvents.add(event) }
        if (first) runCatching { diagnosticEventListener(event) }
    }

    private fun logStartupTx(
        phase: NcmLinkPhase,
        packetType: String,
        timeoutMillis: Int,
        bytes: Int,
        attempt: Long,
        startedAtNanos: Long,
        result: NcmSendResult,
        resultOverride: String?,
    ) {
        if (
            !diagnosticsEnabled || phase == NcmLinkPhase.NCM_LINK_READY ||
            startupTxLogCount.incrementAndGet() > MAX_STARTUP_TX_LOGS
        ) {
            return
        }
        val resultName = resultOverride ?: when (result) {
            NcmSendResult.Sent -> "SENT"
            NcmSendResult.NotReady -> "NOT_READY"
            is NcmSendResult.Failed -> "FAILED"
        }
        val errorType = (result as? NcmSendResult.Failed)?.error?.javaClass?.simpleName ?: "none"
        reportDiagnostic(
            "NCM_TX profile=${diagnosticProfile.name} phase=$phase packetType=$packetType " +
                "timeoutMs=$timeoutMillis bytes=$bytes attempt=$attempt result=$resultName errorType=$errorType " +
                "elapsedMs=${(System.nanoTime() - startedAtNanos) / NANOS_PER_MILLISECOND}",
        )
    }

    private fun reportDiagnostic(message: String) {
        if (!diagnosticsEnabled) return
        Log.i(IphoneCarPlayConfiguration.TAG, message)
        runCatching { onDiagnosticLog(message) }
    }

    /**
     * Returns the next complete Ethernet frame, or null when [timeoutMillis] elapses without one.
     * USB reads may split or coalesce NTB blocks; this method reassembles whole blocks internally.
     */
    fun recv(timeoutMillis: Long): ByteArray? {
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        synchronized(readLock) {
            checkOpen()
            if (frames.isNotEmpty()) return pollFrame()

            val deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLISECOND
            while (true) {
                drainFrames()
                if (frames.isNotEmpty()) return pollFrame()
                val remainingNanos = deadline - System.nanoTime()
                if (remainingNanos <= 0) return null
                val chunkLength = readChunk(
                    (remainingNanos + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND,
                )
                if (chunkLength == null) {
                    if (readLifecycle.isClosing()) return null
                    continue
                }
                appendBuffered(readBuffer, chunkLength)
            }
        }
    }

    override fun close() {
        statusRunning.set(false)
        val closePlan = synchronized(stateLock) {
            if (closed) return
            closed = true
            readLifecycle.beginClose().also { if (it.cancelQueuedRequest) readCancels.incrementAndGet() }
        }
        // Wakes a reader blocked in requestWait(); it then observes the closed state.
        if (closePlan.cancelQueuedRequest) runCatching { closePlan.request?.cancel() }
        statusThread?.let { thread ->
            thread.interrupt()
            try {
                thread.join(STATUS_POLL_TIMEOUT_MILLIS + 250L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        // Closing the connection wakes requestWait(); wait for recv() to leave before closing the
        // persistent request. UsbDeviceConnection.close() also releases its claimed interfaces.
        runCatching { connection.close() }
        synchronized(readLock) { }
        val requestToClose = synchronized(stateLock) { readLifecycle.takeForClose() }
        runCatching { requestToClose?.close() }
        val snapshot = diagnosticSnapshot()
        reportDiagnostic(
            "NCM_AB_RESULT ncmOutcome=${snapshot.ncmOutcome.name} profile=${snapshot.profile.name} " +
                "attempt=${snapshot.attempt} rxProven=${snapshot.rxProven} txProven=${snapshot.txProven} " +
                "readinessPhase=${snapshot.linkPhase} statusEndpoint=" +
                "${snapshot.statusEndpoint?.let { "0x${it.toString(16)}" } ?: "none"} " +
                "statusPolling=${snapshot.statusPollingEnabled} " +
                "preReadyOutTimeoutMs=${snapshot.profile.preReadyOutTimeoutMillis} " +
                "readMode=${if (snapshot.profile.synchronousBulkIn) "SYNC_BULK_IN" else "ASYNC_USB_REQUEST"} " +
                "readCompletions=${snapshot.readCompletions} readBytes=${snapshot.readBytes} " +
                "ipv6RxPackets=${snapshot.ipv6RxPackets} ipv6TxAttempts=${snapshot.ipv6TxAttempts} " +
                "ipv6TxSuccesses=${snapshot.ipv6TxSuccesses} failure=${snapshot.failure ?: "none"}",
        )
    }

    private fun drainStatus(endpoint: UsbEndpoint) {
        val buffer = ByteArray(endpoint.maxPacketSize.coerceAtLeast(64))
        reportDiagnostic(
            "NCM_STATUS profile=${diagnosticProfile.name} statusEndpoint=0x${endpoint.address.toString(16)} " +
                "statusPollStarted",
        )
        while (statusRunning.get()) {
            val attempt = statusPollAttempts.incrementAndGet()
            val transferred = try {
                connection.bulkTransfer(endpoint, buffer, buffer.size, STATUS_POLL_TIMEOUT_MILLIS)
            } catch (error: RuntimeException) {
                statusPollFailures.incrementAndGet()
                reportDiagnostic(
                    "NCM_STATUS profile=${diagnosticProfile.name} statusEndpoint=0x${endpoint.address.toString(16)} " +
                        "statusPollError=${error.javaClass.simpleName}",
                )
                return
            }
            if (transferred <= 0) {
                val noDataCount = statusPollNoData.incrementAndGet()
                if (noDataCount <= 3 || noDataCount % STATUS_POLL_LOG_EVERY == 0L) {
                    reportDiagnostic(
                        "NCM_STATUS profile=${diagnosticProfile.name} statusEndpoint=0x${endpoint.address.toString(16)} " +
                            "statusPollResult bytes=$transferred result=NO_DATA statusPollAttempt=$attempt " +
                            "statusPollNoData=$noDataCount",
                    )
                }
                try {
                    Thread.sleep(STATUS_POLL_INTERVAL_MILLIS)
                } catch (_: InterruptedException) {
                    return
                }
                continue
            }
            val prefixLength = minOf(transferred, MAX_STATUS_NOTIFICATION_HEX_BYTES)
            val notificationType = if (transferred >= 2) buffer[1].toInt() and 0xff else null
            val prefix = buffer.copyOfRange(0, prefixLength).joinToString("") {
                "%02x".format(it.toInt() and 0xff)
            }
            reportDiagnostic(
                "NCM_STATUS profile=${diagnosticProfile.name} statusEndpoint=0x${endpoint.address.toString(16)} " +
                    "statusPollResult bytes=$transferred notificationType=${notificationType ?: "unknown"} " +
                    "payloadPrefix=$prefix",
            )
        }
    }

    private fun drainFrames() {
        while (true) {
            if (bufferedSize < 12) return
            if (readU32(buffered, 0) != Ntb16Codec.NTH16_SIG) {
                throw failSession("NCM read buffer does not begin with an NTB16 header")
            }
            val blockLength = readU16(buffered, 8)
            if (blockLength < 28) throw failSession("Invalid NTB16 block length $blockLength")
            val padded = blockLength % USB_PACKET_SIZE == 0
            val wireLength = blockLength + if (padded) 1 else 0
            if (bufferedSize < wireLength) return
            if (padded && buffered[blockLength].toInt() != 0) {
                throw failSession("Invalid NTB16 short-packet pad")
            }
            for (frame in Ntb16Codec.parse(buffered, 0, blockLength)) enqueueFrame(frame)
            val remaining = bufferedSize - wireLength
            buffered.copyInto(buffered, 0, wireLength, bufferedSize)
            bufferedSize = remaining
        }
    }

    private fun appendBuffered(source: ByteArray, length: Int) {
        val required = bufferedSize + length
        if (required > buffered.size) {
            val capacity = maxOf(required, maxOf(READ_CHUNK_BYTES, buffered.size * 2))
            val grown = ByteArray(capacity)
            buffered.copyInto(grown, 0, 0, bufferedSize)
            buffered = grown
        }
        source.copyInto(buffered, bufferedSize, 0, length)
        bufferedSize += length
    }

    private fun enqueueFrame(frame: ByteArray) {
        if (frames.size >= MAX_QUEUED_FRAMES || queuedBytes + frame.size > MAX_QUEUED_BYTES) {
            throw failSession("NCM frame queue exceeded its bounds")
        }
        ethernetRxFrames.incrementAndGet()
        emitFirst(NcmDiagnosticEvent.FIRST_ETHERNET_RX)
        val ipv6 = EthernetIpv6Codec.parseIpv6View(frame)
        if (ipv6 != null) {
            ipv6RxPackets.incrementAndGet()
            emitFirst(NcmDiagnosticEvent.FIRST_IPV6_RX)
            if (linkReadiness.recordInboundIpv6()) emitFirst(NcmDiagnosticEvent.LINK_READY)
            if (EthernetIpv6Codec.isNeighborDiscovery(frame, ipv6.payloadOffset, ipv6.payloadLength)) {
                ndpRxPackets.incrementAndGet()
            }
        }
        frames.addLast(frame)
        queuedBytes += frame.size
    }

    private fun pollFrame(): ByteArray {
        val frame = frames.removeFirst()
        queuedBytes -= frame.size
        return frame
    }

    private fun readChunk(timeoutMillis: Long): Int? {
        if (diagnosticProfile.synchronousBulkIn) return readChunkSynchronously(timeoutMillis)
        var newlyQueued = false
        val request = try {
            synchronized(stateLock) {
                checkOpenLocked()
                val current = readLifecycle.requestOrCreate {
                    UsbRequest().also { created ->
                        val initialized = try {
                            created.initialize(connection, inEndpoint)
                        } catch (error: RuntimeException) {
                            runCatching { created.close() }
                            throw error
                        }
                        if (!initialized) {
                            runCatching { created.close() }
                            throw failSession("NCM_READ_ERROR: Android could not initialize the read request")
                        }
                    }
                }
                if (readLifecycle.needsQueue()) {
                    directReadBuffer.clear()
                    val queued = try {
                        current.queue(directReadBuffer)
                    } catch (error: RuntimeException) {
                        readQueueFailures.incrementAndGet()
                        readLifecycle.markQueueFailure()
                        throw failSession("NCM_QUEUE_ERROR: Android failed to queue the read request", error)
                    }
                    if (!queued) {
                        readQueueFailures.incrementAndGet()
                        readLifecycle.markQueueFailure()
                        throw failSession("NCM_QUEUE_ERROR: Android could not queue the read request")
                    }
                    readLifecycle.markQueued()
                    readQueues.incrementAndGet()
                    newlyQueued = true
                }
                current
            }
        } catch (error: RuntimeException) {
            if (readLifecycle.isClosing()) return null
            throw failSession("NCM_READ_ERROR: NCM read request setup failed", error)
        } catch (error: IphoneUsbException) {
            if ("NCM_QUEUE_ERROR" in error.message.orEmpty()) {
                reportDiagnostic(
                    "NCM_USB_READ profile=${diagnosticProfile.name} mode=ASYNC_USB_REQUEST " +
                        "readQueueResult=FAILED count=${readQueueFailures.get()}",
                )
            }
            throw error
        }
        if (newlyQueued) {
            emitFirst(NcmDiagnosticEvent.READ_QUEUED)
            reportDiagnostic(
                "NCM_USB_READ profile=${diagnosticProfile.name} mode=ASYNC_USB_REQUEST " +
                    "readQueueResult=QUEUED count=${readQueues.get()}",
            )
        }
        try {
            val completed = try {
                connection.requestWait(timeoutMillis.coerceAtLeast(1))
            } catch (_: TimeoutException) {
                val timeoutCount = synchronized(stateLock) {
                    if (readLifecycle.isClosing()) return null
                    readLifecycle.onTimeout()
                    readTimeouts.incrementAndGet()
                }
                if (timeoutCount <= 3L || timeoutCount % READ_DIAGNOSTIC_LOG_EVERY == 0L) {
                    reportDiagnostic(
                        "NCM_USB_READ profile=${diagnosticProfile.name} mode=ASYNC_USB_REQUEST " +
                            "requestWaitResult=TIMEOUT count=$timeoutCount",
                    )
                }
                return null
            }
            if (completed == null) {
                synchronized(stateLock) {
                    if (!readLifecycle.onNullResult()) return null
                    readNullErrors.incrementAndGet()
                }
                reportDiagnostic(
                    "NCM_USB_READ profile=${diagnosticProfile.name} mode=ASYNC_USB_REQUEST " +
                        "requestWaitResult=NULL_ERROR",
                )
                throw failSession("NCM_REQUEST_WAIT_ERROR: Android returned no NCM read request")
            }
            var firstCompletion = false
            val transferred = synchronized(stateLock) {
                if (readLifecycle.isClosing()) return null
                if (!readLifecycle.onCompletion(completed)) {
                    readUnexpectedCompletions.incrementAndGet()
                    throw failSession("NCM_REQUEST_WAIT_ERROR: Android completed an unexpected NCM request")
                }
                firstCompletion = readCompletions.incrementAndGet() == 1L
                directReadBuffer.position().also { readBytes.addAndGet(it.toLong()) }
            }
            if (firstCompletion) {
                emitFirst(NcmDiagnosticEvent.FIRST_USB_COMPLETION)
                reportDiagnostic(
                    "NCM_USB_READ profile=${diagnosticProfile.name} mode=ASYNC_USB_REQUEST " +
                        "requestWaitResult=COMPLETED bytes=$transferred",
                )
            }
            if (transferred <= 0) return null
            directReadBuffer.flip()
            directReadBuffer.get(readBuffer, 0, transferred)
            return transferred
        } catch (error: IphoneUsbException) {
            throw error
        } catch (error: RuntimeException) {
            if (readLifecycle.isClosing()) return null
            throw failSession("NCM_REQUEST_WAIT_ERROR: NCM read failed", error)
        }
    }

    private fun readChunkSynchronously(timeoutMillis: Long): Int? {
        val call = syncReadCalls.incrementAndGet()
        val boundedTimeout = timeoutMillis.coerceAtLeast(1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val startedAtNanos = System.nanoTime()
        val transferred = try {
            connection.bulkTransfer(inEndpoint, readBuffer, readBuffer.size, boundedTimeout)
        } catch (error: RuntimeException) {
            readNullErrors.incrementAndGet()
            reportDiagnostic(
                "NCM_USB_READ profile=${diagnosticProfile.name} mode=SYNC_BULK_IN " +
                    "bulkTransferResult=ERROR error=${error.javaClass.simpleName}",
            )
            throw failSession("NCM_SYNC_READ_ERROR: Android bulk IN failed", error)
        }
        if (readLifecycle.isClosing()) return null
        if (transferred <= 0) {
            val noData = syncReadNoData.incrementAndGet()
            readTimeouts.incrementAndGet()
            if (noData <= 3L || noData % READ_DIAGNOSTIC_LOG_EVERY == 0L) {
                reportDiagnostic(
                    "NCM_USB_READ profile=${diagnosticProfile.name} mode=SYNC_BULK_IN " +
                        "bulkTransferResult=$transferred result=NO_DATA count=$noData timeoutMs=$boundedTimeout " +
                        "elapsedMs=${(System.nanoTime() - startedAtNanos) / NANOS_PER_MILLISECOND}",
                )
            }
            return null
        }
        val firstCompletion = readCompletions.incrementAndGet() == 1L
        readBytes.addAndGet(transferred.toLong())
        if (firstCompletion) {
            emitFirst(NcmDiagnosticEvent.FIRST_USB_COMPLETION)
            reportDiagnostic(
                "NCM_USB_READ profile=${diagnosticProfile.name} mode=SYNC_BULK_IN " +
                    "bulkTransferResult=COMPLETED bytes=$transferred call=$call " +
                    "elapsedMs=${(System.nanoTime() - startedAtNanos) / NANOS_PER_MILLISECOND}",
            )
        }
        return transferred
    }

    private fun failSession(message: String, cause: Throwable? = null): IphoneUsbException.DeviceUnavailable {
        val error = IphoneUsbException.DeviceUnavailable(message, cause)
        synchronized(stateLock) {
            if (failure == null) {
                failure = error
                readLifecycle.markFailure()
            }
        }
        return error
    }

    private fun checkOpen() {
        synchronized(stateLock) { checkOpenLocked() }
    }

    private fun checkOpenLocked() {
        failure?.let { throw it }
        if (closed) throw IphoneUsbException.DeviceUnavailable("NCM bridge is closed")
    }

    private fun readU16(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xff) or ((source[offset + 1].toInt() and 0xff) shl 8)

    private fun readU32(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xff) or
            ((source[offset + 1].toInt() and 0xff) shl 8) or
            ((source[offset + 2].toInt() and 0xff) shl 16) or
            ((source[offset + 3].toInt() and 0xff) shl 24)

    companion object {
        private const val READ_CHUNK_BYTES = 32 * 1024
        private const val USB_PACKET_SIZE = 512
        private const val STATUS_POLL_TIMEOUT_MILLIS = 20
        private const val STATUS_POLL_INTERVAL_MILLIS = 500L
        private const val STATUS_POLL_LOG_EVERY = 20L
        private const val MAX_STATUS_NOTIFICATION_HEX_BYTES = 8
        private const val MAX_STARTUP_TX_LOGS = 8L
        private const val READ_DIAGNOSTIC_LOG_EVERY = 20L
        private const val MAX_QUEUED_FRAMES = 256
        private const val MAX_QUEUED_BYTES = 1 shl 20
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val USB_CONTROL_DIAGNOSTIC_TIMEOUT_MILLIS = 250
        private const val GET_NTB_PARAMETERS_REQUEST = 0x80
        private const val NTB_PARAMETERS_BYTES = 28
        private const val USB_TYPE_CLASS = 0x20
        private const val USB_RECIPIENT_INTERFACE = 0x01

        internal fun readChunkBytesForDiagnostics(): Int = READ_CHUNK_BYTES

        /** Claims and activates the NCM control/data interfaces; owns the connection on success. */
        fun open(
            connection: UsbDeviceConnection,
            function: NcmFunctionDiscovery.NcmFunction,
            selectionDiagnostics: String =
                "control=${function.control.id}/${function.control.alternateSetting} " +
                    "data=${function.data.id}/${function.data.alternateSetting}",
            onDiagnosticEvent: (NcmDiagnosticEvent) -> Unit = {},
            queryNtbParameters: Boolean = false,
            diagnosticProfile: NcmDiagnosticProfile = NcmDiagnosticProfile.AUTO,
            diagnosticAttempt: Int = 0,
            diagnosticsEnabled: Boolean = false,
            onDiagnosticLog: (String) -> Unit = {},
        ): NcmUsbBridge {
            val claimed = ArrayList<UsbInterface>(2)
            try {
                val statusAddress = function.statusIn?.address
                val statusPollingEnabled = diagnosticProfile.statusPolling && statusAddress != null
                val profileLine =
                    "NCM_AB profile=${diagnosticProfile.name} attempt=$diagnosticAttempt " +
                        "control=${function.control.id}/${function.control.alternateSetting} " +
                        "data=${function.data.id}/${function.data.alternateSetting} " +
                        "statusEndpoint=${statusAddress?.let { "0x${it.toString(16)}" } ?: "none"} " +
                        "bulkIn=0x${function.bulkIn.address.toString(16)} " +
                        "bulkOut=0x${function.bulkOut.address.toString(16)} " +
                        "statusPolling=$statusPollingEnabled " +
                        "readMode=${if (diagnosticProfile.synchronousBulkIn) "SYNC_BULK_IN" else "ASYNC_USB_REQUEST"} " +
                        "preReadyOutTimeoutMs=${diagnosticProfile.preReadyOutTimeoutMillis} " +
                        "selection={$selectionDiagnostics}"
                if (diagnosticsEnabled) {
                    Log.i(IphoneCarPlayConfiguration.TAG, profileLine)
                    runCatching { onDiagnosticLog(profileLine) }
                }
                val descriptorHostMac = readNcmHostMac(connection, function.control.id)
                Log.i(
                    IphoneCarPlayConfiguration.TAG,
                    "ncm descriptor hostMacPresent=${descriptorHostMac != null}",
                )
                // Apple's Ethernet function exposes control and data as alternate settings of the
                // same interface id, so it must be claimed once and switched with setInterface.
                val sameInterface = function.control.id == function.data.id
                val first = if (sameInterface) function.data else function.control
                val firstClaimed = connection.claimInterface(first, true)
                Log.i(
                    IphoneCarPlayConfiguration.TAG,
                    "claim iface=${first.id}/${first.alternateSetting} class=${first.interfaceClass}" +
                        " subclass=${first.interfaceSubclass} proto=${first.interfaceProtocol} ok=$firstClaimed",
                )
                if (!firstClaimed) {
                    throw IphoneUsbException.DeviceUnavailable(
                        "Android could not claim the NCM interface ${first.id}",
                    )
                }
                claimed.add(first)
                val ntbParameters = if (queryNtbParameters) {
                    readNtbParameters(connection, function.control.id)
                } else {
                    null
                }
                val ntbParametersSummary = when {
                    !queryNtbParameters -> "not-queried"
                    ntbParameters == null -> "unavailable"
                    else -> ntbParameters.summary()
                }
                if (!sameInterface) {
                    val dataClaimed = connection.claimInterface(function.data, true)
                    Log.i(
                        IphoneCarPlayConfiguration.TAG,
                        "claim iface=${function.data.id}/${function.data.alternateSetting}" +
                            " class=${function.data.interfaceClass} ok=$dataClaimed",
                    )
                    if (!dataClaimed) {
                        throw IphoneUsbException.DeviceUnavailable(
                            "Android could not claim the NCM data interface ${function.data.id}",
                        )
                    }
                    claimed.add(function.data)
                }
                val altSelected = connection.setInterface(function.data)
                Log.i(
                    IphoneCarPlayConfiguration.TAG,
                    "setInterface iface=${function.data.id}/${function.data.alternateSetting} ok=$altSelected",
                )
                if (!altSelected) {
                    throw IphoneUsbException.DeviceUnavailable(
                        "Android could not select the NCM data alternate setting",
                    )
                }
                Log.i(
                    IphoneCarPlayConfiguration.TAG,
                    "ncm status endpoint=${statusAddress?.let { "0x${it.toString(16)}" } ?: "none"}",
                )
                Log.i(
                    IphoneCarPlayConfiguration.TAG,
                    "ncm status polling=$statusPollingEnabled",
                )
                return NcmUsbBridge(
                    connection,
                    function.bulkOut,
                    function.bulkIn,
                    function.statusIn,
                    descriptorHostMac,
                    "$selectionDiagnostics ntbParameters=$ntbParametersSummary",
                    onDiagnosticEvent,
                    diagnosticProfile,
                    diagnosticAttempt,
                    diagnosticsEnabled,
                    onDiagnosticLog,
                )
            } catch (error: Throwable) {
                for (usbInterface in claimed.asReversed()) {
                    try {
                        connection.releaseInterface(usbInterface)
                    } catch (_: RuntimeException) {
                        // The connection close below is authoritative.
                    }
                }
                connection.close()
                if (error is IphoneUsbException) throw error
                throw IphoneUsbException.DeviceUnavailable("Android NCM open failed", error)
            }
        }

        internal fun parseNtbParameters(buffer: ByteArray, length: Int = buffer.size): NcmNtbParameters? {
            if (length < NTB_PARAMETERS_BYTES || buffer.size < NTB_PARAMETERS_BYTES) return null
            if (readU16(buffer, 0) < NTB_PARAMETERS_BYTES) return null
            return NcmNtbParameters(
                supportedFormats = readU16(buffer, 2),
                ntbInMaxSize = readU32(buffer, 4),
                ndpInDivisor = readU16(buffer, 8),
                ndpInRemainder = readU16(buffer, 10),
                ndpInAlignment = readU16(buffer, 12),
                ntbOutMaxSize = readU32(buffer, 16),
                ndpOutDivisor = readU16(buffer, 20),
                ndpOutRemainder = readU16(buffer, 22),
                ndpOutAlignment = readU16(buffer, 24),
                ntbOutMaxDatagrams = readU16(buffer, 26),
            )
        }

        private fun readNtbParameters(
            connection: UsbDeviceConnection,
            controlInterfaceId: Int,
        ): NcmNtbParameters? {
            val buffer = ByteArray(NTB_PARAMETERS_BYTES)
            val transferred = runCatching {
                connection.controlTransfer(
                    UsbConstants.USB_DIR_IN or USB_TYPE_CLASS or USB_RECIPIENT_INTERFACE,
                    GET_NTB_PARAMETERS_REQUEST,
                    0,
                    controlInterfaceId,
                    buffer,
                    buffer.size,
                    USB_CONTROL_DIAGNOSTIC_TIMEOUT_MILLIS,
                )
            }.getOrDefault(-1)
            return parseNtbParameters(buffer, transferred)
        }

        private fun readU16(source: ByteArray, offset: Int): Int =
            (source[offset].toInt() and 0xff) or ((source[offset + 1].toInt() and 0xff) shl 8)

        private fun readU32(source: ByteArray, offset: Int): Long =
            (source[offset].toLong() and 0xff) or
                ((source[offset + 1].toLong() and 0xff) shl 8) or
                ((source[offset + 2].toLong() and 0xff) shl 16) or
                ((source[offset + 3].toLong() and 0xff) shl 24)

        private fun readNcmHostMac(connection: UsbDeviceConnection, controlInterfaceId: Int): ByteArray? {
            val index = ethernetMacStringIndex(connection.rawDescriptors, controlInterfaceId) ?: return null
            val buffer = ByteArray(256)
            val length = connection.controlTransfer(
                UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_STANDARD,
                USB_REQUEST_GET_DESCRIPTOR,
                (USB_STRING_DESCRIPTOR_TYPE shl 8) or index,
                USB_ENGLISH_US,
                buffer,
                buffer.size,
                USB_CONTROL_TIMEOUT_MILLIS,
            )
            if (length < 4 || (buffer[1].toInt() and 0xff) != USB_STRING_DESCRIPTOR_TYPE) return null
            val descriptorLength = (buffer[0].toInt() and 0xff).coerceAtMost(length)
            if (descriptorLength < 4) return null
            val value = buffer.copyOfRange(2, descriptorLength).toString(Charsets.UTF_16LE)
            val hex = value.filter { it.digitToIntOrNull(16) != null }
            if (hex.length != 12) return null
            return ByteArray(6) { offset -> hex.substring(offset * 2, offset * 2 + 2).toInt(16).toByte() }
        }

        private fun ethernetMacStringIndex(raw: ByteArray, controlInterfaceId: Int): Int? {
            var offset = 0
            var currentInterface = -1
            while (offset + 2 <= raw.size) {
                val length = raw[offset].toInt() and 0xff
                val type = raw[offset + 1].toInt() and 0xff
                if (length < 2 || offset + length > raw.size) return null
                if (type == USB_INTERFACE_DESCRIPTOR_TYPE && length >= 9) {
                    currentInterface = raw[offset + 2].toInt() and 0xff
                } else if (
                    type == CDC_FUNCTIONAL_DESCRIPTOR_TYPE &&
                    length >= 4 &&
                    currentInterface == controlInterfaceId &&
                    (raw[offset + 2].toInt() and 0xff) == CDC_ETHERNET_SUBTYPE
                ) {
                    return (raw[offset + 3].toInt() and 0xff).takeIf { it != 0 }
                }
                offset += length
            }
            return null
        }

        private const val USB_INTERFACE_DESCRIPTOR_TYPE = 0x04
        private const val USB_REQUEST_GET_DESCRIPTOR = 0x06
        private const val USB_STRING_DESCRIPTOR_TYPE = 0x03
        private const val CDC_FUNCTIONAL_DESCRIPTOR_TYPE = 0x24
        private const val CDC_ETHERNET_SUBTYPE = 0x0f
        private const val USB_ENGLISH_US = 0x0409
        private const val USB_CONTROL_TIMEOUT_MILLIS = 1_000
    }
}
