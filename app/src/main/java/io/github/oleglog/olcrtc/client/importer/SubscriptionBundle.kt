package io.github.oleglog.olcrtc.client.importer

import io.github.oleglog.olcrtc.client.profile.ImportedProfile
import io.github.oleglog.olcrtc.client.profile.ProfileUri
import java.net.URI

internal data class SubscriptionBundle(
    val name: String,
    val slug: String?,
    val url: String,
    val serverVersion: String?,
    val mirrors: List<Mirror>,
    val mirrorKey: String?,
    val deduplication: Boolean,
    val updateWhenConnectedOnly: Boolean,
    val profiles: List<ImportedProfile>,
    val rejectedProfiles: List<String>,
) {
    data class Mirror(
        val type: String?,
        val url: String,
        val encrypted: Boolean,
        val algorithm: String?,
    )
}

internal object SubscriptionBundleParser {
    private const val MAX_PROFILE_COUNT = 10_000
    private const val MAX_PROFILE_LENGTH = 16 * 1024

    fun parse(raw: String): SubscriptionBundle {
        val root = Json.parse(raw).objectValue("bundle")
        val type = root.alias("type").stringValue("type")
        require(type == "olcrtc-sub" || type == "olcrtc_subscription_bundle") {
            "Unsupported bundle type"
        }
        require(root.alias("v").integerValue("v") == 2L) { "Unsupported bundle version" }

        val serverVersion = root.aliasOrNull("sv")?.stringValue("sv")
        if (serverVersion != null) require(Semver.parse(serverVersion) >= Semver(1, 9, 45)) {
            "Server version must be at least 1.9.45"
        }

        val url = root.alias("u", "url", "subscription_url").stringValue("u")
        val uri = runCatching { URI(url) }.getOrElse { throw IllegalArgumentException("Invalid subscription URL", it) }
        require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()) {
            "Subscription URL must use HTTPS"
        }

        val profileValues = root.aliasOrNull("p", "profiles")?.arrayValue("p").orEmpty()
        require(profileValues.size <= MAX_PROFILE_COUNT) { "Bundle has too many profiles" }
        val profiles = mutableListOf<ImportedProfile>()
        val rejected = mutableListOf<String>()
        profileValues.forEachIndexed { index, value ->
            val profile = value.stringValue("p[$index]")
            require(profile.length <= MAX_PROFILE_LENGTH) { "Profile p[$index] is too long" }
            runCatching { ProfileUri.parse(profile) }
                .onSuccess(profiles::add)
                .onFailure { rejected += "p[$index]: ${it.message ?: "unsupported profile"}" }
        }
        val mirrors = root.aliasOrNull("m", "mirrors")?.arrayValue("m").orEmpty().mapIndexed { index, value ->
            val mirror = value.objectValue("m[$index]")
            SubscriptionBundle.Mirror(
                type = mirror.optionalString("t", "type"),
                url = mirror.alias("u", "url").stringValue("m[$index].u"),
                encrypted = mirror.optionalBoolean(false, "e", "encrypted"),
                algorithm = mirror.optionalString("a", "algorithm"),
            )
        }

        return SubscriptionBundle(
            name = root.optionalString("n", "name")?.ifBlank { null } ?: "olcRTC subscription",
            slug = root.optionalString("s", "slug")?.ifBlank { null },
            url = url,
            serverVersion = serverVersion,
            mirrors = mirrors,
            mirrorKey = root.optionalString("mk", "mirror_key")?.ifBlank { null },
            deduplication = root.optionalBoolean(true, "d", "deduplication"),
            updateWhenConnectedOnly = root.optionalBoolean(false, "uc", "update_when_connected_only"),
            profiles = profiles,
            rejectedProfiles = rejected,
        )
    }

    private fun Map<String, Json>.alias(vararg names: String): Json =
        aliasOrNull(*names) ?: throw IllegalArgumentException("Missing ${names.first()}")

    private fun Map<String, Json>.aliasOrNull(vararg names: String): Json? {
        val present = names.filter(::containsKey)
        require(present.size <= 1) { "Conflicting aliases: ${present.joinToString()}" }
        return present.firstOrNull()?.let(::get)
    }

    private fun Map<String, Json>.optionalString(vararg names: String): String? =
        aliasOrNull(*names)?.let { if (it === Json.Null) null else it.stringValue(names.first()) }

    private fun Map<String, Json>.optionalBoolean(default: Boolean, vararg names: String): Boolean =
        aliasOrNull(*names)?.booleanValue(names.first()) ?: default

    private data class Semver(val major: Int, val minor: Int, val patch: Int) : Comparable<Semver> {
        override fun compareTo(other: Semver): Int =
            compareValuesBy(this, other, Semver::major, Semver::minor, Semver::patch)

        companion object {
            fun parse(value: String): Semver {
                val match = Regex("^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-[0-9A-Za-z.-]+)?(?:\\+[0-9A-Za-z.-]+)?$")
                    .matchEntire(value) ?: throw IllegalArgumentException("Invalid server version")
                return Semver(match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3].toInt())
            }
        }
    }
}

