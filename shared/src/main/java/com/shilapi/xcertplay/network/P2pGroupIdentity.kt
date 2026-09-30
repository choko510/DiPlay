package com.shilapi.xcertplay.network

internal data class P2pGroupIdentity(
    val networkName: String,
    val passphrase: String?,
    val ownerAddress: String?,
    val interfaceName: String?,
)

internal data class P2pGroupObservation(
    val isGroupOwner: Boolean,
    val identity: P2pGroupIdentity?,
)

internal object P2pGroupIdentityPolicy {
    fun isCreationCandidate(
        createSucceeded: Boolean,
        requestIsCurrent: Boolean,
        groupWasAbsentBeforeCreate: Boolean,
        requestedName: String?,
        expectedPassphrase: String?,
        current: P2pGroupObservation,
    ): Boolean {
        val identity = current.identity ?: return false
        if (!createSucceeded || !requestIsCurrent || !groupWasAbsentBeforeCreate ||
            !current.isGroupOwner || !isComplete(identity)
        ) {
            return false
        }
        return if (requestedName != null) {
            identity.networkName == requestedName &&
                (expectedPassphrase == null || identity.passphrase == expectedPassphrase)
        } else {
            identity.networkName.startsWith("DIRECT-")
        }
    }

    fun confirmsSystemDefault(previous: P2pGroupIdentity?, current: P2pGroupObservation): Boolean =
        current.isGroupOwner && current.identity?.let { isComplete(it) && it.networkName.startsWith("DIRECT-") && it == previous } == true

    fun matches(expected: P2pGroupIdentity, current: P2pGroupObservation): Boolean {
        val identity = current.identity ?: return false
        return current.isGroupOwner && expected.networkName == identity.networkName &&
            expected.passphrase == identity.passphrase && expected.interfaceName == identity.interfaceName &&
            (expected.ownerAddress == null || identity.ownerAddress == null || expected.ownerAddress == identity.ownerAddress)
    }

    fun isComplete(identity: P2pGroupIdentity): Boolean =
        identity.networkName.isNotBlank() && !identity.passphrase.isNullOrBlank() &&
            !identity.interfaceName.isNullOrBlank()
}
