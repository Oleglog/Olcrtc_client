package io.github.oleglog.olcrtc.client.vpn

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeConfigInstrumentedTest {
    @Test
    fun dnsInterceptConfigIsAcceptedByBundledXray() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        GomobileCore.validateXrayConfig(
            context.noBackupFilesDir.absolutePath,
            NativeConfig.xray(socksPort = 1080, olcrtcSocksPort = 1081),
        )
    }
}
