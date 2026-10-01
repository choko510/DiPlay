package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairVerifyIdentityResolutionTest {
    @Test
    fun identityRemainsPendingBeforeTheFinalPairVerifyMessage() {
        val verify = newPairVerify()

        verify.handle(stateMessage(1))

        assertFalse(verify.isIdentityVerificationResolved)
        assertFalse(verify.isVerified)
    }

    @Test
    fun finalPairVerifyFailureStillMarksIdentityAsResolved() {
        val verify = newPairVerify()

        verify.handle(stateMessage(3))

        assertTrue(verify.isIdentityVerificationResolved)
        assertFalse(verify.isVerified)
        assertTrue(verify.verifiedControllerId == null)
    }

    private fun newPairVerify() = PairVerify(
        identity = AirPlayIdentity(ByteArray(32), ByteArray(32), "example-accessory"),
        pairings = PairingStore(),
    )

    private fun stateMessage(state: Int): ByteArray = Tlv8Codec.encode(
        listOf(Tlv8Item(type = 0x06, value = byteArrayOf(state.toByte()))),
    )
}
