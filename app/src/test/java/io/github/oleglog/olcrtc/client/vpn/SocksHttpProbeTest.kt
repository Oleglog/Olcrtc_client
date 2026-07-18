package io.github.oleglog.olcrtc.client.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.security.cert.CertificateException
import javax.net.ssl.HttpsURLConnection

class SocksHttpProbeTest {
    @Test
    fun measuresOnlyRealNoContentResponseThroughLoopbackSocks() {
        var usedProxy: Proxy? = null
        val connection = FakeConnection(HttpURLConnection.HTTP_NO_CONTENT)
        val clock = ArrayDeque(listOf(1_000_000L, 43_000_000L))

        val latency = measureSocksHttpLatency(1080, "https://example.com/generate_204", 5000,
            clockNanos = { clock.removeFirst() },
            openConnection = { _, proxy -> usedProxy = proxy; connection },
        )

        assertEquals(42, latency)
        assertEquals(Proxy.Type.SOCKS, usedProxy?.type())
        assertEquals(1080, (usedProxy?.address() as InetSocketAddress).port)
        assertEquals("HEAD", connection.requestMethod)
        assertEquals(5000, connection.connectTimeout)
        assertTrue(connection.disconnected)

        val failure = runCatching {
            measureSocksHttpLatency(1080, "https://example.com/generate_204", 5000,
                openConnection = { _, _ -> FakeConnection(HttpURLConnection.HTTP_BAD_GATEWAY) },
            )
        }.exceptionOrNull()
        assertEquals("SOCKS probe returned HTTP 502", failure?.message)
    }

    private class FakeConnection(private val status: Int) : HttpsURLConnection(URL("https://example.com")) {
        var disconnected = false
        override fun getResponseCode(): Int = status
        override fun disconnect() { disconnected = true }
        override fun usingProxy(): Boolean = true
        override fun connect() = Unit
        override fun getCipherSuite(): String = ""
        override fun getLocalCertificates() = null
        override fun getServerCertificates() = throw CertificateException()
    }
}
