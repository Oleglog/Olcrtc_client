package io.github.oleglog.olcrtc.client

import io.github.oleglog.olcrtc.client.routing.RoutingSettings
import io.github.oleglog.olcrtc.client.routing.RoutingSettings.BackgroundEffects.Intensity
import io.github.oleglog.olcrtc.client.ui.particleCount
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundEffectsTest {
    @Test
    fun alwaysModeDoesNotDependOnVpnState() {
        val always = RoutingSettings.BackgroundEffects(enabled = true, always = true)
        val vpnOnly = always.copy(always = false)

        assertTrue(backgroundEffectsActive(always, vpnActive = false))
        assertTrue(backgroundEffectsActive(vpnOnly, vpnActive = true))
        assertFalse(backgroundEffectsActive(vpnOnly, vpnActive = false))
        assertFalse(backgroundEffectsActive(always.copy(enabled = false), vpnActive = true))
    }

    @Test
    fun intensityIncreasesParticleCount() {
        assertTrue(particleCount(Intensity.LOW) < particleCount(Intensity.MEDIUM))
        assertTrue(particleCount(Intensity.MEDIUM) < particleCount(Intensity.HIGH))
    }
}
