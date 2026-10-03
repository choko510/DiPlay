package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.transport.NcmSendResult

internal object NcmStartupNdpRetry {
    // Only NS/NA control frames are repeated after ambiguous OUT timeouts; TCP and UDP rely on their own behavior.
    private val BACKOFF_MILLIS = longArrayOf(50, 100, 200, 400)
    const val NOT_READY_TIMEOUT_MILLIS = 100
    const val NORMAL_TIMEOUT_MILLIS = 2_000

    fun send(
        startupNeighborDiscovery: Boolean,
        linkReady: Boolean,
        preReadyOutTimeoutMillis: Int = NOT_READY_TIMEOUT_MILLIS,
        isActive: () -> Boolean,
        sendOnce: (timeoutMillis: Int) -> NcmSendResult,
        pause: (delayMillis: Long) -> Unit,
    ): NcmSendResult {
        if (linkReady) return sendOnce(NORMAL_TIMEOUT_MILLIS)
        if (!startupNeighborDiscovery) return sendOnce(preReadyOutTimeoutMillis)

        for (retryIndex in 0..BACKOFF_MILLIS.size) {
            if (!isActive()) return NcmSendResult.NotReady
            when (val result = sendOnce(preReadyOutTimeoutMillis)) {
                NcmSendResult.Sent -> return result
                is NcmSendResult.Failed -> return result
                NcmSendResult.NotReady -> {
                    if (retryIndex == BACKOFF_MILLIS.size) return result
                    pause(BACKOFF_MILLIS[retryIndex])
                }
            }
        }
        return NcmSendResult.NotReady
    }
}
