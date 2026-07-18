package io.github.oleglog.olcrtc.client.vpn

import java.net.InetSocketAddress
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

internal fun measureSocksHttpLatency(
    socksPort: Int,
    url: String,
    timeoutMillis: Int,
    clockNanos: () -> Long = System::nanoTime,
    openConnection: (URL, Proxy) -> HttpsURLConnection = { target, proxy ->
        target.openConnection(proxy) as HttpsURLConnection
    },
): Long {
    require(socksPort in 1..65535) { "invalid SOCKS port" }
    require(timeoutMillis > 0) { "timeout must be positive" }
    val target = URL(url).also { require(it.protocol == "https") { "HTTPS URL required" } }
    val proxy = Proxy(
        Proxy.Type.SOCKS,
        InetSocketAddress.createUnresolved("127.0.0.1", socksPort),
    )
    val connection = openConnection(target, proxy).apply {
        instanceFollowRedirects = false
        useCaches = false
        requestMethod = "HEAD"
        connectTimeout = timeoutMillis
        readTimeout = timeoutMillis
    }
    val startedAt = clockNanos()
    try {
        val status = connection.responseCode
        check(connection.usingProxy()) { "SOCKS probe bypassed proxy" }
        check(status == HttpURLConnection.HTTP_NO_CONTENT) {
            "SOCKS probe returned HTTP $status"
        }
        return TimeUnit.NANOSECONDS.toMillis(clockNanos() - startedAt).coerceAtLeast(1)
    } finally {
        connection.disconnect()
    }
}
