package io.github.oleglog.olcrtc.client.vpn

import mobilecore.Mobilecore

internal object GomobileCore : NativeCore {
    override fun setProtector(protector: SocketProtector) {
        Mobilecore.setProtector { fd -> protector.protect(fd.toInt()) }
    }

    fun setLogWriter(writer: (String) -> Unit) {
        Mobilecore.setLogWriter { message -> message?.takeIf(String::isNotBlank)?.let(writer) }
    }

    override fun startOlcrtc(config: NativeOlcrtcConfig) {
        Mobilecore.startOlcrtc(
            config.provider,
            config.transport,
            config.roomId,
            config.clientId,
            config.keyHex,
            config.authToken,
            config.dnsServer,
            config.vp8Fps.toLong(),
            config.vp8BatchSize.toLong(),
            config.keepaliveSeconds.toLong(),
            config.socksPort.toLong(),
        )
    }

    override fun stopOlcrtc() {
        Mobilecore.stopOlcrtc()
    }

    override fun waitOlcrtcReady(timeoutMillis: Int) {
        Mobilecore.waitOlcrtcReady(timeoutMillis.toLong())
    }

    override fun startXray(assetDirectory: String, configJson: String) {
        Mobilecore.startXray(assetDirectory, configJson)
    }

    override fun waitXrayReady(socksPort: Int, timeoutMillis: Int) {
        Mobilecore.waitXrayReady(socksPort.toLong(), timeoutMillis.toLong())
    }

    override fun isXrayRunning(): Boolean = Mobilecore.isXrayRunning()

    override fun isOlcrtcRunning(): Boolean = Mobilecore.isOlcrtcRunning()

    fun urlTest(url: String, timeoutMillis: Int): Long = Mobilecore.urlTest(url, timeoutMillis.toLong())

    fun validateXrayConfig(assetDirectory: String, configJson: String) {
        Mobilecore.validateXrayConfig(assetDirectory, configJson)
    }

    override fun trafficCounters(): TrafficCounters = TrafficCounters(
        bytesUp = Mobilecore.trafficBytesUp(),
        bytesDown = Mobilecore.trafficBytesDown(),
    )

    fun coreVersions(): CoreVersions = CoreVersions(
        xray = runCatching { Mobilecore.xrayVersion() }.getOrDefault("unknown"),
        olcrtc = runCatching { Mobilecore.olcrtcVersion() }.getOrDefault("unknown"),
    )

    fun isFatalError(error: Throwable): Boolean =
        Mobilecore.isFatalError(error.message.orEmpty())

    override fun stopAll() {
        Mobilecore.stopAll()
    }
}
