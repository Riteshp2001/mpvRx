/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.player

import kotlin.math.abs

data class AmbientGlowPreset(
  val blurSamples: Int,
  val maxRadius: Float,
  val glowIntensity: Float,
  val satBoost: Float,
  val vignetteStrength: Float,
  val warmth: Float,
  val fadeCurve: Float,
  val opacity: Float,
)

object AmbientShaderPresets {
  val glowFast = AmbientGlowPreset(8, 0.15f, 1.2f, 1.0f, 0.3f, 0.0f, 1.2f, 0.8f)
  val glowBalanced = AmbientGlowPreset(18, 0.28f, 1.45f, 1.25f, 0.55f, 0.0f, 1.7f, 1.0f)
  val glowHighQuality = AmbientGlowPreset(24, 0.35f, 1.5f, 1.3f, 0.7f, 0.0f, 1.8f, 1.0f)
}

data class FxAmbientPreset(
  val blurSamples: Int,
  val spread: Float,
  val intensity: Float,
  val satBoost: Float,
  val vignetteStrength: Float,
  val warmth: Float,
  val fadeCurve: Float,
  val opacity: Float,
)

object FxAmbientPresets {
  val cinemaFast = FxAmbientPreset(6, 0.08f, 0.9f, 1.0f, 0.0f, 0.0f, 1.15f, 1.0f)
  val cinemaBalanced = FxAmbientPreset(8, 0.10f, 1.0f, 1.0f, 0.0f, 0.0f, 1.15f, 1.0f)
  val cinemaHighQuality = FxAmbientPreset(16, 0.12f, 1.1f, 1.05f, 0.0f, 0.0f, 1.15f, 1.0f)

  val echoFast = FxAmbientPreset(3, 0.08f, 0.9f, 1.0f, 0.0f, 0.0f, 1.8f, 1.0f)
  val echoBalanced = FxAmbientPreset(5, 0.10f, 1.0f, 1.0f, 0.0f, 0.0f, 1.8f, 1.0f)
  val echoHighQuality = FxAmbientPreset(7, 0.12f, 1.1f, 1.05f, 0.0f, 0.0f, 1.8f, 1.0f)

  val mirrorFast = FxAmbientPreset(3, 0.05f, 0.9f, 1.0f, 0.0f, 0.0f, 2.0f, 1.0f)
  val mirrorBalanced = FxAmbientPreset(3, 0.06f, 1.0f, 1.0f, 0.0f, 0.0f, 2.0f, 1.0f)
  val mirrorHighQuality = FxAmbientPreset(5, 0.08f, 1.1f, 1.05f, 0.0f, 0.0f, 2.0f, 1.0f)
}

fun matchesGlowPreset(
  preset: AmbientGlowPreset,
  blurSamples: Int,
  maxRadius: Float,
  glowIntensity: Float,
  satBoost: Float,
  vignetteStrength: Float,
  warmth: Float,
  fadeCurve: Float,
  opacity: Float,
): Boolean =
  blurSamples == preset.blurSamples &&
    closeTo(maxRadius, preset.maxRadius) &&
    closeTo(glowIntensity, preset.glowIntensity) &&
    closeTo(satBoost, preset.satBoost) &&
    closeTo(vignetteStrength, preset.vignetteStrength) &&
    closeTo(warmth, preset.warmth) &&
    closeTo(fadeCurve, preset.fadeCurve) &&
    closeTo(opacity, preset.opacity)

fun matchesFxPreset(
  preset: FxAmbientPreset,
  blurSamples: Int,
  spread: Float,
  intensity: Float,
  satBoost: Float,
  vignetteStrength: Float,
  warmth: Float,
  fadeCurve: Float,
  opacity: Float,
): Boolean =
  blurSamples == preset.blurSamples &&
    closeTo(spread, preset.spread) &&
    closeTo(intensity, preset.intensity) &&
    closeTo(satBoost, preset.satBoost) &&
    closeTo(vignetteStrength, preset.vignetteStrength) &&
    closeTo(warmth, preset.warmth) &&
    closeTo(fadeCurve, preset.fadeCurve) &&
    closeTo(opacity, preset.opacity)

private fun closeTo(left: Float, right: Float, tolerance: Float = 0.01f): Boolean = abs(left - right) <= tolerance
