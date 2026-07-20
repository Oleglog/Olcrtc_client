package io.github.oleglog.olcrtc.client

import io.github.oleglog.olcrtc.client.routing.RoutingSettings
import io.github.oleglog.olcrtc.client.routing.RoutingSettings.BackgroundEffects.Intensity
import io.github.oleglog.olcrtc.client.routing.RoutingSettings.BackgroundEffects.Style
import io.github.oleglog.olcrtc.client.routing.parseBackgroundEffectStyle
import io.github.oleglog.olcrtc.client.ui.particleCount
import io.github.oleglog.olcrtc.client.ui.particleFrameIntervalMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundEffectsTest {
    @Test
    fun backgroundEffectsDefaultToCleanLowDrift() {
        val effects = RoutingSettings.BackgroundEffects()

        assertFalse(effects.enabled)
        assertEquals(Style.DRIFT, effects.style)
        assertEquals(Intensity.LOW, effects.intensity)
        assertTrue(effects.copy(enabled = true).enabled)
    }

    @Test
    fun intensityIncreasesParticleCount() {
        assertTrue(particleCount(Intensity.LOW) < particleCount(Intensity.MEDIUM))
        assertTrue(particleCount(Intensity.MEDIUM) < particleCount(Intensity.HIGH))
        assertTrue(particleCount(Style.DRIFT, Intensity.MEDIUM) < particleCount(Style.SNOW, Intensity.MEDIUM))
    }

    @Test
    fun legacyEffectsMigrateToDrift() {
        assertEquals(Style.DRIFT, parseBackgroundEffectStyle("GLOW"))
        assertEquals(Style.DRIFT, parseBackgroundEffectStyle("SNOW"))
        assertEquals(Style.DRIFT, parseBackgroundEffectStyle("RAIN"))
    }

    @Test
    fun batteryRecommendationAppearsOnlyUntilHandledOrOptimizationDisabled() {
        assertTrue(shouldShowBatteryOptimizationPrompt(ignoringOptimizations = false, promptHandled = false))
        assertFalse(shouldShowBatteryOptimizationPrompt(ignoringOptimizations = true, promptHandled = false))
        assertFalse(shouldShowBatteryOptimizationPrompt(ignoringOptimizations = false, promptHandled = true))
    }

    @Test
    fun slowParticlesUseFewerAnimationWakeups() {
        assertEquals(33L, particleFrameIntervalMillis(Style.DRIFT))
        assertEquals(50L, particleFrameIntervalMillis(Style.SNOW))
        assertEquals(50L, particleFrameIntervalMillis(Style.RAIN))
    }
}
