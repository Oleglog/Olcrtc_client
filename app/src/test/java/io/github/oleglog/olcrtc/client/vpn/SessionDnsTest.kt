package io.github.oleglog.olcrtc.client.vpn

import io.github.oleglog.olcrtc.client.data.ProfileConfig
import io.github.oleglog.olcrtc.client.profile.olcrtc.OlcrtcProfile
import io.github.oleglog.olcrtc.client.profile.standard.StandardProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionDnsTest {
    @Test
    fun separatesOlcrtcCarrierDnsFromTunnelDns() {
        val olcrtc = ProfileConfig.Olcrtc(OlcrtcProfile(
            name = "olcRTC",
            provider = OlcrtcProfile.Provider.WBSTREAM,
            transport = OlcrtcProfile.Transport.VP8CHANNEL,
            roomId = "room",
            clientId = "client",
            keyHex = "a".repeat(64),
            dnsServer = "77.88.8.8:53",
        ))

        val defaults = sessionDns(olcrtc, null)
        assertEquals("1.1.1.1:53", defaults.tunnel.toString())
        assertEquals("77.88.8.8:53", defaults.carrier?.toString())

        val configured = sessionDns(olcrtc, "9.9.9.9:53")
        assertEquals("9.9.9.9:53", configured.tunnel.toString())
        assertEquals("77.88.8.8:53", configured.carrier?.toString())
    }

    @Test
    fun standardProfileKeepsItsDnsAsTunnelDns() {
        val standard = ProfileConfig.Standard(StandardProfile(
            name = "Trojan",
            protocol = StandardProfile.Protocol.TROJAN,
            address = "example.com",
            port = 443,
            password = "secret",
            security = StandardProfile.Security.TLS,
            dnsServer = "8.8.8.8:53",
        ))

        val dns = sessionDns(standard, "9.9.9.9:53")
        assertEquals("8.8.8.8:53", dns.tunnel.toString())
        assertNull(dns.carrier)
    }
}
