package com.shilapi.xcertplay.dsp

import android.app.Application
import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidPlaybackVolumeMonitorRobolectricTest {
    @Test
    fun streamVolumeBroadcastRefreshesPlaybackReadingWithoutOpeningStudioUi() {
        val context: Application = RuntimeEnvironment.getApplication()
        val readings = mutableListOf<DspVolumeReading>()
        val monitor = AndroidPlaybackVolumeMonitor(context, readings::add)
        monitor.start()

        monitor.setPlaybackRoute(deviceType = 11, deviceAddress = "device", usesMusicStream = true)
        val audioManager = context.getSystemService(AudioManager::class.java)
        val maximum = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val nextIndex = if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) 1 else 0
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, nextIndex.coerceAtMost(maximum), 0)
        monitor.handleVolumeChanged(AudioManager.STREAM_MUSIC)

        assertEquals(2, readings.size)
        assertTrue(monitor.read() == readings.last())
        assertFalse(readings.last().trustworthy)
        assertFalse(readings.last().routeToken?.contains("device") == true)

        monitor.setPlaybackRoute(deviceType = 11, deviceAddress = "second-device", usesMusicStream = true)
        assertFalse(monitor.read().trustworthy)
    }
}
