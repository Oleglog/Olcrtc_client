package io.github.oleglog.olcrtc.client.vpn

enum class ConnectionStage {
    IDLE,
    LOAD_PROFILE,
    WAIT_NETWORK,
    PREPARE_ASSETS,
    CREATE_TUN,
    START_CARRIER,
    START_XRAY,
    START_HEV,
    VERIFY_DATAPATH,
    READY,
    STOPPING,
    ;

    companion object {
        fun fromOrdinal(value: Int): ConnectionStage = entries.getOrNull(value) ?: IDLE
    }
}
