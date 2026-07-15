package io.github.oleglog.olcrtc.client.vpn

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

class HevTunnel : NativeTunnel {
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private var run: Future<Int>? = null

    @Synchronized
    override fun start(config: ByteArray, tunFd: Int) {
        require(config.isNotEmpty()) { "config must not be empty" }
        require(tunFd >= 0) { "tunFd must not be negative" }
        check(run == null) { "hev tunnel already running" }
        run = worker.submit<Int> { nativeRun(config, tunFd) }
    }

    @Synchronized
    override fun stop(): Int {
        val current = run ?: return 0
        if (!current.isDone) nativeStop()
        return current.get().also { run = null }
    }

    fun stats(): LongArray = nativeStats()

    override fun trafficCounters(): TrafficCounters {
        val values = runCatching { nativeStats() }.getOrElse { return TrafficCounters() }
        return TrafficCounters(
            bytesUp = values.getOrNull(1)?.coerceAtLeast(0) ?: 0,
            bytesDown = values.getOrNull(3)?.coerceAtLeast(0) ?: 0,
        )
    }

    override fun close() {
        try {
            stop()
        } finally {
            worker.shutdown()
        }
    }

    private external fun nativeRun(config: ByteArray, tunFd: Int): Int
    private external fun nativeStop()
    private external fun nativeStats(): LongArray

    private companion object {
        init {
            System.loadLibrary("hev-socks5-tunnel")
        }
    }
}
