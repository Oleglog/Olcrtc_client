package io.github.oleglog.olcrtc.client.vpn

/**
 * Builds a DNS-over-TCP query body for [labels] (length-prefixed wire form:
 * alternating length byte and label chars, e.g. 7,"android",3,"com").
 * Pure function extracted from OlcrtcVpnService for unit testing.
 */
internal fun buildDnsQuery(queryId: Int, labels: List<Any>): ByteArray {
    val header = byteArrayOf(
        (queryId ushr 8).toByte(), queryId.toByte(),
        1, 0, 0, 1, 0, 0, 0, 0, 0, 0,
    )
    val question = buildList<Byte> {
        labels.forEach { part ->
            when (part) {
                is Int -> add(part.toByte())
                is String -> part.forEach { char -> add(char.code.toByte()) }
                else -> throw IllegalArgumentException("DNS label part must be Int or String")
            }
        }
        add(0)
        add(0); add(1)
        add(0); add(1)
    }.toByteArray()
    return header + question
}
