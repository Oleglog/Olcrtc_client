package io.github.oleglog.olcrtc.client.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkDiagnosticsTest {
    private fun probeFor(results: Map<String, NetworkDiagnostics.FrontDoorProbe>) =
        { url: String, _: Int ->
            results.getValue(url)
        }

    @Test
    fun classifiesReachableAndBlockedDoors() {
        val results = NetworkDiagnostics.checkFrontDoors(
            request = probeFor(
                mapOf(
                    "https://meet.jit.si/" to NetworkDiagnostics.FrontDoorProbe(true, 120, "HTTP 200"),
                    "https://telemost.yandex.ru/" to NetworkDiagnostics.FrontDoorProbe(false, null, "HTTP 403"),
                    "https://stream.wb.ru/" to NetworkDiagnostics.FrontDoorProbe(false, null, "Timeout"),
                ),
            ),
        )

        assertEquals(3, results.size)
        assertTrue(results[0].reachable)
        assertEquals(120L, results[0].latencyMillis)
        assertFalse(results[1].reachable)
        assertFalse(results[2].reachable)
    }

    @Test
    fun recommendsFastestReachableCarrier() {
        val results = listOf(
            NetworkDiagnostics.FrontDoorResult("Jitsi", true, 300, "HTTP 200"),
            NetworkDiagnostics.FrontDoorResult("Telemost", true, 90, "HTTP 200"),
            NetworkDiagnostics.FrontDoorResult("WBStream", false, null, "Timeout"),
        )

        assertEquals("Telemost", NetworkDiagnostics.formatRecommendation(results))
    }

    @Test
    fun recommendsNoneWhenAllBlocked() {
        val results = listOf(
            NetworkDiagnostics.FrontDoorResult("Jitsi", false, null, "Timeout"),
            NetworkDiagnostics.FrontDoorResult("Telemost", false, null, "Timeout"),
        )

        assertEquals("none", NetworkDiagnostics.formatRecommendation(results))
    }

    @Test
    fun probeFailureBecomesBlockedResult() {
        val results = NetworkDiagnostics.checkFrontDoors(
            request = { _, _ -> throw java.net.SocketTimeoutException("timed out") },
        )

        assertTrue(results.isNotEmpty())
        assertTrue(results.all { !it.reachable })
    }
}
