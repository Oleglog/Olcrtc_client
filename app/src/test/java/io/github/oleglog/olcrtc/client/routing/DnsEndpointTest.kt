package io.github.oleglog.olcrtc.client.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DnsEndpointTest {
    @Test
    fun parsesAndCanonicalizesNumericEndpoints() {
        assertEquals("77.88.8.8:53", DnsEndpoint.parse("77.88.8.8:53").toString())
        assertEquals("[2001:db8:0:0:0:0:0:1]:5353", DnsEndpoint.parse("[2001:db8::1]:5353").toString())
    }

    @Test
    fun rejectsHostnamesMissingPortsAndAmbiguousAddresses() {
        listOf(
            "dns.example:53",
            "77.88.8.8",
            "077.88.8.8:53",
            "2001:db8::1:53",
            "[fe80::1%wlan0]:53",
            "[2001:db8::1]:0",
        ).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { DnsEndpoint.parse(value) }
        }
    }
}
