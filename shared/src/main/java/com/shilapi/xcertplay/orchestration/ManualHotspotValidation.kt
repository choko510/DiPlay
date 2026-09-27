package com.shilapi.xcertplay.orchestration

/** Rules an existing (car) hotspot must meet before CarPlay can hand its credentials to the iPhone. */
object ManualHotspotValidation {
    /** Why a manually entered hotspot cannot be used for handoff. */
    enum class Issue {
        EMPTY_SSID,
        SSID_TOO_LONG,
        INVALID_CHARACTER,
        INVALID_PASSWORD_LENGTH,
    }

    /** Security implied by the password: the car hotspot UI only offers open or WPA2 networks. */
    fun securityFor(passphrase: String): ManualHotspotSecurity =
        if (passphrase.isEmpty()) ManualHotspotSecurity.OPEN else ManualHotspotSecurity.WPA2

    /** Returns a typed validation reason, or null when the name and password can be used. */
    fun issue(ssid: String, passphrase: String): Issue? = when {
        ssid.isBlank() -> Issue.EMPTY_SSID
        ssid.encodeToByteArray().size > 32 -> Issue.SSID_TOO_LONG
        '\u0000' in ssid || '\u0000' in passphrase -> Issue.INVALID_CHARACTER
        passphrase.isNotEmpty() && passphrase.length !in 8..63 -> Issue.INVALID_PASSWORD_LENGTH
        else -> null
    }

    /** Returns a message for the user, or null when the name and password can be used. */
    fun validate(ssid: String, passphrase: String): String? = when (issue(ssid, passphrase)) {
        Issue.EMPTY_SSID -> "Enter the car hotspot name"
        Issue.SSID_TOO_LONG -> "The hotspot name must be at most 32 bytes"
        Issue.INVALID_CHARACTER -> "The name or password contains an invalid character"
        Issue.INVALID_PASSWORD_LENGTH -> "The hotspot password must be 8–63 characters"
        null -> null
    }
}
