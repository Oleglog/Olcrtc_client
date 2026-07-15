package io.github.oleglog.olcrtc.client.routing

import android.content.Context
import android.system.Os
import io.github.oleglog.olcrtc.client.vpn.GomobileCore
import io.github.oleglog.olcrtc.client.vpn.NativeConfig
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

internal class GeoAssetManager(private val context: Context) {
    fun prepare(): Result<File> = runCatching {
        val directory = File(context.noBackupFilesDir, DIRECTORY)
        directory.mkdirs()
        FILES.forEach { asset ->
            val target = File(directory, asset.name)
            if (!target.isFile || sha256(target) != asset.sha256) {
                copyBundled(asset, target)
            }
        }
        GomobileCore.validateXrayConfig(
            directory.absolutePath,
            NativeConfig.xray(
                socksPort = 1080,
                routingPolicy = RoutingPolicy(RoutingPolicy.Preset.RUSSIA_DIRECT),
            ),
        )
        directory
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

    private companion object {
        const val DIRECTORY = "xray-assets"
        val FILES = listOf(
            Asset("geoip.dat", "b9f84648ec798e2c2ce4b2991a2891ed433689943aaefb098592177cf4ed789a"),
            Asset("geosite.dat", "3fa6caf07505ca7f5c2d269da0e915d87e6c9b959b47e82865084b7b107119ec"),
        )
    }
}
