package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.orchestration.ConnectionTraceEvent
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicLong

/** App-owned bounded trace storage. Controller callbacks only enqueue enum-derived records. */
internal class ConnectionTraceStore(private val directory: File) {
    private val executor = Executors.newSingleThreadExecutor(ThreadFactory { task ->
        Thread(task, "diplay-connection-trace").apply { isDaemon = true }
    })
    private val previousElapsed = LinkedHashMap<String, Long>()

    fun append(attemptId: String, sequence: Long, event: ConnectionTraceEvent) {
        val safeAttemptId = attemptId.takeIf { ATTEMPT_ID.matches(it) } ?: "attempt-unknown"
        runCatching {
            executor.execute {
                runCatching {
                    val previous = previousElapsed.put(safeAttemptId, event.elapsedMs)
                    if (previousElapsed.size > MAX_TRACKED_ATTEMPTS) {
                        previousElapsed.remove(previousElapsed.keys.first())
                    }
                    val delta = if (previous == null) event.elapsedMs else
                        (event.elapsedMs - previous).coerceAtLeast(0L)
                    appendLine(format(safeAttemptId, sequence, event, delta))
                }
            }
        }
    }

    fun snapshot(): String = runCatching {
        val result: Future<String> = executor.submit<String> {
            runCatching {
                REPORT_FILES.asReversed().mapNotNull { name ->
                    File(directory, name).takeIf(File::isFile)?.readText(Charsets.UTF_8)
                }.joinToString("")
            }.getOrDefault("")
        }
        result.get()
    }.getOrDefault("")

    fun reportSection(): String = buildString {
        appendLine("--- Connection trace history (captured while debug mode was enabled) ---")
        append(snapshot())
    }

    private fun appendLine(line: String) {
        runCatching {
            directory.mkdirs()
            val file = File(directory, CURRENT_FILE)
            if (file.length() + line.length + 1 > MAX_BYTES) rotate(file)
            file.appendText("$line\n", Charsets.UTF_8)
        }
    }

    private fun rotate(file: File) {
        for (index in ARCHIVE_FILES.lastIndex downTo 1) {
            val source = File(directory, ARCHIVE_FILES[index - 1])
            val destination = File(directory, ARCHIVE_FILES[index])
            if (source.exists()) {
                if (destination.exists()) destination.delete()
                source.renameTo(destination)
            }
        }
        val oldest = File(directory, ARCHIVE_FILES.first())
        if (oldest.exists()) oldest.delete()
        if (file.exists()) file.renameTo(oldest)
    }

    internal fun closeForTests() {
        executor.shutdown()
    }

    private fun format(
        attemptId: String,
        sequence: Long,
        event: ConnectionTraceEvent,
        deltaMs: Long,
    ): String = buildString {
        append("attempt=").append(attemptId)
        append(" seq=").append(sequence)
        append(" elapsed_ms=").append(event.elapsedMs.coerceAtLeast(0L))
        append(" delta_ms=").append(deltaMs)
        append(" stage=").append(event.stage.name)
        event.error?.let { append(" error=").append(it.name) }
    }

    companion object {
        private const val CURRENT_FILE = "connection-trace.log"
        private const val MAX_BYTES = 128 * 1024L
        private const val MAX_TRACKED_ATTEMPTS = 256
        private val ATTEMPT_ID = Regex("attempt-[a-z0-9-]{1,40}")
        private val ARCHIVE_FILES = listOf("connection-trace-1.log", "connection-trace-2.log", "connection-trace-3.log")
        private val REPORT_FILES = ARCHIVE_FILES.reversed() + CURRENT_FILE
        private val nextId = AtomicLong()

        @Volatile private var appStore: ConnectionTraceStore? = null

        fun shared(context: Context): ConnectionTraceStore = appStore ?: synchronized(this) {
            appStore ?: ConnectionTraceStore(File(context.applicationContext.filesDir, "logs")).also {
                appStore = it
            }
        }

        fun newAttemptId(): String = "attempt-${System.currentTimeMillis().toString(36)}-${nextId.incrementAndGet()}"
    }
}
