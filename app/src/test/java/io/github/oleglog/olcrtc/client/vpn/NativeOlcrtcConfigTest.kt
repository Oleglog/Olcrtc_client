package io.github.oleglog.olcrtc.client.vpn

import io.github.oleglog.olcrtc.client.profile.olcrtc.OlcrtcProfile
import io.github.oleglog.olcrtc.client.routing.DnsEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

    @Test
    fun jitsiUsesExtendedReadyTimeout() {
        val profile = OlcrtcProfile(
            name = "Jitsi",
            provider = OlcrtcProfile.Provider.JITSI,
            transport = OlcrtcProfile.Transport.DATACHANNEL,
            roomId = "https://meet.cryptopro.ru/room",
            clientId = "client",
            keyHex = "a".repeat(64),
        )

        val config = NativeOlcrtcConfig.from(
            profile,
            socksPort = 1081,
            dns = DnsEndpoint.parse("1.1.1.1:53"),
        )

        assertEquals("jitsi", config.provider)
        assertEquals("datachannel", config.transport)
        assertEquals(NativeOlcrtcConfig.JITSI_READY_TIMEOUT_MILLIS, config.readyTimeoutMillis)
        assertNotEquals(NativeOlcrtcConfig.DEFAULT_READY_TIMEOUT_MILLIS, config.readyTimeoutMillis)
    }
}
