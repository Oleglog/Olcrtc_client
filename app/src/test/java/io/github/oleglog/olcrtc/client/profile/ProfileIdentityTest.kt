package io.github.oleglog.olcrtc.client.profile

import io.github.oleglog.olcrtc.client.profile.olcrtc.OlcrtcProfile
import io.github.oleglog.olcrtc.client.profile.standard.StandardProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ProfileIdentityTest {
    @Test
    fun ignoresNameAndNormalizesCaseInsensitiveFields() {
        val first = standard(name = "First", address = "EXAMPLE.COM", uuid = UUID.uppercase())
        val second = standard(name = "Second", address = "example.com", uuid = UUID)

        assertEquals(ProfileIdentity.hash(first), ProfileIdentity.hash(second))
    }

    @Test
    fun includesSecretsAndDistinguishesNullFromEmpty() {
        val first = olcrtc(name = "First", authToken = "token")
        val second = olcrtc(name = "Second", authToken = "other-token")
        val withoutServerName = standard(serverName = null)
        val emptyServerName = standard(serverName = "")

        assertNotEquals(ProfileIdentity.hash(first), ProfileIdentity.hash(second))
        assertNotEquals(ProfileIdentity.hash(withoutServerName), ProfileIdentity.hash(emptyServerName))
    }

    @Test
    fun normalizesEquivalentDnsEndpoints() {
        val first = olcrtc(name = "First", authToken = null, dnsServer = "[2001:db8::1]:53")
        val second = olcrtc(name = "Second", authToken = null, dnsServer = "[2001:db8:0:0:0:0:0:1]:53")

        assertEquals(ProfileIdentity.hash(first), ProfileIdentity.hash(second))
    }

    @Test
    fun ignoresLocalCompatibilityMode() {
        val current = olcrtc(name = "Current", authToken = null)
        val legacy = current.copy(
            name = "Legacy",
            compatibilityMode = OlcrtcProfile.CompatibilityMode.LEGACY,
        )

        assertEquals(ProfileIdentity.hash(current), ProfileIdentity.hash(legacy))
    }

    @Test
    fun ignoresInsignificantXhttpJsonWhitespace() {
        val first = xhttp("""{"noSSEHeader":true,"xmux":{"maxConcurrency":"2-4"}}""")
        val second = xhttp(""" { "noSSEHeader" : true, "xmux" : { "maxConcurrency" : "2-4" } } """)

        assertEquals(ProfileIdentity.hash(first), ProfileIdentity.hash(second))
    }

    private fun olcrtc(name: String, authToken: String?, dnsServer: String? = null) = OlcrtcProfile(
        name = name,
        provider = OlcrtcProfile.Provider.WBSTREAM,
        transport = OlcrtcProfile.Transport.VP8CHANNEL,
        roomId = "room",
        clientId = "client",
        keyHex = "a".repeat(64),
        authToken = authToken,
        dnsServer = dnsServer,
    )

    private fun standard(
        name: String = "VLESS",
        address: String = "example.com",
        uuid: String = UUID,
        serverName: String? = null,
    ) = StandardProfile(
        name = name,
        protocol = StandardProfile.Protocol.VLESS,
        address = address,
        port = 443,
        uuid = uuid,
        transport = StandardProfile.Transport.WS,
        security = StandardProfile.Security.TLS,
        serverName = serverName,
        webSocketPath = "/ws",
    )

    private fun xhttp(extra: String) = StandardProfile(
        name = "XHTTP",
        protocol = StandardProfile.Protocol.VLESS,
        address = "example.com",
        port = 443,
        uuid = UUID,
        transport = StandardProfile.Transport.XHTTP,
        security = StandardProfile.Security.REALITY,
        realityPublicKey = "public-key",
        realityShortId = "short-id",
        xhttpMode = "auto",
        xhttpPath = "/xhttp",
        xhttpExtraJson = extra,
    )

    companion object {
        private const val UUID = "00000000-0000-0000-0000-00000000000a"
    }
}
