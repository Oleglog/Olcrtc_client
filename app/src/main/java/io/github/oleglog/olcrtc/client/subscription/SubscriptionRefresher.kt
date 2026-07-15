package io.github.oleglog.olcrtc.client.subscription

import io.github.oleglog.olcrtc.client.data.ProfileRepository
import io.github.oleglog.olcrtc.client.data.SubscriptionSource
import io.github.oleglog.olcrtc.client.importer.Json
import io.github.oleglog.olcrtc.client.importer.SubscriptionPayload
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal class SubscriptionRefresher(
    private val repository: ProfileRepository,
    private val userHttp: SubscriptionHttpClient = SubscriptionHttpClient(),
    private val strictHttp: SubscriptionHttpClient = SubscriptionHttpClient(),
) {
    fun refreshStale(now: Long = System.currentTimeMillis()): Int =
        repository.getStaleSubscriptionIds(now).count { refresh(it, now) }

    fun refresh(subscriptionId: Long, now: Long = System.currentTimeMillis()): Boolean {
        val source = requireNotNull(repository.getSubscriptionSource(subscriptionId)) { "Subscription not found" }
        return runCatching { refreshPrimary(subscriptionId, source, now) }
            .recoverCatching { primaryError ->
                if (source.kind != "OLCRTC" || source.mirrorUrl == null || source.mirrorKey == null) {
                    throw primaryError
                }
                refreshMirror(subscriptionId, source, now)
            }
            .getOrElse { error ->
                repository.markSubscriptionRefresh(subscriptionId, errorCode(error), now)
                false
            }
    }

    private fun refreshPrimary(subscriptionId: Long, source: SubscriptionSource, now: Long): Boolean {
        val response = userHttp.get(
            source.url,
            etag = source.etag,
            lastModified = source.lastModified,
            allowUntrustedCertificate = true,
        )
        if (response.notModified) {
            repository.markSubscriptionRefresh(
                subscriptionId = subscriptionId,
                errorCode = null,
                now = now,
                etag = response.etag,
                lastModified = response.lastModified,
                successful = true,
            )
            return true
        }
        val profiles = SubscriptionPayload.parse(requireNotNull(response.body)).profiles
        repository.replaceSubscriptionProfiles(
            subscriptionId = subscriptionId,
            profiles = profiles,
            now = now,
            etag = response.etag,
            lastModified = response.lastModified,
        )
        return true
    }

    private fun refreshMirror(subscriptionId: Long, source: SubscriptionSource, now: Long): Boolean {
        require(source.mirrorType.equals("yandex_disk", ignoreCase = true)) { "Unsupported subscription mirror" }
        val metadataUrl = YANDEX_DOWNLOAD_ENDPOINT + URLEncoder.encode(source.mirrorUrl, StandardCharsets.UTF_8.name())
        val metadata = requireNotNull(strictHttp.get(metadataUrl).body)
        val href = Json.parse(metadata.decodeToString()).objectValue("yandex download")
            .getValue("href")
            .stringValue("href")
        requireHttps(href)
        val envelope = requireNotNull(strictHttp.get(href).body)
        val profiles = SubscriptionPayload.decryptMirror(envelope, source.mirrorKey!!).profiles
        repository.replaceSubscriptionProfiles(subscriptionId, profiles, now)
        return true
    }

    private fun requireHttps(value: String) {
        val uri = runCatching { URI(value) }.getOrElse { throw IllegalArgumentException("Invalid mirror download URL", it) }
        require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()) {
            "Mirror download URL must use HTTPS"
        }
    }

    private fun errorCode(error: Throwable): String = when (error) {
        is javax.net.ssl.SSLException -> "TLS"
        is java.net.SocketTimeoutException -> "TIMEOUT"
        is java.io.IOException -> "NETWORK"
        else -> "INVALID_PAYLOAD"
    }

    private companion object {
        const val YANDEX_DOWNLOAD_ENDPOINT =
            "https://cloud-api.yandex.net/v1/disk/public/resources/download?public_key="
    }
}
