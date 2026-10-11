/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.gyrolet.mpvrx.R
import java.util.Locale

val SystemTypography = Typography().withExpressiveEmphasis()

private const val GoogleSansRoundedAxis = 100f

@OptIn(ExperimentalTextApi::class)
val GoogleSansRounded =
  FontFamily(
    Font(
      resId = R.font.gflex_variable,
      weight = FontWeight.Thin,
      variationSettings =
        FontVariation.Settings(
          FontVariation.weight(FontWeight.Thin.weight),
          FontVariation.Setting("ROND", GoogleSansRoundedAxis),
        ),
    ),
    Font(
      resId = R.font.gflex_variable,
      weight = FontWeight.ExtraLight,
      variationSettings =
        FontVariation.Settings(
          FontVariation.weight(FontWeight.ExtraLight.weight),
          FontVariation.Setting("ROND", GoogleSansRoundedAxis),
        ),
    ),
    Font(
      resId = R.font.gflex_variable,
      weight = FontWeight.Light,
      variationSettings =
        FontVariation.Settings(
          FontVariation.weight(FontWeight.Light.weight),
          FontVariation.Setting("ROND", GoogleSansRoundedAxis),
        ),
    ),
    Font(
      resId = R.font.gflex_variable,
      weight = FontWeight.Normal,
      variationSettings =
        FontVariation.Settings(
          FontVariation.weight(FontWeight.Normal.weight),
          FontVariation.Setting("ROND", GoogleSansRoundedAxis),
        ),
    ),
    Font(
      resId = R.font.gflex_variable,
      weight = FontWeight.Medium,
      variationSettings =
        FontVariation.Settings(
          FontVariation.weight(FontWeight.Medium.weight),
          FontVariation.Setting("ROND", GoogleSansRoundedAxis),
        ),
    ),
    Font(
      resId = R.font.gflex_variable,
      weight = FontWeight.SemiBold,
      variationSettings =
        FontVariation.Settings(
          FontVariation.weight(FontWeight.SemiBold.weight),
          FontVariation.Setting("ROND", GoogleSansRoundedAxis),
        ),
    ),
    Font(
      resId = R.font.gflex_variable,
      weight = FontWeight.Bold,
      variationSettings =
        FontVariation.Settings(
          FontVariation.weight(FontWeight.Bold.weight),
          FontVariation.Setting("ROND", GoogleSansRoundedAxis),
        ),
    ),
    Font(
      resId = R.font.gflex_variable,
      weight = FontWeight.ExtraBold,
      variationSettings =
        FontVariation.Settings(
          FontVariation.weight(FontWeight.ExtraBold.weight),
          FontVariation.Setting("ROND", GoogleSansRoundedAxis),
        ),
    ),
    Font(
      resId = R.font.gflex_variable,
      weight = FontWeight.Black,
      variationSettings =
        FontVariation.Settings(
          FontVariation.weight(FontWeight.Black.weight),
          FontVariation.Setting("ROND", GoogleSansRoundedAxis),
        ),
    ),
  )

/**
 * Display cut of the bundled Google Sans Flex variable font.
 *
 * The wider, lightly slanted axis mix gives app headings and hero copy a recognizable expressive
 * voice; body text keeps [GoogleSansRounded] for readability and predictable fallback. Multiple
 * weight instances keep the variable axes intentional instead of relying on synthetic bolding.
 */
