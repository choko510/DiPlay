package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import java.net.InetAddress
import org.junit.Assert.assertFalse
import org.junit.Test

class MicrophoneUplinkLifecycleTest {
    @Test
    fun closeBeforeStartPreventsAStaleUplinkFromStartingLater() {
        val uplink = MicrophoneUplink(
            MicrophoneConfig(
                audioType = "telephony",
                sampleRate = 16_000,
                channels = 1,
                payloadType = 100,
                frameMillis = 20,
                host = InetAddress.getLoopbackAddress(),
                port = 1,
                key = ByteArray(32),
                codec = AudioCodecKind.LPCM,
            ),
        )

        uplink.close()

        assertFalse(uplink.start())
    }
}
