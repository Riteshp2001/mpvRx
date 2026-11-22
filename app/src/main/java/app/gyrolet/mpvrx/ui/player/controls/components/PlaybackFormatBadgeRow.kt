/* SPDX-License-Identifier: AGPL-3.0-or-later */

package app.gyrolet.mpvrx.ui.player.controls.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.components.rememberAssetLogoPainter
import app.gyrolet.mpvrx.ui.liquidglass.liquidGlassActive
import app.gyrolet.mpvrx.ui.player.BadgeArtwork
import app.gyrolet.mpvrx.ui.player.PlaybackFormatBadge
import app.gyrolet.mpvrx.ui.player.controls.PlayerButtonAlpha
import app.gyrolet.mpvrx.ui.player.observePlaybackFormatBadges
import org.koin.compose.koinInject

private val BadgeRailShape = RoundedCornerShape(12.dp)
private val BrandBackingShape = RoundedCornerShape(5.dp)

@Composable
internal fun PlaybackFormatBadgeRow(
  modifier: Modifier = Modifier,
  logoHeight: Dp = 20.dp,
  tvButtonBackground: Color? = null,
) {
  val preferences = koinInject<AppearancePreferences>()
  val showBadges by preferences.showMediaFormatBadges.collectAsState()
  if (!showBadges) return
  val colorizeBadges by preferences.colorizeMediaFormatBadges.collectAsState()

  val formatFlow = remember { observePlaybackFormatBadges() }
  val badges by formatFlow.collectAsStateWithLifecycle(initialValue = emptyList())
  if (badges.isEmpty()) return

  val backgroundLevel = LocalPlayerButtonsOpacity.current
  val foreground = if (tvButtonBackground == null) playerButtonContentColor() else Color.White
  val surfaceColor = if (tvButtonBackground == null) playerButtonColorScheme().surfaceContainerHigh else Color(0xFF232323)
  val inkEdge = if (liquidGlassActive() || backgroundLevel < 0.45f) {
    (if (foreground.luminance() < 0.45f) Color.White else Color.Black).copy(alpha = 0.7f)
  } else Color.Unspecified
  PlayerControlSurface(BadgeRailShape, modifier, baseColor = tvButtonBackground) {
    MediaFormatBadges(
      badges = badges,
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
      logoHeight = logoHeight,
      foreground = foreground,
      background = surfaceColor,
      inkEdge = inkEdge,
      colorize = colorizeBadges,
    )
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MediaFormatBadges(
  badges: List<PlaybackFormatBadge>,
  modifier: Modifier = Modifier,
  logoHeight: Dp = 20.dp,
  foreground: Color = MaterialTheme.colorScheme.onSurface,
  background: Color = MaterialTheme.colorScheme.surface,
  inkEdge: Color = Color.Unspecified,
  colorize: Boolean = true,
) {
  if (badges.isEmpty()) return
  FlowRow(
    modifier = modifier,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    badges.forEach { badge ->
      FormatBadge(
        badge = badge,
        logoHeight = logoHeight,
        foreground = foreground,
        inkEdge = inkEdge,
        darkBackground = background.luminance() < 0.45f,
        colorize = colorize,
        modifier = Modifier.align(Alignment.CenterVertically),
      )
    }
  }
}

@Composable
private fun FormatBadge(
  badge: PlaybackFormatBadge,
  logoHeight: Dp,
  foreground: Color,
  inkEdge: Color,
  darkBackground: Boolean,
  colorize: Boolean,
  modifier: Modifier = Modifier,
) {
  val description = if (badge.isChannelLayout) {
    "${stringResource(R.string.media_info_stat_channels)}: ${badge.detail}"
  } else {
    badge.detail
  }
  val tintArgb = if (colorize) {
    if (darkBackground) badge.darkTintArgb else badge.lightTintArgb
  } else null
  val badgeForeground = tintArgb?.let(::Color) ?: foreground
  val path = badge.asset
  val painter = if (path == null) null else rememberAssetLogoPainter("media_format_logos/$path")
  Box(modifier.heightIn(min = logoHeight + 4.dp), contentAlignment = Alignment.Center) {
    if (painter == null) {
      val shadow = if (inkEdge.isSpecified) {
        Shadow(color = inkEdge, blurRadius = with(LocalDensity.current) { 2.dp.toPx() })
      } else null
      Text(
        badge.label,
        color = badgeForeground,
        style = MaterialTheme.typography.labelMedium.copy(shadow = shadow),
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier = Modifier.clearAndSetSemantics { contentDescription = description },
      )
    } else {
      val aspect = painter.intrinsicSize.width / painter.intrinsicSize.height
      val artwork = badge.artwork
      val contrast = when (artwork) {
        BadgeArtwork.OnDark -> Color(0xFF25262B).takeUnless { darkBackground }
        BadgeArtwork.OnLight -> Color(0xFFF6F4F8).takeIf { darkBackground }
        BadgeArtwork.ThemeInk, BadgeArtwork.Original -> null
      }
      val needsBacking = artwork == BadgeArtwork.OnDark || artwork == BadgeArtwork.OnLight
      val backing =
        if (contrast == null) Modifier else Modifier.background(contrast, BrandBackingShape)
      val useThemeInk = artwork == BadgeArtwork.ThemeInk
      val edge = if (useThemeInk && inkEdge.isSpecified) Modifier.logoInkEdge(painter, inkEdge) else Modifier
      Image(
        painter = painter,
        contentDescription = description,
        colorFilter = if (useThemeInk) ColorFilter.tint(badgeForeground) else null,
        contentScale = ContentScale.Fit,
        modifier = backing
          .then(if (needsBacking) Modifier.padding(horizontal = 3.dp, vertical = 2.dp) else Modifier)
          .size(width = logoHeight * aspect, height = logoHeight)
          .then(edge),
      )
    }
  }
}

// Keep monochrome artwork readable over video without adding an opaque plate at low opacity.
private fun Modifier.logoInkEdge(painter: Painter, color: Color): Modifier = drawWithCache {
  val intrinsic = painter.intrinsicSize
  val scale = minOf(size.width / intrinsic.width, size.height / intrinsic.height)
  val fitted = intrinsic * scale
  val left = (size.width - fitted.width) / 2f
  val top = (size.height - fitted.height) / 2f
  val edge = 0.65.dp.toPx()
  val tint = ColorFilter.tint(color)
  val offsets = listOf(-edge to 0f, edge to 0f, 0f to -edge, 0f to edge)
  onDrawWithContent {
    for ((x, y) in offsets) {
      translate(left + x, top + y) { with(painter) { draw(fitted, colorFilter = tint) } }
    }
    drawContent()
  }
}
