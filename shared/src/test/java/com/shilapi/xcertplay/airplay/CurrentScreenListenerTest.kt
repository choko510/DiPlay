package com.shilapi.xcertplay.airplay

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Test

class CurrentScreenListenerTest {
    @Test
    fun replacedStreamCallbacksCannotReachTheSinkOrCloseTheSession() {
        val sink = RecordingSink()
        val current = AtomicBoolean(true)
        val closeCalls = AtomicInteger()
        val listener = currentScreenListener(
            type = MAIN_SCREEN_TYPE,
            sink = sink,
            isCurrent = current::get,
            onClosed = { closeCalls.incrementAndGet() },
        )
        val codecConfig = byteArrayOf(0x67, 0x42)
        val firstFrame = byteArrayOf(0, 0, 0, 1, 0x65)

        listener.onCodec(VideoCodec.H264)
        listener.onConfig(codecConfig)
        listener.onFrame(firstFrame)
        assertEquals(
            listOf(
                ScreenEvent.Codec(MAIN_SCREEN_TYPE, VideoCodec.H264),
                ScreenEvent.Config(MAIN_SCREEN_TYPE, codecConfig.toList()),
                ScreenEvent.Frame(MAIN_SCREEN_TYPE, firstFrame.toList()),
            ),
            sink.events.toList(),
        )

        current.set(false)
        listener.onCodec(VideoCodec.H265)
        listener.onConfig(byteArrayOf(0x68, 0xce.toByte()))
        listener.onFrame(byteArrayOf(0, 0, 0, 1, 0x41))
        listener.onClosed(IOException("replaced stream closed"))

        assertEquals(3, sink.events.size)
        assertEquals(0, closeCalls.get())
    }

    @Test
    fun currentStreamCloseIsDelegatedOnce() {
        val sink = RecordingSink()
        val closeCalls = AtomicInteger()
        val listener = currentScreenListener(
            type = MAIN_SCREEN_TYPE,
            sink = sink,
            isCurrent = { true },
            onClosed = { closeCalls.incrementAndGet() },
        )

        listener.onClosed(null)

        assertEquals(1, closeCalls.get())
    }

    private sealed interface ScreenEvent {
        data class Codec(val type: Int, val codec: VideoCodec) : ScreenEvent
        data class Config(val type: Int, val bytes: List<Byte>) : ScreenEvent
        data class Frame(val type: Int, val bytes: List<Byte>) : ScreenEvent
    }

    private class RecordingSink : MediaSink {
        val events = CopyOnWriteArrayList<ScreenEvent>()

        override fun onVideoCodec(type: Int, codec: VideoCodec) {
            events += ScreenEvent.Codec(type, codec)
        }

        override fun onVideoConfig(type: Int, codecData: ByteArray) {
            events += ScreenEvent.Config(type, codecData.toList())
        }

        override fun onVideoFrame(type: Int, naluBytes: ByteArray) {
            events += ScreenEvent.Frame(type, naluBytes.toList())
        }
    }

    private companion object {
        const val MAIN_SCREEN_TYPE = 110
    }
}
