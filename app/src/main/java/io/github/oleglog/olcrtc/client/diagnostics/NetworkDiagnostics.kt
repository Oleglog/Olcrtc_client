package io.github.oleglog.olcrtc.client.diagnostics

import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Issue #53: network diagnostics that answer "what does this mobile
 * operator actually let through" without starting a VPN session.
 *
 * Block 1 (direct HTTP): lightweight HEAD requests to carrier front doors
 * (Jitsi static assets, Telemost landing, WBStream site) over the current
 * network. Anything that answers counts as reachable; timeouts and TLS
 * errors count as blocked. No secrets are involved.
 *
 * Blocks 2-3 (profile probes through real tunnels and the egress verdict)
 * reuse the existing ProfileLatencyProbe / verifyDatapath machinery in the
 * UI layer; this file stays dependency-free so the classification and
 * recommendation logic is unit-testable.
 */
internal object NetworkDiagnostics {
    /** Front doors probed directly (no VPN). Order is display order. */
    val CARRIER_FRONT_DOORS = listOf(
        CarrierFrontDoor("Jitsi", "https://meet.jit.si/"),
        CarrierFrontDoor("Telemost", "https://telemost.yandex.ru/"),
        CarrierFrontDoor("WBStream", "https://stream.wb.ru/"),
    )

    /** Per-request timeout for a front-door HEAD. */
    const val FRONT_DOOR_TIMEOUT_MILLIS = 8_000

    data class CarrierFrontDoor(val label: String, val url: String)

    data class FrontDoorResult(
        val label: String,
        val reachable: Boolean,
        val latencyMillis: Long?,
        val detail: String,
    )

    fun checkFrontDoors(
        doors: List<CarrierFrontDoor> = CARRIER_FRONT_DOORS,
        request: (String, Int) -> FrontDoorProbe = ::headProbe,
    ): List<FrontDoorResult> = doors.map { door ->
        runCatching { request(door.url, FRONT_DOOR_TIMEOUT_MILLIS) }
            .fold(
                onSuccess = { probe ->
                    FrontDoorResult(door.label, probe.reachable, probe.latencyMillis, probe.detail)
                },
                onFailure = { error ->
                    FrontDoorResult(door.label, false, null, error.javaClass.simpleName)
                },
            )
    }

    data class FrontDoorProbe(
        val reachable: Boolean,
        val latencyMillis: Long?,
        val detail: String,
    )

    fun formatRecommendation(results: List<FrontDoorResult>): String {
        val reachable = results.filter(FrontDoorResult::reachable)
        if (reachable.isEmpty()) return "none"
        // Prefer the fastest reachable carrier.
        return reachable.minByOrNull { it.latencyMillis ?: Long.MAX_VALUE }?.label ?: "none"
    }

    private fun headProbe(url: String, timeoutMillis: Int): FrontDoorProbe {
        val target = URL(url).also { require(it.protocol == "https") { "HTTPS URL required" } }
        val startedAt = System.nanoTime()
        val connection = target.openConnection() as HttpsURLConnection
        try {
            connection.instanceFollowRedirects = true
            connection.useCaches = false
            connection.requestMethod = "HEAD"
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = timeoutMillis
            val status = connection.responseCode
            val elapsed = (System.nanoTime() - startedAt) / 1_000_000
            return if (status in 200..399) {
                FrontDoorProbe(true, elapsed.coerceAtLeast(1), "HTTP $status")
            } else {
                FrontDoorProbe(false, null, "HTTP $status")
            }
        } finally {
            connection.disconnect()
        }
    }
}
