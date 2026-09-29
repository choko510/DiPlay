package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.transport.IphoneUsbException
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.concurrent.TimeoutException

enum class ConnectionTraceStage {
    ATTEMPT_STARTED,
    MFI_READY,
    USB_DISCOVERED,
    USB_PERMISSION_GRANTED,
    USB_REENUMERATED,
    USB_IAP2_SESSION_OPENED,
    USBMUX_READY,
    PAIRING_READY,
    CARKIT_SERVICE_OPENED,
    NCM_ATTACHED,
    IAP2_IDENTIFICATION_ACCEPTED,
    MFI_AUTHENTICATED,
    CARPLAY_START_SENT,
    NCM_BRIDGE_STARTED,
    NCM_READ_QUEUED,
    NCM_FIRST_USB_COMPLETION,
    NCM_FIRST_ETHERNET_RX,
    NCM_FIRST_IPV6_RX,
    NCM_PEER_MAC_LEARNED,
    NCM_FIRST_IPV6_TX_ATTEMPT,
    NCM_FIRST_IPV6_TX_SUCCESS,
    NCM_TX_NOT_READY,
    WIRED_STARTUP_SLOW,
    WIRED_DEEP_RECOVERY_CANDIDATE,
    AIRPLAY_SESSION_ACTIVE,
    AIRPLAY_CONTROL_ACCEPTED,
    AIRPLAY_CONTROL_ENCRYPTION_STARTED,
    AIRPLAY_CONTROL_ENCRYPTED,
    AIRPLAY_EVENT_ACCEPTED,
    SCREEN_STREAM_OPENED,
    FIRST_FRAME_RENDERED,
    RETRY,
    CANCELLED,
    ERROR,
}

enum class ConnectionTraceError {
    USB,
    PERMISSION,
    PAIRING,
    MFI,
    NETWORK,
    CONTROL,
    AIRPLAY,
    DECODER,
    UNKNOWN,
    NCM_REQUEST_WAIT_ERROR,
    NCM_QUEUE_ERROR,
    USBMUX_TRANSPORT_ERROR,
    STARTUP_WATCHDOG_TIMEOUT,
    USER_OR_PHYSICAL_DISCONNECT,
    AIRPLAY_HANDSHAKE_ERROR,
}

data class ConnectionTraceEvent(
    val stage: ConnectionTraceStage,
    val elapsedMs: Long,
    val error: ConnectionTraceError? = null,
    val detail: String? = null,
)

internal fun connectionTraceElapsedMs(startedAtNanos: Long, nowNanos: Long): Long =
    ((nowNanos - startedAtNanos).coerceAtLeast(0L)) / NANOS_PER_MILLISECOND

internal fun classifyConnectionTraceError(error: Throwable): ConnectionTraceError {
    val causes = generateSequence(error) { it.cause }.toList()
    val names = causes.map { it.javaClass.simpleName.lowercase(Locale.US) }
    val messages = causes.mapNotNull { it.message?.lowercase(Locale.US) }

    if (causes.any { it is SecurityException || it is IphoneUsbException.PermissionDenied }) {
        return ConnectionTraceError.PERMISSION
    }

    val operationText = causes.flatMap { cause ->
        listOf(cause.javaClass.simpleName.lowercase(Locale.US), cause.message?.lowercase(Locale.US).orEmpty())
    }
    if (operationText.any {
            "lockdown" in it || "plist" in it || "startsession" in it || "startservice" in it ||
                "tls" in it || "ssl" in it
        }
    ) {
        return ConnectionTraceError.PAIRING
    }
    if (operationText.any { "ncm_request_wait_error" in it }) {
        return ConnectionTraceError.NCM_REQUEST_WAIT_ERROR
    }
    if (operationText.any { "ncm_queue_error" in it }) return ConnectionTraceError.NCM_QUEUE_ERROR
    if (operationText.any { "usbmux_transport_error" in it }) return ConnectionTraceError.USBMUX_TRANSPORT_ERROR
    if (operationText.any {
            "usbmux" in it || "ncm" in it || "usb host" in it || "usb-host" in it ||
                "usbhost" in it || "usb config" in it || "usb configuration" in it ||
                "iphone configuration" in it || "carplay configuration" in it
        }
    ) {
        return ConnectionTraceError.USB
    }
    if (operationText.any { "iap2" in it }) return ConnectionTraceError.CONTROL

    val usbError = causes.filterIsInstance<IphoneUsbException>().firstOrNull()
    if (usbError is IphoneUsbException.TimedOut) return ConnectionTraceError.CONTROL
    if (usbError != null) return ConnectionTraceError.USB

    if (causes.any { it is SocketTimeoutException || it is TimeoutException }) {
        return ConnectionTraceError.CONTROL
    }

    if (names.any { "decoder" in it } || messages.any { "decoder" in it || "codec" in it }) {
        return ConnectionTraceError.DECODER
    }
    if (names.any { "pair" in it || "lockdown" in it } || messages.any { "pair" in it || "lockdown" in it }) {
        return ConnectionTraceError.PAIRING
    }
    if (names.any { "mfi" in it || "auth" in it } || messages.any { "mfi" in it || "auth" in it }) {
        return ConnectionTraceError.MFI
    }
    if (names.any { "usb" in it || "iphone" in it || "ncm" in it } ||
        messages.any { "usb" in it || "iphone" in it || "ncm" in it }
    ) {
        return ConnectionTraceError.USB
    }
    if (names.any { "timeout" in it || "timedout" in it || "timed_out" in it } ||
        messages.any { "timeout" in it || "timed out" in it || "timed-out" in it }
    ) {
        return ConnectionTraceError.CONTROL
    }
    if (names.any { "airplay" in it || "rtsp" in it } || messages.any { "airplay" in it || "rtsp" in it }) {
        return ConnectionTraceError.AIRPLAY
    }
    if (names.any { "network" in it || "socket" in it || "vpn" in it } ||
        messages.any { "network" in it || "vpn" in it || "hotspot" in it }
    ) {
        return ConnectionTraceError.NETWORK
    }
    if (names.any { "iap2" in it || "control" in it } || messages.any { "iap2" in it || "control" in it }) {
        return ConnectionTraceError.CONTROL
    }
    return ConnectionTraceError.UNKNOWN
}

private const val NANOS_PER_MILLISECOND = 1_000_000L
