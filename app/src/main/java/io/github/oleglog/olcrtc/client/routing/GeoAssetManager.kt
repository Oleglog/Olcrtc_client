package io.github.oleglog.olcrtc.client.routing

import android.content.Context
import android.system.Os
import io.github.oleglog.olcrtc.client.vpn.GomobileCore
import io.github.oleglog.olcrtc.client.vpn.NativeConfig
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

internal class GeoAssetManager(private val context: Context) {
    @Volatile private var preparedDirectory: File? = null

    @Synchronized
    fun prepare(): Result<File> {
        preparedDirectory?.let { return Result.success(it) }
        return runCatching {
            val directory = assetDirectory()
            FILES.forEach { asset ->
                val target = File(directory, asset.name)
                if (!target.isFile || sha256(target) != asset.sha256) {
                    copyBundled(asset, target)
                }
            }
            validate(directory)
            directory
        }.onSuccess { preparedDirectory = it }
    }

    @Synchronized
    fun updateFromDefaultSources(): Result<File> = runCatching {
        val directory = assetDirectory()
        val backups = FILES.associate { asset ->
            val target = File(directory, asset.name)
            val backup = File(directory, "${asset.name}.bak")
            if (target.isFile) target.copyTo(backup, overwrite = true)
            asset.name to backup
        }
        runCatching {
            DEFAULT_SOURCES.forEach { source -> download(source.url, File(directory, source.name)) }
            validate(directory)
            backups.values.forEach(File::delete)
            directory
        }.getOrElse { error ->
            backups.forEach { (name, backup) ->
                if (backup.isFile) backup.copyTo(File(directory, name), overwrite = true)
            }
            throw error
        }
    }.onSuccess { preparedDirectory = it }

    private fun assetDirectory(): File = File(context.noBackupFilesDir, DIRECTORY).apply { mkdirs() }

    private fun validate(directory: File) {
        GomobileCore.validateXrayConfig(
            directory.absolutePath,
            NativeConfig.xray(
                socksPort = 1080,
                routingPolicy = RoutingPolicy(RoutingPolicy.Preset.RUSSIA_DIRECT),
            ),
        )
    }

    private fun download(url: String, target: File) {
        require(url.startsWith("https://", ignoreCase = true)) { "Geo asset URL must use HTTPS" }
        val temporary = File(target.parentFile, "${target.name}.download")
        temporary.delete()
        val connection = URL(url).openConnection() as HttpsURLConnection
        connection.connectTimeout = TIMEOUT_MILLIS
        connection.readTimeout = TIMEOUT_MILLIS
        connection.requestMethod = "GET"
        try {
            require(connection.responseCode in 200..299) { "Geo asset download failed: HTTP ${connection.responseCode}" }
            connection.inputStream.use { input -> FileOutputStream(temporary).use { output -> input.copyTo(output) } }
        } finally {
            connection.disconnect()
        }
        require(temporary.length() > 0) { "Downloaded geo asset is empty" }
        Os.rename(temporary.absolutePath, target.absolutePath)
    }

    private fun copyBundled(asset: Asset, target: File) {
        val temporary = File(target.parentFile, "${target.name}.tmp")
        temporary.delete()
        context.assets.open("geo/${asset.name}").use { input ->
            FileOutputStream(temporary).use { output -> input.copyTo(output) }
        }
        check(sha256(temporary) == asset.sha256) { "Invalid bundled ${asset.name}" }
        Os.rename(temporary.absolutePath, target.absolutePath)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private data class Asset(val name: String, val sha256: String)

    private data class Source(val name: String, val url: String)

    private companion object {
        const val DIRECTORY = "xray-assets"
        const val TIMEOUT_MILLIS = 30_000
        val FILES = listOf(
            Asset("geoip.dat", "b9f84648ec798e2c2ce4b2991a2891ed433689943aaefb098592177cf4ed789a"),
            Asset("geosite.dat", "3fa6caf07505ca7f5c2d269da0e915d87e6c9b959b47e82865084b7b107119ec"),
        )
        val DEFAULT_SOURCES = listOf(
            Source("geoip.dat", "https://github.com/v2fly/geoip/releases/latest/download/geoip.dat"),
            Source("geosite.dat", "https://github.com/v2fly/domain-list-community/releases/latest/download/dlc.dat"),
        )
    }
}
