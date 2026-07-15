package io.github.oleglog.olcrtc.client.importer

import java.security.MessageDigest

internal data class MultipartProgress(
    val received: Int,
    val total: Int,
    val payload: String?,
)

internal class MultipartSession(
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    private var bundleId: String? = null
    private var total = 0
    private var hash = ""
    private var startedAt = 0L
    private val chunks = mutableMapOf<Int, String>()

    fun add(raw: String): MultipartProgress {
        val part = MultipartPart.parse(raw)
        val now = clockMillis()
        if (bundleId != null && now - startedAt >= TTL_MILLIS) {
            clear()
            throw IllegalArgumentException("Multipart session expired")
        }
        if (bundleId == null) {
            bundleId = part.bundleId
            total = part.total
            hash = part.hash
            startedAt = now
        } else {
            require(part.bundleId == bundleId && part.total == total && part.hash == hash) {
                "Multipart part belongs to another session"
            }
        }

        val previous = chunks.putIfAbsent(part.index, part.chunk)
        require(previous == null || previous == part.chunk) { "Conflicting multipart duplicate" }
        if (chunks.size < total) return MultipartProgress(chunks.size, total, null)

        val payload = (1..total).joinToString(separator = "") { chunks.getValue(it) }
        val actualHash = sha256(payload)
        require(actualHash == hash && actualHash.startsWith(bundleId!!)) { "Multipart checksum mismatch" }
        return MultipartProgress(total, total, payload)
    }

    fun clear() {
        bundleId = null
        total = 0
        hash = ""
        startedAt = 0L
        chunks.clear()
    }

    private data class MultipartPart(
        val bundleId: String,
        val index: Int,
        val total: Int,
        val hash: String,
        val chunk: String,
    ) {
        companion object {
            fun parse(raw: String): MultipartPart {
                require(raw.length <= MAX_PART_LENGTH && raw.all { it.code in 0x20..0x7e }) {
                    "Multipart part must be at most 1200 ASCII characters"
                }
                val fields = raw.split(':', limit = 6)
                require(fields.size == 6 && fields[0].equals("olcrtc+part", ignoreCase = true)) {
                    "Invalid multipart format"
                }
                require(fields[1] == "1") { "Unsupported multipart version" }
                val bundleId = fields[2].lowercase()
                require(bundleId.matches(Regex("[0-9a-f]{16}"))) { "Invalid multipart bundle ID" }
                val position = fields[3].split('/', limit = 2)
                require(position.size == 2) { "Invalid multipart position" }
                val index = position[0].toIntOrNull() ?: throw IllegalArgumentException("Invalid multipart index")
                val total = position[1].toIntOrNull() ?: throw IllegalArgumentException("Invalid multipart total")
                require(total in 2..128 && index in 1..total) { "Invalid multipart position" }
                val hash = fields[4].lowercase()
                require(hash.matches(Regex("[0-9a-f]{64}")) && hash.startsWith(bundleId)) {
                    "Invalid multipart hash"
                }
                require(fields[5].isNotEmpty()) { "Multipart chunk is empty" }
                return MultipartPart(bundleId, index, total, hash, fields[5])
            }
        }
    }

    companion object {
        private const val MAX_PART_LENGTH = 1200
        private const val TTL_MILLIS = 10 * 60 * 1000L

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.encodeToByteArray())
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
