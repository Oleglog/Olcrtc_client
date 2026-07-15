package io.github.oleglog.olcrtc.client.vpn

internal class ReconnectBackoff {
    private var attempt = 0

    fun nextDelayMillis(): Long = DELAYS_MILLIS[minOf(attempt++, DELAYS_MILLIS.lastIndex)]

    fun reset() {
        attempt = 0
    }

    private companion object {
        val DELAYS_MILLIS = longArrayOf(1_000, 2_000, 5_000, 10_000, 30_000, 60_000)
    }
}
