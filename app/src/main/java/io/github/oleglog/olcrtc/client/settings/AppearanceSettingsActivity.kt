package io.github.oleglog.olcrtc.client.settings

import android.app.Activity
import android.content.res.ColorStateList
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import io.github.oleglog.olcrtc.client.R
import io.github.oleglog.olcrtc.client.databinding.ActivityAppearanceSettingsBinding
import io.github.oleglog.olcrtc.client.routing.RoutingSettings
import io.github.oleglog.olcrtc.client.ui.AppearanceTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppearanceSettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAppearanceSettingsBinding
    private val settings by lazy { RoutingSettings.open(applicationContext) }
    private lateinit var appearance: RoutingSettings.Appearance
    private lateinit var effects: RoutingSettings.BackgroundEffects

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppearanceTheme.apply(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityAppearanceSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }

        appearance = settings.getAppearance()
        effects = settings.getBackgroundEffects()
        bindValues()
        bindActions()
        updatePreview()
    }

    private fun bindValues() {
        binding.glowSlider.valueFrom = 0f
        binding.glowSlider.valueTo = 100f
        binding.glowSlider.stepSize = 5f
        setPaletteSelection(appearance.palette)
        setAccentSelection(appearance.accent)
        binding.effectGroup.check(atmosphereButton())
        binding.intensityGroup.check(intensityButton(effects.intensity))
        binding.glowSlider.value = appearance.glowIntensity.toFloat()
        binding.motionEnabled.isChecked = appearance.motionEnabled
        updateAtmosphereControls()
    }

    private fun bindActions() {
        binding.close.setOnClickListener { finish() }
        listOf(
            binding.paletteSystem to RoutingSettings.Appearance.Palette.SYSTEM,
            binding.paletteNeutral to RoutingSettings.Appearance.Palette.NEUTRAL,
            binding.paletteBronze to RoutingSettings.Appearance.Palette.BRONZE,
            binding.paletteBlack to RoutingSettings.Appearance.Palette.BLACK,
            binding.paletteMono to RoutingSettings.Appearance.Palette.MONO,
        ).forEach { (button, palette) ->
            button.setOnClickListener {
                appearance = appearance.copy(palette = palette).normalized()
                setPaletteSelection(appearance.palette)
                setAccentSelection(appearance.accent)
                updatePreview()
            }
        }
        listOf(
            binding.accentAuto to RoutingSettings.Appearance.Accent.AUTO,
            binding.accentTeal to RoutingSettings.Appearance.Accent.TEAL,
            binding.accentBlue to RoutingSettings.Appearance.Accent.BLUE,
            binding.accentViolet to RoutingSettings.Appearance.Accent.VIOLET,
            binding.accentRose to RoutingSettings.Appearance.Accent.ROSE,
            binding.accentAmber to RoutingSettings.Appearance.Accent.AMBER,
        ).forEach { (button, accent) ->
            button.setOnClickListener {
                appearance = appearance.copy(accent = accent).normalized()
                setPaletteSelection(appearance.palette)
                setAccentSelection(appearance.accent)
                updatePreview()
            }
        }
        binding.effectGroup.addOnButtonCheckedListener { _, checkedId, checked ->
            if (checked) {
                applyAtmosphere(checkedId)
                updatePreview()
            }
        }
        binding.intensityGroup.addOnButtonCheckedListener { _, checkedId, checked ->
            if (checked) effects = effects.copy(intensity = intensityForButton(checkedId))
        }
        binding.glowSlider.addOnChangeListener { _, value, fromUser ->
            appearance = appearance.copy(glowIntensity = value.toInt())
            if (fromUser && !effects.enabled) {
                binding.effectGroup.check(
                    if (value == 0f) R.id.effect_snow else R.id.effect_rain,
                )
            }
            updatePreview()
        }
        binding.motionEnabled.setOnCheckedChangeListener { _, checked ->
            appearance = appearance.copy(motionEnabled = checked)
        }
        binding.apply.setOnClickListener { save() }
    }

    private fun updatePreview() {
        val color = ContextCompat.getColor(this, previewColor())
        val surface = previewSurfaceColor()
        binding.previewCard.setCardBackgroundColor(surface)
        binding.previewCard.strokeColor = color
        binding.previewConnect.setCardBackgroundColor(ColorUtils.blendARGB(surface, color, 0.14f))
        binding.previewIcon.imageTintList = ColorStateList.valueOf(color)
        binding.previewProfile.setTextColor(previewOnSurfaceColor())
        binding.previewLabel.setTextColor(previewOnSurfaceVariantColor())
        binding.previewStatus.setTextColor(color)
        binding.previewGlow.backgroundTintList = ColorStateList.valueOf(color)
        binding.previewGlow.alpha = 0.08f + appearance.glowIntensity / 100f * 0.32f
        binding.glowValue.text = getString(R.string.appearance_glow_value, appearance.glowIntensity)
    }

    private fun save() {
        binding.apply.isEnabled = false
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                settings.setAppearance(appearance)
                settings.setBackgroundEffects(effects)
            }
            setResult(Activity.RESULT_OK)
            finish()
        }
    }

    private fun paletteButton(value: RoutingSettings.Appearance.Palette): Int = when (value) {
        RoutingSettings.Appearance.Palette.SYSTEM -> R.id.palette_system
        RoutingSettings.Appearance.Palette.NEUTRAL -> R.id.palette_neutral
        RoutingSettings.Appearance.Palette.BRONZE -> R.id.palette_bronze
        RoutingSettings.Appearance.Palette.BLACK -> R.id.palette_black
        RoutingSettings.Appearance.Palette.MONO -> R.id.palette_mono
    }

    private fun setPaletteSelection(value: RoutingSettings.Appearance.Palette) {
        val selected = paletteButton(value)
        listOf(
            binding.paletteSystem,
            binding.paletteNeutral,
            binding.paletteBronze,
            binding.paletteBlack,
            binding.paletteMono,
        )
            .forEach { it.isChecked = it.id == selected }
    }

    private fun accentButton(value: RoutingSettings.Appearance.Accent): Int = when (value) {
        RoutingSettings.Appearance.Accent.AUTO -> R.id.accent_auto
        RoutingSettings.Appearance.Accent.TEAL -> R.id.accent_teal
        RoutingSettings.Appearance.Accent.BLUE -> R.id.accent_blue
        RoutingSettings.Appearance.Accent.VIOLET -> R.id.accent_violet
        RoutingSettings.Appearance.Accent.ROSE -> R.id.accent_rose
        RoutingSettings.Appearance.Accent.AMBER -> R.id.accent_amber
    }

    private fun accentButtons(): List<MaterialButton> = listOf(
        binding.accentAuto,
        binding.accentTeal,
        binding.accentBlue,
        binding.accentViolet,
        binding.accentRose,
        binding.accentAmber,
    )

    private fun setAccentSelection(value: RoutingSettings.Appearance.Accent) {
        val selected = accentButton(value)
        val enabled = appearance.palette != RoutingSettings.Appearance.Palette.MONO
        accentButtons().forEach { button ->
            button.isChecked = button.id == selected
            button.isEnabled = enabled
            button.alpha = if (enabled) 1f else 0.4f
            button.strokeWidth = resources.getDimensionPixelSize(
                if (button.isChecked) R.dimen.card_border_active else R.dimen.card_border,
            )
        }
    }

    private fun previewColor(): Int = when {
        appearance.palette == RoutingSettings.Appearance.Palette.MONO -> R.color.appearance_preview_mono
        appearance.accent == RoutingSettings.Appearance.Accent.TEAL -> R.color.appearance_accent_teal
        appearance.accent == RoutingSettings.Appearance.Accent.BLUE -> R.color.appearance_accent_blue
        appearance.accent == RoutingSettings.Appearance.Accent.VIOLET -> R.color.appearance_accent_violet
        appearance.accent == RoutingSettings.Appearance.Accent.ROSE -> R.color.appearance_accent_rose
        appearance.accent == RoutingSettings.Appearance.Accent.AMBER -> R.color.appearance_accent_amber
        appearance.palette == RoutingSettings.Appearance.Palette.SYSTEM -> R.color.appearance_preview_system
        appearance.palette == RoutingSettings.Appearance.Palette.NEUTRAL -> R.color.appearance_preview_neutral
        appearance.palette == RoutingSettings.Appearance.Palette.BRONZE -> R.color.appearance_preview_bronze
        appearance.palette == RoutingSettings.Appearance.Palette.BLACK -> R.color.appearance_preview_black
        else -> R.color.appearance_preview_mono
    }

    private fun previewSurfaceColor(): Int {
        val resource = when {
            appearance.palette == RoutingSettings.Appearance.Palette.NEUTRAL -> R.color.olcrtc_surface
            appearance.palette == RoutingSettings.Appearance.Palette.BRONZE -> R.color.olcrtc_bronze_surface
            appearance.palette == RoutingSettings.Appearance.Palette.BLACK -> R.color.olcrtc_black_surface
            appearance.palette == RoutingSettings.Appearance.Palette.MONO -> R.color.olcrtc_mono_surface
            else -> 0
        }
        return if (resource == 0) {
            com.google.android.material.color.MaterialColors.getColor(
                binding.root,
                com.google.android.material.R.attr.colorSurface,
            )
        } else {
            ContextCompat.getColor(this, resource)
        }
    }

    private fun previewOnSurfaceColor(): Int = previewThemeColor(
        neutral = R.color.olcrtc_on_surface,
        bronze = R.color.olcrtc_bronze_on_surface,
        black = R.color.olcrtc_black_on_surface,
        mono = R.color.olcrtc_mono_on_surface,
        systemAttribute = com.google.android.material.R.attr.colorOnSurface,
    )

    private fun previewOnSurfaceVariantColor(): Int = previewThemeColor(
        neutral = R.color.olcrtc_on_surface_variant,
        bronze = R.color.olcrtc_bronze_on_surface_variant,
        black = R.color.olcrtc_black_on_surface_variant,
        mono = R.color.olcrtc_mono_on_surface_variant,
        systemAttribute = com.google.android.material.R.attr.colorOnSurfaceVariant,
    )

    private fun previewThemeColor(
        neutral: Int,
        bronze: Int,
        black: Int,
        mono: Int,
        systemAttribute: Int,
    ): Int {
        val resource = when (appearance.palette) {
            RoutingSettings.Appearance.Palette.NEUTRAL -> neutral
            RoutingSettings.Appearance.Palette.BRONZE -> bronze
            RoutingSettings.Appearance.Palette.BLACK -> black
            RoutingSettings.Appearance.Palette.MONO -> mono
            RoutingSettings.Appearance.Palette.SYSTEM -> 0
        }
        return if (resource != 0) ContextCompat.getColor(this, resource) else
            com.google.android.material.color.MaterialColors.getColor(binding.root, systemAttribute)
    }

    private fun atmosphereButton(): Int = when {
        effects.enabled -> R.id.effect_drift
        appearance.glowIntensity == 0 -> R.id.effect_snow
        else -> R.id.effect_rain
    }

    private fun applyAtmosphere(id: Int) {
        val glow = when (id) {
            R.id.effect_snow -> 0
            else -> appearance.glowIntensity.takeIf { it > 0 } ?: 60
        }
        appearance = appearance.copy(glowIntensity = glow)
        effects = effects.copy(
            enabled = id == R.id.effect_drift,
            style = RoutingSettings.BackgroundEffects.Style.DRIFT,
        )
        if (binding.glowSlider.value.toInt() != glow) binding.glowSlider.value = glow.toFloat()
        updateAtmosphereControls()
    }

    private fun updateAtmosphereControls() {
        binding.driftIntensityLabel.isVisible = effects.enabled
        binding.intensityGroup.isVisible = effects.enabled
    }

    private fun intensityButton(value: RoutingSettings.BackgroundEffects.Intensity): Int = when (value) {
        RoutingSettings.BackgroundEffects.Intensity.LOW -> R.id.intensity_low
        RoutingSettings.BackgroundEffects.Intensity.MEDIUM -> R.id.intensity_medium
        RoutingSettings.BackgroundEffects.Intensity.HIGH -> R.id.intensity_high
    }

    private fun intensityForButton(id: Int): RoutingSettings.BackgroundEffects.Intensity = when (id) {
        R.id.intensity_low -> RoutingSettings.BackgroundEffects.Intensity.LOW
        R.id.intensity_high -> RoutingSettings.BackgroundEffects.Intensity.HIGH
        else -> RoutingSettings.BackgroundEffects.Intensity.MEDIUM
    }
}
