package io.github.oleglog.olcrtc.client.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class ReconnectBackoffTest {
    @Test
    fun followsRequiredDelaysAndCapsAtOneMinute() {
        val backoff = ReconnectBackoff()

        assertEquals(1_000, backoff.nextDelayMillis())
        assertEquals(2_000, backoff.nextDelayMillis())
        assertEquals(5_000, backoff.nextDelayMillis())
        assertEquals(10_000, backoff.nextDelayMillis())
        assertEquals(30_000, backoff.nextDelayMillis())
        assertEquals(60_000, backoff.nextDelayMillis())
        assertEquals(60_000, backoff.nextDelayMillis())
    }

    @Test
    fun resetRestartsAtOneSecond() {
        val backoff = ReconnectBackoff()

        backoff.nextDelayMillis()
        backoff.nextDelayMillis()
        backoff.reset()

        assertEquals(1_000, backoff.nextDelayMillis())
    }
}
