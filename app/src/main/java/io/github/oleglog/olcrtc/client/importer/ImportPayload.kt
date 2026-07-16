package io.github.oleglog.olcrtc.client.importer

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater

internal sealed interface BundleImportResult {
    data class Pending(val received: Int, val total: Int) : BundleImportResult
    data class Complete(val bundle: SubscriptionBundle) : BundleImportResult
}

internal sealed interface DecodedImportPayload {
    data class Profile(val uri: String) : DecodedImportPayload
    data class Bundle(val raw: String) : DecodedImportPayload
    data class Multipart(val raw: String) : DecodedImportPayload
}

internal class BundleImportDispatcher(
    private val multipart: MultipartSession = MultipartSession(),
) {
    fun clear() {
        multipart.clear()
    }

    fun accept(raw: String): BundleImportResult {
        if (!raw.startsWith("olcrtc+part:", ignoreCase = true)) {
            return BundleImportResult.Complete(ImportPayload.parseBundle(raw))
        }
        val progress = multipart.add(raw)
        return progress.payload?.let {
            BundleImportResult.Complete(ImportPayload.parseBundle(it)).also { multipart.clear() }
        } ?: BundleImportResult.Pending(progress.received, progress.total)
    }
}

internal object ImportPayload {
    private const val GZIP_PREFIX = "olcrtc+gz:"
    private const val MAX_COMPRESSED_BYTES = 512 * 1024
    private const val MAX_DECOMPRESSED_BYTES = 4 * 1024 * 1024

    fun decode(raw: String): DecodedImportPayload {
        val encoded = raw.trim()
        if (encoded.startsWith("olcrtc+part:", ignoreCase = true)) {
            return DecodedImportPayload.Multipart(encoded)
        }
        val value = unpack(encoded)
        if (!value.startsWith('{')) return DecodedImportPayload.Profile(value)
        return managerProfileUriOrNull(value)
            ?.let { DecodedImportPayload.Profile(it) }
            ?: DecodedImportPayload.Bundle(value)
    }

    fun unpack(raw: String): String {
        val value = raw.trim()
        return (if (value.startsWith(GZIP_PREFIX, ignoreCase = true)) {
            decodeGzip(value.substring(GZIP_PREFIX.length))
        } else {
            value
        }).trim()
    }

    fun parseBundle(raw: String): SubscriptionBundle = SubscriptionBundleParser.parse(unpack(raw))

    fun managerProfileUriOrNull(raw: String): String? {
        if (!raw.trimStart().startsWith('{')) return null
        val root = Json.parse(raw).objectValue("manager QR")
        if (root.keys != setOf("uri")) return null
        val uri = root.getValue("uri").stringValue("uri").trim()
        require(uri.length <= 16 * 1024) { "Profile URI is too long" }
        require(uri.substringBefore(':').lowercase() in setOf("olcrtc", "vless", "vmess", "trojan")) {
            "Unsupported profile scheme"
        }
        return uri
    }

    internal fun decodeGzip(encoded: String): String {
        require(encoded.isNotEmpty()) { "GZIP payload is empty" }
        require(encoded.length <= ((MAX_COMPRESSED_BYTES + 2) / 3) * 4) { "Compressed payload is too large" }
        val normalized = encoded.replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        val compressed = runCatching { Base64.getDecoder().decode(padded) }
            .getOrElse { throw IllegalArgumentException("Invalid GZIP Base64", it) }
        require(compressed.size <= MAX_COMPRESSED_BYTES) { "Compressed payload is too large" }
        val decompressed = inflateSingleMember(compressed)
        return runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(decompressed))
                .toString()
        }.getOrElse { throw IllegalArgumentException("GZIP payload is not valid UTF-8", it) }
    }

    private fun inflateSingleMember(input: ByteArray): ByteArray {
        var offset = parseHeader(input)
        val inflater = Inflater(true)
        val output = ByteArrayOutputStream(minOf(input.size * 2, MAX_DECOMPRESSED_BYTES))
        val buffer = ByteArray(8192)
        try {
            inflater.setInput(input, offset, input.size - offset)
            while (!inflater.finished()) {
                val count = try {
                    inflater.inflate(buffer)
                } catch (error: DataFormatException) {
                    throw IllegalArgumentException("Invalid GZIP data", error)
                }
                if (count > 0) {
                    require(output.size() + count <= MAX_DECOMPRESSED_BYTES) { "Decompressed payload is too large" }
                    output.write(buffer, 0, count)
                } else {
                    require(!inflater.needsDictionary() && !inflater.needsInput()) { "Truncated GZIP data" }
                }
            }
            offset = input.size - inflater.remaining
        } finally {
            inflater.end()
        }

        require(offset + 8 == input.size) { "Trailing GZIP data" }
        val result = output.toByteArray()
        val expectedCrc = littleEndian(input, offset)
        val expectedSize = littleEndian(input, offset + 4)
        val crc = CRC32().apply { update(result) }.value
        require(crc == expectedCrc && result.size.toLong() and 0xffff_ffffL == expectedSize) {
            "Invalid GZIP checksum"
        }
        return result
    }

    private fun parseHeader(input: ByteArray): Int {
        require(input.size >= 18) { "Truncated GZIP data" }
        require(input[0].toInt() and 0xff == 0x1f && input[1].toInt() and 0xff == 0x8b) { "Invalid GZIP header" }
        require(input[2].toInt() and 0xff == 8) { "Unsupported GZIP compression method" }
        val flags = input[3].toInt() and 0xff
        require(flags and 0xe0 == 0) { "Invalid GZIP flags" }
        var offset = 10
        if (flags and 0x04 != 0) {
            require(offset + 2 <= input.size) { "Truncated GZIP extra field" }
            val length = (input[offset].toInt() and 0xff) or ((input[offset + 1].toInt() and 0xff) shl 8)
            offset += 2
            require(offset + length <= input.size) { "Truncated GZIP extra field" }
            offset += length
        }
        if (flags and 0x08 != 0) offset = skipZeroTerminated(input, offset, "name")
        if (flags and 0x10 != 0) offset = skipZeroTerminated(input, offset, "comment")
        if (flags and 0x02 != 0) {
            require(offset + 2 <= input.size) { "Truncated GZIP header checksum" }
            offset += 2
        }
        require(offset < input.size - 8) { "Truncated GZIP data" }
        return offset
    }

    private fun skipZeroTerminated(input: ByteArray, start: Int, name: String): Int {
        var offset = start
        while (offset < input.size && input[offset] != 0.toByte()) offset++
        require(offset < input.size) { "Truncated GZIP $name" }
        return offset + 1
    }

    private fun littleEndian(input: ByteArray, offset: Int): Long =
        (input[offset].toLong() and 0xff) or
            ((input[offset + 1].toLong() and 0xff) shl 8) or
            ((input[offset + 2].toLong() and 0xff) shl 16) or
            ((input[offset + 3].toLong() and 0xff) shl 24)
}
