package com.shilapi.xcertplay

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

    @Test fun debugPreferenceSurvivesPreferenceReload() {
        val context = RuntimeEnvironment.getApplication()
        AirPlayPersistence.saveDebugLogsEnabled(context, true)

        assertTrue(AirPlayPersistence.loadDebugLogsEnabled(context))
        AirPlayPersistence.saveDebugLogsEnabled(context, false)
    }
}
