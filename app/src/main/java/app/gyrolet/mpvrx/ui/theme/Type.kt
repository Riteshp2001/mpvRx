/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
 */

package app.gyrolet.mpvrx.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import app.gyrolet.mpvrx.R

// Roboto Flex font family (variable font supporting weights 100-900)
val RobotoFlex =
  FontFamily(
    Font(
      resId = R.font.roboto_flex,
      weight = FontWeight.Thin,
      style = FontStyle.Normal,
    ),
    Font(
      resId = R.font.roboto_flex,
      weight = FontWeight.ExtraLight,
      style = FontStyle.Normal,
    ),
    Font(
      resId = R.font.roboto_flex,
      weight = FontWeight.Light,
      style = FontStyle.Normal,
    ),
    Font(
      resId = R.font.roboto_flex,
      weight = FontWeight.Normal,
      style = FontStyle.Normal,
    ),
    Font(
      resId = R.font.roboto_flex,
      weight = FontWeight.Medium,
      style = FontStyle.Normal,
    ),
    Font(
      resId = R.font.roboto_flex,
      weight = FontWeight.SemiBold,
      style = FontStyle.Normal,
    ),
    Font(
      resId = R.font.roboto_flex,
      weight = FontWeight.Bold,
      style = FontStyle.Normal,
    ),
    Font(
      resId = R.font.roboto_flex,
      weight = FontWeight.ExtraBold,
      style = FontStyle.Normal,
    ),
    Font(
      resId = R.font.roboto_flex,
      weight = FontWeight.Black,
      style = FontStyle.Normal,
    ),
  )

val SystemTypography = Typography()

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

// Use PixelPlayer's rounded Google Sans Flex typography app-wide by default.
val AppTypography =
  SystemTypography.run {
    copy(
      displayLarge = displayLarge.copy(fontFamily = GoogleSansRounded),
      displayMedium = displayMedium.copy(fontFamily = GoogleSansRounded),
      displaySmall = displaySmall.copy(fontFamily = GoogleSansRounded),
      headlineLarge = headlineLarge.copy(fontFamily = GoogleSansRounded),
      headlineMedium = headlineMedium.copy(fontFamily = GoogleSansRounded),
      headlineSmall = headlineSmall.copy(fontFamily = GoogleSansRounded),
      titleLarge = titleLarge.copy(fontFamily = GoogleSansRounded),
      titleMedium = titleMedium.copy(fontFamily = GoogleSansRounded),
      titleSmall = titleSmall.copy(fontFamily = GoogleSansRounded),
      bodyLarge = bodyLarge.copy(fontFamily = GoogleSansRounded),
      bodyMedium = bodyMedium.copy(fontFamily = GoogleSansRounded),
      bodySmall = bodySmall.copy(fontFamily = GoogleSansRounded),
      labelLarge = labelLarge.copy(fontFamily = GoogleSansRounded),
      labelMedium = labelMedium.copy(fontFamily = GoogleSansRounded),
      labelSmall = labelSmall.copy(fontFamily = GoogleSansRounded),
    )
  }

// ═══════════════════════════════════════════════════════════
// Material 3 Expressive Typography Extensions
// Bumps font weights one step heavier for expressive emphasis
// ═══════════════════════════════════════════════════════════

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

val AppEmphasizedTypography =
  EmphasizedTypography(
    displayLarge = AppTypography.displayLarge.copy(fontWeight = FontWeight.Black),
    displayMedium = AppTypography.displayMedium.copy(fontWeight = FontWeight.Black),
    displaySmall = AppTypography.displaySmall.copy(fontWeight = FontWeight.ExtraBold),
    headlineLarge = AppTypography.headlineLarge.copy(fontWeight = FontWeight.ExtraBold),
    headlineMedium = AppTypography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold),
    headlineSmall = AppTypography.headlineSmall.copy(fontWeight = FontWeight.Bold),
    titleLarge = AppTypography.titleLarge.copy(fontWeight = FontWeight.Bold),
    titleMedium = AppTypography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = AppTypography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = AppTypography.bodyLarge.copy(fontWeight = FontWeight.Medium),
    bodyMedium = AppTypography.bodyMedium.copy(fontWeight = FontWeight.Medium),
    bodySmall = AppTypography.bodySmall.copy(fontWeight = FontWeight.Medium),
    labelLarge = AppTypography.labelLarge.copy(fontWeight = FontWeight.Bold),
    labelMedium = AppTypography.labelMedium.copy(fontWeight = FontWeight.Bold),
    labelSmall = AppTypography.labelSmall.copy(fontWeight = FontWeight.Bold),
  )

val LocalEmphasizedTypography = staticCompositionLocalOf { AppEmphasizedTypography }
