package com.shilapi.xcertplay.orchestration

internal class WirelessTeardownDispatcher {
    fun dispatch(action: () -> Unit) {
        Thread(action, THREAD_NAME).apply {
            isDaemon = true
            start()
        }
    }

    private companion object {
        const val THREAD_NAME = "xcertplay-wireless-teardown"
    }
}
