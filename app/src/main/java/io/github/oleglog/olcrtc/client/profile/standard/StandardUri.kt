package io.github.oleglog.olcrtc.client.profile.standard

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64

object StandardUri {
    private val allowedParameters = setOf(
        "encryption", "security", "type", "flow", "sni", "fp", "alpn", "allowInsecure",
        "pbk", "sid", "spx", "host", "path", "serviceName", "mode", "extra", "dns", "headerType",
    )

    fun parse(raw: String): StandardProfile {
        val scheme = raw.substringBefore(':').lowercase()
        return when (scheme) {
            "vless" -> parseUrl(raw, StandardProfile.Protocol.VLESS)
            "trojan" -> parseUrl(raw, StandardProfile.Protocol.TROJAN)
            "vmess" -> parseVmess(raw)
            "ss" -> parseShadowsocks(raw)
            else -> throw IllegalArgumentException("Unsupported standard profile scheme")
        }
    }

    fun serialize(profile: StandardProfile): String = when (profile.protocol) {
        StandardProfile.Protocol.VLESS -> serializeUrl(profile, "vless", requireNotNull(profile.uuid))
        StandardProfile.Protocol.TROJAN -> serializeUrl(profile, "trojan", requireNotNull(profile.password))
        StandardProfile.Protocol.VMESS -> serializeVmess(profile)
        StandardProfile.Protocol.SHADOWSOCKS -> serializeShadowsocks(profile)
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
        require(params["headerType"] == null || params["headerType"].equals("none", ignoreCase = true)) {
            "Unsupported stream header"
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
            webSocketHost = params["host"]?.takeIf { transport == StandardProfile.Transport.WS && it.isNotBlank() },
            webSocketPath = path?.takeIf { transport == StandardProfile.Transport.WS && it.isNotBlank() },
            grpcServiceName = (params["serviceName"] ?: path)
                ?.takeIf { transport == StandardProfile.Transport.GRPC && it.isNotBlank() },
            xhttpMode = params["mode"]?.takeIf { transport == StandardProfile.Transport.XHTTP && it.isNotBlank() },
            xhttpHost = params["host"]?.takeIf { transport == StandardProfile.Transport.XHTTP && it.isNotBlank() },
            xhttpPath = path?.takeIf { transport == StandardProfile.Transport.XHTTP && it.isNotBlank() },
            xhttpExtraJson = params["extra"]?.takeIf { transport == StandardProfile.Transport.XHTTP && it.isNotBlank() },
            dnsServer = params["dns"]?.takeIf(String::isNotBlank),
        )
    }

