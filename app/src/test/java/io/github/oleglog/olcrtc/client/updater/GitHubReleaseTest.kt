package io.github.oleglog.olcrtc.client.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReleaseTest {
    private val arm64 = asset("olcRTC-Client-v1.1.1-arm64-v8a.apk")
    private val armv7 = asset("olcRTC-Client-v1.1.1-armeabi-v7a.apk")
    private val universal = asset("olcRTC-Client-v1.1.1-universal.apk")
    private val release = GitHubRelease("v1.1.1", "v1.1.1", false, "", listOf(universal, armv7, arm64, asset("SHA256SUMS.txt")))

    @Test
    fun selectsBestSupportedAbiBeforeUniversal() {
        assertEquals(arm64, UpdateAssetSelector.selectApk(release, listOf("arm64-v8a", "armeabi-v7a")))
        assertEquals(armv7, UpdateAssetSelector.selectApk(release, listOf("armeabi-v7a")))
        assertEquals(universal, UpdateAssetSelector.selectApk(release, listOf("x86_64")))
        assertTrue(UpdateAssetSelector.isCompatibleApk(arm64, listOf("arm64-v8a")))
        assertFalse(UpdateAssetSelector.isCompatibleApk(arm64, listOf("armeabi-v7a")))
    }

    @Test
    fun excludesNonApkAssetsAndReturnsNullWithoutFallback() {
        assertEquals(3, UpdateAssetSelector.apkAssets(release).size)
        assertNull(UpdateAssetSelector.selectApk(release.copy(assets = listOf(asset("SHA256SUMS.txt"))), listOf("arm64-v8a")))
    }

    @Test
    fun promptsOncePerNewCompatibleReleaseAndThrottlesChecks() {
        val result = UpdateCheckResult(release, arm64, newerThanCurrent = true)
        assertTrue(shouldShowUpdatePrompt(result, null))
        assertFalse(shouldShowUpdatePrompt(result, "v1.1.1"))
        assertFalse(shouldShowUpdatePrompt(result.copy(newerThanCurrent = false), null))
        assertFalse(shouldShowUpdatePrompt(result.copy(selectedAsset = null), null))
        assertTrue(shouldRunAutomaticUpdateCheck(false, 0, 10, 300_000))
        assertFalse(shouldRunAutomaticUpdateCheck(false, 10, 20, 300_000))
        assertFalse(shouldRunAutomaticUpdateCheck(true, 0, 10, 300_000))
    }

    private fun asset(name: String) = GitHubRelease.ReleaseAsset(name, "https://example.com/$name", 1)
}
