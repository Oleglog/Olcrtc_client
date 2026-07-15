package io.github.oleglog.olcrtc.client.vpn

import io.github.oleglog.olcrtc.client.profile.olcrtc.OlcrtcProfile
import io.github.oleglog.olcrtc.client.routing.DnsEndpoint

internal data class NativeOlcrtcConfig(
    val provider: String,
    val transport: String,
    val roomId: String,
    val clientId: String,
    val keyHex: String,
    val authToken: String,
    val dnsServer: String,
    val vp8Fps: Int,
    val vp8BatchSize: Int,
    val keepaliveSeconds: Int,
    val socksPort: Int,
) {
    companion object {
        fun from(profile: OlcrtcProfile, socksPort: Int, dns: DnsEndpoint) = NativeOlcrtcConfig(
            provider = profile.provider.value,
            transport = profile.transport.value,
            roomId = profile.roomId,
            clientId = profile.clientId,
            keyHex = profile.keyHex,
            authToken = profile.authToken.orEmpty(),
            dnsServer = dns.toString(),
            vp8Fps = profile.vp8Fps,
            vp8BatchSize = profile.vp8BatchSize,
            keepaliveSeconds = profile.keepaliveIntervalSeconds,
            socksPort = socksPort,
        )
    }
}
