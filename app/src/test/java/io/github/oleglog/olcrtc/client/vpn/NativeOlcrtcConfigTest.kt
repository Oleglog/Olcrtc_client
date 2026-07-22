package io.github.oleglog.olcrtc.client.vpn

import io.github.oleglog.olcrtc.client.profile.olcrtc.OlcrtcProfile
import io.github.oleglog.olcrtc.client.routing.DnsEndpoint
import org.junit.Assert.assertEquals
import org.junit.Test

class NativeOlcrtcConfigTest {
    @Test
    fun propagatesCompatibilityModeToNativeConfig() {
        val profile = OlcrtcProfile(
            name = "Legacy",
            provider = OlcrtcProfile.Provider.WBSTREAM,
            transport = OlcrtcProfile.Transport.VP8CHANNEL,
            compatibilityMode = OlcrtcProfile.CompatibilityMode.LEGACY,
            roomId = "room",
            clientId = "client",
            keyHex = "a".repeat(64),
        )

        val config = NativeOlcrtcConfig.from(
            profile,
            socksPort = 1081,
            dns = DnsEndpoint.parse("77.88.8.8:53"),
        )

        assertEquals("legacy", config.compatibilityMode)
    }
}
