package io.github.oleglog.olcrtc.client.vpn

import java.io.Closeable

internal class NativeSession(
    private val nativeCore: NativeCore,
    private val hevTunnel: NativeTunnel,
    private val establishTun: () -> TunDescriptor,
    private val verifyDatapath: () -> Unit,
) : Closeable {
    private var coreStarted = false
    private var tun: TunDescriptor? = null
    private var closed = false

    fun start(
        socksPort: Int,
        assetDirectory: String,
        xrayConfig: String,
        hevConfig: ByteArray,
        olcrtcConfig: NativeOlcrtcConfig? = null,
    ) {
        check(!closed && !coreStarted) { "native session already used" }
        try {
            coreStarted = true
            if (olcrtcConfig != null) {
                nativeCore.startOlcrtc(olcrtcConfig)
                nativeCore.waitOlcrtcReady(OLCRTC_READY_TIMEOUT_MILLIS)
            }
            nativeCore.startXray(assetDirectory, xrayConfig)
            nativeCore.waitXrayReady(socksPort, XRAY_READY_TIMEOUT_MILLIS)
            val descriptor = establishTun()
            tun = descriptor
            hevTunnel.start(hevConfig, descriptor.fd)
            verifyDatapath()
        } catch (error: Throwable) {
            stop(error)
            throw error
        }
    }

    fun trafficCounters(): TrafficCounters = hevTunnel.trafficCounters()

    override fun close() {
        stop(null)
    }

    private fun stop(original: Throwable?) {
        if (closed) return
        closed = true
        var failure = original
        try {
            hevTunnel.close()
        } catch (error: Throwable) {
            if (failure == null) failure = error else failure.addSuppressed(error)
        }
        try {
            tun?.close()
        } catch (error: Throwable) {
            if (failure == null) failure = error else failure.addSuppressed(error)
        } finally {
            tun = null
        }
        if (coreStarted) {
            try {
                nativeCore.stopAll()
            } catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            } finally {
                coreStarted = false
            }
        }
        if (original == null && failure != null) throw failure
    }

    private companion object {
        const val OLCRTC_READY_TIMEOUT_MILLIS = 15_000
        const val XRAY_READY_TIMEOUT_MILLIS = 5_000
    }
}
