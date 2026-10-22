package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.transport.WiredPathDiagnostics
import com.shilapi.xcertplay.transport.WiredPathEvent
import com.shilapi.xcertplay.transport.WiredPathReportReason
import com.shilapi.xcertplay.orchestration.ConnectionTraceStage
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class WiredPathAirPlayDiagnosticsTest {
    @Test
    fun mainAndAltSetupAndMainFirstFrameAreSeparateMilestones() {
        val diagnostics = WiredPathDiagnostics("run-airplay", 1, enabled = true)
        diagnostics.recordAttachGeneration(42)
        val stages = CopyOnWriteArrayList<ConnectionTraceStage>()
        val openedTypes = CopyOnWriteArrayList<Int>()
        val session = AirPlaySession(
            socket = Socket(),
            config = AirPlayConfig(
                deviceName = "fixture",
                deviceId = "02:00:00:00:00:02",
                btMac = "02:00:00:00:00:01",
                sourceVersion = "1.0",
                main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
            ),
            identity = AirPlayIdentity.generate(),
            pairings = PairingStore(),
            mfi = null,
            listener = object : AirPlaySessionListener {
                override fun onConnectionTrace(stage: ConnectionTraceStage) {
                    stages += stage
                }

                override fun onScreenStreamOpened(session: AirPlaySession, streamType: Int) {
                    openedTypes += streamType
                }
            },
            media = object : AirPlayMediaHandler {
                override fun onScreen(session: AirPlaySession, type: Int, stream: Map<String, Any?>): Int = 7001
            },
            wiredPathDiagnostics = diagnostics,
            attachGeneration = 42,
        )

        try {
            val handleStreams = AirPlaySession::class.java.getDeclaredMethod("handleStreams", List::class.java)
                .apply { isAccessible = true }
            handleStreams.invoke(
                session,
                listOf(mapOf("type" to ALT_SCREEN, "streamConnectionID" to 2L)),
            )
            assertEquals(emptyList<ConnectionTraceStage>(), stages.toList())
            assertEquals(listOf(ALT_SCREEN), openedTypes.toList())
            session.videoFrameSubmittedToSurface(ALT_SCREEN)
            assertEquals(emptyList<ConnectionTraceStage>(), stages.toList())

            handleStreams.invoke(session, listOf(mapOf("type" to MAIN_SCREEN, "streamConnectionID" to 1L)))
            assertEquals(listOf(ConnectionTraceStage.SCREEN_STREAM_OPENED), stages.toList())
            assertEquals(listOf(ALT_SCREEN, MAIN_SCREEN), openedTypes.toList())
            session.videoFrameSubmittedToSurface(MAIN_SCREEN)
            session.videoFrameSubmittedToSurface(MAIN_SCREEN)

            val snapshot = diagnostics.snapshot(WiredPathReportReason.MAIN)
            assertEquals(1L, snapshot.counter(WiredPathEvent.MAIN_SETUP).count)
            assertEquals(1L, snapshot.counter(WiredPathEvent.ALT_SETUP).count)
            assertEquals(1L, snapshot.counter(WiredPathEvent.MAIN_FIRST_FRAME).count)
        } finally {
            session.close()
        }
    }

    private companion object {
        const val MAIN_SCREEN = 110
        const val ALT_SCREEN = 111
    }
}
