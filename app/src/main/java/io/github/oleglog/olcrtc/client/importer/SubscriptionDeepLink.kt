package io.github.oleglog.olcrtc.client.importer

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64

internal data class SubscriptionDeepLink(
    val url: String,
    val name: String?,
    val mirrorType: String?,
    val mirrorUrl: String?,
    val mirrorKey: String?,
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
        val sourceUrl = singleValue(query, "url")
            ?: throw IllegalArgumentException("subscription URL is required")
        val name = singleValue(query, "name")
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.also { require(it.length <= MAX_NAME_CHARS) { "subscription name is too large" } }
        val mirrorTypeValue = singleValue(query, "mirror_type")
        val mirrorUrlValue = singleValue(query, "mirror_url")
        val mirrorKeyValue = singleValue(query, "mirror_key")
        val mirrorFieldCount = listOf(mirrorTypeValue, mirrorUrlValue, mirrorKeyValue).count { it != null }
        require(mirrorFieldCount == 0 || mirrorFieldCount == 3) { "subscription mirror fields are incomplete" }
        val mirrorType = mirrorTypeValue?.trim()?.lowercase()
        if (mirrorType != null) require(mirrorType == "yandex_disk") { "unsupported subscription mirror" }
        return SubscriptionDeepLink(
            url = validateHttpsUrl(sourceUrl),
            name = name,
            mirrorType = mirrorType,
            mirrorUrl = mirrorUrlValue?.let(::validateHttpsUrl),
            mirrorKey = mirrorKeyValue?.let(::validateMirrorKey),
        )
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

    private fun singleValue(query: Map<String, List<String>>, name: String): String? {
        val values = query[name] ?: return null
        require(values.size == 1) { "$name must appear once" }
        return values.single()
    }

    private fun validateMirrorKey(raw: String): String {
        val value = raw.trim()
        require(value.matches(Regex("[A-Za-z0-9_-]{43}"))) { "invalid subscription mirror key" }
        require(Base64.getUrlDecoder().decode("$value=").size == 32) { "invalid subscription mirror key" }
        return value
    }
}
