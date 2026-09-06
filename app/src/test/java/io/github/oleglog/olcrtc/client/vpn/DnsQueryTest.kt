package io.github.oleglog.olcrtc.client.vpn

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class DnsQueryTest {
    @Test
    fun buildsWireFormQuery() {
        val query = buildDnsQuery(0x1234, listOf(7, "android", 3, "com"))

        val expected = byteArrayOf(
            0x12, 0x34,
            1, 0, 0, 1, 0, 0, 0, 0, 0, 0,
            7, 'a'.code.toByte(), 'n'.code.toByte(), 'd'.code.toByte(),
            'r'.code.toByte(), 'o'.code.toByte(), 'i'.code.toByte(), 'd'.code.toByte(),
            3, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
            0, 0, 1, 0, 1,
        )
        assertArrayEquals(expected, query)
    }

    @Test
    fun encodesAllPolicyNames() {
        TunnelHealthPolicy.DATAPATH_DNS_NAMES.forEach { labels ->
            val query = buildDnsQuery(1, labels)
            // Header (12) + labels + root (1) + qtype/qclass (4).
            val labelBytes = labels.filterIsInstance<String>().sumOf(String::length) +
                labels.filterIsInstance<Int>().size
            assertEquals(12 + labelBytes + 1 + 4, query.size)
        }
    }
}
