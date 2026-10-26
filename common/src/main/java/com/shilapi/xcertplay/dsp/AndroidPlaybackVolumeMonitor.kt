package com.shilapi.xcertplay.dsp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import java.security.MessageDigest

internal class AndroidPlaybackVolumeMonitor(
    context: Context,
    private val onReadingChanged: (DspVolumeReading) -> Unit,
) : DspVolumeSource {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private var route: PlaybackRoute? = null
    private var started = false

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1)?.let(::handleVolumeChanged)
        }
    }

    @Synchronized
    fun start() {
        if (started) return
        val filter = IntentFilter(ACTION_VOLUME_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            appContext.registerReceiver(volumeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(volumeReceiver, filter)
        }
        started = true
    }

    @Synchronized
    fun stop() {
        if (!started) return
        runCatching { appContext.unregisterReceiver(volumeReceiver) }
        started = false
    }

    @Synchronized
    fun setPlaybackRoute(deviceType: Int?, deviceAddress: String?, usesMusicStream: Boolean) {
        val nextRoute = PlaybackRoute(deviceType, deviceAddress, usesMusicStream)
        route = nextRoute
        refresh()
    }

    @Synchronized
    internal fun handleVolumeChanged(streamType: Int) {
        if (streamType != AudioManager.STREAM_MUSIC) return
        refresh()
    }

    @Synchronized
    internal fun refresh() {
        onReadingChanged(read())
    }

    @Synchronized
    override fun read(): DspVolumeReading {
        val activeRoute = route ?: return DspVolumeReading()
        val manager = audioManager ?: return DspVolumeReading()
        val index = currentStreamIndex() ?: return DspVolumeReading()
        val maximum = runCatching { manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrNull()
            ?: return DspVolumeReading()
        val fixed = runCatching { manager.isVolumeFixed }.getOrDefault(true)
        return playbackVolumeReading(
            deviceType = activeRoute.deviceType,
            deviceAddress = activeRoute.deviceAddress,
            usesMusicStream = activeRoute.usesMusicStream,
            streamIndex = index,
            streamMaximum = maximum,
            volumeFixed = fixed,
        )
    }

    private fun currentStreamIndex(): Int? =
        runCatching { audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrNull()

    private data class PlaybackRoute(
        val deviceType: Int?,
        val deviceAddress: String?,
        val usesMusicStream: Boolean,
    )

    private companion object {
        const val ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
        const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
    }
}

internal fun playbackVolumeReading(
    deviceType: Int?,
    deviceAddress: String?,
    usesMusicStream: Boolean,
    streamIndex: Int,
    streamMaximum: Int,
    volumeFixed: Boolean,
): DspVolumeReading {
    if (!usesMusicStream || deviceType == null || deviceAddress.isNullOrBlank() ||
        streamMaximum <= 0 || volumeFixed
    ) {
        return DspVolumeReading()
    }
    val identity = "$deviceType:$deviceAddress".toByteArray(Charsets.UTF_8)
    val routeToken = MessageDigest.getInstance("SHA-256").digest(identity)
        .take(16)
        .joinToString("") { "%02x".format(it) }
    return DspVolumeReading(
        routeToken = routeToken,
        volumeFraction = streamIndex.coerceIn(0, streamMaximum).toDouble() / streamMaximum,
        trustworthy = false,
    )
}
