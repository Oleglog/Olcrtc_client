package io.github.oleglog.olcrtc.client.updater

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

internal data class VerifiedApkUpdate(
    val apkFile: File,
    val release: GitHubRelease,
    val asset: GitHubRelease.ReleaseAsset,
    val sha256: String,
)

internal enum class UpdateInstallAction {
    REQUEST_PERMISSION,
    DOWNLOAD,
}

internal fun updateInstallAction(canRequestPackageInstalls: Boolean): UpdateInstallAction =
    if (canRequestPackageInstalls) UpdateInstallAction.DOWNLOAD else UpdateInstallAction.REQUEST_PERMISSION

internal class ApkUpdateInstaller(
    private val context: Context,
    private val expectedPackageName: String = context.packageName,
    private val expectedSigningCertSha256: String = io.github.oleglog.olcrtc.client.BuildConfig.EXPECTED_SIGNING_CERT_SHA256,
) {
    fun downloadAndVerify(release: GitHubRelease, asset: GitHubRelease.ReleaseAsset): VerifiedApkUpdate {
        require(asset.downloadUrl.startsWith("https://", ignoreCase = true)) { "APK download must use HTTPS" }
        val checksumAsset = release.assets.firstOrNull { it.name.equals("SHA256SUMS.txt", ignoreCase = true) }
            ?: throw IllegalArgumentException("SHA256SUMS.txt is missing")
        val checksums = UpdateAssetSelector.parseSha256Sums(fetchText(checksumAsset.downloadUrl))
        val expectedSha256 = checksums[asset.name]
            ?: throw IllegalArgumentException("SHA-256 checksum for ${asset.name} is missing")
        val apkFile = downloadApk(asset)
        val actualSha256 = sha256(apkFile)
        require(actualSha256.equals(expectedSha256, ignoreCase = true)) { "APK checksum mismatch" }
        verifyPackage(apkFile)
        return VerifiedApkUpdate(apkFile, release, asset, actualSha256)
    }

    fun installIntent(update: VerifiedApkUpdate): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", update.apkFile)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun canRequestPackageInstalls(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    private fun downloadApk(asset: GitHubRelease.ReleaseAsset): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val file = File(dir, asset.name)
        val connection = URL(asset.downloadUrl).openConnection() as HttpsURLConnection
        connection.connectTimeout = TIMEOUT_MILLIS
        connection.readTimeout = TIMEOUT_MILLIS
        connection.requestMethod = "GET"
        connection.use {
            require(responseCode in 200..299) { "APK download failed: HTTP $responseCode" }
            inputStream.use { input -> file.outputStream().use(input::copyTo) }
        }
        return file
    }

    private fun fetchText(url: String): String {
        require(url.startsWith("https://", ignoreCase = true)) { "Checksum download must use HTTPS" }
        val connection = URL(url).openConnection() as HttpsURLConnection
        connection.connectTimeout = TIMEOUT_MILLIS
        connection.readTimeout = TIMEOUT_MILLIS
        connection.requestMethod = "GET"
        return connection.use {
            require(responseCode in 200..299) { "Checksum download failed: HTTP $responseCode" }
            inputStream.reader().use { it.readText() }
        }
    }

    private fun verifyPackage(apkFile: File) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val archive = context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, flags)
            ?: throw IllegalArgumentException("APK package metadata is unreadable")
        require(archive.packageName == expectedPackageName) { "APK package name mismatch" }
        val expectedDigest = expectedSigningCertSha256.takeIf(String::isNotBlank) ?: return
        val actualDigests = archive.signingCertificateDigests()
        require(actualDigests.any { it.equals(expectedDigest, ignoreCase = true) }) {
            "APK signing certificate mismatch"
        }
    }

    private fun PackageInfo.signingCertificateDigests(): List<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        signingInfo?.apkContentsSigners.orEmpty().map { signature -> sha256(signature.toByteArray()) }
    } else {
        @Suppress("DEPRECATION")
        signatures.orEmpty().map { signature -> sha256(signature.toByteArray()) }
    }

    private inline fun <T> HttpsURLConnection.use(block: HttpsURLConnection.() -> T): T = try {
        block()
    } finally {
        disconnect()
    }

    companion object {
        private const val TIMEOUT_MILLIS = 30_000
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"

        fun sha256(file: File): String = file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
            digest.digest().toHex()
        }

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .toHex()

        private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }
    }
}
