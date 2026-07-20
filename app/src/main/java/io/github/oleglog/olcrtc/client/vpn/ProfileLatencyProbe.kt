package io.github.oleglog.olcrtc.client.vpn

import android.content.Context
import io.github.oleglog.olcrtc.client.data.ProfileConfig
import java.util.concurrent.ExecutorService
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future

internal data class ProfileProbeTarget(
    val reference: String,
    val config: ProfileConfig,
)

internal class ProfileLatencyProbe(
    context: Context,
    private val dnsServer: String?,
    private val workers: ExecutorService,
) {
    private val assetDirectory = context.noBackupFilesDir.absolutePath

    fun testAll(
        targets: List<ProfileProbeTarget>,
        onStarted: (List<String>) -> Unit,
        onResult: (String, Result<Long>) -> Unit,
    ) {
        GomobileCore.stopProfileProbe()
        try {
            targets.filter { it.config is ProfileConfig.Standard }
                .chunked(MAX_PARALLEL_PROBES)
                .forEach { testStandardGroup(it, onStarted, onResult) }
            targets.filter { it.config is ProfileConfig.Olcrtc }
                .forEach { testOlcrtc(it, onStarted, onResult) }
        } finally {
            GomobileCore.stopProfileProbe()
        }
    }

    fun stop() = GomobileCore.stopProfileProbe()

    private fun testStandardGroup(
        targets: List<ProfileProbeTarget>,
        onStarted: (List<String>) -> Unit,
        onResult: (String, Result<Long>) -> Unit,
    ) {
        if (Thread.currentThread().isInterrupted) return
        onStarted(targets.map(ProfileProbeTarget::reference))
        val profiles = targets.map { (it.config as ProfileConfig.Standard).value }
        GomobileCore.startProfileProbe(assetDirectory, NativeConfig.profileProbe(profiles))
        try {
            val futures = targets.mapIndexed { index, _ ->
                workers.submit<Long> {
                    GomobileCore.profileProbeUrlTest(
                        TEST_URL,
                        TEST_TIMEOUT_MILLIS,
                        NativeConfig.profileProbeTag(index),
                    ).coerceAtLeast(1)
                }
            }
            targets.zip(futures).forEach { (target, future) ->
                onResult(target.reference, await(future))
            }
        } finally {
            GomobileCore.stopProfileProbe()
        }
    }

    private fun testOlcrtc(
        target: ProfileProbeTarget,
        onStarted: (List<String>) -> Unit,
        onResult: (String, Result<Long>) -> Unit,
    ) {
        if (Thread.currentThread().isInterrupted) return
        onStarted(listOf(target.reference))
        val profile = target.config as ProfileConfig.Olcrtc
        val dns = sessionDns(profile, dnsServer)
        val olcrtc = NativeOlcrtcConfig.from(profile.value, freeLoopbackPort(), checkNotNull(dns.carrier))
        val xrayPort = freeLoopbackPort(olcrtc.socksPort)
        val result = runCatching {
            GomobileCore.startProfileProbeOlcrtc(olcrtc)
            GomobileCore.waitOlcrtcReady(olcrtc.readyTimeoutMillis)
            GomobileCore.startProfileProbe(
                assetDirectory,
                NativeConfig.xray(xrayPort, olcrtc.socksPort, dns.tunnel),
            )
            GomobileCore.profileProbeUrlTest(TEST_URL, TEST_TIMEOUT_MILLIS, LATENCY_TEST_TAG).coerceAtLeast(1)
        }
        runCatching { GomobileCore.stopProfileProbe() }
        onResult(target.reference, result)
    }

    private fun await(future: Future<Long>): Result<Long> = try {
        Result.success(future.get())
    } catch (error: InterruptedException) {
        Thread.currentThread().interrupt()
        Result.failure(error)
    } catch (error: ExecutionException) {
        Result.failure(error.cause ?: error)
    }

    private companion object {
        const val MAX_PARALLEL_PROBES = 4
        const val TEST_URL = "https://www.google.com/generate_204"
        const val TEST_TIMEOUT_MILLIS = 5_000
        const val LATENCY_TEST_TAG = "latency-test"
    }
}
