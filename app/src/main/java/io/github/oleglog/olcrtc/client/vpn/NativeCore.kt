package io.github.oleglog.olcrtc.client.vpn

import java.io.Closeable

internal interface NativeCore {
    fun setProtector(protector: SocketProtector)
    fun startOlcrtc(config: NativeOlcrtcConfig)
    fun waitOlcrtcReady(timeoutMillis: Int)
    fun startXray(assetDirectory: String, configJson: String)
    fun waitXrayReady(socksPort: Int, timeoutMillis: Int)
    fun trafficCounters(): TrafficCounters = TrafficCounters()
    fun stopAll()
}

internal data class TrafficCounters(
    val bytesUp: Long = 0,
    val bytesDown: Long = 0,
)

internal data class CoreVersions(
    val xray: String = "unknown",
    val olcrtc: String = "unknown",
)

internal fun interface SocketProtector {
    fun protect(fd: Int): Boolean
}

internal interface TunDescriptor : Closeable {
    val fd: Int
}

internal interface NativeTunnel : Closeable {
    fun start(config: ByteArray, tunFd: Int)
    fun stop(): Int
    fun trafficCounters(): TrafficCounters = TrafficCounters()
}
