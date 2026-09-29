package com.shilapi.xcertplay.network

/** onStarted can precede address assignment. A unique AP's link-local IPv6 is valid without IPv4. */
internal object LocalOnlyHotspotInterfacePolicy {
    data class Candidate(
        val name: String,
        val ipv4: Set<String>,
        val bssid: String?,
        val ipv6LinkLocal: Set<String> = emptySet(),
    ) {
        val addresses: Set<String> get() = ipv4 + ipv6LinkLocal
    }

    fun select(
        candidates: List<Candidate>,
        previousAddresses: Set<String>,
        upstreamInterfaces: Set<String>,
        configuredBssid: String?,
    ): Candidate? {
        val possible = candidates.filter {
            it.name !in upstreamInterfaces && it.addresses.isNotEmpty() &&
                it.name.matches(Regex("(?:ap|wlan|swlan|softap)[0-9]+"))
        }
        if (configuredBssid != null) {
            return possible.singleOrNull { it.bssid.equals(configuredBssid, ignoreCase = true) }
        }
        return possible.filter { candidate ->
            candidate.ipv4.any { it !in previousAddresses } || candidate.ipv6LinkLocal.isNotEmpty()
        }.singleOrNull()
    }
}