internal sealed interface Json {
    data class Object(val value: Map<String, Json>) : Json
    data class Array(val value: List<Json>) : Json
    data class StringValue(val value: String) : Json
    data class NumberValue(val value: String) : Json
    data class BooleanValue(val value: Boolean) : Json
    data object Null : Json

    fun objectValue(name: String): Map<String, Json> = (this as? Object)?.value
        ?: throw IllegalArgumentException("$name must be an object")

    fun arrayValue(name: String): List<Json> = (this as? Array)?.value
        ?: throw IllegalArgumentException("$name must be an array")

    fun stringValue(name: String): String = (this as? StringValue)?.value
        ?: throw IllegalArgumentException("$name must be a string")

    fun integerValue(name: String): Long = (this as? NumberValue)?.value?.toLongOrNull()
        ?: throw IllegalArgumentException("$name must be an integer")

    fun booleanValue(name: String): Boolean = (this as? BooleanValue)?.value
        ?: throw IllegalArgumentException("$name must be boolean")

    companion object {
        fun parse(raw: String): Json = Parser(raw).parse()
    }

    private class Parser(private val raw: String) {
        private var index = 0

        fun parse(): Json {
            val value = value()
            whitespace()
            require(index == raw.length) { "Unexpected JSON content" }
            return value
        }

        private fun value(): Json {
            whitespace()
            require(index < raw.length) { "Unexpected end of JSON" }
            return when (raw[index]) {
                '{' -> objectValue()
                '[' -> arrayValue()
                '"' -> StringValue(string())
                't' -> literal("true", BooleanValue(true))
                'f' -> literal("false", BooleanValue(false))
                'n' -> literal("null", Null)
                '-', in '0'..'9' -> NumberValue(number())
                else -> throw IllegalArgumentException("Invalid JSON value")
            }
        }

        private fun objectValue(): Json {
            expect('{')
            val result = linkedMapOf<String, Json>()
            whitespace()
            if (take('}')) return Object(result)
            while (true) {
                val key = string()
                require(key !in result) { "Duplicate JSON field: $key" }
                expect(':')
                result[key] = value()
                whitespace()
                if (take('}')) return Object(result)
                expect(',')
            }
        }

        private fun arrayValue(): Json {
            expect('[')
            val result = mutableListOf<Json>()
            whitespace()
            if (take(']')) return Array(result)
            while (true) {
                result += value()
                whitespace()
                if (take(']')) return Array(result)
                expect(',')
            }
        }

        private fun string(): String {
            expect('"')
            return buildString {
                while (index < raw.length) {
                    when (val character = raw[index++]) {
                        '"' -> return@buildString
                        '\\' -> {
                            require(index < raw.length) { "Invalid JSON escape" }
                            when (val escaped = raw[index++]) {
                                '"', '\\', '/' -> append(escaped)
                                'b' -> append('\b')
                                'f' -> append('')
                                'n' -> append('\n')
                                'r' -> append('\r')
                                't' -> append('\t')
                                'u' -> {
                                    require(index + 4 <= raw.length) { "Invalid JSON unicode escape" }
                                    val code = raw.substring(index, index + 4).toIntOrNull(16)
                                        ?: throw IllegalArgumentException("Invalid JSON unicode escape")
                                    append(code.toChar())
                                    index += 4
                                }
                                else -> throw IllegalArgumentException("Invalid JSON escape: $escaped")
                            }
                        }
                        else -> {
                            require(character.code >= 0x20) { "Invalid JSON control character" }
                            append(character)
                        }
                    }
                }
                throw IllegalArgumentException("Unterminated JSON string")
            }
        }

        private fun number(): String {
            val start = index
            if (take('-')) Unit
            require(index < raw.length && raw[index].isDigit()) { "Invalid JSON number" }
            if (raw[index] == '0') index++ else while (index < raw.length && raw[index].isDigit()) index++
            if (take('.')) {
                require(index < raw.length && raw[index].isDigit()) { "Invalid JSON number" }
                while (index < raw.length && raw[index].isDigit()) index++
            }
            if (index < raw.length && raw[index] in "eE") {
                index++
                if (index < raw.length && raw[index] in "+-") index++
                require(index < raw.length && raw[index].isDigit()) { "Invalid JSON number" }
                while (index < raw.length && raw[index].isDigit()) index++
            }
            return raw.substring(start, index)
        }

        private fun <T : Json> literal(text: String, value: T): T {
            require(raw.startsWith(text, index)) { "Invalid JSON literal" }
            index += text.length
            return value
        }

        private fun expect(character: Char) {
            whitespace()
            require(take(character)) { "Expected '$character'" }
        }

        private fun take(character: Char): Boolean =
            if (index < raw.length && raw[index] == character) true.also { index++ } else false

        private fun whitespace() {
            while (index < raw.length && raw[index] in " \t\r\n") index++
        }
    }
}
