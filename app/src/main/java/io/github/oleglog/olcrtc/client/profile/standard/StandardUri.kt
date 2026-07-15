package io.github.oleglog.olcrtc.client.profile.standard

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64

object StandardUri {
    private val allowedParameters = setOf(
        "encryption", "security", "type", "flow", "sni", "fp", "alpn", "allowInsecure",
        "pbk", "sid", "spx", "host", "path", "serviceName", "mode", "extra", "dns",
    )

    fun parse(raw: String): StandardProfile {
        val scheme = raw.substringBefore(':').lowercase()
        return when (scheme) {
            "vless" -> parseUrl(raw, StandardProfile.Protocol.VLESS)
            "trojan" -> parseUrl(raw, StandardProfile.Protocol.TROJAN)
            "vmess" -> parseVmess(raw)
            else -> throw IllegalArgumentException("Unsupported standard profile scheme")
        }
    }

    private fun parseUrl(raw: String, protocol: StandardProfile.Protocol): StandardProfile {
        val uri = URI(raw)
        val credential = uri.rawUserInfo?.let(::decode)?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException(if (protocol == StandardProfile.Protocol.TROJAN) "password is required" else "UUID is required")
        val address = uri.host?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("address is required")
        require(uri.port in 1..65535) { "port must be in 1..65535" }
        val params = parseQuery(uri.rawQuery)
        val unknown = params.keys - allowedParameters
        require(unknown.isEmpty()) { "Unsupported parameters: ${unknown.sorted().joinToString()}" }
        if (protocol == StandardProfile.Protocol.VLESS) {
            require(params["encryption"] == null || params["encryption"] == "none") { "VLESS encryption must be none" }
        }

        val transport = parseTransport(params["type"] ?: "tcp")
        val security = parseSecurity(params["security"] ?: if (protocol == StandardProfile.Protocol.TROJAN) "tls" else "none")
        val path = params["path"]
        return StandardProfile(
            name = uri.rawFragment?.let(::decode).orEmpty().ifBlank { protocol.name },
            protocol = protocol,
            address = address,
            port = uri.port,
            uuid = credential.takeIf { protocol == StandardProfile.Protocol.VLESS },
            password = credential.takeIf { protocol == StandardProfile.Protocol.TROJAN },
            transport = transport,
            security = security,
            flow = params["flow"]?.takeIf(String::isNotBlank),
            serverName = params["sni"]?.takeIf(String::isNotBlank),
            alpn = params["alpn"]?.split(',')?.filter(String::isNotBlank).orEmpty(),
            fingerprint = params["fp"]?.takeIf(String::isNotBlank),
            allowInsecure = parseBoolean(params["allowInsecure"]),
            realityPublicKey = params["pbk"]?.takeIf(String::isNotBlank),
            realityShortId = params["sid"]?.takeIf(String::isNotBlank),
            realitySpiderX = params["spx"]?.takeIf(String::isNotBlank),
            webSocketHost = params["host"]?.takeIf(String::isNotBlank),
            webSocketPath = path?.takeIf(String::isNotBlank),
            grpcServiceName = (params["serviceName"] ?: path)?.takeIf(String::isNotBlank),
            xhttpMode = params["mode"]?.takeIf(String::isNotBlank),
            xhttpHost = params["host"]?.takeIf(String::isNotBlank),
            xhttpPath = path?.takeIf(String::isNotBlank),
            xhttpExtraJson = params["extra"]?.takeIf(String::isNotBlank),
            dnsServer = params["dns"]?.takeIf(String::isNotBlank),
        )
    }

    private fun parseVmess(raw: String): StandardProfile {
        val payload = raw.substringAfter(':').removePrefix("//")
        require(payload.isNotBlank()) { "VMess payload is required" }
        val json = decodeBase64(payload).decodeToString()
        val values = FlatJson.parse(json)
        val allowed = setOf("v", "ps", "add", "port", "id", "aid", "scy", "net", "type", "host", "path", "tls", "sni", "alpn", "fp", "allowInsecure", "dns")
        val unknown = values.keys - allowed
        require(unknown.isEmpty()) { "Unsupported VMess fields: ${unknown.sorted().joinToString()}" }
        require(values["v"] == null || values["v"] == "2") { "Unsupported VMess version" }
        val transport = parseTransport(values["net"] ?: "tcp")
        require(values["type"] == null || values["type"] == "none") { "Unsupported VMess header type" }
        val path = values["path"]?.takeIf(String::isNotBlank)
        return StandardProfile(
            name = values["ps"].orEmpty().ifBlank { "VMess" },
            protocol = StandardProfile.Protocol.VMESS,
            address = values.required("add"),
            port = values.required("port").toIntOrNull() ?: throw IllegalArgumentException("port must be an integer"),
            uuid = values.required("id"),
            alterId = values["aid"]?.let {
                it.toIntOrNull() ?: throw IllegalArgumentException("alterId must be an integer")
            } ?: 0,
            cipher = values["scy"]?.takeIf(String::isNotBlank) ?: "auto",
            transport = transport,
            security = parseSecurity(values["tls"]?.takeIf(String::isNotBlank) ?: "none"),
            serverName = values["sni"]?.takeIf(String::isNotBlank),
            alpn = values["alpn"]?.split(',')?.filter(String::isNotBlank).orEmpty(),
            fingerprint = values["fp"]?.takeIf(String::isNotBlank),
            allowInsecure = parseBoolean(values["allowInsecure"]),
            webSocketHost = values["host"]?.takeIf(String::isNotBlank),
            webSocketPath = path,
            grpcServiceName = path,
            dnsServer = values["dns"]?.takeIf(String::isNotBlank),
        )
    }

