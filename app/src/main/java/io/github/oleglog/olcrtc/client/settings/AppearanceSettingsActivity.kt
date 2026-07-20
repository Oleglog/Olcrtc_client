package io.github.oleglog.olcrtc.client.settings

import android.app.Activity
import android.content.res.ColorStateList
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.lifecycleScope
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
        binding = ActivityAppearanceSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
        binding.effectGroup.check(effectButton(effects.style))
        binding.intensityGroup.check(intensityButton(effects.intensity))
        binding.effectsEnabled.isChecked = effects.enabled
        binding.glowSlider.value = appearance.glowIntensity.toFloat()
        binding.motionEnabled.isChecked = appearance.motionEnabled
    }

    private fun bindActions() {
        binding.close.setOnClickListener { finish() }
        listOf(
            binding.paletteSystem to RoutingSettings.Appearance.Palette.SYSTEM,
            binding.paletteSage to RoutingSettings.Appearance.Palette.SAGE,
            binding.paletteBronze to RoutingSettings.Appearance.Palette.BRONZE,
            binding.palettePolar to RoutingSettings.Appearance.Palette.POLAR,
        ).forEach { (button, palette) ->
            button.setOnClickListener {
                appearance = appearance.copy(palette = palette)
                setPaletteSelection(palette)
                updatePreview()
            }
        }
        binding.effectGroup.addOnButtonCheckedListener { _, checkedId, checked ->
            if (checked) effects = effects.copy(style = effectForButton(checkedId))
        }
        binding.intensityGroup.addOnButtonCheckedListener { _, checkedId, checked ->
            if (checked) effects = effects.copy(intensity = intensityForButton(checkedId))
        }
        binding.effectsEnabled.setOnCheckedChangeListener { _, checked ->
            effects = effects.copy(enabled = checked)
        }
        binding.glowSlider.addOnChangeListener { _, value, _ ->
            appearance = appearance.copy(glowIntensity = value.toInt())
            updatePreview()
        }
        binding.motionEnabled.setOnCheckedChangeListener { _, checked ->
            appearance = appearance.copy(motionEnabled = checked)
        }
        binding.apply.setOnClickListener { save() }
    }

    private fun updatePreview() {
        val color = ContextCompat.getColor(this, when (appearance.palette) {
            RoutingSettings.Appearance.Palette.SYSTEM -> R.color.appearance_preview_system
            RoutingSettings.Appearance.Palette.SAGE -> R.color.appearance_preview_sage
            RoutingSettings.Appearance.Palette.BRONZE -> R.color.appearance_preview_bronze
            RoutingSettings.Appearance.Palette.POLAR -> R.color.appearance_preview_polar
        })
        val surface = com.google.android.material.color.MaterialColors.getColor(
            binding.root,
            com.google.android.material.R.attr.colorSurface,
        )
        binding.previewCard.strokeColor = color
        binding.previewConnect.setCardBackgroundColor(ColorUtils.blendARGB(surface, color, 0.14f))
        binding.previewIcon.imageTintList = ColorStateList.valueOf(color)
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
        RoutingSettings.Appearance.Palette.SAGE -> R.id.palette_sage
        RoutingSettings.Appearance.Palette.BRONZE -> R.id.palette_bronze
        RoutingSettings.Appearance.Palette.POLAR -> R.id.palette_polar
    }

    private fun setPaletteSelection(value: RoutingSettings.Appearance.Palette) {
        val selected = paletteButton(value)
        listOf(binding.paletteSystem, binding.paletteSage, binding.paletteBronze, binding.palettePolar)
            .forEach { it.isChecked = it.id == selected }
    }

    private fun effectButton(value: RoutingSettings.BackgroundEffects.Style): Int = when (value) {
        RoutingSettings.BackgroundEffects.Style.SNOW -> R.id.effect_snow
        RoutingSettings.BackgroundEffects.Style.RAIN -> R.id.effect_rain
        RoutingSettings.BackgroundEffects.Style.DRIFT -> R.id.effect_drift
    }

    private fun effectForButton(id: Int): RoutingSettings.BackgroundEffects.Style = when (id) {
        R.id.effect_rain -> RoutingSettings.BackgroundEffects.Style.RAIN
        R.id.effect_drift -> RoutingSettings.BackgroundEffects.Style.DRIFT
        else -> RoutingSettings.BackgroundEffects.Style.SNOW
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
