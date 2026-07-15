package io.github.oleglog.olcrtc.client.profile.standard

import org.junit.Assert.assertThrows
import org.junit.Test

class StandardProfileTest {
    private val uuid = "00000000-0000-0000-0000-000000000001"

    @Test
    fun acceptsRequiredProtocolMatrix() {
        profile(StandardProfile.Protocol.VLESS, StandardProfile.Transport.TCP, StandardProfile.Security.REALITY, flow = StandardProfile.VISION_FLOW)
        profile(StandardProfile.Protocol.VLESS, StandardProfile.Transport.WS, StandardProfile.Security.TLS, webSocketPath = "/ws")
        profile(StandardProfile.Protocol.VLESS, StandardProfile.Transport.GRPC, StandardProfile.Security.TLS, grpcServiceName = "grpc")
        profile(StandardProfile.Protocol.VLESS, StandardProfile.Transport.XHTTP, StandardProfile.Security.REALITY, xhttpMode = "auto", xhttpPath = "/xhttp")
        profile(StandardProfile.Protocol.VMESS, StandardProfile.Transport.TCP, StandardProfile.Security.NONE)
        profile(StandardProfile.Protocol.VMESS, StandardProfile.Transport.WS, StandardProfile.Security.TLS, webSocketPath = "/ws")
        profile(StandardProfile.Protocol.VMESS, StandardProfile.Transport.GRPC, StandardProfile.Security.TLS, grpcServiceName = "grpc")
        profile(StandardProfile.Protocol.TROJAN, StandardProfile.Transport.TCP, StandardProfile.Security.TLS, password = "secret")
        profile(StandardProfile.Protocol.TROJAN, StandardProfile.Transport.WS, StandardProfile.Security.TLS, password = "secret", webSocketPath = "/ws")
        profile(StandardProfile.Protocol.TROJAN, StandardProfile.Transport.GRPC, StandardProfile.Security.TLS, password = "secret", grpcServiceName = "grpc")
    }

    @Test
    fun rejectsUnsupportedCombinations() {
        assertThrows(IllegalArgumentException::class.java) {
            profile(StandardProfile.Protocol.VMESS, StandardProfile.Transport.XHTTP, StandardProfile.Security.NONE)
        }
        assertThrows(IllegalArgumentException::class.java) {
            profile(StandardProfile.Protocol.VLESS, StandardProfile.Transport.WS, StandardProfile.Security.TLS, flow = StandardProfile.VISION_FLOW, webSocketPath = "/ws")
        }
        assertThrows(IllegalArgumentException::class.java) {
            profile(StandardProfile.Protocol.TROJAN, StandardProfile.Transport.TCP, StandardProfile.Security.NONE, password = "secret")
        }
        assertThrows(IllegalArgumentException::class.java) {
            profile(StandardProfile.Protocol.VLESS, StandardProfile.Transport.TCP, StandardProfile.Security.REALITY, flow = StandardProfile.VISION_FLOW, realityPublicKey = null)
        }
    }

    @Test
    fun validatesXhttpExtraJson() {
        profile(
            StandardProfile.Protocol.VLESS,
            StandardProfile.Transport.XHTTP,
            StandardProfile.Security.REALITY,
            xhttpMode = "auto",
            xhttpPath = "/xhttp",
            xhttpExtraJson = """{"noSSEHeader":true,"xmux":{"maxConcurrency":"2-4"}}""",
        )
        assertThrows(IllegalArgumentException::class.java) {
            profile(
                StandardProfile.Protocol.VLESS,
                StandardProfile.Transport.XHTTP,
                StandardProfile.Security.REALITY,
                xhttpMode = "auto",
                xhttpPath = "/xhttp",
                xhttpExtraJson = """{"mode":"packet-up"}""",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            profile(
                StandardProfile.Protocol.VLESS,
                StandardProfile.Transport.XHTTP,
                StandardProfile.Security.REALITY,
                xhttpMode = "auto",
                xhttpPath = "/xhttp",
                xhttpExtraJson = "[]",
            )
        }
    }

    private fun profile(
        protocol: StandardProfile.Protocol,
        transport: StandardProfile.Transport,
        security: StandardProfile.Security,
        uuid: String? = this.uuid,
        password: String? = null,
        flow: String? = null,
        realityPublicKey: String? = "public-key",
        realityShortId: String? = "short-id",
        webSocketPath: String? = null,
        grpcServiceName: String? = null,
        xhttpMode: String? = null,
        xhttpPath: String? = null,
        xhttpExtraJson: String? = null,
    ) = StandardProfile(
        name = "Test",
        protocol = protocol,
        address = "example.com",
        port = 443,
        uuid = uuid,
        password = password,
        transport = transport,
        security = security,
        flow = flow,
        realityPublicKey = realityPublicKey,
        realityShortId = realityShortId,
        webSocketPath = webSocketPath,
        grpcServiceName = grpcServiceName,
        xhttpMode = xhttpMode,
        xhttpPath = xhttpPath,
        xhttpExtraJson = xhttpExtraJson,
    )
}