    private fun parseTransport(value: String): StandardProfile.Transport = when (value.lowercase()) {
        "tcp", "raw" -> StandardProfile.Transport.TCP
        "ws" -> StandardProfile.Transport.WS
        "grpc" -> StandardProfile.Transport.GRPC
        "xhttp", "splithttp" -> StandardProfile.Transport.XHTTP
        else -> throw IllegalArgumentException("Unsupported transport: $value")
    }

    private fun parseSecurity(value: String): StandardProfile.Security = when (value.lowercase()) {
        "", "none" -> StandardProfile.Security.NONE
        "tls" -> StandardProfile.Security.TLS
        "reality" -> StandardProfile.Security.REALITY
        else -> throw IllegalArgumentException("Unsupported security: $value")
    }

    private fun parseBoolean(value: String?): Boolean = when (value?.lowercase()) {
        null, "", "0", "false" -> false
        "1", "true" -> true
        else -> throw IllegalArgumentException("allowInsecure must be boolean")
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrEmpty()) return emptyMap()
        val result = linkedMapOf<String, String>()
        rawQuery.split('&').forEach { item ->
            val pair = item.split('=', limit = 2)
            val key = decode(pair[0])
            require(key.isNotEmpty()) { "Empty parameter" }
            require(pair.size == 2) { "Missing value for parameter: $key" }
            require(key !in result) { "Duplicate parameter: $key" }
            result[key] = decode(pair[1])
        }
        return result
    }

    private fun decodeBase64(value: String): ByteArray {
        val normalized = value.trim().replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        return runCatching { Base64.getDecoder().decode(padded) }
            .getOrElse { throw IllegalArgumentException("Invalid VMess Base64", it) }
    }

    private fun decode(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8)

    private fun Map<String, String?>.required(name: String): String =
        get(name)?.takeIf(String::isNotBlank) ?: throw IllegalArgumentException("$name is required")

    private object FlatJson {
        fun parse(raw: String): Map<String, String?> {
            var index = 0
            fun skipWhitespace() { while (index < raw.length && raw[index].isWhitespace()) index++ }
            fun expect(character: Char) {
                skipWhitespace()
                require(index < raw.length && raw[index] == character) { "Invalid VMess JSON" }
                index++
            }
            fun string(): String {
                skipWhitespace()
                require(index < raw.length && raw[index++] == '"') { "Invalid VMess JSON string" }
                return buildString {
                    while (index < raw.length) {
                        val character = raw[index++]
                        if (character == '"') return@buildString
                        if (character != '\\') append(character) else {
                            require(index < raw.length) { "Invalid VMess JSON escape" }
                            when (val escaped = raw[index++]) {
                                '"', '\\', '/' -> append(escaped)
                                'b' -> append('\b')
                                'f' -> append('')
                                'n' -> append('\n')
                                'r' -> append('\r')
                                't' -> append('\t')
                                'u' -> {
                                    require(index + 4 <= raw.length) { "Invalid VMess JSON unicode escape" }
                                    append(raw.substring(index, index + 4).toInt(16).toChar())
                                    index += 4
                                }
                                else -> throw IllegalArgumentException("Invalid VMess JSON escape: $escaped")
                            }
                        }
                    }
                    throw IllegalArgumentException("Unterminated VMess JSON string")
                }
            }

            expect('{')
            val result = linkedMapOf<String, String?>()
            skipWhitespace()
            if (index < raw.length && raw[index] == '}') index++ else while (true) {
                val key = string()
                require(key !in result) { "Duplicate VMess field: $key" }
                expect(':')
                skipWhitespace()
                val value = when {
                    index < raw.length && raw[index] == '"' -> string()
                    raw.startsWith("null", index) -> null.also { index += 4 }
                    index < raw.length && (raw[index] == '-' || raw[index].isDigit()) -> {
                        val start = index
                        if (raw[index] == '-') index++
                        require(index < raw.length && raw[index].isDigit()) { "Invalid VMess JSON number" }
                        while (index < raw.length && raw[index].isDigit()) index++
                        raw.substring(start, index)
                    }
                    else -> throw IllegalArgumentException("Invalid VMess JSON value")
                }
                result[key] = value
                skipWhitespace()
                require(index < raw.length) { "Unterminated VMess JSON" }
                if (raw[index++] == '}') break
                require(raw[index - 1] == ',') { "Invalid VMess JSON" }
            }
            skipWhitespace()
            require(index == raw.length) { "Unexpected VMess JSON content" }
            return result
        }
    }
}
