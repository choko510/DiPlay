package com.shilapi.xcertplay.network

import org.junit.Assert.*
import org.junit.Test

class LocalOnlyHotspotInterfacePolicyTest {
    private fun net(name: String, ip: String? = null, mac: String? = null, ipv6: String? = null) =
        LocalOnlyHotspotInterfacePolicy.Candidate(name, setOfNotNull(ip), mac, setOfNotNull(ipv6))

    @Test fun waitsForApAddressInsteadOfAdvertisingEthernetOrStation() {
        val existing = listOf(net("eth0", "192.168.5.1"), net("wlan0", "192.168.31.73"))
        val before = existing.flatMap { it.addresses }.toSet()
        assertNull(LocalOnlyHotspotInterfacePolicy.select(existing + net("ap0"), before, setOf("wlan0"), null))
        val ap = net("ap0", "192.168.43.1")
        assertEquals(ap, LocalOnlyHotspotInterfacePolicy.select(existing + ap, before, setOf("wlan0"), null))
    }

    @Test fun excludesNewStationAddressAndForeignNetworks() {
        val networks = listOf(net("wlan0", "192.168.31.74"), net("p2p0", "192.168.49.1"),
            net("ccmni0", "10.0.0.1"), net("eth0", "192.168.5.1"), net("apcli0", "192.168.6.1"))
        assertNull(LocalOnlyHotspotInterfacePolicy.select(networks, emptySet(), setOf("wlan0"), null))
    }

    @Test fun refusesAmbiguityOrUnprovenExistingAp() {
        val ap = net("ap0", "192.168.43.1")
        assertNull(LocalOnlyHotspotInterfacePolicy.select(listOf(ap, net("wlan1", "192.168.44.1")), emptySet(), emptySet(), null))
        assertNull(LocalOnlyHotspotInterfacePolicy.select(listOf(ap), ap.ipv4, emptySet(), null))
    }

    @Test fun configuredIdentityCanResolveAnExistingSharedApButNeverAnotherBssid() {
        val ap = net("ap0", "192.168.43.1", "00:11:22:33:44:55")
        assertEquals(ap, LocalOnlyHotspotInterfacePolicy.select(listOf(ap), ap.addresses, emptySet(), ap.bssid))
        assertNull(LocalOnlyHotspotInterfacePolicy.select(listOf(ap), emptySet(), emptySet(), "00:11:22:33:44:66"))
    }

    @Test fun selectsAnIpv6OnlyApAfterItsLinkLocalAddressAppears() {
        val existing = listOf(net("eth0", "192.168.5.1"), net("wlan0", ipv6 = "fe80::1"))
        val before = existing.flatMap { it.addresses }.toSet()
        val waiting = net("ap0")
        assertNull(LocalOnlyHotspotInterfacePolicy.select(existing + waiting, before, setOf("wlan0"), null))

        val ap = net("ap0", ipv6 = "fe80::2")
        assertEquals(ap, LocalOnlyHotspotInterfacePolicy.select(existing + ap, before, setOf("wlan0"), null))
    }

    @Test fun configuredBssidCanSelectAnIpv6OnlyApWithAnExistingAddress() {
        val ap = net("ap0", mac = "00:11:22:33:44:55", ipv6 = "fe80::2")
        assertEquals(ap, LocalOnlyHotspotInterfacePolicy.select(listOf(ap), ap.addresses, emptySet(), ap.bssid))
    }

    @Test fun selectsAUniqueIpv6OnlyApEvenWhenItsLinkLocalAddressPredatesStartup() {
        val ap = net("ap0", ipv6 = "fe80::2")
        assertEquals(ap, LocalOnlyHotspotInterfacePolicy.select(listOf(ap), ap.addresses, emptySet(), null))
        assertNull(LocalOnlyHotspotInterfacePolicy.select(
            listOf(ap, net("wlan1", ipv6 = "fe80::3")), ap.addresses, emptySet(), null,
        ))
    }
}
