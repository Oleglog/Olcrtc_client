package io.github.oleglog.olcrtc.client.vpn

import io.github.oleglog.olcrtc.client.profile.openflux.OpenFluxProfile

internal data class NativeOpenFluxConfig(
    val documentUrl: String,
    val transport: String,
    val socksPort: Int,
) {
    init {
        require(documentUrl.isNotBlank()) { "documentUrl is required" }
        require(socksPort in 1..65535) { "socksPort must be in 1..65535" }
    }

    companion object {
        fun from(profile: OpenFluxProfile, socksPort: Int): NativeOpenFluxConfig =
            NativeOpenFluxConfig(
                documentUrl = profile.documentUrl,
                transport = profile.transport.value,
                socksPort = socksPort,
            )
    }
}
