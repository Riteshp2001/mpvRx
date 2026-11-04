/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.theme

import androidx.annotation.StringRes
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import app.gyrolet.mpvrx.R

/** Per-mode color overrides, including exact accents when copying a system palette. */
enum class ThemeColorRole(
  val key: String,
  @StringRes val titleRes: Int,
) {
  Primary("primary", R.string.theme_color_primary),
  Secondary("secondary", R.string.theme_color_secondary),
  Tertiary("tertiary", R.string.theme_color_tertiary),
  OnPrimary("on_primary", R.string.theme_color_on_primary),
  PrimaryContainer("primary_container", R.string.theme_color_primary_container),
  OnPrimaryContainer("on_primary_container", R.string.theme_color_on_primary_container),
  OnSecondary("on_secondary", R.string.theme_color_on_secondary),
  SecondaryContainer("secondary_container", R.string.theme_color_secondary_container),
  OnSecondaryContainer("on_secondary_container", R.string.theme_color_on_secondary_container),
  OnTertiary("on_tertiary", R.string.theme_color_on_tertiary),
  TertiaryContainer("tertiary_container", R.string.theme_color_tertiary_container),
  OnTertiaryContainer("on_tertiary_container", R.string.theme_color_on_tertiary_container),
  Surface("surface", R.string.theme_color_surface),
  OnSurface("on_surface", R.string.theme_color_on_surface),
  OnBackground("on_background", R.string.theme_color_on_background),
  SurfaceVariant("surface_variant", R.string.theme_color_surface_variant),
  OnSurfaceVariant("on_surface_variant", R.string.theme_color_on_surface_variant),
  SurfaceDim("surface_dim", R.string.theme_color_surface_dim),
  SurfaceBright("surface_bright", R.string.theme_color_surface_bright),
  SurfaceContainerLowest("surface_container_lowest", R.string.theme_color_surface_container_lowest),
  SurfaceContainerLow("surface_container_low", R.string.theme_color_surface_container_low),
  SurfaceContainer("surface_container", R.string.theme_color_surface_container),
  SurfaceContainerHigh("surface_container_high", R.string.theme_color_surface_container_high),
  SurfaceContainerHighest("surface_container_highest", R.string.theme_color_surface_container_highest),
  Outline("outline", R.string.theme_color_outline),
  OutlineVariant("outline_variant", R.string.theme_color_outline_variant),
  SurfaceTint("surface_tint", R.string.theme_color_surface_tint),
  InverseSurface("inverse_surface", R.string.theme_color_inverse_surface),
  InverseOnSurface("inverse_on_surface", R.string.theme_color_inverse_on_surface),
  InversePrimary("inverse_primary", R.string.theme_color_inverse_primary),
  Scrim("scrim", R.string.theme_color_scrim),
  Error("error", R.string.theme_color_error),
  OnError("on_error", R.string.theme_color_on_error),
  ErrorContainer("error_container", R.string.theme_color_error_container),
  OnErrorContainer("on_error_container", R.string.theme_color_on_error_container),
  PrimaryFixed("primary_fixed", R.string.theme_color_primary_fixed),
  PrimaryFixedDim("primary_fixed_dim", R.string.theme_color_primary_fixed_dim),
  OnPrimaryFixed("on_primary_fixed", R.string.theme_color_on_primary_fixed),
  OnPrimaryFixedVariant("on_primary_fixed_variant", R.string.theme_color_on_primary_fixed_variant),
  SecondaryFixed("secondary_fixed", R.string.theme_color_secondary_fixed),
  SecondaryFixedDim("secondary_fixed_dim", R.string.theme_color_secondary_fixed_dim),
  OnSecondaryFixed("on_secondary_fixed", R.string.theme_color_on_secondary_fixed),
  OnSecondaryFixedVariant("on_secondary_fixed_variant", R.string.theme_color_on_secondary_fixed_variant),
  TertiaryFixed("tertiary_fixed", R.string.theme_color_tertiary_fixed),
  TertiaryFixedDim("tertiary_fixed_dim", R.string.theme_color_tertiary_fixed_dim),
  OnTertiaryFixed("on_tertiary_fixed", R.string.theme_color_on_tertiary_fixed),
  OnTertiaryFixedVariant("on_tertiary_fixed_variant", R.string.theme_color_on_tertiary_fixed_variant),
  ;

  fun color(scheme: ColorScheme): Color =
    when (this) {
      Primary -> scheme.primary
      Secondary -> scheme.secondary
      Tertiary -> scheme.tertiary
      OnPrimary -> scheme.onPrimary
      PrimaryContainer -> scheme.primaryContainer
      OnPrimaryContainer -> scheme.onPrimaryContainer
      OnSecondary -> scheme.onSecondary
      SecondaryContainer -> scheme.secondaryContainer
      OnSecondaryContainer -> scheme.onSecondaryContainer
      OnTertiary -> scheme.onTertiary
      TertiaryContainer -> scheme.tertiaryContainer
      OnTertiaryContainer -> scheme.onTertiaryContainer
      Surface -> scheme.surface
      OnSurface -> scheme.onSurface
      OnBackground -> scheme.onBackground
      SurfaceVariant -> scheme.surfaceVariant
      OnSurfaceVariant -> scheme.onSurfaceVariant
      SurfaceDim -> scheme.surfaceDim
      SurfaceBright -> scheme.surfaceBright
      SurfaceContainerLowest -> scheme.surfaceContainerLowest
      SurfaceContainerLow -> scheme.surfaceContainerLow
      SurfaceContainer -> scheme.surfaceContainer
      SurfaceContainerHigh -> scheme.surfaceContainerHigh
      SurfaceContainerHighest -> scheme.surfaceContainerHighest
      Outline -> scheme.outline
      OutlineVariant -> scheme.outlineVariant
      SurfaceTint -> scheme.surfaceTint
      InverseSurface -> scheme.inverseSurface
      InverseOnSurface -> scheme.inverseOnSurface
      InversePrimary -> scheme.inversePrimary
      Scrim -> scheme.scrim
      Error -> scheme.error
      OnError -> scheme.onError
      ErrorContainer -> scheme.errorContainer
      OnErrorContainer -> scheme.onErrorContainer
      PrimaryFixed -> scheme.primaryFixed
      PrimaryFixedDim -> scheme.primaryFixedDim
      OnPrimaryFixed -> scheme.onPrimaryFixed
      OnPrimaryFixedVariant -> scheme.onPrimaryFixedVariant
      SecondaryFixed -> scheme.secondaryFixed
      SecondaryFixedDim -> scheme.secondaryFixedDim
      OnSecondaryFixed -> scheme.onSecondaryFixed
      OnSecondaryFixedVariant -> scheme.onSecondaryFixedVariant
      TertiaryFixed -> scheme.tertiaryFixed
      TertiaryFixedDim -> scheme.tertiaryFixedDim
      OnTertiaryFixed -> scheme.onTertiaryFixed
      OnTertiaryFixedVariant -> scheme.onTertiaryFixedVariant
    }
}

