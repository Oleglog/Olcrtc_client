package io.github.oleglog.olcrtc.client.profile.openflux

import org.junit.Assert.assertEquals
import org.junit.Test

class OpenFluxUriTest {
    @Test
    fun parseValidOpenFluxUri() {
        val raw = "openflux://yandex?url=https%3A%2F%2Fdisk.yandex.ru%2Fi%2Ftest1234&t=vyandex&d=1.1.1.1%3A53#Office+Tunnel"
        val profile = OpenFluxUri.parse(raw)

        assertEquals("Office Tunnel", profile.name)
        assertEquals("https://disk.yandex.ru/i/test1234", profile.documentUrl)
        assertEquals(OpenFluxProfile.Transport.VYANDEX, profile.transport)
        assertEquals("1.1.1.1:53", profile.dnsServer)
    }

    @Test
    fun serializeRoundTrip() {
        val original = OpenFluxProfile(
            name = "Test Profile",
            documentUrl = "https://disk.yandex.ru/i/JsCiJYtJjOP-Aw",
            transport = OpenFluxProfile.Transport.VYANDEX,
            dnsServer = "8.8.8.8:53",
        )
        val uri = OpenFluxUri.serialize(original)
        val parsed = OpenFluxUri.parse(uri)

        assertEquals(original.name, parsed.name)
        assertEquals(original.documentUrl, parsed.documentUrl)
        assertEquals(original.transport, parsed.transport)
        assertEquals(original.dnsServer, parsed.dnsServer)
    }
}
