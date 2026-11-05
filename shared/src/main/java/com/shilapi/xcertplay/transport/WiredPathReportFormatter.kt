package com.shilapi.xcertplay.transport

internal object WiredPathReportFormatter {
    fun format(snapshot: WiredPathSnapshot): String = buildString(REPORT_CAPACITY) {
        append("WIRED_PATH_V1 schema=1")
        append(" run=").append(snapshot.runId)
        append(" attempt=").append(snapshot.wiredStartupAttempt)
        append(" attach_generation=").append(snapshot.attachGeneration ?: "none")
        append(" reason=").append(snapshot.reason.key)
        append(" sealed=").append(if (snapshot.sealed) 1 else 0)
        append(" counter_overflow=").append(if (snapshot.counterOverflow) 1 else 0)
        append(" checksum=NOT_CHECKED")
        for (event in WiredPathEvent.entries) {
            val counter = snapshot.counter(event)
            append(' ').append(event.key).append('=').append(counter.count)
            append("_first_ms=").append(counter.firstElapsedMs ?: "none")
        }
    }

    private const val REPORT_CAPACITY = 1_536
}
