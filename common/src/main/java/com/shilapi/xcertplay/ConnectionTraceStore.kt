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

    fun appendWiredPathReport(report: String) {
        if (!isValidWiredPathReport(report)) return
        runCatching {
            executor.execute {
                runCatching { appendLine(report) }
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
        appendLine("NCM_ATTACHED marks VPN/TUN, packet bridge, and AirPlay listener setup; NCM_FIRST_* marks observed link traffic.")
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
        event.detail?.let {
            val safeDetail = it.replace(Regex("[\\r\\n]+"), " ").take(MAX_DETAIL_CHARS)
            append(" detail=").append(safeDetail)
        }
    }

    companion object {
        private const val CURRENT_FILE = "connection-trace.log"
        private const val MAX_BYTES = 128 * 1024L
        private const val MAX_DETAIL_CHARS = 4_096
        private const val MAX_WIRED_PATH_REPORT_CHARS = 4_096
        private const val MAX_TRACKED_ATTEMPTS = 256
        private val ATTEMPT_ID = Regex("attempt-[a-z0-9-]{1,40}")
        private val WIRED_PATH_RUN_ID = Regex("[A-Za-z0-9_-]{1,48}")
        private val WIRED_PATH_REASONS = setOf("START", "SOFT_8S", "HARD_15S", "ACCEPT", "RECORD", "MAIN", "FINAL")
        private val WIRED_PATH_EVENT_KEYS = listOf(
            "start",
            "ncm_usb_queue",
            "ncm_usb_completion",
            "ncm_ethernet_complete",
            "ncm_ethernet_delivered",
            "v4_syn_to_airplay",
            "v6_syn_to_airplay",
            "v6_syn_dest_match",
            "v6_syn_dest_mismatch",
            "v6_syn_dest_unverified",
            "syn_tun_write_success",
            "syn_tun_write_failure",
            "tcp_syn_ack_from_tun",
            "tcp_rst_from_tun",
            "peer_mac_drop",
            "syn_ack_ncm_sent",
            "syn_ack_ncm_not_ready",
            "syn_ack_ncm_failed",
            "first_bulk_out_full_sent",
            "vpn_established",
            "listener_bound",
            "listener_accepted",
            "record_active",
            "main_setup",
            "alt_setup",
            "main_first_frame",
            "invalid_packet",
            "unsupported_packet",
            "unknown_packet",
            "stale_callback",
        )
        private val ARCHIVE_FILES = listOf("connection-trace-1.log", "connection-trace-2.log", "connection-trace-3.log")
        private val REPORT_FILES = ARCHIVE_FILES.reversed() + CURRENT_FILE
        private val nextId = AtomicLong()

        private fun isValidWiredPathReport(report: String): Boolean {
            if (report.length > MAX_WIRED_PATH_REPORT_CHARS) return false
            val fields = report.split(' ')
            if (fields.size < 2 || fields[0] != "WIRED_PATH_V1" || fields[1] != "schema=1") return false

            var index = 2
            fun readValue(expectedKey: String): String? {
                val field = fields.getOrNull(index++) ?: return null
                val separator = field.indexOf('=')
                if (separator <= 0 || separator != field.lastIndexOf('=')) return null
                if (field.substring(0, separator) != expectedKey) return null
                return field.substring(separator + 1).takeIf(String::isNotEmpty)
            }

            if (readValue("run")?.matches(WIRED_PATH_RUN_ID) != true) return false
            if (readValue("attempt")?.toIntOrNull()?.let { it > 0 } != true) return false
            val generation = readValue("attach_generation") ?: return false
            if (generation != "none" && generation.toLongOrNull()?.let { it >= 0L } != true) return false
            val reason = readValue("reason") ?: return false
            if (reason !in WIRED_PATH_REASONS) return false
            val sealed = readValue("sealed") ?: return false
            if (sealed != "0" && sealed != "1") return false
            val counterOverflow = readValue("counter_overflow") ?: return false
            if (counterOverflow != "0" && counterOverflow != "1") return false
            if (readValue("checksum") != "NOT_CHECKED") return false

            for (event in WIRED_PATH_EVENT_KEYS) {
                val count = readValue(event)?.toLongOrNull()?.takeIf { it >= 0L } ?: return false
                val first = readValue("${event}_first_ms") ?: return false
                val firstElapsed = if (first == "none") null else first.toLongOrNull()?.takeIf { it >= 0L } ?: return false
                if ((count == 0L) != (firstElapsed == null)) return false
            }
            return index == fields.size
        }

        @Volatile private var appStore: ConnectionTraceStore? = null

        fun shared(context: Context): ConnectionTraceStore = appStore ?: synchronized(this) {
            appStore ?: ConnectionTraceStore(File(context.applicationContext.filesDir, "logs")).also {
                appStore = it
            }
        }

        fun newAttemptId(): String = "attempt-${System.currentTimeMillis().toString(36)}-${nextId.incrementAndGet()}"
    }
}
