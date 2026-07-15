package io.github.oleglog.olcrtc.client.profile.standard

import io.github.oleglog.olcrtc.client.routing.DnsEndpoint
import java.util.UUID

data class StandardProfile(
    val name: String,
    val protocol: Protocol,
    val address: String,
    val port: Int,
    val uuid: String? = null,
    val password: String? = null,
    val alterId: Int = 0,
    val cipher: String = "auto",
    val transport: Transport = Transport.TCP,
    val security: Security = Security.NONE,
    val flow: String? = null,
    val serverName: String? = null,
    val alpn: List<String> = emptyList(),
    val fingerprint: String? = null,
    val allowInsecure: Boolean = false,
    val realityPublicKey: String? = null,
    val realityShortId: String? = null,
    val realitySpiderX: String? = null,
    val webSocketHost: String? = null,
    val webSocketPath: String? = null,
    val grpcServiceName: String? = null,
    val xhttpMode: String? = null,
    val xhttpHost: String? = null,
    val xhttpPath: String? = null,
    val xhttpExtraJson: String? = null,
    val dnsServer: String? = null,
) {
    init {
        require(name.isNotBlank()) { "name is required" }
        require(address.isNotBlank()) { "address is required" }
        require(port in 1..65535) { "port must be in 1..65535" }
        when (protocol) {
            Protocol.VLESS -> requireUuid()
            Protocol.VMESS -> {
                requireUuid()
                require(alterId >= 0) { "alterId must not be negative" }
                require(cipher in VMESS_CIPHERS) { "unsupported VMess cipher: $cipher" }
            }
            Protocol.TROJAN -> require(!password.isNullOrEmpty()) { "password is required" }
        }
        dnsServer?.let(DnsEndpoint::parse)
        require(!(transport == Transport.XHTTP && protocol != Protocol.VLESS)) {
            "XHTTP is supported only for VLESS"
        }
        require(!(protocol == Protocol.TROJAN && security == Security.NONE)) {
            "Trojan requires TLS or REALITY"
        }
        require(!(protocol == Protocol.VMESS && security == Security.REALITY)) {
            "VMess does not support REALITY"
        }
        require(flow == null || flow == VISION_FLOW) { "unsupported VLESS flow: $flow" }
        require(flow == null || protocol == Protocol.VLESS) { "flow is supported only for VLESS" }
        require(flow == null || transport == Transport.TCP) { "Vision requires TCP/raw transport" }
        require(flow == null || security == Security.REALITY) { "Vision requires REALITY" }
        if (security == Security.REALITY) {
            require(!realityPublicKey.isNullOrBlank()) { "REALITY public key is required" }
            require(!realityShortId.isNullOrBlank()) { "REALITY short ID is required" }
        }
        if (transport == Transport.WS) {
            require(!webSocketPath.isNullOrBlank()) { "WebSocket path is required" }
        }
        if (transport == Transport.GRPC) {
            require(!grpcServiceName.isNullOrBlank()) { "gRPC service name is required" }
        }
        if (transport == Transport.XHTTP) {
            require(!xhttpMode.isNullOrBlank()) { "XHTTP mode is required" }
            require(!xhttpPath.isNullOrBlank()) { "XHTTP path is required" }
            xhttpExtraJson?.let {
                require(it.length <= MAX_XHTTP_EXTRA_LENGTH) { "XHTTP extra is too large" }
                JsonObject.validate(it, XHTTP_EXTRA_FIELDS)
            }
        } else {
            require(xhttpExtraJson == null) { "XHTTP extra requires XHTTP transport" }
        }
    }

    private fun requireUuid() {
        require(!uuid.isNullOrBlank()) { "UUID is required" }
        require(runCatching { UUID.fromString(uuid) }.isSuccess) { "UUID is invalid" }
    }

    enum class Protocol { VLESS, VMESS, TROJAN }

    enum class Transport { TCP, WS, GRPC, XHTTP }

    enum class Security { NONE, TLS, REALITY }

    companion object {
        const val VISION_FLOW = "xtls-rprx-vision"
        private const val MAX_XHTTP_EXTRA_LENGTH = 16 * 1024
        private val VMESS_CIPHERS = setOf("auto", "aes-128-gcm", "chacha20-poly1305", "none")
        private val XHTTP_EXTRA_FIELDS = setOf(
            "headers", "xPaddingBytes", "xPaddingObfsMode", "xPaddingKey", "xPaddingHeader",
            "xPaddingPlacement", "xPaddingMethod", "uplinkHTTPMethod", "sessionPlacement",
            "sessionKey", "seqPlacement", "seqKey", "uplinkDataPlacement", "uplinkDataKey",
            "uplinkChunkSize", "noGRPCHeader", "noSSEHeader", "scMaxEachPostBytes",
            "scMinPostsIntervalMs", "scMaxBufferedPosts", "scStreamUpServerSecs", "xmux",
            "downloadSettings",
        )
    }
}

