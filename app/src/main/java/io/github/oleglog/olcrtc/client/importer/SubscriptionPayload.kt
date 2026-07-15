package io.github.oleglog.olcrtc.client.importer

import io.github.oleglog.olcrtc.client.profile.ImportedProfile
import io.github.oleglog.olcrtc.client.profile.ProfileUri
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal data class SubscriptionProfiles(
    val profiles: List<ImportedProfile>,
    val rejectedProfiles: List<String>,
)

internal object SubscriptionPayload {
    private const val MAX_DECODED_BYTES = 4 * 1024 * 1024
    private const val MAX_PROFILE_COUNT = 10_000
    private const val MAX_PROFILE_LENGTH = 16 * 1024

    fun parse(payload: ByteArray): SubscriptionProfiles {
        require(payload.size <= MAX_DECODED_BYTES) { "Subscription payload is too large" }
        val plain = decodeUtf8(payload, "Subscription payload is not valid UTF-8")
        return runCatching { parseLines(plain) }.getOrElse { plainError ->
            val decoded = runCatching { decodeBase64(plain.trim(), MAX_DECODED_BYTES, "Invalid subscription Base64") }
                .getOrElse { throw plainError }
            parseLines(decodeUtf8(decoded, "Decoded subscription is not valid UTF-8"))
        }
    }

    fun decryptMirror(envelope: ByteArray, key: String): SubscriptionProfiles {
        require(envelope.size <= MAX_DECODED_BYTES) { "Mirror payload is too large" }
        val root = Json.parse(decodeUtf8(envelope, "Mirror payload is not valid UTF-8")).objectValue("mirror")
        require(root.keys == setOf("type", "v", "alg", "nonce", "ciphertext")) { "Invalid mirror envelope fields" }
        require(root.getValue("type").stringValue("type") == "olcrtc-sub-mirror") { "Unsupported mirror type" }
        require(root.getValue("v").integerValue("v") == 1L) { "Unsupported mirror version" }
        require(root.getValue("alg").stringValue("alg") == "AES-256-GCM") { "Unsupported mirror algorithm" }

        val keyBytes = decodeRawBase64Url(key, "Invalid mirror key")
        require(keyBytes.size == 32) { "Mirror key must be 32 bytes" }
        val nonce = decodeRawBase64Url(root.getValue("nonce").stringValue("nonce"), "Invalid mirror nonce")
        require(nonce.size == 12) { "Mirror nonce must be 12 bytes" }
        val ciphertext = decodeRawBase64Url(
            root.getValue("ciphertext").stringValue("ciphertext"),
            "Invalid mirror ciphertext",
        )
        require(ciphertext.size >= 16) { "Mirror ciphertext is too short" }
        require(ciphertext.size - 16 <= MAX_DECODED_BYTES) { "Decoded mirror is too large" }

        val plain = runCatching {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, nonce))
            }.doFinal(ciphertext)
        }.getOrElse { throw IllegalArgumentException("Mirror authentication failed", it) }
        return parse(plain)
    }

    private fun parseLines(raw: String): SubscriptionProfiles {
        val profiles = mutableListOf<ImportedProfile>()
        val rejected = mutableListOf<String>()
        raw.lineSequence().forEachIndexed { index, value ->
            val line = value.trim()
            if (line.isEmpty() || line.startsWith('#')) return@forEachIndexed
            require(line.length <= MAX_PROFILE_LENGTH) { "Profile line ${index + 1} is too long" }
            require(profiles.size + rejected.size < MAX_PROFILE_COUNT) { "Subscription has too many profiles" }
            runCatching { ProfileUri.parse(line) }
                .onSuccess(profiles::add)
                .onFailure { rejected += "line ${index + 1}: ${it.message ?: "unsupported profile"}" }
        }
        require(profiles.isNotEmpty()) { "Subscription has no supported profiles" }
        return SubscriptionProfiles(profiles.distinct(), rejected)
    }

    private fun decodeUtf8(value: ByteArray, message: String): String = runCatching {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(value))
            .toString()
    }.getOrElse { throw IllegalArgumentException(message, it) }

    private fun decodeBase64(value: String, maxBytes: Int, message: String): ByteArray {
        require(value.isNotEmpty()) { message }
        require(value.length <= ((maxBytes + 2) / 3) * 4) { "Decoded subscription is too large" }
        val normalized = value.replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        return runCatching { Base64.getDecoder().decode(padded) }
            .getOrElse { throw IllegalArgumentException(message, it) }
            .also { require(it.size <= maxBytes) { "Decoded subscription is too large" } }
    }

    private fun decodeRawBase64Url(value: String, message: String): ByteArray {
        require(value.isNotEmpty() && '=' !in value && RAW_BASE64_URL.matches(value)) { message }
        return runCatching { Base64.getUrlDecoder().decode(value) }
            .getOrElse { throw IllegalArgumentException(message, it) }
    }

    private val RAW_BASE64_URL = Regex("^[A-Za-z0-9_-]+$")
}
