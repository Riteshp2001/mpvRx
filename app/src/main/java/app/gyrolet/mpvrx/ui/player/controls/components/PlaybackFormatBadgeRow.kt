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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.components.rememberAssetLogoPainter
import app.gyrolet.mpvrx.ui.player.BadgeArtwork
import app.gyrolet.mpvrx.ui.player.PlaybackFormatBadge
import app.gyrolet.mpvrx.ui.player.observePlaybackFormatBadges
import org.koin.compose.koinInject

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlaybackFormatBadgeRow(modifier: Modifier = Modifier, logoHeight: Dp = 18.dp) {
  val showBadges by koinInject<AppearancePreferences>().showMediaFormatBadges.collectAsState()
  if (!showBadges) return

  val formatFlow = remember { observePlaybackFormatBadges() }
  val badges by formatFlow.collectAsStateWithLifecycle(initialValue = emptyList())
  if (badges.isEmpty()) return
  val colors = MaterialTheme.colorScheme
  val background = colors.surfaceContainerHigh
  Surface(
    modifier = modifier,
    color = background,
    contentColor = colors.onSurface,
    shape = MaterialTheme.shapes.small,
  ) {
    FlowRow(
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
      maxLines = 2,
    ) {
      badges.forEach { badge ->
        FormatBadge(
          badge,
          logoHeight,
          colors.onSurface,
          darkBackground = background.luminance() < 0.4f,
          modifier = Modifier.align(Alignment.CenterVertically),
        )
      }
    }
  }
}

@Composable
private fun FormatBadge(
  badge: PlaybackFormatBadge,
  logoHeight: Dp,
  foreground: Color,
  darkBackground: Boolean,
  modifier: Modifier = Modifier,
) {
  val description = if (badge.isChannelLayout) {
    "${stringResource(R.string.media_info_stat_channels)}: ${badge.detail}"
  } else {
    badge.detail
  }
  val path = badge.asset
  val painter = if (path == null) null else rememberAssetLogoPainter("media_format_logos/$path")
  Box(modifier.heightIn(min = logoHeight), contentAlignment = Alignment.Center) {
    if (painter == null) {
      Text(
        badge.label,
        color = foreground,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier = Modifier.clearAndSetSemantics { contentDescription = description },
      )
    } else {
      val aspect = painter.intrinsicSize.width / painter.intrinsicSize.height
      val contrast = when (badge.artwork) {
        BadgeArtwork.OnDark -> Color(0xFF25262B).takeUnless { darkBackground }
        BadgeArtwork.OnLight -> Color(0xFFF6F4F8).takeIf { darkBackground }
        BadgeArtwork.ThemeInk, BadgeArtwork.Original -> null
      }
      val backing = contrast?.let {
        Modifier.background(it, RoundedCornerShape(3.dp)).padding(horizontal = 3.dp, vertical = 2.dp)
      } ?: Modifier
      Image(
        painter = painter,
        contentDescription = description,
        colorFilter = if (badge.artwork == BadgeArtwork.ThemeInk) ColorFilter.tint(foreground) else null,
        contentScale = ContentScale.Fit,
        modifier = backing.size(width = minOf(logoHeight * aspect, logoHeight * 4.5f), height = logoHeight),
      )
    }
  }
}
