package com.shilapi.xcertplay.step4

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.DataInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TunSocketPairInstrumentedTest {
    @Test
    fun parcelFileDescriptorSocketPairCarriesPacketsInBothDirections() {
        val (bridgeSide, peerSide) = ParcelFileDescriptor.createSocketPair()
        val bridgeInput = ParcelFileDescriptor.AutoCloseInputStream(bridgeSide.dup())
        val bridgeOutput = ParcelFileDescriptor.AutoCloseOutputStream(bridgeSide.dup())
        val peerInput = ParcelFileDescriptor.AutoCloseInputStream(peerSide.dup())
        val peerOutput = ParcelFileDescriptor.AutoCloseOutputStream(peerSide.dup())

        try {
            val fromPeer = byteArrayOf(0x60, 0, 0, 0, 0, 1, 59, 64)
            val fromBridge = byteArrayOf(0x60, 0, 0, 0, 0, 2, 59, 64)
            val buffer = ByteArray(8)
            peerOutput.write(fromPeer)
            DataInputStream(bridgeInput).readFully(buffer)
            assertArrayEquals(fromPeer, buffer)
            bridgeOutput.write(fromBridge)
            DataInputStream(peerInput).readFully(buffer)
            assertArrayEquals(fromBridge, buffer)
        } finally {
            bridgeInput.close()
            bridgeOutput.close()
            peerInput.close()
            peerOutput.close()
            bridgeSide.close()
            peerSide.close()
        }
    }
}
