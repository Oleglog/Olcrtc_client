package io.github.oleglog.olcrtc.client.routing

import java.net.Inet6Address
import java.net.InetAddress

internal data class DnsEndpoint private constructor(
    val address: String,
    val port: Int,
) {
    override fun toString(): String = if (address.contains(':')) "[$address]:$port" else "$address:$port"

    companion object {
        const val DEFAULT = "77.88.8.8:53"

        fun parse(value: String): DnsEndpoint {
            val trimmed = value.trim()
            val (address, portText) = if (trimmed.startsWith('[')) {
                val closing = trimmed.indexOf(']')
                require(closing > 1 && closing + 1 < trimmed.length && trimmed[closing + 1] == ':') {
                    "DNS must be an IP:port"
                }
                trimmed.substring(1, closing) to trimmed.substring(closing + 2)
            } else {
                require(trimmed.count { it == ':' } == 1) { "DNS must be an IP:port" }
                trimmed.substringBefore(':') to trimmed.substringAfter(':')
            }
            require('%' !in address) { "Scoped DNS addresses are not supported" }
            val port = portText.toIntOrNull()
                ?: throw IllegalArgumentException("DNS port must be an integer")
            require(port in 1..65535) { "DNS port must be in 1..65535" }
            val parsed = if (address.contains(':')) parseIpv6(address) else parseIpv4(address)
            return DnsEndpoint(parsed.hostAddress.substringBefore('%'), port)
        }

        private fun parseIpv4(value: String): InetAddress {
            val parts = value.split('.')
            require(parts.size == 4 && parts.all { part ->
                part.isNotEmpty() && part.all(Char::isDigit) &&
                    (part == "0" || !part.startsWith('0')) &&
                    part.toIntOrNull() in 0..255
            }) { "DNS address must be numeric IPv4 or IPv6" }
            return InetAddress.getByAddress(parts.map(String::toInt).map(Int::toByte).toByteArray())
        }

        private fun parseIpv6(value: String): InetAddress {
            require(value.all { it == ':' || it == '.' || it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                "DNS address must be numeric IPv4 or IPv6"
            }
            val parsed = runCatching { InetAddress.getByName(value) }.getOrNull()
            require(parsed is Inet6Address) { "DNS address must be numeric IPv4 or IPv6" }
            return parsed
        }
    }
}
