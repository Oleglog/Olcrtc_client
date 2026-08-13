package io.github.oleglog.olcrtc.client.updater

import android.os.Bundle

// ponytail: oneBundle adapter for shipping UpdateCheckResult across the AIDL boundary —
// VPN service packs it after fetching through the SOCKS-loopback proxy, MainActivity unpacks
// it back into the UpdateCheckResult the existing UI consumers expect. Keys live here so the
// two sides never drift.
internal object UpdateCheckWire {
    private const val KEY_SUCCESS = "success"
    private const val KEY_NEWER = "newer"
    private const val KEY_TAG = "tag"
    private const val KEY_NAME = "name"
    private const val KEY_PRERELEASE = "prerelease"
    private const val KEY_BODY = "body"
    private const val KEY_ASSET_NAME = "assetName"
    private const val KEY_ASSET_DOWNLOAD_URL = "assetDownloadUrl"
    private const val KEY_ASSET_SIZE = "assetSize"

    fun pack(result: UpdateCheckResult): Bundle = Bundle().apply {
        putBoolean(KEY_SUCCESS, true)
        putBoolean(KEY_NEWER, result.newerThanCurrent)
        val release = result.release
        putString(KEY_TAG, release.tagName)
        putString(KEY_NAME, release.name)
        putBoolean(KEY_PRERELEASE, release.prerelease)
        putString(KEY_BODY, release.body)
        val asset = result.selectedAsset
        putString(KEY_ASSET_NAME, asset?.name.orEmpty())
        putString(KEY_ASSET_DOWNLOAD_URL, asset?.downloadUrl.orEmpty())
        putLong(KEY_ASSET_SIZE, asset?.size ?: 0L)
    }

    fun failure(): Bundle = Bundle().apply { putBoolean(KEY_SUCCESS, false) }

    fun unpack(bundle: Bundle): UpdateCheckResult? {
        if (!bundle.getBoolean(KEY_SUCCESS, false)) return null
        val tagName = bundle.getString(KEY_TAG).orEmpty()
        val assetName = bundle.getString(KEY_ASSET_NAME).orEmpty()
        val asset = if (assetName.isEmpty()) null else GitHubRelease.ReleaseAsset(
            name = assetName,
            downloadUrl = bundle.getString(KEY_ASSET_DOWNLOAD_URL).orEmpty(),
            size = bundle.getLong(KEY_ASSET_SIZE, 0L),
        )
        val release = GitHubRelease(
            tagName = tagName,
            name = bundle.getString(KEY_NAME).orEmpty(),
            prerelease = bundle.getBoolean(KEY_PRERELEASE, false),
            body = bundle.getString(KEY_BODY).orEmpty(),
            assets = listOfNotNull(asset),
        )
        return UpdateCheckResult(
            release = release,
            selectedAsset = asset,
            newerThanCurrent = bundle.getBoolean(KEY_NEWER, false),
        )
    }
}
