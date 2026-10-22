package app.gyrolet.mpvrx.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance

/** Palette catalogue ported 1:1 from the Android app so every platform looks identical. */
enum class AppTheme(
  val title: String,
  val primaryLight: Color,
  val primaryDark: Color,
  val secondaryLight: Color,
  val secondaryDark: Color,
  val tertiaryLight: Color,
  val tertiaryDark: Color,
  val backgroundLight: Color,
  val backgroundDark: Color,
  /** Android follows the wallpaper; desktop seeds it from the OS accent colour when available. */
  val isDynamic: Boolean = false,
) {
  Default("Default", Color(0xFF794F81), Color(0xFFE8B5EF), Color(0xFF6A596C), Color(0xFFD6C0D6), Color(0xFF82524D), Color(0xFFF5B7B0), Color(0xFFF7F5F8), Color(0xFF161217)),
  Dynamic("Dynamic", Color(0xFF6750A4), Color(0xFFD0BCFF), Color(0xFF625B71), Color(0xFFCCC2DC), Color(0xFF7D5260), Color(0xFFEFB8C8), Color(0xFFFFFBFF), Color(0xFF1C1B1F), isDynamic = true),
  Catppuccin("Catppuccin", Color(0xFF4C6B9A), Color(0xFF9BA8CF), Color(0xFFB76B8F), Color(0xFFD4A5B8), Color(0xFFB8763E), Color(0xFF8AB8A8), Color(0xFFEFF1F5), Color(0xFF1E1E2E)),
  Aurora("Aurora", Color(0xFF0B3FA0), Color(0xFF5B93FF), Color(0xFF5C6B8C), Color(0xFF9FAEC9), Color(0xFF3648A6), Color(0xFF97A8FF), Color(0xFFF3F6FF), Color(0xFF04070F)),
  Cloudflare("Cloudflare", Color(0xFFF6821F), Color(0xFFFFB77C), Color(0xFF6B5E4C), Color(0xFFD6C5AC), Color(0xFF855316), Color(0xFFFABD71), Color(0xFFFFFBF7), Color(0xFF1A1612)),
  CottonCandy("Cotton Candy", Color(0xFFE993C1), Color(0xFFFFB1D5), Color(0xFF70A2C2), Color(0xFF9ED0EF), Color(0xFF9C68AC), Color(0xFFDEB0E9), Color(0xFFFFF8FA), Color(0xFF1A1418)),
  Doom("Doom", Color(0xFFBB2929), Color(0xFFFF6B6B), Color(0xFF6B5353), Color(0xFFD6BABA), Color(0xFF8C4A4A), Color(0xFFFFB4AB), Color(0xFFFFF8F7), Color(0xFF1A1010)),
  GreenApple("Green Apple", Color(0xFF2E7D32), Color(0xFF81C784), Color(0xFF4A6349), Color(0xFFB0CFB1), Color(0xFF3D7B5F), Color(0xFF8FD5B7), Color(0xFFF6FFF6), Color(0xFF0F1A0F)),
  Gruvbox("Gruvbox", Color(0xFF9D5B3F), Color(0xFFD89B6A), Color(0xFF7A7556), Color(0xFFB0AE8A), Color(0xFF4A7B7C), Color(0xFF8AAFA8), Color(0xFFFBF1C7), Color(0xFF282828)),
  Kanagawa("Kanagawa", Color(0xFF5A7785), Color(0xFF7E9CD8), Color(0xFF8A7A6E), Color(0xFFDCA561), Color(0xFF6A8E7F), Color(0xFF98BB6C), Color(0xFFF2ECBC), Color(0xFF1F1F28)),
  Lavender("Lavender", Color(0xFF7C5AB8), Color(0xFFCFBCFF), Color(0xFF635B70), Color(0xFFCBC3DA), Color(0xFF7E525A), Color(0xFFF2B8C1), Color(0xFFFCF8FF), Color(0xFF16121A)),
  Midnight("Midnight", Color(0xFF0D47A1), Color(0xFF90CAF9), Color(0xFF455A64), Color(0xFFB0BEC5), Color(0xFF1565C0), Color(0xFF64B5F6), Color(0xFFF5F9FF), Color(0xFF0D1117)),
  Mocha("Mocha", Color(0xFF795548), Color(0xFFBCAAA4), Color(0xFF5D4037), Color(0xFFA1887F), Color(0xFF6D4C41), Color(0xFFD7CCC8), Color(0xFFFFF9F5), Color(0xFF1A1512)),
  Strawberry("Strawberry", Color(0xFFD81B60), Color(0xFFF48FB1), Color(0xFF6B4958), Color(0xFFD6B0C1), Color(0xFFC2185B), Color(0xFFF8BBD9), Color(0xFFFFF5F8), Color(0xFF1A1015)),
  Tidal("Tidal", Color(0xFF00796B), Color(0xFF80CBC4), Color(0xFF4A635E), Color(0xFFB0CFC9), Color(0xFF00897B), Color(0xFF4DB6AC), Color(0xFFF2FFFD), Color(0xFF0F1A18)),
  Nord("Nord", Color(0xFF5E81AC), Color(0xFF88C0D0), Color(0xFF4C566A), Color(0xFFD8DEE9), Color(0xFFB48EAD), Color(0xFFD8A9C4), Color(0xFFECEFF4), Color(0xFF2E3440)),
  RosePine("Rosé Pine", Color(0xFF907AA9), Color(0xFFC4A7E7), Color(0xFFB4637A), Color(0xFFEBBCBA), Color(0xFF7A9A8A), Color(0xFF9CCFD8), Color(0xFFFAF4ED), Color(0xFF232136)),
  TakoGreen("Tako Green", Color(0xFF66BB6A), Color(0xFFA5D6A7), Color(0xFF546E7A), Color(0xFF90A4AE), Color(0xFF43A047), Color(0xFF81C784), Color(0xFFF5FFF5), Color(0xFF121A12)),
  TokyoNight("Tokyo Night", Color(0xFF3D5A80), Color(0xFF7D9BC1), Color(0xFF6B5B95), Color(0xFFA89DC9), Color(0xFF4A6B5C), Color(0xFF8AB4A3), Color(0xFFF0F1F5), Color(0xFF1A1B26)),
  YinYang("Yin Yang", Color(0xFF424242), Color(0xFFBDBDBD), Color(0xFF616161), Color(0xFFE0E0E0), Color(0xFF757575), Color(0xFFEEEEEE), Color(0xFFFAFAFA), Color(0xFF121212)),
  Yotsuba("Yotsuba", Color(0xFFFF8A65), Color(0xFFFFAB91), Color(0xFF6D5D5B), Color(0xFFD6C4C2), Color(0xFFFF7043), Color(0xFFFFCCBC), Color(0xFFFFF8F5), Color(0xFF1A1412)),
  Sapphire("Sapphire", Color(0xFF1E88E5), Color(0xFF64B5F6), Color(0xFF5C6BC0), Color(0xFF9FA8DA), Color(0xFF0288D1), Color(0xFF4FC3F7), Color(0xFFF3F8FF), Color(0xFF0D1620)),
  Sunset("Sunset", Color(0xFFE65100), Color(0xFFFF9E80), Color(0xFFEF6C00), Color(0xFFFFCC80), Color(0xFFF4511E), Color(0xFFFF8A65), Color(0xFFFFF5F0), Color(0xFF1A120D)),
  Ocean("Ocean", Color(0xFF006064), Color(0xFF4DD0E1), Color(0xFF00838F), Color(0xFF80DEEA), Color(0xFF0097A7), Color(0xFF26C6DA), Color(0xFFF0FFFF), Color(0xFF0A1A1C)),
  Forest("Forest", Color(0xFF1B5E20), Color(0xFF66BB6A), Color(0xFF33691E), Color(0xFF9CCC65), Color(0xFF2E7D32), Color(0xFFA5D6A7), Color(0xFFF1F8E9), Color(0xFF0D1A0D)),
  RoseGold("Rose Gold", Color(0xFFB76E79), Color(0xFFE8A9B0), Color(0xFFAD8075), Color(0xFFDDBFB8), Color(0xFFD4A5A5), Color(0xFFF5D5D5), Color(0xFFFFF5F5), Color(0xFF1A1315)),
  Violet("Violet", Color(0xFF6A1B9A), Color(0xFFCE93D8), Color(0xFF7B1FA2), Color(0xFFE1BEE7), Color(0xFF8E24AA), Color(0xFFBA68C8), Color(0xFFFCF5FF), Color(0xFF150D1A)),
  Amber("Amber", Color(0xFFFF8F00), Color(0xFFFFCA28), Color(0xFFFFA000), Color(0xFFFFD54F), Color(0xFFFFB300), Color(0xFFFFE082), Color(0xFFFFFBF0), Color(0xFF1A1508)),
  Coral("Coral", Color(0xFFFF5252), Color(0xFFFF8A80), Color(0xFFFF6E40), Color(0xFFFFAB91), Color(0xFFFF7043), Color(0xFFFFCCBC), Color(0xFFFFF5F5), Color(0xFF1A1010)),
  Slate("Slate", Color(0xFF455A64), Color(0xFF90A4AE), Color(0xFF546E7A), Color(0xFFB0BEC5), Color(0xFF607D8B), Color(0xFFCFD8DC), Color(0xFFF5F7F8), Color(0xFF151A1C)),
  Dracula("Dracula", Color(0xFF6272A4), Color(0xFFBD93F9), Color(0xFF44475A), Color(0xFFFF79C6), Color(0xFF50FA7B), Color(0xFF8BE9FD), Color(0xFFF8F8F2), Color(0xFF282A36)),
  Monochrome("Monochrome", Color(0xFF212121), Color(0xFFE0E0E0), Color(0xFF424242), Color(0xFFBDBDBD), Color(0xFF616161), Color(0xFF9E9E9E), Color(0xFFFFFFFF), Color(0xFF0A0A0A)),
  ;

  fun getLightColorScheme(): ColorScheme {
    val softOnColor = Color(0xFFFFF9FC)
    val primaryContainer = primaryLight.copy(alpha = 0.22f).compositeOver(backgroundLight)
    val secondaryContainer = secondaryLight.copy(alpha = 0.22f).compositeOver(backgroundLight)
    val tertiaryContainer = tertiaryLight.copy(alpha = 0.22f).compositeOver(backgroundLight)
    val surfaceVariant = primaryLight.copy(alpha = 0.10f).compositeOver(backgroundLight.darken(0.035f))
    val surfaceDim = backgroundLight.darken(0.075f)
    val surfaceBright = backgroundLight.lighten(0.012f)
    val surfaceContainerLowest = backgroundLight.lighten(0.018f)
    val surfaceContainerLow = primaryLight.copy(alpha = 0.030f).compositeOver(backgroundLight)
    val surfaceContainer = primaryLight.copy(alpha = 0.050f).compositeOver(backgroundLight)
    val surfaceContainerHigh = primaryLight.copy(alpha = 0.075f).compositeOver(backgroundLight)
    val surfaceContainerHighest = primaryLight.copy(alpha = 0.105f).compositeOver(backgroundLight)
    val accentSurfaces =
      listOf(
        backgroundLight, surfaceVariant, surfaceDim, surfaceBright, surfaceContainerLowest,
        surfaceContainerLow, surfaceContainer, surfaceContainerHigh, surfaceContainerHighest,
      )
    val primary = primaryLight.withMinimumContrastAgainst(accentSurfaces)
    val secondary = secondaryLight.withMinimumContrastAgainst(accentSurfaces)
    val tertiary = tertiaryLight.withMinimumContrastAgainst(accentSurfaces)
    return lightColorScheme(
      primary = primary,
      onPrimary = primary.accessibleContentColor(),
      primaryContainer = primaryContainer,
      onPrimaryContainer = primaryContainer.accessibleContentColor(),
      secondary = secondary,
      onSecondary = secondary.accessibleContentColor(),
      secondaryContainer = secondaryContainer,
      onSecondaryContainer = secondaryContainer.accessibleContentColor(),
      tertiary = tertiary,
      onTertiary = tertiary.accessibleContentColor(),
      tertiaryContainer = tertiaryContainer,
      onTertiaryContainer = tertiaryContainer.accessibleContentColor(),
      error = Color(0xFFBA1A1A),
      onError = softOnColor,
      errorContainer = Color(0xFFFFDAD6),
      onErrorContainer = Color(0xFF93000A),
      background = backgroundLight,
      onBackground = Color(0xFF1C1B1F),
      surface = backgroundLight,
      onSurface = Color(0xFF1C1B1F),
      surfaceVariant = surfaceVariant,
      onSurfaceVariant = Color(0xFF49454F),
      outline = secondaryLight.copy(alpha = 0.6f).compositeOver(Color(0xFF79747E)),
      outlineVariant = primaryLight.copy(alpha = 0.20f).compositeOver(Color(0xFFCAC4D0)),
      inverseSurface = backgroundDark,
      inverseOnSurface = Color(0xFFF4EFF4),
      inversePrimary = primaryDark.withMinimumContrastAgainst(listOf(backgroundDark)),
      surfaceDim = surfaceDim,
      surfaceBright = surfaceBright,
      surfaceContainerLowest = surfaceContainerLowest,
      surfaceContainerLow = surfaceContainerLow,
      surfaceContainer = surfaceContainer,
      surfaceContainerHigh = surfaceContainerHigh,
      surfaceContainerHighest = surfaceContainerHighest,
    )
  }

  fun getDarkColorScheme(): ColorScheme {
    val primaryContainer = primaryLight.darken(0.2f)
    val secondaryContainer = secondaryLight.darken(0.2f)
    val tertiaryContainer = tertiaryLight.darken(0.2f)
    val surfaceVariant = primaryDark.copy(alpha = 0.18f).compositeOver(Color(0xFF2A2A2A))
    val surfaceContainerLowest = backgroundDark.darken(0.2f)
    val surfaceContainerLow = primaryDark.copy(alpha = 0.08f).compositeOver(backgroundDark)
    val surfaceContainer = primaryDark.copy(alpha = 0.08f).compositeOver(backgroundDark)
    val surfaceContainerHigh = primaryDark.copy(alpha = 0.12f).compositeOver(backgroundDark)
    val surfaceContainerHighest = primaryDark.copy(alpha = 0.16f).compositeOver(backgroundDark)
    val accentSurfaces =
      listOf(
        backgroundDark, surfaceVariant, surfaceContainerLowest, surfaceContainerLow,
        surfaceContainer, surfaceContainerHigh, surfaceContainerHighest,
      )
    val primary = primaryDark.withMinimumContrastAgainst(accentSurfaces)
    val secondary = secondaryDark.withMinimumContrastAgainst(accentSurfaces)
    val tertiary = tertiaryDark.withMinimumContrastAgainst(accentSurfaces)
    return darkColorScheme(
      primary = primary,
      onPrimary = primary.accessibleContentColor(),
      primaryContainer = primaryContainer,
      onPrimaryContainer = primaryContainer.accessibleContentColor(),
      secondary = secondary,
      onSecondary = secondary.accessibleContentColor(),
      secondaryContainer = secondaryContainer,
      onSecondaryContainer = secondaryContainer.accessibleContentColor(),
      tertiary = tertiary,
      onTertiary = tertiary.accessibleContentColor(),
      tertiaryContainer = tertiaryContainer,
      onTertiaryContainer = tertiaryContainer.accessibleContentColor(),
      error = Color(0xFFFFB4AB),
      onError = Color(0xFF690005),
      errorContainer = Color(0xFF93000A),
      onErrorContainer = Color(0xFFFFDAD6),
      background = backgroundDark,
      onBackground = Color(0xFFE6E1E5),
      surface = backgroundDark,
      onSurface = Color(0xFFE6E1E5),
      surfaceVariant = surfaceVariant,
      onSurfaceVariant = Color(0xFFCAC4D0),
      outline = secondaryDark.copy(alpha = 0.5f).compositeOver(Color(0xFF938F99)),
      outlineVariant = primaryDark.copy(alpha = 0.22f).compositeOver(Color(0xFF49454F)),
      inverseSurface = backgroundLight,
      inverseOnSurface = Color(0xFF313033),
      inversePrimary = primaryLight.withMinimumContrastAgainst(listOf(backgroundLight)),
      surfaceContainerLowest = surfaceContainerLowest,
      surfaceContainerLow = surfaceContainerLow,
      surfaceContainer = surfaceContainer,
      surfaceContainerHigh = surfaceContainerHigh,
      surfaceContainerHighest = surfaceContainerHighest,
    )
  }

  fun getAmoledColorScheme(): ColorScheme =
    getDarkColorScheme().copy(
      background = Color.Black,
      surface = Color.Black,
      surfaceVariant = primaryDark.copy(alpha = 0.08f).compositeOver(Color(0xFF1A1A1A)),
      surfaceContainer = primaryDark.copy(alpha = 0.05f).compositeOver(Color(0xFF151515)),
      surfaceContainerLow = primaryDark.copy(alpha = 0.05f).compositeOver(Color(0xFF151515)),
      surfaceContainerLowest = Color.Black,
      surfaceContainerHigh = primaryDark.copy(alpha = 0.05f).compositeOver(Color(0xFF151515)),
      surfaceContainerHighest = primaryDark.copy(alpha = 0.08f).compositeOver(Color(0xFF1F1F1F)),
      surfaceDim = Color.Black,
      surfaceBright = primaryDark.copy(alpha = 0.06f).compositeOver(Color(0xFF2A2A2A)),
    )

  companion object {
    fun fromName(name: String): AppTheme = entries.firstOrNull { it.name == name } ?: Default
  }
}

