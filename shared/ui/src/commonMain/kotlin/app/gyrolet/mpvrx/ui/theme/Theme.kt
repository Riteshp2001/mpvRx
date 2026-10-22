package app.gyrolet.mpvrx.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gyrolet.mpvrx.core.preferences.AppearancePreferences
import app.gyrolet.mpvrx.core.preferences.DarkMode
import app.gyrolet.mpvrx.ui.resources.Res
import app.gyrolet.mpvrx.ui.resources.gflex_variable
import app.gyrolet.mpvrx.ui.util.collectAsState
import org.jetbrains.compose.resources.Font

@Immutable
data class Spacing(
  val extraSmall: Dp = 4.dp,
  val smaller: Dp = 8.dp,
  val small: Dp = 12.dp,
  val medium: Dp = 16.dp,
  val large: Dp = 24.dp,
  val larger: Dp = 32.dp,
  val extraLarge: Dp = 48.dp,
  val largest: Dp = 64.dp,
)

val LocalSpacing = compositionLocalOf { Spacing() }

val MaterialTheme.spacing: Spacing
  @Composable @ReadOnlyComposable
  get() = LocalSpacing.current

object AppShapeScale {
  val extraSmall = RoundedCornerShape(4.dp)
  val small = RoundedCornerShape(8.dp)
  val medium = RoundedCornerShape(12.dp)
  val large = RoundedCornerShape(16.dp)
  val largeIncreased = RoundedCornerShape(20.dp)
  val extraLarge = RoundedCornerShape(28.dp)
  val extraLargeIncreased = RoundedCornerShape(32.dp)
  val extraExtraLarge = RoundedCornerShape(48.dp)
  val full = RoundedCornerShape(50)
}

val AppShapes =
  Shapes(
    extraSmall = AppShapeScale.extraSmall,
    small = AppShapeScale.small,
    medium = AppShapeScale.medium,
    large = AppShapeScale.large,
    largeIncreased = AppShapeScale.largeIncreased,
    extraLarge = AppShapeScale.extraLarge,
    extraLargeIncreased = AppShapeScale.extraLargeIncreased,
    extraExtraLarge = AppShapeScale.extraExtraLarge,
  )

/** Dark scheme of the selected palette, used by the player which is always dark. */
val LocalDarkAppColorScheme = staticCompositionLocalOf<ColorScheme?> { null }

/** OS accent colour supplied by the platform shell for the Dynamic theme (null = unsupported). */
val LocalPlatformAccentColor = staticCompositionLocalOf<Color?> { null }

private const val GoogleSansRoundedAxis = 100f

@Composable
private fun googleSansRounded(): FontFamily {
  val weights =
    listOf(
      FontWeight.Light, FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold,
      FontWeight.Bold, FontWeight.ExtraBold, FontWeight.Black,
    )
  val fonts =
    weights.map { weight ->
      Font(
        resource = Res.font.gflex_variable,
        weight = weight,
        variationSettings =
          FontVariation.Settings(
            FontVariation.weight(weight.weight),
            FontVariation.Setting("ROND", GoogleSansRoundedAxis),
          ),
      )
    }
  return remember(fonts) { FontFamily(fonts) }
}

@Composable
private fun googleSansExpressiveDisplay(): FontFamily {
  val fonts =
    listOf(
      Triple(FontWeight.Medium, 110f, 60f),
      Triple(FontWeight.SemiBold, 111f, 70f),
      Triple(FontWeight.Bold, 112f, 80f),
      Triple(FontWeight.ExtraBold, 112f, 90f),
      Triple(FontWeight.Black, 112f, 100f),
    ).map { (weight, width, grade) ->
      Font(
        resource = Res.font.gflex_variable,
        weight = weight,
        variationSettings =
          FontVariation.Settings(
            FontVariation.weight(weight.weight),
            FontVariation.width(width),
            FontVariation.slant(-4f),
            FontVariation.Setting("GRAD", grade),
            FontVariation.Setting("ROND", GoogleSansRoundedAxis),
          ),
      )
    }
  return remember(fonts) { FontFamily(fonts) }
}

fun typographyWithFontFamily(fontFamily: FontFamily, displayFontFamily: FontFamily = fontFamily): Typography =
  Typography().run {
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
      bodyLarge = bodyLarge.copy(fontFamily = fontFamily, letterSpacing = 0.sp),
      bodyMedium = bodyMedium.copy(fontFamily = fontFamily, letterSpacing = 0.sp),
      bodySmall = bodySmall.copy(fontFamily = fontFamily, letterSpacing = 0.sp),
      labelLarge = labelLarge.copy(fontFamily = fontFamily, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
      labelMedium = labelMedium.copy(fontFamily = fontFamily, fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
      labelSmall = labelSmall.copy(fontFamily = fontFamily, fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
    )
  }

fun resolveAppColorScheme(
  theme: AppTheme,
  dark: Boolean,
  amoled: Boolean,
  platformAccent: Color?,
): ColorScheme =
  when {
    theme.isDynamic && platformAccent != null -> dynamicColorScheme(platformAccent, dark, amoled)
    dark && amoled -> theme.getAmoledColorScheme()
    dark -> theme.getDarkColorScheme()
    else -> theme.getLightColorScheme()
  }

@Composable
fun MpvrxTheme(
  preferences: AppearancePreferences,
  content: @Composable () -> Unit,
) {
  val darkMode by preferences.darkMode.collectAsState()
  val amoled by preferences.amoledMode.collectAsState()
  val themeName by preferences.appTheme.collectAsState()
  val useSystemFont by preferences.useSystemFont.collectAsState()
  val systemDark = isSystemInDarkTheme()
  val accent = LocalPlatformAccentColor.current
  val theme = AppTheme.fromName(themeName)
  val dark =
    when (darkMode) {
      DarkMode.Dark -> true
      DarkMode.Light -> false
      DarkMode.System -> systemDark
    }
  val colorScheme = remember(theme, dark, amoled, accent) { resolveAppColorScheme(theme, dark, amoled, accent) }
  val darkScheme = remember(theme, amoled, accent) { resolveAppColorScheme(theme, true, amoled, accent) }

  val rounded = googleSansRounded()
  val display = googleSansExpressiveDisplay()
  val typography =
    remember(rounded, display, useSystemFont) {
      if (useSystemFont) Typography() else typographyWithFontFamily(rounded, display)
    }

  CompositionLocalProvider(
    LocalSpacing provides Spacing(),
    LocalDarkAppColorScheme provides darkScheme,
  ) {
    MaterialExpressiveTheme(
      colorScheme = colorScheme,
      typography = typography,
      shapes = AppShapes,
      motionScheme = MotionScheme.expressive(),
      content = content,
    )
  }
}

/** Player surfaces always use the dark variant of the chosen palette, as on Android. */
@Composable
fun PlayerTheme(content: @Composable () -> Unit) {
  val scheme = LocalDarkAppColorScheme.current ?: MaterialTheme.colorScheme
  MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes, content = content)
}
