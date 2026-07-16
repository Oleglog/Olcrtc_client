package io.github.oleglog.olcrtc.client.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class NativeSessionTest {
    @Test
    fun rollsBackXrayWhenTunCreationFails() {
        val events = mutableListOf<String>()
        val session = NativeSession(
            RecordingCore(events),
            RecordingTunnel(events),
            establishTun = { error("tun") },
            verifyDatapath = {},
        )

        runCatching { session.start(1080, "/assets", "{}", byteArrayOf(1)) }

        assertEquals(listOf("xray:start", "xray:ready", "tunnel:close", "core:stop"), events)
    }

    @Test
    fun rollsBackXrayWhenReadinessFails() {
        val events = mutableListOf<String>()
        val session = NativeSession(
            RecordingCore(events, failReadiness = true),
            RecordingTunnel(events),
            establishTun = { error("unused") },
            verifyDatapath = {},
        )

        val failure = runCatching { session.start(1080, "/assets", "{}", byteArrayOf(1)) }.exceptionOrNull()

        assertEquals("not ready", failure?.message)
        assertEquals(listOf("xray:start", "xray:ready", "tunnel:close", "core:stop"), events)
    }

    @Test
    fun reportsFailedStartupStage() {
        val events = mutableListOf<String>()
        val stages = mutableListOf<String>()
        val session = NativeSession(
            RecordingCore(events, failReadiness = true),
            RecordingTunnel(events),
            establishTun = { error("unused") },
            verifyDatapath = {},
            reportStage = { message, error ->
                stages += if (error == null) message else "$message: ${error.message}"
            },
        )

        runCatching {
            session.start(1080, "/assets", "{}", byteArrayOf(1))
        }

        assertEquals(
            listOf(
                "Xray startup started",
                "Xray startup failed: not ready",
            ),
            stages,
        )
    }

    @Test
    fun startsOlcrtcBeforeXray() {
        val events = mutableListOf<String>()
        val core = RecordingCore(events)
        val session = NativeSession(
            core,
            RecordingTunnel(events),
            establishTun = { error("tun") },
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
        )

        runCatching { session.start(1080, "/assets", "{}", byteArrayOf(1), config) }

        assertEquals(
            listOf(
                "olcrtc:start",
                "olcrtc:ready",
                "xray:start",
                "xray:ready",
                "tunnel:close",
                "core:stop",
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
            establishTun = { error("unused") },
            verifyDatapath = {},
        )

        val failure = runCatching {
            session.start(1080, "/assets", "{}", byteArrayOf(1), olcrtcConfig())
        }.exceptionOrNull()

        assertEquals("olcrtc start", failure?.message)
        assertEquals(listOf("olcrtc:start", "tunnel:close", "core:stop"), events)
    }

    @Test
    fun preservesOlcrtcReadinessFailureDuringRollback() {
        val events = mutableListOf<String>()
        val session = NativeSession(
            RecordingCore(events, failOlcrtcReadiness = true),
            RecordingTunnel(events),
            establishTun = { error("unused") },
            verifyDatapath = {},
        )

        val failure = runCatching {
            session.start(1080, "/assets", "{}", byteArrayOf(1), olcrtcConfig())
        }.exceptionOrNull()

        assertEquals("olcrtc readiness", failure?.message)
        assertEquals(
            listOf("olcrtc:start", "olcrtc:ready", "tunnel:close", "core:stop"),
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
            listOf("xray:start", "xray:ready", "tunnel:start", "tunnel:close", "tun:close", "core:stop"),
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
                "tunnel:close",
                "tun:close",
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
    fun configRoutesUdpThroughLocalSocks() {
        val xray = NativeConfig.xray(1080)
        val hev = NativeConfig.hev(1080).decodeToString()

        assertTrue(xray.contains("\"udp\": true"))
        assertTrue(hev.contains("address: 127.0.0.1"))
        assertTrue(hev.contains("port: 1080"))
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

        override fun setProtector(protector: SocketProtector) = Unit

        override fun startOlcrtc(config: NativeOlcrtcConfig) {
            events += "olcrtc:start"
            if (failOlcrtcStart) error("olcrtc start")
        }

        override fun waitOlcrtcReady(timeoutMillis: Int) {
            olcrtcReadyTimeoutMillis = timeoutMillis
            events += "olcrtc:ready"
            if (failOlcrtcReadiness) error("olcrtc readiness")
        }

        override fun startXray(assetDirectory: String, configJson: String) {
            assertEquals("/assets", assetDirectory)
            events += "xray:start"
        }

        override fun waitXrayReady(socksPort: Int, timeoutMillis: Int) {
            events += "xray:ready"
            if (failReadiness) error("not ready")
        }

        override fun stopAll() {
            events += "core:stop"
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
        private val running: Boolean = true,
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
