package io.github.oleglog.olcrtc.client.vpn

import io.github.oleglog.olcrtc.client.data.ProfileConfig
import io.github.oleglog.olcrtc.client.profile.olcrtc.OlcrtcProfile
import io.github.oleglog.olcrtc.client.routing.DnsEndpoint

internal data class SessionDns(
    val tunnel: DnsEndpoint,
    val carrier: DnsEndpoint? = null,
)

internal fun sessionDns(profile: ProfileConfig, globalDns: String?): SessionDns = when (profile) {
    is ProfileConfig.Olcrtc -> SessionDns(
        tunnel = DnsEndpoint.parse(globalDns ?: DnsEndpoint.TUNNEL_DEFAULT),
        carrier = DnsEndpoint.parse(profile.value.dnsServer ?: globalDns ?: DnsEndpoint.DEFAULT),
    )
    is ProfileConfig.Standard -> SessionDns(
        tunnel = DnsEndpoint.parse(profile.value.dnsServer ?: globalDns ?: DnsEndpoint.TUNNEL_DEFAULT),
    )
}

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
    val readyTimeoutMillis: Int = DEFAULT_READY_TIMEOUT_MILLIS,
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
            readyTimeoutMillis = if (profile.provider == OlcrtcProfile.Provider.WBSTREAM) {
                WBSTREAM_READY_TIMEOUT_MILLIS
            } else {
                DEFAULT_READY_TIMEOUT_MILLIS
            },
        )

        const val DEFAULT_READY_TIMEOUT_MILLIS = 15_000
        const val WBSTREAM_READY_TIMEOUT_MILLIS = 45_000
    }
}
