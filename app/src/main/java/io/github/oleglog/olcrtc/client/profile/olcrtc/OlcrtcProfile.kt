package io.github.oleglog.olcrtc.client.profile.olcrtc

import io.github.oleglog.olcrtc.client.routing.DnsEndpoint

data class OlcrtcProfile(
    val name: String,
    val provider: Provider,
    val transport: Transport,
    val compatibilityMode: CompatibilityMode = CompatibilityMode.CURRENT,
    val roomId: String,
    val roomPassword: String? = null,
    val clientId: String,
    val keyHex: String,
    val authToken: String? = null,
    val dnsServer: String? = null,
    val vp8Fps: Int = DEFAULT_VP8_FPS,
    val vp8BatchSize: Int = DEFAULT_VP8_BATCH,
    val keepaliveIntervalSeconds: Int = DEFAULT_KEEPALIVE_SECONDS,
) {
    init {
        require(roomId.isNotBlank()) { "roomId is required" }
        require(clientId.isNotBlank()) { "clientId is required" }
        require(KEY_REGEX.matches(keyHex)) { "keyHex must contain exactly 64 hexadecimal characters" }
        require(vp8Fps in 1..120) { "vp8Fps must be in 1..120" }
        require(vp8BatchSize in 1..64) { "vp8BatchSize must be in 1..64" }
        require(keepaliveIntervalSeconds in 0..3600) { "keepalive must be in 0..3600" }
        require(provider.supports(transport)) { "$provider does not support $transport" }
        dnsServer?.let(DnsEndpoint::parse)
    }

    enum class Provider(val value: String) {
        WBSTREAM("wbstream"),
        TELEMOST("telemost"),
        JITSI("jitsi");

        fun supports(transport: Transport): Boolean = when (this) {
            WBSTREAM, TELEMOST -> transport == Transport.VP8CHANNEL
            JITSI -> transport == Transport.DATACHANNEL
        }

        companion object {
            fun parse(value: String): Provider = entries.firstOrNull { it.value == value.lowercase() }
                ?: throw IllegalArgumentException("Unsupported olcRTC provider: $value")
        }
    }

    enum class Transport(val value: String) {
        VP8CHANNEL("vp8channel"),
        DATACHANNEL("datachannel");

        companion object {
            fun parse(value: String): Transport = entries.firstOrNull { it.value == value.lowercase() }
                ?: throw IllegalArgumentException("Unsupported olcRTC transport: $value")
        }
    }

    enum class CompatibilityMode(val value: String) {
        CURRENT("current"),
        LEGACY("legacy");

        override fun toString(): String = value

        companion object {
            fun parse(value: String): CompatibilityMode =
                entries.firstOrNull { it.value == value.trim().lowercase() }
                    ?: throw IllegalArgumentException("Unsupported olcRTC compatibility mode: $value")
        }
    }

    companion object {
        const val DEFAULT_VP8_FPS = 120
        const val DEFAULT_VP8_BATCH = 64
        const val LEGACY_VP8_FPS = 60
        const val LEGACY_VP8_BATCH = 8
        const val DEFAULT_KEEPALIVE_SECONDS = 15
        private val KEY_REGEX = Regex("^[0-9a-fA-F]{64}$")

        fun manual(
            name: String,
            roomId: String,
            clientId: String,
            keyHex: String,
        ) = OlcrtcProfile(
            name = name,
            provider = Provider.WBSTREAM,
            transport = Transport.VP8CHANNEL,
            roomId = roomId,
            clientId = clientId,
            keyHex = keyHex,
        )
    }
}
