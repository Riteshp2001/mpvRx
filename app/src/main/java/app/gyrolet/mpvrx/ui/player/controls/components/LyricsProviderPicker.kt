/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.player.controls.components

import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
