package io.github.oleglog.olcrtc.client.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(JUnit4::class)
class NativeSessionTest {
    @Test
    fun doesNotStartCoreWhenTunCreationFails() {
        val events = mutableListOf<String>()
        val session = NativeSession(
            RecordingCore(events),
            RecordingTunnel(events),
            establishTun = { error("tun") },
            verifyDatapath = {},
        )

        runCatching { session.start(1080, "/assets", "{}", byteArrayOf(1)) }

        assertEquals(listOf("tunnel:close"), events)
    }

    @Test
    fun rollsBackXrayWhenReadinessFails() {
        val events = mutableListOf<String>()
        val session = NativeSession(
            RecordingCore(events, failReadiness = true),
            RecordingTunnel(events),
            establishTun = { RecordingTun(events) },
            verifyDatapath = {},
        )

        val failure = runCatching { session.start(1080, "/assets", "{}", byteArrayOf(1)) }.exceptionOrNull()

        assertEquals("not ready", failure?.message)
        assertEquals(listOf("xray:start", "xray:ready", "tun:close", "tunnel:close", "core:stop"), events)
    }

    @Test
    fun reportsFailedStartupStage() {
        val events = mutableListOf<String>()
        val stages = mutableListOf<String>()
        val session = NativeSession(
            RecordingCore(events, failReadiness = true),
            RecordingTunnel(events),
            establishTun = { RecordingTun(events) },
            verifyDatapath = {},
            reportStage = { stage, elapsed, error ->
                stages += when {
                    elapsed == null -> "$stage started"
                    error == null -> "$stage ready"
                    else -> "$stage failed: ${error.message}"
                }
            },
        )

        runCatching {
            session.start(1080, "/assets", "{}", byteArrayOf(1))
        }

        assertEquals(
            listOf(
                "CREATE_TUN started",
                "CREATE_TUN ready",
                "START_XRAY started",
                "START_XRAY failed: not ready",
            ),
            stages,
        )
    }