private object JsonObject {
    fun validate(raw: String, allowedKeys: Set<String>) {
        var index = 0

        fun skipWhitespace() {
            while (index < raw.length && raw[index].isWhitespace()) index++
        }

        fun string(): String {
            skipWhitespace()
            require(index < raw.length && raw[index++] == '"') { "Invalid XHTTP extra JSON" }
            return buildString {
                while (index < raw.length) {
                    when (val character = raw[index++]) {
                        '"' -> return@buildString
                        '\\' -> {
                            require(index < raw.length) { "Invalid XHTTP extra JSON escape" }
                            val escaped = raw[index++]
                            require(escaped in charArrayOf('"', '\\', '/', 'b', 'f', 'n', 'r', 't', 'u')) {
                                "Invalid XHTTP extra JSON escape"
                            }
                            if (escaped == 'u') {
                                require(index + 4 <= raw.length) { "Invalid XHTTP extra JSON escape" }
                                require(raw.substring(index, index + 4).all(Char::isHexDigit)) {
                                    "Invalid XHTTP extra JSON escape"
                                }
                                index += 4
                            }
                            append('?')
                        }
                        else -> {
                            require(character.code >= 0x20) { "Invalid XHTTP extra JSON string" }
                            append(character)
                        }
                    }
                }
                throw IllegalArgumentException("Unterminated XHTTP extra JSON string")
            }
        }

        fun value() {
            skipWhitespace()
            require(index < raw.length) { "Invalid XHTTP extra JSON value" }
            when (raw[index]) {
                '"' -> string()
                '{', '[' -> {
                    val closers = ArrayDeque<Char>()
                    closers.addLast(if (raw[index++] == '{') '}' else ']')
                    while (index < raw.length && closers.isNotEmpty()) {
                        when (raw[index]) {
                            '"' -> string()
                            '{' -> {
                                closers.addLast('}')
                                index++
                            }
                            '[' -> {
                                closers.addLast(']')
                                index++
                            }
                            '}', ']' -> {
                                require(raw[index] == closers.removeLast()) { "Invalid XHTTP extra JSON value" }
                                index++
                            }
                            else -> index++
                        }
                    }
                    require(closers.isEmpty()) { "Unterminated XHTTP extra JSON value" }
                }
                else -> {
                    val start = index
                    while (index < raw.length && raw[index] !in charArrayOf(',', '}')) index++
                    val literal = raw.substring(start, index).trim()
                    require(
                        literal == "true" || literal == "false" || literal == "null" ||
                            literal.toLongOrNull() != null,
                    ) { "Invalid XHTTP extra JSON value" }
                }
            }
        }

        skipWhitespace()
        require(index < raw.length && raw[index++] == '{') { "XHTTP extra must be a JSON object" }
        skipWhitespace()
        if (index < raw.length && raw[index] == '}') {
            index++
        } else {
            while (true) {
                val key = string()
                require(key in allowedKeys) { "Unsupported XHTTP extra field: $key" }
                skipWhitespace()
                require(index < raw.length && raw[index++] == ':') { "Invalid XHTTP extra JSON" }
                value()
                skipWhitespace()
                require(index < raw.length) { "Unterminated XHTTP extra JSON" }
                when (raw[index++]) {
                    '}' -> break
                    ',' -> Unit
                    else -> throw IllegalArgumentException("Invalid XHTTP extra JSON")
                }
            }
        }
        skipWhitespace()
        require(index == raw.length) { "Unexpected XHTTP extra JSON content" }
    }

    private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
