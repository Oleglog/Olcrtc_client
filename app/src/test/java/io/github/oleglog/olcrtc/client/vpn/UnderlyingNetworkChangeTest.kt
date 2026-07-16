package io.github.oleglog.olcrtc.client.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class UnderlyingNetworkChangeTest {
    @Test
    fun keepsSameUnderlyingNetwork() {
        assertEquals(
            UnderlyingNetworkChange.KEEP,
            underlyingNetworkChange(
                current = "wifi",
                candidate = "wifi",
                available = true,
                eligible = true,
            ),
        )
    }

    @Test
    fun ignoresVpnNetwork() {
        assertEquals(
            UnderlyingNetworkChange.KEEP,
            underlyingNetworkChange(
                current = "wifi",
                candidate = "vpn",
                available = true,
                eligible = false,
            ),
        )
    }

    @Test
    fun replacesUnderlyingNetwork() {
        assertEquals(
            UnderlyingNetworkChange.REPLACE,
            underlyingNetworkChange(
                current = "wifi",
                candidate = "mobile",
                available = true,
                eligible = true,
            ),
        )
    }

    @Test
    fun losesOnlyCurrentUnderlyingNetwork() {
        assertEquals(
            UnderlyingNetworkChange.LOST,
            underlyingNetworkChange(
                current = "wifi",
                candidate = "wifi",
                available = false,
                eligible = false,
            ),
        )
        assertEquals(
            UnderlyingNetworkChange.KEEP,
            underlyingNetworkChange(
                current = "mobile",
                candidate = "wifi",
                available = false,
                eligible = false,
            ),
        )
    }
}
