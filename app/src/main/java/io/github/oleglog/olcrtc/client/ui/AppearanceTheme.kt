package io.github.oleglog.olcrtc.client.ui

import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.color.DynamicColors
import io.github.oleglog.olcrtc.client.R
import io.github.oleglog.olcrtc.client.routing.RoutingSettings

internal object AppearanceTheme {
    fun apply(
        activity: AppCompatActivity,
        appearance: RoutingSettings.Appearance = RoutingSettings.open(activity.applicationContext).getAppearance(),
    ) {
        when (appearance.palette) {
            RoutingSettings.Appearance.Palette.SYSTEM -> DynamicColors.applyToActivityIfAvailable(activity)
            RoutingSettings.Appearance.Palette.NEUTRAL -> Unit
            RoutingSettings.Appearance.Palette.BRONZE ->
                activity.theme.applyStyle(R.style.ThemeOverlay_OlcrtcClient_Bronze, true)
            RoutingSettings.Appearance.Palette.BLACK ->
                activity.theme.applyStyle(R.style.ThemeOverlay_OlcrtcClient_Black, true)
            RoutingSettings.Appearance.Palette.MONO ->
                activity.theme.applyStyle(R.style.ThemeOverlay_OlcrtcClient_Mono, true)
        }
        if (appearance.palette != RoutingSettings.Appearance.Palette.MONO) {
            val accentOverlay = when (appearance.accent) {
                RoutingSettings.Appearance.Accent.AUTO -> 0
                RoutingSettings.Appearance.Accent.TEAL -> R.style.ThemeOverlay_OlcrtcClient_Accent_Teal
                RoutingSettings.Appearance.Accent.BLUE -> R.style.ThemeOverlay_OlcrtcClient_Accent_Blue
                RoutingSettings.Appearance.Accent.VIOLET -> R.style.ThemeOverlay_OlcrtcClient_Accent_Violet
                RoutingSettings.Appearance.Accent.ROSE -> R.style.ThemeOverlay_OlcrtcClient_Accent_Rose
                RoutingSettings.Appearance.Accent.AMBER -> R.style.ThemeOverlay_OlcrtcClient_Accent_Amber
            }
            if (accentOverlay != 0) activity.theme.applyStyle(accentOverlay, true)
        }
    }
}