/** Derives a full scheme from an OS accent colour for [AppTheme.Dynamic]. */
fun dynamicColorScheme(accent: Color, dark: Boolean, amoled: Boolean): ColorScheme {
  val base = AppTheme.Dynamic
  val lightAccent = accent.darken(0.15f)
  val darkAccent = accent.lighten(0.35f)
  val tinted =
    if (dark) {
      base.getDarkColorScheme().copy(
        primary = darkAccent.withMinimumContrastAgainst(listOf(base.backgroundDark)),
        onPrimary = darkAccent.accessibleContentColor(),
        primaryContainer = lightAccent.darken(0.2f),
        onPrimaryContainer = lightAccent.darken(0.2f).accessibleContentColor(),
        surfaceContainerLow = darkAccent.copy(alpha = 0.08f).compositeOver(base.backgroundDark),
        surfaceContainer = darkAccent.copy(alpha = 0.08f).compositeOver(base.backgroundDark),
        surfaceContainerHigh = darkAccent.copy(alpha = 0.12f).compositeOver(base.backgroundDark),
        surfaceContainerHighest = darkAccent.copy(alpha = 0.16f).compositeOver(base.backgroundDark),
      )
    } else {
      base.getLightColorScheme().copy(
        primary = lightAccent.withMinimumContrastAgainst(listOf(base.backgroundLight)),
        onPrimary = lightAccent.accessibleContentColor(),
        primaryContainer = lightAccent.copy(alpha = 0.22f).compositeOver(base.backgroundLight),
        surfaceContainerLow = lightAccent.copy(alpha = 0.030f).compositeOver(base.backgroundLight),
        surfaceContainer = lightAccent.copy(alpha = 0.050f).compositeOver(base.backgroundLight),
        surfaceContainerHigh = lightAccent.copy(alpha = 0.075f).compositeOver(base.backgroundLight),
        surfaceContainerHighest = lightAccent.copy(alpha = 0.105f).compositeOver(base.backgroundLight),
      )
    }
  return if (dark && amoled) {
    tinted.copy(background = Color.Black, surface = Color.Black, surfaceContainerLowest = Color.Black, surfaceDim = Color.Black)
  } else {
    tinted
  }
}

