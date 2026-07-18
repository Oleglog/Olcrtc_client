package io.github.oleglog.olcrtc.client.updater

import android.os.Build
import java.net.URL
import javax.net.ssl.HttpsURLConnection

internal data class UpdateCheckResult(
    val release: GitHubRelease,
    val selectedAsset: GitHubRelease.ReleaseAsset?,
    val newerThanCurrent: Boolean,
)

internal class GitHubUpdateClient(
    private val releaseEndpoint: String = LATEST_RELEASE_URL,
    private val releasesEndpoint: String = RELEASES_URL,
    private val currentVersion: String,
    private val supportedAbis: List<String> = Build.SUPPORTED_ABIS.toList(),
) {
    fun check(): UpdateCheckResult {
        val release = GitHubReleaseParser.parse(fetchText(releaseEndpoint))
        return UpdateCheckResult(
            release = release,
            selectedAsset = UpdateAssetSelector.selectApk(release, supportedAbis),
            newerThanCurrent = VersionComparator.isNewer(release.tagName, currentVersion),
        )
    }

    fun listReleases(): List<GitHubRelease> = GitHubReleaseParser.parseList(fetchText(releasesEndpoint))
        .filterNot { it.prerelease }

    private fun fetchText(url: String): String {
        val connection = URL(url).openConnection() as HttpsURLConnection
        connection.connectTimeout = TIMEOUT_MILLIS
        connection.readTimeout = TIMEOUT_MILLIS
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "olcRTC-Client")
        return connection.use {
            require(responseCode in 200..299) { "GitHub update check failed: HTTP $responseCode" }
            inputStream.reader().use { it.readText() }
        }
    }

    private inline fun <T> HttpsURLConnection.use(block: HttpsURLConnection.() -> T): T = try {
        block()
    } finally {
        disconnect()
    }

    companion object {
        private const val TIMEOUT_MILLIS = 15_000
        private const val LATEST_RELEASE_URL = "https://api.github.com/repos/Oleglog/Olcrtc_client/releases/latest"
        private const val RELEASES_URL = "https://api.github.com/repos/Oleglog/Olcrtc_client/releases?per_page=5"
    }
}

internal fun shouldRunAutomaticUpdateCheck(
    checkInProgress: Boolean,
    lastCheckElapsedMillis: Long,
    nowElapsedMillis: Long,
    minimumIntervalMillis: Long,
): Boolean = !checkInProgress &&
    (lastCheckElapsedMillis == 0L || nowElapsedMillis - lastCheckElapsedMillis >= minimumIntervalMillis)

internal fun shouldShowUpdatePrompt(result: UpdateCheckResult, lastPromptedTag: String?): Boolean =
    result.newerThanCurrent && result.selectedAsset != null && result.release.tagName != lastPromptedTag

internal object VersionComparator {
    fun isNewer(candidate: String, current: String): Boolean {
        val left = normalize(candidate)
        val right = normalize(current)
        repeat(maxOf(left.size, right.size)) { index ->
            val diff = left.getOrElse(index) { 0 } - right.getOrElse(index) { 0 }
            if (diff != 0) return diff > 0
        }
        return false
    }

    private fun normalize(value: String): List<Int> = value
        .trim()
        .removePrefix("v")
        .substringBefore('-')
        .split('.')
        .map { it.toIntOrNull() ?: 0 }
}
