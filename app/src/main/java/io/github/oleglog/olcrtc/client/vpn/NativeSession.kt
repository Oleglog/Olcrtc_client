package io.github.oleglog.olcrtc.client.vpn

import java.io.Closeable

internal class NativeSession(
    private val nativeCore: NativeCore,
    private val hevTunnel: NativeTunnel,
    private val establishTun: () -> TunDescriptor,
    private val verifyDatapath: () -> Unit,
    private val reportStage: (String, Throwable?) -> Unit = { _, _ -> },
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
            val descriptor = establishTun()
            tun = descriptor
            coreStarted = true
            if (olcrtcConfig != null) {
                stage("olcRTC startup") {
                    nativeCore.startOlcrtc(olcrtcConfig)
                    nativeCore.waitOlcrtcReady(olcrtcConfig.readyTimeoutMillis)
                }
            }
            stage("Xray startup") {
                nativeCore.startXray(assetDirectory, xrayConfig)
                nativeCore.waitXrayReady(socksPort, XRAY_READY_TIMEOUT_MILLIS)
            }
            stage("HEV startup") {
                hevTunnel.start(hevConfig, descriptor.fd)
                check(hevTunnel.isRunning()) {
                    "HEV tunnel exited during startup"
                }
            }
            stage("VPN datapath verification") {
                verifyDatapath()
                check(hevTunnel.isRunning()) {
                    "HEV tunnel exited during datapath verification"
                }
            }
        } catch (error: Throwable) {
            stop(error)
            throw error
        }
    }

    fun trafficCounters(): TrafficCounters = hevTunnel.trafficCounters()

    private inline fun stage(
        name: String,
        action: () -> Unit,
    ) {
        reportStage("$name started", null)
        try {
            action()
            reportStage("$name ready", null)
        } catch (error: Throwable) {
            reportStage("$name failed", error)
            throw error
        }
    }

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
        const val XRAY_READY_TIMEOUT_MILLIS = 5_000
    }
}
