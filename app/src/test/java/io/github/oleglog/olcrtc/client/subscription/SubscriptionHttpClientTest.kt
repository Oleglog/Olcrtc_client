package io.github.oleglog.olcrtc.client.subscription

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.Proxy
import java.net.URL
import java.security.cert.CertificateException
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLHandshakeException

class SubscriptionHttpClientTest {
    @Test
    fun followsHttpsRedirectAndSendsConditionalHeaders() {
        val redirect = FakeConnection(302, headers = mapOf("Location" to "/next"))
        val success = FakeConnection(
            200,
            body = "payload".encodeToByteArray(),
            headers = mapOf("ETag" to "new-etag", "Last-Modified" to "new-date"),
        )
        val connections = ArrayDeque(listOf(redirect, success))
        val client = SubscriptionHttpClient { connections.removeFirst() }

        val result = client.get("https://example.com/start", etag = "old-etag", lastModified = "old-date")

        assertArrayEquals("payload".encodeToByteArray(), result.body)
        assertEquals("new-etag", result.etag)
        assertEquals("new-date", result.lastModified)
        assertEquals("old-etag", redirect.requestProperties["If-None-Match"]?.single())
        assertEquals("old-date", success.requestProperties["If-Modified-Since"]?.single())
        assertEquals("no-cache, no-store", redirect.requestProperties["Cache-Control"]?.single())
        assertEquals("no-cache", success.requestProperties["Pragma"]?.single())
        assertFalse(redirect.useCaches)
        assertFalse(success.useCaches)
        assertTrue(redirect.disconnected)
        assertTrue(success.disconnected)
    }

    @Test
    fun handlesNotModifiedWithoutBody() {
        val connection = FakeConnection(304, headers = mapOf("ETag" to "etag"))
        val result = SubscriptionHttpClient { connection }.get(
            "https://example.com/sub",
            lastModified = "date",
        )

        assertTrue(result.notModified)
        assertNull(result.body)
        assertEquals("etag", result.etag)
        assertEquals("date", result.lastModified)
    }

    @Test
    fun rejectsInsecureRedirectAndOversizedBody() {
        assertThrows(IllegalArgumentException::class.java) {
            SubscriptionHttpClient {
                FakeConnection(302, headers = mapOf("Location" to "http://example.com/sub"))
            }.get("https://example.com/sub")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SubscriptionHttpClient {
                FakeConnection(200, body = ByteArray(2 * 1024 * 1024 + 1))
            }.get("https://example.com/sub")
        }
    }

    @Test
    fun usesConfiguredProxyForEveryConnection() {
        val proxy = Proxy(Proxy.Type.SOCKS, java.net.InetSocketAddress.createUnresolved("127.0.0.1", 1080))
        val openedWith = mutableListOf<Proxy?>()
        val connections = ArrayDeque(
            listOf(
                FakeConnection(302, headers = mapOf("Location" to "/next")),
                FakeConnection(200, body = "ok".encodeToByteArray()),
            ),
        )
        val client = SubscriptionHttpClient(
            proxy = proxy,
            openConnection = { _, usedProxy ->
                openedWith += usedProxy
                connections.removeFirst()
            },
        )

        assertArrayEquals("ok".encodeToByteArray(), client.get("https://example.com/sub").body)
        assertEquals(listOf(proxy, proxy), openedWith)
    }

    @Test
    fun retriesHandshakeOnlyWhenExplicitlyAllowed() {
        val failure = FakeConnection(200, responseFailure = SSLHandshakeException("untrusted"))
        val success = FakeConnection(200, body = "ok".encodeToByteArray())
        val connections = ArrayDeque(listOf(failure, success))
        val client = SubscriptionHttpClient { connections.removeFirst() }

        assertArrayEquals(
            "ok".encodeToByteArray(),
            client.get("https://example.com/sub", allowUntrustedCertificate = true).body,
        )
        assertTrue(success.sslSocketFactory != HttpsURLConnection.getDefaultSSLSocketFactory())

        assertThrows(SSLHandshakeException::class.java) {
            SubscriptionHttpClient { FakeConnection(200, responseFailure = SSLHandshakeException("untrusted")) }
                .get("https://example.com/sub")
        }
    }

    private class FakeConnection(
        private val status: Int,
        private val body: ByteArray = byteArrayOf(),
        private val headers: Map<String, String> = emptyMap(),
        private val responseFailure: Exception? = null,
    ) : HttpsURLConnection(URL("https://example.com")) {
        var disconnected = false

        override fun getResponseCode(): Int {
            responseFailure?.let { throw it }
            return status
        }

        override fun getHeaderField(name: String): String? = headers[name]
        override fun getContentLengthLong(): Long = body.size.toLong()
        override fun getInputStream(): InputStream = ByteArrayInputStream(body)
        override fun disconnect() { disconnected = true }
        override fun usingProxy(): Boolean = false
        override fun connect() = Unit
        override fun getCipherSuite(): String = ""
        override fun getLocalCertificates() = null
        override fun getServerCertificates() = throw CertificateException()
    }
}
