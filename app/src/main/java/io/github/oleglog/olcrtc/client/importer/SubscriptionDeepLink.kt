package io.github.oleglog.olcrtc.client.importer

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

internal data class SubscriptionDeepLink(
    val url: String,
    val name: String?,
)

internal object SubscriptionDeepLinkParser {
    private const val MAX_LINK_CHARS = 8 * 1024
    private const val MAX_SOURCE_URL_CHARS = 4 * 1024
    private const val MAX_NAME_CHARS = 120

    fun parseOrNull(raw: String): SubscriptionDeepLink? {
        val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
        if (!uri.scheme.equals("olcrtc", ignoreCase = true) || !uri.host.equals("subscription", ignoreCase = true)) {
            return null
        }
        require(raw.length <= MAX_LINK_CHARS) { "subscription link is too large" }
        require(uri.path.isNullOrEmpty() || uri.path == "/") { "invalid subscription link path" }
        require(uri.fragment == null) { "subscription link fragment is not allowed" }
        val query = parseQuery(uri.rawQuery)
        val sourceUrl = query["url"]?.singleOrNull()
            ?: throw IllegalArgumentException("subscription URL is required")
        val name = query["name"]?.singleOrNull()
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.also { require(it.length <= MAX_NAME_CHARS) { "subscription name is too large" } }
        return SubscriptionDeepLink(validateHttpsUrl(sourceUrl), name)
    }

    fun validateHttpsUrl(raw: String): String {
        val value = raw.trim()
        require(value.length <= MAX_SOURCE_URL_CHARS) { "subscription URL is too large" }
        val uri = URI(value)
        require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()) {
            "subscription URL must use HTTPS"
        }
        require(uri.userInfo == null) { "subscription URL credentials are not allowed" }
        require(uri.fragment == null) { "subscription URL fragment is not allowed" }
        return value
    }

    private fun parseQuery(rawQuery: String?): Map<String, List<String>> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return rawQuery.split('&').map { item ->
            val parts = item.split('=', limit = 2)
            decode(parts[0]) to decode(parts.getOrElse(1) { "" })
        }.groupBy({ it.first }, { it.second })
    }

    private fun decode(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
}
