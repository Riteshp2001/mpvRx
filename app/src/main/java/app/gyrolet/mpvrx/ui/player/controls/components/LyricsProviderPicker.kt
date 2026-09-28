/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.player.controls.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.lyrics.LyricsProvider
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons

/**
 * Which online source the lyrics are coming from, and the row that opens the
 * sheet asking for another one.
 *
 * The label doubles as the indicator: an automatic lookup shows the provider
 * that actually answered in brackets, so "Auto (LRCLIB)" says both what the
 * picker is set to and who is talking. A chosen provider shows itself alone,
 * because then the two agree.
 */
@Composable
fun LyricsProviderPicker(
  preferredProvider: LyricsProvider?,
  onlineProvider: LyricsProvider?,
  onOpenSheet: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
) {
  val label =
    when {
      preferredProvider != null -> preferredProvider.label
      onlineProvider != null -> stringResource(R.string.lyrics_provider_auto_with, onlineProvider.label)
      else -> stringResource(R.string.lyrics_provider_auto)
    }

  FilterChip(
    modifier = modifier,
    selected = preferredProvider != null || onlineProvider != null,
    onClick = onOpenSheet,
    enabled = enabled,
    label = { Text(label, fontWeight = FontWeight.Bold) },
    trailingIcon = {
      Icon(
        imageVector = Icons.RoundedFilled.ArrowDropDown,
        contentDescription = null,
      )
    },
    colors =
      FilterChipDefaults.filterChipColors(
        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
      ),
  )
}

/**
 * The single line above the scrubber naming who supplied the lyrics on screen,
 * which opens the source sheet when tapped.
 *
 * This sits where the current-line strip sits when the lyrics are closed, so the
 * row under the track metadata always says something rather than leaving a gap.
 */
@Composable
fun LyricsSourceLine(
  preferredProvider: LyricsProvider?,
  onlineProvider: LyricsProvider?,
  isLoading: Boolean,
  onOpenSheet: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val provider = preferredProvider ?: onlineProvider
  val label =
    when {
      isLoading -> stringResource(R.string.lyrics_loading_label)
      provider != null -> stringResource(R.string.lyrics_by, provider.label)
      else -> stringResource(R.string.lyrics_provider_auto)
    }

  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(8.dp))
        .clickable(onClick = onOpenSheet)
        .padding(horizontal = 4.dp, vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.titleMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f, fill = false),
    )
    Icon(
      imageVector = Icons.RoundedFilled.ArrowDropDown,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.size(18.dp),
    )
  }
}
