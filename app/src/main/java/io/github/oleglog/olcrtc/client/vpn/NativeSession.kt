package io.github.oleglog.olcrtc.client.vpn

import java.io.Closeable
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

internal class NativeSession(
    private val nativeCore: NativeCore,
    private val hevTunnel: NativeTunnel,
    private val establishTun: () -> TunDescriptor,
    verifyDatapath: () -> Unit,
    reportStage: (ConnectionStage, Long?, Throwable?) -> Unit = { _, _, _ -> },
    reportStop: (routeReleasedMillis: Long, totalMillis: Long) -> Unit = { _, _ -> },
) : Closeable {
    private val lifecycle = Any()
    private val verifyDatapath = verifyDatapath
    private val reportStage = reportStage
    private val reportStop = reportStop
    @Volatile private var coreStarted = false
    @Volatile private var olcrtcStarted = false
    @Volatile private var tun: TunDescriptor? = null
    @Volatile private var closed = false

    fun start(
        socksPort: Int,
        assetDirectory: String,
        xrayConfig: String,
        hevConfig: ByteArray,
        olcrtcConfig: NativeOlcrtcConfig? = null,
    ) {
        checkOpen()
        startSafely {
            val descriptor = stage(ConnectionStage.CREATE_TUN) {
                synchronized(lifecycle) {
                    checkOpen()
                    establishTun().also { tun = it }
                }
            }
            startRuntime(descriptor, socksPort, assetDirectory, xrayConfig, hevConfig, olcrtcConfig)
        }
    }

    private fun startRuntime(
        descriptor: TunDescriptor,
        socksPort: Int,
        assetDirectory: String,
        xrayConfig: String,
        hevConfig: ByteArray,
        olcrtcConfig: NativeOlcrtcConfig?,
    ) {
        if (olcrtcConfig != null) {
            stage(ConnectionStage.START_CARRIER) {
                synchronized(lifecycle) {
                    checkOpen()
                    coreStarted = true
                    nativeCore.startOlcrtc(olcrtcConfig)
                    olcrtcStarted = true
                }
                nativeCore.waitOlcrtcReady(olcrtcConfig.readyTimeoutMillis)
            }
        }
        stage(ConnectionStage.START_XRAY) {
            synchronized(lifecycle) {
                checkOpen()
                coreStarted = true
                nativeCore.startXray(assetDirectory, xrayConfig)
            }
            nativeCore.waitXrayReady(socksPort, XRAY_READY_TIMEOUT_MILLIS)
        }
        stage(ConnectionStage.START_HEV) {
            synchronized(lifecycle) {
                checkOpen()
                hevTunnel.start(hevConfig, descriptor.fd)
                check(hevTunnel.isRunning()) {
                    "HEV tunnel exited during startup"
                }
            }
        }
        stage(ConnectionStage.VERIFY_DATAPATH) {
            verifyDatapath()
            check(hevTunnel.isRunning()) {
                "HEV tunnel exited during datapath verification"
            }
        }
    }

    private inline fun startSafely(action: () -> Unit) {
        try {
            action()
        } catch (error: Throwable) {
            val failure = if (closed && error !is CancellationException) {
                CancellationException("native session cancelled").apply { initCause(error) }
            } else {
                error
            }
            stop(failure)
            throw failure
        }
    }

    fun trafficCounters(): TrafficCounters = hevTunnel.trafficCounters()

    fun isRunning(): Boolean =
        !closed && coreStarted && tun != null && hevTunnel.isRunning() &&
            nativeCore.isXrayRunning() && (!olcrtcStarted || nativeCore.isOlcrtcRunning())

    fun releaseTun() {
        val descriptor = synchronized(lifecycle) {
            if (closed) return
            tun.also { tun = null }
        }
        descriptor?.close()
    }

    private inline fun <T> stage(
        stage: ConnectionStage,
        action: () -> T,
    ): T {
        checkOpen()
        reportStage(stage, null, null)
        val startedAt = System.nanoTime()
        try {
            val result = action()
            checkOpen()
            reportStage(stage, elapsedMillis(startedAt), null)
            return result
        } catch (error: Throwable) {
            reportStage(stage, elapsedMillis(startedAt), error)
            throw error
        }
    }

    override fun close() {
        stop(null)
    }

    private fun stop(original: Throwable?) {
        val resources = synchronized(lifecycle) {
            if (closed) return
            closed = true
            StopResources(tun, coreStarted).also {
                tun = null
                coreStarted = false
                olcrtcStarted = false
            }
        }
        var failure = original
        val stopStartedAt = System.nanoTime()
        var routeReleasedMillis = 0L
        try {
            resources.tun?.close()
        } catch (error: Throwable) {
            if (failure == null) failure = error else failure.addSuppressed(error)
        } finally {
            routeReleasedMillis = elapsedMillis(stopStartedAt)
        }
        try {
            hevTunnel.close()
        } catch (error: Throwable) {
            if (failure == null) failure = error else failure.addSuppressed(error)
        }
        try {
            if (resources.stopCore) {
                nativeCore.stopAll()
            }
        } catch (error: Throwable) {
            if (failure == null) failure = error else failure.addSuppressed(error)
        } finally {
            runCatching { reportStop(routeReleasedMillis, elapsedMillis(stopStartedAt)) }
                .exceptionOrNull()
                ?.let { error ->
                    if (failure == null) failure = error else failure?.addSuppressed(error)
                }
        }
        if (original == null && failure != null) throw failure
    }

    private fun checkOpen() {
        if (closed) throw CancellationException("native session cancelled")
    }

    private fun elapsedMillis(startedAt: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

    private data class StopResources(
        val tun: TunDescriptor?,
        val stopCore: Boolean,
    )

    private companion object {
        const val XRAY_READY_TIMEOUT_MILLIS = 5_000
    }
}