private const val MinimumTextContrast = 4.5f

private fun Color.accessibleContentColor(): Color =
  if (contrastRatio(Color.Black) >= contrastRatio(Color.White)) Color.Black else Color.White

private fun Color.withMinimumContrastAgainst(backgrounds: List<Color>): Color {
  if (backgrounds.minOf { contrastRatio(it) } >= MinimumTextContrast) return this
  val blackContrast = backgrounds.minOf { Color.Black.contrastRatio(it) }
  val whiteContrast = backgrounds.minOf { Color.White.contrastRatio(it) }
  val target = if (blackContrast >= whiteContrast) Color.Black else Color.White
  var insufficient = 0f
  var sufficient = 1f
  repeat(12) {
    val fraction = (insufficient + sufficient) / 2f
    val candidate = blendToward(target, fraction)
    if (backgrounds.minOf { candidate.contrastRatio(it) } >= MinimumTextContrast) sufficient = fraction else insufficient = fraction
  }
  return blendToward(target, sufficient)
}

private fun Color.contrastRatio(other: Color): Float {
  val a = luminance()
  val b = other.luminance()
  return (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)
}

private fun Color.blendToward(target: Color, fraction: Float): Color =
  Color(
    red = red + (target.red - red) * fraction,
    green = green + (target.green - green) * fraction,
    blue = blue + (target.blue - blue) * fraction,
    alpha = alpha + (target.alpha - alpha) * fraction,
  )

private fun Color.darken(factor: Float): Color =
  Color(
    red = (red * (1 - factor)).coerceIn(0f, 1f),
    green = (green * (1 - factor)).coerceIn(0f, 1f),
    blue = (blue * (1 - factor)).coerceIn(0f, 1f),
    alpha = alpha,
  )

private fun Color.lighten(factor: Float): Color =
  Color(
    red = (red + (1 - red) * factor).coerceIn(0f, 1f),
    green = (green + (1 - green) * factor).coerceIn(0f, 1f),
    blue = (blue + (1 - blue) * factor).coerceIn(0f, 1f),
    alpha = alpha,
  )
