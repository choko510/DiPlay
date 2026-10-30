package com.shilapi.xcertplay.dsp

import android.app.Application
import android.content.Intent
import android.media.AudioManager
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidPlaybackVolumeMonitorRobolectricTest {
    @Test
    fun streamVolumeBroadcastRefreshesPlaybackReadingWithoutOpeningStudioUi() {
        val context: Application = RuntimeEnvironment.getApplication()
        val readings = mutableListOf<DspVolumeReading>()
        val action = "com.shilapi.xcertplay.test.VOLUME_CHANGED"
        val monitor = AndroidPlaybackVolumeMonitor(
            context,
            volumeChangedAction = action,
            onReadingChanged = readings::add,
        )
        monitor.start()

        monitor.setPlaybackRoute(deviceType = 11, deviceAddress = "device", usesMusicStream = true)
        val audioManager = context.getSystemService(AudioManager::class.java)
        val maximum = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val nextIndex = if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) 1 else 0
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, nextIndex.coerceAtMost(maximum), 0)
        context.sendBroadcast(
            Intent(action).setPackage(context.packageName)
                .putExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", AudioManager.STREAM_RING),
        )
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, readings.size)
        context.sendBroadcast(
            Intent(action).setPackage(context.packageName)
                .putExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", AudioManager.STREAM_MUSIC),
        )
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(2, readings.size)
        assertTrue(monitor.read() == readings.last())
        assertFalse(readings.last().trustworthy)
        assertFalse(readings.last().routeToken?.contains("device") == true)

        monitor.setPlaybackRoute(deviceType = 11, deviceAddress = "second-device", usesMusicStream = true)
        assertFalse(monitor.read().trustworthy)
        monitor.stop()
        context.sendBroadcast(
            Intent(action).setPackage(context.packageName)
                .putExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", AudioManager.STREAM_MUSIC),
        )
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(3, readings.size)
    }
}
