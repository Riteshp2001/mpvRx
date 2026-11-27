/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.theme

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette

/** Name used for the auto-generated, wallpaper-derived custom theme. */
const val WALLPAPER_DERIVED_THEME_NAME = "__wallpaper__"

/**
 * Extracts a [CustomThemeDefinition] from a wallpaper bitmap using [Palette].
 *
 * Picks vibrant/muted swatches for primary/secondary/tertiary and derives a
 * light and dark tone (via HSL lightness) for each seed color plus the
 * background, similar in spirit to Android's wallpaper-based dynamic color.
 */
fun extractThemeFromWallpaper(bitmap: Bitmap): CustomThemeDefinition? {
  val palette = Palette.from(bitmap).generate()

  val primarySeed =
    palette.vibrantSwatch?.rgb
      ?: palette.dominantSwatch?.rgb
      ?: palette.mutedSwatch?.rgb
      ?: return null
  val secondarySeed =
    palette.mutedSwatch?.rgb
      ?: palette.lightVibrantSwatch?.rgb
      ?: palette.darkVibrantSwatch?.rgb
      ?: primarySeed
  val tertiarySeed =
    palette.darkVibrantSwatch?.rgb
      ?: palette.lightMutedSwatch?.rgb
      ?: palette.darkMutedSwatch?.rgb
      ?: primarySeed
  val backgroundSeed = palette.dominantSwatch?.rgb ?: primarySeed

  return CustomThemeDefinition(
    name = WALLPAPER_DERIVED_THEME_NAME,
    primaryLight = Color(primarySeed).toToneForLight(),
    primaryDark = Color(primarySeed).toToneForDark(),
    secondaryLight = Color(secondarySeed).toToneForLight(),
    secondaryDark = Color(secondarySeed).toToneForDark(),
    tertiaryLight = Color(tertiarySeed).toToneForLight(),
    tertiaryDark = Color(tertiarySeed).toToneForDark(),
    backgroundLight = Color(backgroundSeed).toBackgroundToneForLight(),
    backgroundDark = Color(backgroundSeed).toBackgroundToneForDark(),
  )
}

private fun Color.toHsl(): FloatArray {
  val hsl = FloatArray(3)
  ColorUtils.colorToHSL(toArgb(), hsl)
  return hsl
}

private fun FloatArray.toColor(): Color = Color(ColorUtils.HSLToColor(this))

/** Accent tone tuned for use as `primary`/`secondary`/`tertiary` on a light background. */
private fun Color.toToneForLight(): Color {
  val hsl = toHsl()
  hsl[1] = hsl[1].coerceAtLeast(0.35f)
  hsl[2] = hsl[2].coerceIn(0.30f, 0.50f)
  return hsl.toColor()
}

/** Accent tone tuned for use as `primary`/`secondary`/`tertiary` on a dark background. */
private fun Color.toToneForDark(): Color {
  val hsl = toHsl()
  hsl[1] = hsl[1].coerceAtLeast(0.30f)
  hsl[2] = hsl[2].coerceIn(0.65f, 0.85f)
  return hsl.toColor()
}

/** Background tone: near-white, faintly tinted with the seed hue. */
private fun Color.toBackgroundToneForLight(): Color {
  val hsl = toHsl()
  hsl[1] = hsl[1].coerceIn(0.10f, 0.22f)
  hsl[2] = 0.97f
  return hsl.toColor()
}

/** Background tone: near-black, faintly tinted with the seed hue. */
private fun Color.toBackgroundToneForDark(): Color {
  val hsl = toHsl()
  hsl[1] = hsl[1].coerceIn(0.12f, 0.28f)
  hsl[2] = 0.08f
  return hsl.toColor()
}