    private fun parseVmess(raw: String): StandardProfile {
        val payload = raw.substringAfter(':').removePrefix("//")
        require(payload.isNotBlank()) { "VMess payload is required" }
        val json = decodeVmessPayload(payload)
        require(json.trimStart().startsWith('{')) { "Invalid VMess Base64" }
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
            webSocketPath = path.takeIf { transport == StandardProfile.Transport.WS },
            grpcServiceName = path.takeIf { transport == StandardProfile.Transport.GRPC },
            dnsServer = values["dns"]?.takeIf(String::isNotBlank),
        )
    }

    private fun parseShadowsocks(raw: String): StandardProfile {
        val encoded = raw.substringAfter(':', "").removePrefix("//")
        require(encoded.isNotBlank()) { "Shadowsocks payload is required" }
        val rawFragment = encoded.substringAfter('#', "")
        val withoutFragment = encoded.substringBefore('#')
        val authority = withoutFragment.substringBefore('?')
        val query = withoutFragment.substringAfter('?', "")
        val params = parseQuery(query.takeIf(String::isNotEmpty))
        require(params.keys.all { it == "plugin" }) { "Unsupported Shadowsocks parameters" }
        require(params["plugin"].isNullOrBlank()) { "Shadowsocks plugins are not supported" }

        val (credentials, endpoint) = if ('@' in authority) {
            decodeBase64Text(authority.substringBeforeLast('@')) to authority.substringAfterLast('@')
        } else {
            val legacy = decodeBase64Text(authority)
            require('@' in legacy) { "Invalid Shadowsocks payload" }
            legacy.substringBeforeLast('@') to legacy.substringAfterLast('@')
        }
        val separator = credentials.indexOf(':')
        require(separator > 0 && separator < credentials.lastIndex) { "Invalid Shadowsocks credentials" }
        val method = credentials.substring(0, separator).lowercase()
        val password = credentials.substring(separator + 1)
        val server = URI("ss://user@$endpoint")
        val address = server.host?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("address is required")
        require(server.port in 1..65535) { "port must be in 1..65535" }
        return StandardProfile(
            name = rawFragment.takeIf(String::isNotEmpty)?.let(::decode).orEmpty().ifBlank { "Shadowsocks" },
            protocol = StandardProfile.Protocol.SHADOWSOCKS,
            address = address,
            port = server.port,
            password = password,
            cipher = method,
        )
    }

    private fun serializeUrl(profile: StandardProfile, scheme: String, credential: String): String {
        val params = linkedMapOf<String, String>()
        if (profile.protocol == StandardProfile.Protocol.VLESS) params["encryption"] = "none"
        params["type"] = when (profile.transport) {
            StandardProfile.Transport.TCP -> "tcp"
            StandardProfile.Transport.WS -> "ws"
            StandardProfile.Transport.GRPC -> "grpc"
            StandardProfile.Transport.XHTTP -> "xhttp"
        }
        params["security"] = when (profile.security) {
            StandardProfile.Security.NONE -> "none"
            StandardProfile.Security.TLS -> "tls"
            StandardProfile.Security.REALITY -> "reality"
        }
        profile.flow?.let { params["flow"] = it }
        profile.serverName?.let { params["sni"] = it }
        if (profile.alpn.isNotEmpty()) params["alpn"] = profile.alpn.joinToString(",")
        profile.fingerprint?.let { params["fp"] = it }
        if (profile.allowInsecure) params["allowInsecure"] = "1"
        profile.realityPublicKey?.let { params["pbk"] = it }
        profile.realityShortId?.let { params["sid"] = it }
        profile.realitySpiderX?.let { params["spx"] = it }
        when (profile.transport) {
            StandardProfile.Transport.WS -> {
                profile.webSocketHost?.let { params["host"] = it }
                profile.webSocketPath?.let { params["path"] = it }
            }
            StandardProfile.Transport.GRPC -> profile.grpcServiceName?.let { params["serviceName"] = it }
            StandardProfile.Transport.XHTTP -> {
                profile.xhttpMode?.let { params["mode"] = it }
                profile.xhttpHost?.let { params["host"] = it }
                profile.xhttpPath?.let { params["path"] = it }
                profile.xhttpExtraJson?.let { params["extra"] = it }
            }
            StandardProfile.Transport.TCP -> Unit
        }
        profile.dnsServer?.let { params["dns"] = it }
        val query = params.entries.joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }
        return "$scheme://${encode(credential)}@${profile.address}:${profile.port}?$query#${encode(profile.name)}"
    }

    private fun serializeVmess(profile: StandardProfile): String {
        val values = linkedMapOf(
            "v" to "2",
            "ps" to profile.name,
            "add" to profile.address,
            "port" to profile.port.toString(),
            "id" to profile.uuid.orEmpty(),
            "aid" to profile.alterId.toString(),
            "scy" to profile.cipher,
            "net" to when (profile.transport) {
                StandardProfile.Transport.TCP -> "tcp"
                StandardProfile.Transport.WS -> "ws"
                StandardProfile.Transport.GRPC -> "grpc"
                StandardProfile.Transport.XHTTP -> "xhttp"
            },
            "type" to "none",
            "tls" to when (profile.security) {
                StandardProfile.Security.NONE -> ""
                StandardProfile.Security.TLS -> "tls"
                StandardProfile.Security.REALITY -> "reality"
            },
            "sni" to profile.serverName.orEmpty(),
            "alpn" to profile.alpn.joinToString(","),
            "fp" to profile.fingerprint.orEmpty(),
            "allowInsecure" to if (profile.allowInsecure) "1" else "0",
            "host" to profile.webSocketHost.orEmpty(),
            "path" to when (profile.transport) {
                StandardProfile.Transport.WS -> profile.webSocketPath.orEmpty()
                StandardProfile.Transport.GRPC -> profile.grpcServiceName.orEmpty()
                else -> ""
            },
            "dns" to profile.dnsServer.orEmpty(),
        )
        val json = values.entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
            "\"${escapeJson(key)}\":\"${escapeJson(value)}\""
        }
        return "vmess://" + Base64.getUrlEncoder().withoutPadding().encodeToString(json.encodeToByteArray())
    }

    private fun serializeShadowsocks(profile: StandardProfile): String {
        val credentials = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("${profile.cipher}:${requireNotNull(profile.password)}".encodeToByteArray())
        val address = if (':' in profile.address) "[${profile.address}]" else profile.address
        return "ss://$credentials@$address:${profile.port}#${encode(profile.name)}"
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

    private fun decodeVmessPayload(value: String): String {
        val normalized = value.trim().replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        return runCatching { Base64.getDecoder().decode(padded) }
            .getOrElse { throw IllegalArgumentException("Invalid VMess Base64", it) }
            .let { bytes ->
                runCatching { bytes.decodeToString() }
                    .getOrElse { throw IllegalArgumentException("Invalid VMess Base64", it) }
            }
    }

    private fun decodeBase64Text(value: String): String {
        val normalized = value.trim().replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        val bytes = runCatching { Base64.getDecoder().decode(padded) }
            .getOrElse { throw IllegalArgumentException("Invalid Shadowsocks Base64", it) }
        return runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }
            .getOrElse { throw IllegalArgumentException("Invalid Shadowsocks Base64", it) }
    }

    private fun decode(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8.name())

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    private fun escapeJson(value: String): String = buildString {
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
    }

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
