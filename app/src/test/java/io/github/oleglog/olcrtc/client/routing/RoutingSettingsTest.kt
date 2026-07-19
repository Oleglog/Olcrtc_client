package io.github.oleglog.olcrtc.client.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RoutingSettingsTest {
    @Test
    fun acceptsOnePersistedProfileReference() {
        assertEquals(
            7L,
            RoutingSettings.VpnIntent(desiredConnected = true, localProfileId = 7).localProfileId,
        )
        assertEquals(
            "subscription-profile",
            RoutingSettings.VpnIntent(
                desiredConnected = true,
                subscriptionProfileId = "subscription-profile",
            ).subscriptionProfileId,
        )
    }

    @Test
    fun rejectsAmbiguousOrInvalidProfileReferences() {
        assertThrows(IllegalArgumentException::class.java) {
            RoutingSettings.VpnIntent(
                desiredConnected = true,
                localProfileId = 7,
                subscriptionProfileId = "subscription-profile",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            RoutingSettings.VpnIntent(desiredConnected = true, localProfileId = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RoutingSettings.VpnIntent(desiredConnected = true, subscriptionProfileId = " ")
        }
    }

    @Test
    fun parsesAppearancePaletteAndRejectsInvalidGlowIntensity() {
        assertEquals(RoutingSettings.Appearance.Palette.POLAR, parseAppearancePalette("POLAR"))
        assertEquals(RoutingSettings.Appearance.Palette.SAGE, parseAppearancePalette("unknown"))
        assertThrows(IllegalArgumentException::class.java) {
            RoutingSettings.Appearance(glowIntensity = 101)
        }
    }
}
