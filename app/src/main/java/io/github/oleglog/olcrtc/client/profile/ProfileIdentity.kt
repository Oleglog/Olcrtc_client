package io.github.oleglog.olcrtc.client.profile

import io.github.oleglog.olcrtc.client.profile.olcrtc.OlcrtcProfile
import io.github.oleglog.olcrtc.client.profile.standard.StandardProfile
import io.github.oleglog.olcrtc.client.routing.DnsEndpoint
import java.security.MessageDigest

internal object ProfileIdentity {
    fun hash(profile: OlcrtcProfile): String = digest(
        "OLCRTC",
        profile.provider.value,
        profile.transport.value,
        profile.roomId,
        profile.roomPassword,
        profile.clientId,
        profile.keyHex.lowercase(),
        profile.authToken,
        profile.dnsServer?.let { DnsEndpoint.parse(it).toString() },
        profile.vp8Fps.toString(),
        profile.vp8BatchSize.toString(),
        profile.keepaliveIntervalSeconds.toString(),
    )

    fun hash(profile: StandardProfile): String = digest(
        profile.protocol.name,
        profile.address.lowercase(),
        profile.port.toString(),
        profile.uuid?.lowercase(),
        profile.password,
        profile.alterId.toString(),
        profile.cipher,
        profile.transport.name,
        profile.security.name,
        profile.flow,
        profile.serverName?.lowercase(),
        profile.alpn.joinToString(separator = "|", transform = ::lengthPrefixed),
        profile.fingerprint,
        profile.allowInsecure.toString(),
        profile.realityPublicKey,
        profile.realityShortId,
        profile.realitySpiderX,
        profile.webSocketHost?.lowercase(),
        profile.webSocketPath,
        profile.grpcServiceName,
        profile.xhttpMode,
        profile.xhttpHost?.lowercase(),
        profile.xhttpPath,
        profile.xhttpExtraJson?.let(::compactJson),
        profile.dnsServer?.let { DnsEndpoint.parse(it).toString() },
    )

    private fun digest(vararg values: String?): String {
        val digest = MessageDigest.getInstance("SHA-256")
        values.forEach { value ->
            val bytes = value?.encodeToByteArray()
            digest.update(if (bytes == null) -1 else bytes.size)
            if (bytes != null) digest.update(bytes)
        }
        val hex = "0123456789abcdef"
        return buildString(64) {
            digest.digest().forEach { byte ->
                val value = byte.toInt() and 0xff
                append(hex[value ushr 4])
                append(hex[value and 0x0f])
            }
        }
    }

    private fun lengthPrefixed(value: String): String = "${value.length}:$value"

    private fun compactJson(value: String): String = buildString(value.length) {
        var quoted = false
        var escaped = false
        value.forEach { character ->
            when {
                escaped -> {
                    append(character)
                    escaped = false
                }
                quoted && character == '\\' -> {
                    append(character)
                    escaped = true
                }
                character == '"' -> {
                    append(character)
                    quoted = !quoted
                }
                quoted || !character.isWhitespace() -> append(character)
            }
        }
    }

    private fun MessageDigest.update(value: Int) {
        update(byteArrayOf(
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte(),
        ))
    }
}
