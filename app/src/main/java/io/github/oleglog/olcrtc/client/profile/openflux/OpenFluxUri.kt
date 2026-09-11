package io.github.oleglog.olcrtc.client.profile.openflux

import android.net.Uri
import java.net.URLDecoder
import java.net.URLEncoder

internal object OpenFluxUri {
    fun parse(raw: String): OpenFluxProfile {
        val trimmed = raw.trim()
        require(trimmed.startsWith("openflux://", ignoreCase = true)) {
            "OpenFlux profile URI must start with openflux://"
        }

        // Format: openflux://yandex?url=<doc_url>&t=vyandex&dns=8.8.8.8#ProfileName
        // Or: openflux://?url=<doc_url>#ProfileName
        val uri = Uri.parse(trimmed)
        val urlParam = uri.getQueryParameter("url") ?: uri.getQueryParameter("u")
        require(!urlParam.isNullOrBlank()) { "OpenFlux URI requires 'url' parameter" }

        val transportParam = uri.getQueryParameter("t") ?: uri.getQueryParameter("transport") ?: "auto"
        val dnsParam = uri.getQueryParameter("d") ?: uri.getQueryParameter("dns")
        val fragment = uri.fragment?.takeIf(String::isNotBlank)

        val name = fragment ?: "OpenFlux"

        return OpenFluxProfile(
            name = name,
            documentUrl = urlParam,
            transport = OpenFluxProfile.Transport.parse(transportParam),
            dnsServer = dnsParam,
        )
    }

    fun serialize(profile: OpenFluxProfile): String {
        val encodedUrl = URLEncoder.encode(profile.documentUrl, "UTF-8")
        val builder = StringBuilder("openflux://yandex?url=").append(encodedUrl)
        if (profile.transport != OpenFluxProfile.Transport.AUTO) {
            builder.append("&t=").append(profile.transport.value)
        }
        if (!profile.dnsServer.isNullOrBlank()) {
            builder.append("&d=").append(URLEncoder.encode(profile.dnsServer, "UTF-8"))
        }
        if (profile.name.isNotBlank()) {
            builder.append("#").append(URLEncoder.encode(profile.name, "UTF-8"))
        }
        return builder.toString()
    }
}