    @Test
    fun closeDuringOlcrtcReadinessCancelsBeforeXrayStarts() {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val readyEntered = CountDownLatch(1)
        val releaseReady = CountDownLatch(1)
        val core = BlockingOlcrtcCore(events, readyEntered, releaseReady)
        val session = NativeSession(
            core,
            RecordingTunnel(events),
            establishTun = { RecordingTun(events) },
            verifyDatapath = {},
        )
        val executor = Executors.newSingleThreadExecutor()
        try {
            val startup = executor.submit<Throwable?> {
                runCatching {
                    session.start(1080, "/assets", "{}", byteArrayOf(1), olcrtcConfig())
                }.exceptionOrNull()
            }

            assertTrue(readyEntered.await(1, TimeUnit.SECONDS))
            session.close()

            assertTrue(startup.get(2, TimeUnit.SECONDS) is CancellationException)
            assertFalse(events.contains("xray:start"))
            assertEquals(1, events.count { it == "core:stop" })
        } finally {
            releaseReady.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun establishesTunBeforeOlcrtcAndXray() {
        val events = mutableListOf<String>()
        val core = RecordingCore(events)
        val session = NativeSession(
            core,
            RecordingTunnel(events),
            establishTun = {
                events += "tun:establish"
                RecordingTun(events)
            },
            verifyDatapath = {},
        )
        val config = NativeOlcrtcConfig(
            provider = "wbstream",
            transport = "vp8channel",
            roomId = "room",
            clientId = "client",
            keyHex = "a".repeat(64),
            authToken = "",
            dnsServer = "77.88.8.8:53",
            vp8Fps = 120,
            vp8BatchSize = 64,
            keepaliveSeconds = 15,
            socksPort = 1081,
            readyTimeoutMillis = NativeOlcrtcConfig.WBSTREAM_READY_TIMEOUT_MILLIS,
        )

        runCatching { session.start(1080, "/assets", "{}", byteArrayOf(1), config) }

        assertEquals(
            listOf(
                "tun:establish",
                "olcrtc:start",
                "olcrtc:ready",
                "xray:start",
                "xray:ready",
                "tunnel:start",
            ),
            events,
        )
        assertEquals(NativeOlcrtcConfig.WBSTREAM_READY_TIMEOUT_MILLIS, core.olcrtcReadyTimeoutMillis)
    }

    @Test
    fun preservesOlcrtcStartFailureDuringRollback() {
        val events = mutableListOf<String>()
        val session = NativeSession(
            RecordingCore(events, failOlcrtcStart = true),
            RecordingTunnel(events),
            establishTun = { RecordingTun(events) },
            verifyDatapath = {},
        )

        val failure = runCatching {
            session.start(1080, "/assets", "{}", byteArrayOf(1), olcrtcConfig())
        }.exceptionOrNull()

        assertEquals("olcrtc start", failure?.message)
        assertEquals(listOf("olcrtc:start", "tun:close", "tunnel:close", "core:stop"), events)
    }

    @Test
    fun preservesOlcrtcReadinessFailureDuringRollback() {
        val events = mutableListOf<String>()
        val session = NativeSession(
            RecordingCore(events, failOlcrtcReadiness = true),
            RecordingTunnel(events),
            establishTun = { RecordingTun(events) },
            verifyDatapath = {},
        )

        val failure = runCatching {
            session.start(1080, "/assets", "{}", byteArrayOf(1), olcrtcConfig())
        }.exceptionOrNull()

        assertEquals("olcrtc readiness", failure?.message)
        assertEquals(
            listOf("olcrtc:start", "olcrtc:ready", "tun:close", "tunnel:close", "core:stop"),
            events,
        )
    }

    @Test
    fun verifiesDatapathAfterTunnelStarts() {
        val events = mutableListOf<String>()
        val session = NativeSession(
            RecordingCore(events),
            RecordingTunnel(events),
            establishTun = { RecordingTun(events) },
            verifyDatapath = { events += "datapath:verify" },
        )

        session.start(1080, "/assets", "{}", byteArrayOf(1))

        assertEquals(
            listOf("xray:start", "xray:ready", "tunnel:start", "datapath:verify"),
            events,
        )
        session.close()
    }

    @Test
    fun standardRuntimeNeverStartsOlcrtcCarrier() {
        val events = mutableListOf<String>()
        val session = NativeSession(
            RecordingCore(events),
            RecordingTunnel(events),
            establishTun = { RecordingTun(events) },
            verifyDatapath = {},
        )

        session.start(1080, "/assets", "{}", byteArrayOf(1))

        assertFalse(events.contains("olcrtc:start"))
        assertFalse(events.contains("olcrtc:ready"))
        session.close()
    }

    @Test
    fun reportsRuntimeTunnelExit() {
        val events = mutableListOf<String>()
        val tunnel = RecordingTunnel(events)
        val session = NativeSession(
            RecordingCore(events),
            tunnel,
            establishTun = { RecordingTun(events) },
            verifyDatapath = {},
        )

        assertFalse(session.isRunning())
        session.start(1080, "/assets", "{}", byteArrayOf(1))
        assertTrue(session.isRunning())
        tunnel.running = false
        assertFalse(session.isRunning())
        session.close()
        assertFalse(session.isRunning())
    }

    @Test
    fun reportsRuntimeXrayExitWhileTunnelStillRuns() {
        val events = mutableListOf<String>()
        val core = RecordingCore(events)
        val session = NativeSession(
            core,
            RecordingTunnel(events),
            establishTun = { RecordingTun(events) },
            verifyDatapath = {},
        )

        session.start(1080, "/assets", "{}", byteArrayOf(1))
        assertTrue(session.isRunning())
        core.xrayRunning = false
        assertFalse(session.isRunning())
        session.close()
    }

    @Test
    fun reportsRuntimeOlcrtcExitWhileTunnelStillRuns() {
        val events = mutableListOf<String>()
        val core = RecordingCore(events)
        val session = NativeSession(
            core,
            RecordingTunnel(events),
            establishTun = { RecordingTun(events) },
            verifyDatapath = {},
        )

        session.start(1080, "/assets", "{}", byteArrayOf(1), olcrtcConfig())
        assertTrue(session.isRunning())
        core.olcrtcRunning = false
        assertFalse(session.isRunning())
        session.close()
    }

    @Test
    fun rejectsTunnelThatExitsDuringStartup() {
        val events = mutableListOf<String>()
        val session = NativeSession(
            RecordingCore(events),
            RecordingTunnel(events, running = false),
            establishTun = { RecordingTun(events) },
            verifyDatapath = { events += "datapath:verify" },
        )

        val failure = runCatching {
            session.start(1080, "/assets", "{}", byteArrayOf(1))
        }.exceptionOrNull()

        assertEquals("HEV tunnel exited during startup", failure?.message)
        assertEquals(
            listOf("xray:start", "xray:ready", "tunnel:start", "tun:close", "tunnel:close", "core:stop"),
            events,
        )
    }

    @Test
    fun preservesDatapathFailureDuringRollback() {
        val events = mutableListOf<String>()
        val session = NativeSession(
            RecordingCore(events),
            RecordingTunnel(events),
            establishTun = { RecordingTun(events) },
            verifyDatapath = {
                events += "datapath:verify"
                error("datapath")
            },
        )

        val failure = runCatching { session.start(1080, "/assets", "{}", byteArrayOf(1)) }.exceptionOrNull()

        assertEquals("datapath", failure?.message)
        assertEquals(
            listOf(
                "xray:start",
                "xray:ready",
                "tunnel:start",
                "datapath:verify",
                "tun:close",
                "tunnel:close",
                "core:stop",
            ),
            events,
        )
    }

    @Test
    fun stopIsIdempotentBeforeStart() {
        val events = mutableListOf<String>()
        val session = NativeSession(
            RecordingCore(events),
            RecordingTunnel(events),
            establishTun = { error("unused") },
            verifyDatapath = {},
        )

        session.close()
        session.close()

        assertEquals(listOf("tunnel:close"), events)
    }

    @Test
    fun reportsRouteReleaseBeforeFullNativeStop() {
        val events = mutableListOf<String>()
        var timings: Pair<Long, Long>? = null
        val session = NativeSession(
            RecordingCore(events),
            RecordingTunnel(events),
            establishTun = { RecordingTun(events) },
            verifyDatapath = {},
            reportStop = { routeReleased, total -> timings = routeReleased to total },
        )

        session.start(1080, "/assets", "{}", byteArrayOf(1))
        session.close()

        assertTrue(timings != null)
        assertTrue(requireNotNull(timings).first <= requireNotNull(timings).second)
    }

    @Test
    fun routeCanBeReleasedBeforeSlowNativeCleanup() {
        val events = mutableListOf<String>()
        val session = NativeSession(
            RecordingCore(events),
            RecordingTunnel(events),
            establishTun = { RecordingTun(events) },
            verifyDatapath = {},
        )
        session.start(1080, "/assets", "{}", byteArrayOf(1))
        events.clear()

        session.releaseTun()
        assertEquals(listOf("tun:close"), events)

        session.close()
        assertEquals(listOf("tun:close", "tunnel:close", "core:stop"), events)
    }

    @Test
    fun configRoutesUdpThroughLocalSocks() {
        val xray = NativeConfig.xray(1080)
        val hev = NativeConfig.hev(1080).decodeToString()

        assertTrue(xray.contains("\"udp\": true"))
        assertTrue(hev.contains("address: 127.0.0.1"))
        assertTrue(hev.contains("port: 1080"))
        assertFalse(hev.contains("ipv6:"))
        val olcrtcXray = NativeConfig.xray(1080, 1081)
        assertTrue(olcrtcXray.contains("\"protocol\": \"socks\""))
        assertTrue(olcrtcXray.contains("\"port\": 1081"))
    }

    private fun olcrtcConfig() = NativeOlcrtcConfig(
        provider = "wbstream",
        transport = "vp8channel",
        roomId = "room",
        clientId = "client",
        keyHex = "a".repeat(64),
        authToken = "",
        dnsServer = "77.88.8.8:53",
        vp8Fps = 120,
        vp8BatchSize = 64,
        keepaliveSeconds = 15,
        socksPort = 1081,
    )

    private class RecordingCore(
        private val events: MutableList<String>,
        private val failReadiness: Boolean = false,
        private val failOlcrtcStart: Boolean = false,
        private val failOlcrtcReadiness: Boolean = false,
    ) : NativeCore {
        var olcrtcReadyTimeoutMillis = 0
        var xrayRunning = false
        var olcrtcRunning = false

        override fun setProtector(protector: SocketProtector) = Unit

        override fun startOlcrtc(config: NativeOlcrtcConfig) {
            events += "olcrtc:start"
            if (failOlcrtcStart) error("olcrtc start")
            olcrtcRunning = true
        }

        override fun waitOlcrtcReady(timeoutMillis: Int) {
            olcrtcReadyTimeoutMillis = timeoutMillis
            events += "olcrtc:ready"
            if (failOlcrtcReadiness) error("olcrtc readiness")
        }

        override fun startXray(assetDirectory: String, configJson: String) {
            assertEquals("/assets", assetDirectory)
            events += "xray:start"
            xrayRunning = true
        }

        override fun waitXrayReady(socksPort: Int, timeoutMillis: Int) {
            events += "xray:ready"
            if (failReadiness) error("not ready")
        }

        override fun isXrayRunning(): Boolean = xrayRunning

        override fun isOlcrtcRunning(): Boolean = olcrtcRunning

        override fun stopAll() {
            events += "core:stop"
            xrayRunning = false
            olcrtcRunning = false
        }
    }

    private class BlockingOlcrtcCore(
        private val events: MutableList<String>,
        private val readyEntered: CountDownLatch,
        private val releaseReady: CountDownLatch,
    ) : NativeCore {
        @Volatile private var running = false

        override fun setProtector(protector: SocketProtector) = Unit

        override fun startOlcrtc(config: NativeOlcrtcConfig) {
            events += "olcrtc:start"
            running = true
        }

        override fun waitOlcrtcReady(timeoutMillis: Int) {
            events += "olcrtc:ready"
            readyEntered.countDown()
            releaseReady.await(2, TimeUnit.SECONDS)
            error("stopped")
        }

        override fun startXray(assetDirectory: String, configJson: String) {
            events += "xray:start"
        }

        override fun waitXrayReady(socksPort: Int, timeoutMillis: Int) = Unit

        override fun isXrayRunning(): Boolean = running

        override fun isOlcrtcRunning(): Boolean = running

        override fun stopAll() {
            events += "core:stop"
            running = false
            releaseReady.countDown()
        }
    }

    private class RecordingTun(private val events: MutableList<String>) : TunDescriptor {
        override val fd = 1

        override fun close() {
            events += "tun:close"
        }
    }

    private class RecordingTunnel(
        private val events: MutableList<String>,
        var running: Boolean = true,
    ) : NativeTunnel {
        override fun start(config: ByteArray, tunFd: Int) {
            events += "tunnel:start"
        }

        override fun stop(): Int {
            events += "tunnel:stop"
            return 0
        }

        override fun isRunning(): Boolean = running

        override fun close() {
            events += "tunnel:close"
        }
    }
}