@OptIn(ExperimentalTextApi::class)
val GoogleSansExpressiveDisplay =
  FontFamily(
    Font(
      resId = R.font.gflex_variable,
      weight = FontWeight.Medium,
      variationSettings =
        FontVariation.Settings(
          FontVariation.weight(FontWeight.Medium.weight),
          FontVariation.width(110f),
          FontVariation.slant(-4f),
          FontVariation.Setting("GRAD", 60f),
          FontVariation.Setting("ROND", GoogleSansRoundedAxis),
        ),
    ),
    Font(
      resId = R.font.gflex_variable,
      weight = FontWeight.SemiBold,
      variationSettings =
        FontVariation.Settings(
          FontVariation.weight(FontWeight.SemiBold.weight),
          FontVariation.width(111f),
          FontVariation.slant(-4f),
          FontVariation.Setting("GRAD", 70f),
          FontVariation.Setting("ROND", GoogleSansRoundedAxis),
        ),
    ),
    Font(
      resId = R.font.gflex_variable,
      weight = FontWeight.Bold,
      variationSettings =
        FontVariation.Settings(
          FontVariation.weight(FontWeight.Bold.weight),
          FontVariation.width(112f),
          FontVariation.slant(-4f),
          FontVariation.Setting("GRAD", 80f),
          FontVariation.Setting("ROND", GoogleSansRoundedAxis),
        ),
    ),
    Font(
      resId = R.font.gflex_variable,
      weight = FontWeight.ExtraBold,
      variationSettings =
        FontVariation.Settings(
          FontVariation.weight(FontWeight.ExtraBold.weight),
          FontVariation.width(112f),
          FontVariation.slant(-3f),
          FontVariation.Setting("GRAD", 90f),
          FontVariation.Setting("ROND", GoogleSansRoundedAxis),
        ),
    ),
    Font(
      resId = R.font.gflex_variable,
      weight = FontWeight.Black,
      variationSettings =
        FontVariation.Settings(
          FontVariation.weight(FontWeight.Black.weight),
          FontVariation.width(112f),
          FontVariation.slant(-2f),
          FontVariation.Setting("GRAD", 100f),
          FontVariation.Setting("ROND", GoogleSansRoundedAxis),
        ),
    ),
  )

