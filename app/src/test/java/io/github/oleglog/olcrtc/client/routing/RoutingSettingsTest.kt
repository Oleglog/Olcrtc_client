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
        assertEquals(RoutingSettings.Appearance.Palette.NEUTRAL, parseAppearancePalette("SAGE"))
        assertEquals(RoutingSettings.Appearance.Palette.NEUTRAL, parseAppearancePalette("POLAR"))
        assertEquals(RoutingSettings.Appearance.Palette.BLACK, parseAppearancePalette("BLACK"))
        assertEquals(RoutingSettings.Appearance.Palette.SYSTEM, parseAppearancePalette("unknown"))
        assertEquals(RoutingSettings.Appearance.Accent.VIOLET, parseAppearanceAccent("VIOLET"))
        assertEquals(RoutingSettings.Appearance.Accent.AUTO, parseAppearanceAccent("unknown"))
        assertThrows(IllegalArgumentException::class.java) {
            RoutingSettings.Appearance(glowIntensity = 101)
        }
    }
}
