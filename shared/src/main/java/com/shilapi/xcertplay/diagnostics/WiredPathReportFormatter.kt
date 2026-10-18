package com.shilapi.xcertplay.diagnostics

internal object WiredPathReportFormatter {
    fun format(snapshot: WiredPathSnapshot): String = buildString {
        append("WIRED_PATH_V1 schema=1 runId=").append(snapshot.controllerRunId)
        append(" attempt=").append(snapshot.attempt)
        append(" attachGeneration=")
        if (snapshot.attachGeneration < 0) append("none") else append(snapshot.attachGeneration)
        append(" reason=").append(snapshot.reason.name)
        append(" elapsedMs=").append(snapshot.elapsedMillis())
        append(" timeBase=").append(if (snapshot.hasStartSessionTime()) "START_SESSION" else "ATTEMPT")
        append(" startSessionAtAttemptMs=")
        append(snapshot.firstElapsedMillis(WiredPathEvent.START_SESSION_SENT) ?: "none")
        for (event in WiredPathEvent.entries) {
            append(' ').append(event.logKey).append('=').append(snapshot.count(event)).append('@')
            val elapsed = snapshot.firstElapsedSinceStartSessionMillis(event)
                ?: snapshot.firstElapsedMillis(event)
            if (elapsed == null) append("none") else append(elapsed)
        }
    }
}