internal fun ColorScheme.withOverrides(overrides: Map<ThemeColorRole, Color>): ColorScheme =
  copy(
    primary = overrides[ThemeColorRole.Primary] ?: primary,
    secondary = overrides[ThemeColorRole.Secondary] ?: secondary,
    tertiary = overrides[ThemeColorRole.Tertiary] ?: tertiary,
    onPrimary =
      overrides[ThemeColorRole.OnPrimary]
        ?: overrides[ThemeColorRole.Primary]?.accessibleContentColor()
        ?: onPrimary,
    primaryContainer = overrides[ThemeColorRole.PrimaryContainer] ?: primaryContainer,
    onPrimaryContainer =
      overrides[ThemeColorRole.OnPrimaryContainer]
        ?: overrides[ThemeColorRole.PrimaryContainer]?.accessibleContentColor()
        ?: onPrimaryContainer,
    onSecondary =
      overrides[ThemeColorRole.OnSecondary] ?: overrides[ThemeColorRole.Secondary]?.accessibleContentColor()
        ?: onSecondary,
    secondaryContainer = overrides[ThemeColorRole.SecondaryContainer] ?: secondaryContainer,
    onSecondaryContainer =
      overrides[ThemeColorRole.OnSecondaryContainer]
        ?: overrides[ThemeColorRole.SecondaryContainer]?.accessibleContentColor()
        ?: onSecondaryContainer,
    onTertiary =
      overrides[ThemeColorRole.OnTertiary]
        ?: overrides[ThemeColorRole.Tertiary]?.accessibleContentColor()
        ?: onTertiary,
    tertiaryContainer = overrides[ThemeColorRole.TertiaryContainer] ?: tertiaryContainer,
    onTertiaryContainer =
      overrides[ThemeColorRole.OnTertiaryContainer]
        ?: overrides[ThemeColorRole.TertiaryContainer]?.accessibleContentColor()
        ?: onTertiaryContainer,
    surface = overrides[ThemeColorRole.Surface] ?: surface,
    onSurface =
      overrides[ThemeColorRole.OnSurface] ?: overrides[ThemeColorRole.Surface]?.accessibleContentColor() ?: onSurface,
    onBackground = overrides[ThemeColorRole.OnBackground] ?: onBackground,
    surfaceVariant = overrides[ThemeColorRole.SurfaceVariant] ?: surfaceVariant,
    onSurfaceVariant =
      overrides[ThemeColorRole.OnSurfaceVariant] ?: overrides[ThemeColorRole.SurfaceVariant]?.accessibleContentColor()
        ?: onSurfaceVariant,
    surfaceDim = overrides[ThemeColorRole.SurfaceDim] ?: surfaceDim,
    surfaceBright = overrides[ThemeColorRole.SurfaceBright] ?: surfaceBright,
    surfaceContainerLowest = overrides[ThemeColorRole.SurfaceContainerLowest] ?: surfaceContainerLowest,
    surfaceContainerLow = overrides[ThemeColorRole.SurfaceContainerLow] ?: surfaceContainerLow,
    surfaceContainer = overrides[ThemeColorRole.SurfaceContainer] ?: surfaceContainer,
    surfaceContainerHigh = overrides[ThemeColorRole.SurfaceContainerHigh] ?: surfaceContainerHigh,
    surfaceContainerHighest = overrides[ThemeColorRole.SurfaceContainerHighest] ?: surfaceContainerHighest,
    outline = overrides[ThemeColorRole.Outline] ?: outline,
    outlineVariant = overrides[ThemeColorRole.OutlineVariant] ?: outlineVariant,
    surfaceTint = overrides[ThemeColorRole.SurfaceTint] ?: surfaceTint,
    inverseSurface = overrides[ThemeColorRole.InverseSurface] ?: inverseSurface,
    inverseOnSurface =
      overrides[ThemeColorRole.InverseOnSurface] ?: overrides[ThemeColorRole.InverseSurface]?.accessibleContentColor()
        ?: inverseOnSurface,
    inversePrimary = overrides[ThemeColorRole.InversePrimary] ?: inversePrimary,
    scrim = overrides[ThemeColorRole.Scrim] ?: scrim,
    error = overrides[ThemeColorRole.Error] ?: error,
    onError = overrides[ThemeColorRole.OnError] ?: overrides[ThemeColorRole.Error]?.accessibleContentColor() ?: onError,
    errorContainer = overrides[ThemeColorRole.ErrorContainer] ?: errorContainer,
    onErrorContainer =
      overrides[ThemeColorRole.OnErrorContainer] ?: overrides[ThemeColorRole.ErrorContainer]?.accessibleContentColor()
        ?: onErrorContainer,
    primaryFixed = overrides[ThemeColorRole.PrimaryFixed] ?: primaryFixed,
    primaryFixedDim = overrides[ThemeColorRole.PrimaryFixedDim] ?: primaryFixedDim,
    onPrimaryFixed =
      overrides[ThemeColorRole.OnPrimaryFixed] ?: overrides[ThemeColorRole.PrimaryFixed]?.accessibleContentColor()
        ?: onPrimaryFixed,
    onPrimaryFixedVariant =
      overrides[ThemeColorRole.OnPrimaryFixedVariant]
        ?: overrides[ThemeColorRole.PrimaryFixedDim]?.accessibleContentColor()
        ?: onPrimaryFixedVariant,
    secondaryFixed = overrides[ThemeColorRole.SecondaryFixed] ?: secondaryFixed,
    secondaryFixedDim = overrides[ThemeColorRole.SecondaryFixedDim] ?: secondaryFixedDim,
    onSecondaryFixed =
      overrides[ThemeColorRole.OnSecondaryFixed] ?: overrides[ThemeColorRole.SecondaryFixed]?.accessibleContentColor()
        ?: onSecondaryFixed,
    onSecondaryFixedVariant =
      overrides[ThemeColorRole.OnSecondaryFixedVariant]
        ?: overrides[ThemeColorRole.SecondaryFixedDim]?.accessibleContentColor()
        ?: onSecondaryFixedVariant,
    tertiaryFixed = overrides[ThemeColorRole.TertiaryFixed] ?: tertiaryFixed,
    tertiaryFixedDim = overrides[ThemeColorRole.TertiaryFixedDim] ?: tertiaryFixedDim,
    onTertiaryFixed =
      overrides[ThemeColorRole.OnTertiaryFixed] ?: overrides[ThemeColorRole.TertiaryFixed]?.accessibleContentColor()
        ?: onTertiaryFixed,
    onTertiaryFixedVariant =
      overrides[ThemeColorRole.OnTertiaryFixedVariant]
        ?: overrides[ThemeColorRole.TertiaryFixedDim]?.accessibleContentColor()
        ?: onTertiaryFixedVariant,
  )

internal fun serializeOverrides(overrides: Map<ThemeColorRole, Color>): String =
  ThemeColorRole.entries
    .mapNotNull { role ->
      overrides[role]?.let { color -> "${role.key}=#%08X".format(color.toArgb()) }
    }.joinToString(";")

internal fun parseOverrides(serialized: String): Map<ThemeColorRole, Color> =
  serialized
    .split(';')
    .mapNotNull { entry ->
      val key = entry.substringBefore('=')
      val role = ThemeColorRole.entries.firstOrNull { it.key == key } ?: return@mapNotNull null
      val color = runCatching { Color(android.graphics.Color.parseColor(entry.substringAfter('='))) }.getOrNull()
      color?.let { role to it }
    }.toMap()
