package io.github.oleglog.olcrtc.client.vpn

enum class VpnState {
    NO_PROFILE,
    DISCONNECTED,
    PREPARING,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    STOPPING,
    ERROR,
}
