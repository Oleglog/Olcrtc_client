package io.github.oleglog.olcrtc.client.updater

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

internal data class GitHubRelease(
    val tagName: String,
    val name: String,
    val prerelease: Boolean,
    val body: String,
    val assets: List<ReleaseAsset>,
) {
    data class ReleaseAsset(
        val name: String,
        val downloadUrl: String,
        val size: Long,
    )
}

internal object GitHubReleaseParser {
    fun parse(rawJson: String): GitHubRelease = parse(JSONObject(rawJson))

    fun parseList(rawJson: String): List<GitHubRelease> = JSONArray(rawJson).asSequence()
        .map { value -> parse(value as JSONObject) }
        .toList()

    private fun parse(root: JSONObject): GitHubRelease {
        val assets = root.getJSONArray("assets").asSequence()
            .map { value -> value as JSONObject }
            .map { asset ->
                GitHubRelease.ReleaseAsset(
                    name = asset.getString("name"),
                    downloadUrl = asset.getString("browser_download_url"),
                    size = asset.optLong("size", 0),
                )
            }
            .toList()
        return GitHubRelease(
            tagName = root.getString("tag_name"),
            name = root.optString("name", root.getString("tag_name")),
            prerelease = root.optBoolean("prerelease", false),
            body = root.optString("body", ""),
            assets = assets,
        )
    }

    private fun JSONArray.asSequence(): Sequence<Any> = sequence {
        for (index in 0 until length()) yield(get(index))
    }
}

internal object UpdateAssetSelector {
    fun selectApk(release: GitHubRelease, supportedAbis: List<String>): GitHubRelease.ReleaseAsset? {
        val apkAssets = apkAssets(release)
        supportedAbis.forEach { abi ->
            apkAssets.firstOrNull { asset -> asset.name.contains("-$abi.apk", ignoreCase = true) }
                ?.let { return it }
        }
        return apkAssets.firstOrNull { it.name.contains("-universal.apk", ignoreCase = true) }
    }

    fun apkAssets(release: GitHubRelease): List<GitHubRelease.ReleaseAsset> =
        release.assets.filter { it.name.endsWith(".apk", ignoreCase = true) }

    fun isCompatibleApk(asset: GitHubRelease.ReleaseAsset, supportedAbis: List<String>): Boolean =
        asset.name.contains("-universal.apk", ignoreCase = true) ||
            supportedAbis.any { abi -> asset.name.contains("-$abi.apk", ignoreCase = true) }

    fun parseSha256Sums(value: String): Map<String, String> = value
        .lineSequence()
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith('#') }
        .mapNotNull { line ->
            val parts = line.split(Regex("\\s+"), limit = 2)
            val hash = parts.getOrNull(0)?.lowercase(Locale.US)
            val name = parts.getOrNull(1)?.trimStart('*')
            if (hash != null && name != null && SHA256.matches(hash)) name to hash else null
        }
        .toMap()

    private val SHA256 = Regex("^[0-9a-f]{64}$")
}
