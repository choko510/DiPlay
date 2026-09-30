package com.shilapi.xcertplay.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class P2pGroupIdentityPolicyTest {
    private val systemGroup = P2pGroupIdentity(
        networkName = "DIRECT-ab",
        passphrase = "system-generated-password",
        ownerAddress = "02:11:22:33:44:55",
        interfaceName = "p2p0",
    )

    @Test
    fun systemDefaultNeedsSuccessfulCreationAndAnEmptyPreCreateState() {
        val current = P2pGroupObservation(isGroupOwner = true, identity = systemGroup)
        assertFalse(P2pGroupIdentityPolicy.isCreationCandidate(false, true, true, null, null, current))
        assertFalse(P2pGroupIdentityPolicy.isCreationCandidate(true, false, true, null, null, current))
        assertFalse(P2pGroupIdentityPolicy.isCreationCandidate(true, true, false, null, null, current))
        assertFalse(P2pGroupIdentityPolicy.isCreationCandidate(true, true, true, null, null,
            current.copy(isGroupOwner = false)))
        assertTrue(P2pGroupIdentityPolicy.isCreationCandidate(true, true, true, null, null, current))
    }

    @Test
    fun systemDefaultIdentityRequiresTwoMatchingOwnerObservations() {
        val current = P2pGroupObservation(isGroupOwner = true, identity = systemGroup)
        assertFalse(P2pGroupIdentityPolicy.confirmsSystemDefault(null, current))
        assertFalse(P2pGroupIdentityPolicy.confirmsSystemDefault(systemGroup.copy(passphrase = "changed"), current))
        assertTrue(P2pGroupIdentityPolicy.confirmsSystemDefault(systemGroup, current))
        assertFalse(P2pGroupIdentityPolicy.confirmsSystemDefault(systemGroup,
            current.copy(isGroupOwner = false)))
    }

    @Test
    fun customGroupMustMatchTheRequestedNameAndPassphrase() {
        val current = P2pGroupObservation(isGroupOwner = true, identity = systemGroup)
        assertTrue(P2pGroupIdentityPolicy.isCreationCandidate(true, true, true, "DIRECT-ab",
            "system-generated-password", current))
        assertFalse(P2pGroupIdentityPolicy.isCreationCandidate(true, true, true, "DIRECT-other",
            "system-generated-password", current))
        assertFalse(P2pGroupIdentityPolicy.isCreationCandidate(true, true, true, "DIRECT-ab",
            "different-password", current))
    }

    @Test
    fun cleanupRequiresTheSameObservedGroupIdentity() {
        val current = P2pGroupObservation(isGroupOwner = true, identity = systemGroup)
        assertTrue(P2pGroupIdentityPolicy.matches(systemGroup, current))
        assertTrue(P2pGroupIdentityPolicy.matches(systemGroup, current.copy(
            identity = systemGroup.copy(ownerAddress = null),
        )))
        assertFalse(P2pGroupIdentityPolicy.matches(systemGroup, current.copy(isGroupOwner = false)))
        assertFalse(P2pGroupIdentityPolicy.matches(systemGroup, current.copy(
            identity = systemGroup.copy(networkName = "DIRECT-other"),
        )))
        assertFalse(P2pGroupIdentityPolicy.matches(systemGroup, current.copy(
            identity = systemGroup.copy(passphrase = "different-password"),
        )))
    }
}