fun typographyWithFontFamily(
  fontFamily: FontFamily,
  displayFontFamily: FontFamily = fontFamily,
): Typography =
  SystemTypography.run {
    copy(
      displayLarge = displayLarge.copy(fontFamily = displayFontFamily, fontWeight = FontWeight.Black, letterSpacing = 0.sp),
      displayMedium = displayMedium.copy(fontFamily = displayFontFamily, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.sp),
      displaySmall = displaySmall.copy(fontFamily = displayFontFamily, fontWeight = FontWeight.Bold, letterSpacing = 0.sp),
      headlineLarge = headlineLarge.copy(fontFamily = displayFontFamily, fontWeight = FontWeight.Bold, letterSpacing = 0.sp),
      headlineMedium = headlineMedium.copy(fontFamily = displayFontFamily, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
      headlineSmall = headlineSmall.copy(fontFamily = displayFontFamily, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
      titleLarge = titleLarge.copy(fontFamily = fontFamily, fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
      titleMedium = titleMedium.copy(fontFamily = fontFamily, fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
      titleSmall = titleSmall.copy(fontFamily = fontFamily, fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
      bodyLarge = bodyLarge.copy(fontFamily = fontFamily, fontWeight = FontWeight.Normal, letterSpacing = 0.sp),
      bodyMedium = bodyMedium.copy(fontFamily = fontFamily, fontWeight = FontWeight.Normal, letterSpacing = 0.sp),
      bodySmall = bodySmall.copy(fontFamily = fontFamily, fontWeight = FontWeight.Normal, letterSpacing = 0.sp),
      labelLarge = labelLarge.copy(fontFamily = fontFamily, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
      labelMedium = labelMedium.copy(fontFamily = fontFamily, fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
      labelSmall = labelSmall.copy(fontFamily = fontFamily, fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
    ).withExpressiveEmphasis()
  }

// Use PixelPlayer's rounded Google Sans Flex typography app-wide by default.
val AppTypography = typographyWithFontFamily(GoogleSansRounded, GoogleSansExpressiveDisplay)

val LocalAppFontFamily = staticCompositionLocalOf { GoogleSansRounded }

@Composable
fun fontFamilyForText(text: String): FontFamily =
  if (text.requiresSystemFontFallback()) FontFamily.SansSerif else LocalAppFontFamily.current

fun expressiveDisplayFontForText(text: String): FontFamily =
  if (text.requiresSystemFontFallback()) FontFamily.SansSerif else GoogleSansExpressiveDisplay

fun localeRequiresSystemFont(locale: Locale): Boolean =
  locale.getDisplayName(locale).requiresSystemFontFallback()

private fun String.requiresSystemFontFallback(): Boolean {
  var index = 0
  while (index < length) {
    val codePoint = Character.codePointAt(this, index)
    when (Character.UnicodeScript.of(codePoint)) {
      Character.UnicodeScript.LATIN,
      Character.UnicodeScript.COMMON,
      Character.UnicodeScript.INHERITED,
      -> Unit
      else -> return true
    }
    index += Character.charCount(codePoint)
  }
  return false
}

// ═══════════════════════════════════════════════════════════
// Material 3 Expressive Typography Extensions
// Bumps font weights one step heavier for expressive emphasis
// ═══════════════════════════════════════════════════════════

@Immutable
data class EmphasizedTypography(
  val displayLarge: TextStyle,
  val displayMedium: TextStyle,
  val displaySmall: TextStyle,
  val headlineLarge: TextStyle,
  val headlineMedium: TextStyle,
  val headlineSmall: TextStyle,
  val titleLarge: TextStyle,
  val titleMedium: TextStyle,
  val titleSmall: TextStyle,
  val bodyLarge: TextStyle,
  val bodyMedium: TextStyle,
  val bodySmall: TextStyle,
  val labelLarge: TextStyle,
  val labelMedium: TextStyle,
  val labelSmall: TextStyle,
)

/** Keep native Material components and app headings on the same selected font and type scale. */
private fun Typography.withExpressiveEmphasis(): Typography =
  copy(
    displayLargeEmphasized = displayLarge.copy(fontWeight = FontWeight.Black),
    displayMediumEmphasized = displayMedium.copy(fontWeight = FontWeight.Black),
    displaySmallEmphasized = displaySmall.copy(fontWeight = FontWeight.ExtraBold),
    headlineLargeEmphasized = headlineLarge.copy(fontWeight = FontWeight.ExtraBold),
    headlineMediumEmphasized = headlineMedium.copy(fontWeight = FontWeight.ExtraBold),
    headlineSmallEmphasized = headlineSmall.copy(fontWeight = FontWeight.Bold),
    titleLargeEmphasized = titleLarge.copy(fontWeight = FontWeight.Bold),
    titleMediumEmphasized = titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmallEmphasized = titleSmall.copy(fontWeight = FontWeight.SemiBold),
    bodyLargeEmphasized = bodyLarge.copy(fontWeight = FontWeight.Medium),
    bodyMediumEmphasized = bodyMedium.copy(fontWeight = FontWeight.Medium),
    bodySmallEmphasized = bodySmall.copy(fontWeight = FontWeight.Medium),
    labelLargeEmphasized = labelLarge.copy(fontWeight = FontWeight.Bold),
    labelMediumEmphasized = labelMedium.copy(fontWeight = FontWeight.Bold),
    labelSmallEmphasized = labelSmall.copy(fontWeight = FontWeight.Bold),
  )

fun emphasizedTypography(typography: Typography): EmphasizedTypography =
  EmphasizedTypography(
    displayLarge = typography.displayLargeEmphasized,
    displayMedium = typography.displayMediumEmphasized,
    displaySmall = typography.displaySmallEmphasized,
    headlineLarge = typography.headlineLargeEmphasized,
    headlineMedium = typography.headlineMediumEmphasized,
    headlineSmall = typography.headlineSmallEmphasized,
    titleLarge = typography.titleLargeEmphasized,
    titleMedium = typography.titleMediumEmphasized,
    titleSmall = typography.titleSmallEmphasized,
    bodyLarge = typography.bodyLargeEmphasized,
    bodyMedium = typography.bodyMediumEmphasized,
    bodySmall = typography.bodySmallEmphasized,
    labelLarge = typography.labelLargeEmphasized,
    labelMedium = typography.labelMediumEmphasized,
    labelSmall = typography.labelSmallEmphasized,
  )

val AppEmphasizedTypography = emphasizedTypography(AppTypography)

val LocalEmphasizedTypography = staticCompositionLocalOf { AppEmphasizedTypography }
