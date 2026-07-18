package io.github.oleglog.olcrtc.client.profile.standard

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StandardUriTest {
    private val uuid = "00000000-0000-0000-0000-000000000001"

    @Test
    fun parsesVlessRealityVision() {
        val profile = StandardUri.parse(
            "vless://$uuid@example.com:443?encryption=none&type=tcp&security=reality&flow=xtls-rprx-vision&sni=www.example.com&fp=chrome&pbk=public-key&sid=short-id&spx=%2F#Main",
        )

        assertEquals(StandardProfile.Protocol.VLESS, profile.protocol)
        assertEquals(StandardProfile.Transport.TCP, profile.transport)
        assertEquals(StandardProfile.Security.REALITY, profile.security)
        assertEquals(StandardProfile.VISION_FLOW, profile.flow)
        assertEquals("public-key", profile.realityPublicKey)
        assertEquals("Main", profile.name)
    }

    @Test
    fun parsesVlessWebSocketAndXhttp() {
        val webSocket = StandardUri.parse(
            "vless://$uuid@example.com:443?encryption=none&type=ws&security=tls&host=cdn.example.com&path=%2Fws&sni=example.com",
        )
        val xhttp = StandardUri.parse(
            "vless://$uuid@example.com:443?encryption=none&type=xhttp&security=reality&mode=auto&path=%2Fxhttp&pbk=key&sid=short",
        )

        assertEquals("/ws", webSocket.webSocketPath)
        assertEquals("cdn.example.com", webSocket.webSocketHost)
        assertEquals(StandardProfile.Transport.XHTTP, xhttp.transport)
        assertEquals("auto", xhttp.xhttpMode)
    }

    @Test
    fun parsesTrojanGrpcReality() {
        val profile = StandardUri.parse(
            "trojan://secret@example.com:443?type=grpc&security=reality&serviceName=proxy&pbk=key&sid=short#Trojan",
        )

        assertEquals(StandardProfile.Protocol.TROJAN, profile.protocol)
        assertEquals("secret", profile.password)
        assertEquals("proxy", profile.grpcServiceName)
        assertEquals(StandardProfile.Security.REALITY, profile.security)
    }

    @Test
    fun parsesLegacyVmessBase64Json() {
        val json = """{"v":"2","ps":"VMess","add":"example.com","port":"443","id":"$uuid","aid":"0","scy":"auto","net":"ws","type":"none","host":"cdn.example.com","path":"/vmess","tls":"tls","sni":"example.com","fp":"chrome"}"""
        val uri = "vmess://" + Base64.getEncoder().withoutPadding().encodeToString(json.encodeToByteArray())

        val profile = StandardUri.parse(uri)

        assertEquals(StandardProfile.Protocol.VMESS, profile.protocol)
        assertEquals(StandardProfile.Transport.WS, profile.transport)
        assertEquals(StandardProfile.Security.TLS, profile.security)
        assertEquals("/vmess", profile.webSocketPath)
    }

    @Test
    fun parsesModernAndLegacyShadowsocksLinks() {
        val credentials = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("aes-256-gcm:secret".encodeToByteArray())
        val modern = StandardUri.parse("ss://$credentials@example.com:8388#Main%20SS")
        val legacyPayload = Base64.getEncoder().withoutPadding()
            .encodeToString("chacha20-ietf-poly1305:password@example.net:443".encodeToByteArray())
        val legacy = StandardUri.parse("ss://$legacyPayload#Legacy")

        assertEquals(StandardProfile.Protocol.SHADOWSOCKS, modern.protocol)
        assertEquals("aes-256-gcm", modern.cipher)
        assertEquals("secret", modern.password)
        assertEquals("Main SS", modern.name)
        assertEquals("example.net", legacy.address)
        assertEquals("chacha20-ietf-poly1305", legacy.cipher)
        assertEquals(modern, StandardUri.parse(StandardUri.serialize(modern)))
    }

    @Test
    fun serializesStandardProfilesRoundTrip() {
        val vless = StandardProfile(
            name = "VLESS Main",
            protocol = StandardProfile.Protocol.VLESS,
            address = "example.com",
            port = 443,
            uuid = uuid,
            transport = StandardProfile.Transport.WS,
            security = StandardProfile.Security.TLS,
            serverName = "example.com",
            webSocketHost = "cdn.example.com",
            webSocketPath = "/ws",
            dnsServer = "77.88.8.8:53",
        )
        val trojan = StandardProfile(
            name = "Trojan Main",
            protocol = StandardProfile.Protocol.TROJAN,
            address = "trojan.example.com",
            port = 443,
            password = "secret",
            transport = StandardProfile.Transport.GRPC,
            security = StandardProfile.Security.REALITY,
            realityPublicKey = "pub",
            realityShortId = "sid",
            grpcServiceName = "grpc",
        )
        val vmess = StandardProfile(
            name = "VMess Main",
            protocol = StandardProfile.Protocol.VMESS,
            address = "vmess.example.com",
            port = 443,
            uuid = uuid,
            transport = StandardProfile.Transport.WS,
            security = StandardProfile.Security.TLS,
            webSocketHost = "cdn.example.com",
            webSocketPath = "/vmess",
        )

        assertEquals(vless, StandardUri.parse(StandardUri.serialize(vless)))
        assertEquals(trojan, StandardUri.parse(StandardUri.serialize(trojan)))
        assertEquals(vmess, StandardUri.parse(StandardUri.serialize(vmess)))
    }

    @Test
    fun rejectsUnknownDuplicateUnsafeAndMalformedParameters() {
        assertThrows(IllegalArgumentException::class.java) {
            StandardUri.parse("vless://$uuid@example.com:443?type=tcp&security=tls&security=none")
        }
        assertThrows(IllegalArgumentException::class.java) {
            StandardUri.parse("trojan://secret@example.com:443?type=tcp&security=tls&insecure=1")
        }
        assertThrows(IllegalArgumentException::class.java) {
            StandardUri.parse("vmess://" + Base64.getEncoder().encodeToString("{\"v\":true}".encodeToByteArray()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            StandardUri.parse("vmess://" + Base64.getEncoder().encodeToString("{\"add\":\"example.com\",\"port\":\"443\",\"id\":\"$uuid\",\"aid\":\"bad\"}".encodeToByteArray()))
        }
        val error = assertThrows(IllegalArgumentException::class.java) {
            StandardUri.parse("vmess://not-base64")
        }
        assertTrue(error.message!!.contains("Base64"))
        assertThrows(IllegalArgumentException::class.java) {
            StandardUri.parse("ss://YWVzLTEyOC1jZmI6c2VjcmV0@example.com:8388")
        }
        assertThrows(IllegalArgumentException::class.java) {
            StandardUri.parse("ss://YWVzLTI1Ni1nY206c2VjcmV0@example.com:8388?plugin=v2ray-plugin")
        }
    }
}
