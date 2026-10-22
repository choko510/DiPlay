package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.orchestration.ConnectionTraceEvent
import com.shilapi.xcertplay.orchestration.ConnectionTraceStage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ConnectionTraceStoreTest {
    @Test fun snapshotPreservesCallbackOrderAndAddsReportHeader() {
        val store = ConnectionTraceStore(File(RuntimeEnvironment.getApplication().cacheDir, "trace-order"))
        store.append("attempt-test-1", 1, ConnectionTraceEvent(ConnectionTraceStage.ATTEMPT_STARTED, 0))
        store.append("attempt-test-1", 2, ConnectionTraceEvent(ConnectionTraceStage.MFI_READY, 37))

        val report = store.reportSection()
        assertTrue(report.contains("Connection trace history"))
        assertTrue(report.indexOf("seq=1") < report.indexOf("seq=2"))
        assertTrue(report.contains("elapsed_ms=37 delta_ms=37 stage=MFI_READY"))
        store.closeForTests()
    }

    @Test fun rotatesOldRecordsAndRejectsFreeFormAttemptIdentifiers() {
        val directory = File(RuntimeEnvironment.getApplication().cacheDir, "trace-rotation").apply {
            deleteRecursively()
        }
        val store = ConnectionTraceStore(directory)
        repeat(1_800) { index ->
            store.append(
                "phone-name=SensitiveDevice",
                index.toLong(),
                ConnectionTraceEvent(ConnectionTraceStage.USB_DISCOVERED, index.toLong()),
            )
        }

        val snapshot = store.snapshot()
        assertTrue(snapshot.contains("seq=1799"))
        assertTrue(snapshot.contains("attempt-unknown"))
        assertFalse(snapshot.contains("SensitiveDevice"))
        val traceFiles = directory.listFiles().orEmpty().filter { it.name.startsWith("connection-trace") }
        assertTrue(traceFiles.size >= 2)
        assertTrue(traceFiles.sumOf(File::length) <= 4 * 128 * 1024L)
        store.closeForTests()
    }

    @Test fun reportPersistsBoundedStartupDiagnosticDetailsOnTheTraceEvent() {
        val store = ConnectionTraceStore(File(RuntimeEnvironment.getApplication().cacheDir, "trace-detail"))
        store.append(
            "attempt-test-detail",
            1,
            ConnectionTraceEvent(
                ConnectionTraceStage.WIRED_STARTUP_SLOW,
                8_000,
                detail = "ncmReadQueued=true\nreadNullErrors=0",
            ),
        )

        val report = store.reportSection()
        assertTrue(report.contains("stage=WIRED_STARTUP_SLOW"))
        assertTrue(report.contains("detail=ncmReadQueued=true readNullErrors=0"))
        assertFalse(report.contains("\nreadNullErrors=0"))
        store.closeForTests()
    }

    @Test fun wiredPathReportsUseTheirOwnFixedLineFormat() {
        val directory = File(
            RuntimeEnvironment.getApplication().cacheDir,
            "trace-wired-path-${System.nanoTime()}",
        )
        val store = ConnectionTraceStore(directory)
        val report = validWiredPathReport()
        try {
            store.appendWiredPathReport(report)
            store.appendWiredPathReport("WIRED_PATH_V1 schema=1 payload=fixture with spaces")

            val snapshot = store.snapshot()
            assertTrue(snapshot.contains(report))
            assertFalse(snapshot.contains("payload=fixture"))
        } finally {
            store.closeForTests()
        }
    }

    @Test fun wiredPathReportRejectsUnknownFieldsAndInvalidCounterValues() {
        val directory = File(
            RuntimeEnvironment.getApplication().cacheDir,
            "trace-wired-path-allowlist-${System.nanoTime()}",
        )
        val store = ConnectionTraceStore(directory)
        val valid = validWiredPathReport()
        try {
            store.appendWiredPathReport(valid.replace("checksum=NOT_CHECKED", "checksum=NOT_CHECKED token=example-token"))
            store.appendWiredPathReport(valid.replace("checksum=NOT_CHECKED", "checksum=NOT_CHECKED ssid=example-ssid"))
            store.appendWiredPathReport(valid.replace("start=0", "start=-1"))
            store.appendWiredPathReport(valid)

            val snapshot = store.snapshot()
            assertTrue(snapshot.contains(valid))
            assertFalse(snapshot.contains("example-token"))
            assertFalse(snapshot.contains("example-ssid"))
        } finally {
            store.closeForTests()
        }
    }

    private fun validWiredPathReport(): String {
        val eventKeys = listOf(
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
        val fields = mutableListOf(
            "run=run-fixture",
            "attempt=1",
            "attach_generation=7",
            "reason=START",
            "sealed=0",
            "counter_overflow=0",
            "checksum=NOT_CHECKED",
        )
        for (event in eventKeys) {
            fields += "$event=0"
            fields += "${event}_first_ms=none"
        }
        fields[7] = "start=1"
        fields[8] = "start_first_ms=12"
        return "WIRED_PATH_V1 schema=1 ${fields.joinToString(" ")}"
    }

    @Test fun debugPreferenceDefaultsOffAndCanBeReturnedToOff() {
        val context = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
        val hadSavedValue = preferences.contains("debug_logs_enabled")
        val savedValue = preferences.getBoolean("debug_logs_enabled", false)

        try {
            preferences.edit().remove("debug_logs_enabled").commit()
            assertFalse(AirPlayPersistence.loadDebugLogsEnabled(context))
            AirPlayPersistence.saveDebugLogsEnabled(context, true)
            assertTrue(AirPlayPersistence.loadDebugLogsEnabled(context))
            AirPlayPersistence.saveDebugLogsEnabled(context, false)
            assertFalse(AirPlayPersistence.loadDebugLogsEnabled(context))
        } finally {
            val editor = preferences.edit()
            if (hadSavedValue) editor.putBoolean("debug_logs_enabled", savedValue)
            else editor.remove("debug_logs_enabled")
            editor.commit()
        }
    }
}
