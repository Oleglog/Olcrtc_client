package io.github.oleglog.olcrtc.client.routing

internal data class RoutingPolicy(
    val preset: Preset = Preset.ALL_VPN,
    val allowLan: Boolean = false,
) {
    enum class Preset {
        RUSSIA_DIRECT,
        ALL_VPN,
    }
}
