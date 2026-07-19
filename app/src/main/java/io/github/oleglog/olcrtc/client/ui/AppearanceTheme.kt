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
            RoutingSettings.Appearance.Palette.SAGE -> Unit
            RoutingSettings.Appearance.Palette.BRONZE ->
                activity.theme.applyStyle(R.style.ThemeOverlay_OlcrtcClient_Bronze, true)
            RoutingSettings.Appearance.Palette.POLAR ->
                activity.theme.applyStyle(R.style.ThemeOverlay_OlcrtcClient_Polar, true)
        }
    }
}
