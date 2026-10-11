/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Derived from BitChord's lyrics provider sheet, GPL-3.0-or-later.
 */

package app.gyrolet.mpvrx.ui.player.controls.components.sheets

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.lyrics.LyricsProvider
import app.gyrolet.mpvrx.presentation.components.PlayerSheet
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.PlayerViewModel
import app.gyrolet.mpvrx.ui.theme.AppConnectedShapeTokens

/**
 * Every online source as a sheet row, with what it has done for this track so far.
 *
 * The row is the switch: tapping a provider asks [PlayerViewModel.switchLyricsProvider]
 * for it, which reuses an answer already fetched and only spends a request on a source
 * the track has not seen. The status tells the user which row is worth the tap.
 */
@Composable
fun LyricsProviderSheet(
  viewModel: PlayerViewModel,
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val state by viewModel.lyricsUiState.collectAsState()

  PlayerSheet(onDismissRequest, title = stringResource(R.string.lyrics_provider_sheet_title)) {
    val autoLabel = stringResource(R.string.lyrics_provider_auto)

    LazyColumn(
      modifier = modifier.fillMaxWidth(),
      contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(AppConnectedShapeTokens.spacing),
    ) {
      item(key = "auto") {
        val status = autoStatus(state)
        AudioTrackRow(
          title = autoLabel,
          isSelected = state.preferredProvider == null,
          details = state.onlineProvider?.takeIf { status == ProviderStatus.CURRENT }?.label,
          onClick = {
            viewModel.switchLyricsProvider(null)
          },
          trailing = { ProviderStatusChip(status) },
          groupIndex = 0,
          groupSize = LyricsProvider.entries.size + 1,
        )
      }

      itemsIndexed(LyricsProvider.entries, key = { _, provider -> provider.name }) { index, provider ->
        AudioTrackRow(
          title = provider.label,
          details = provider.detail.takeIf { it.isNotBlank() },
          isSelected = state.preferredProvider == provider,
          enabled = state.onlineEnabled,
          onClick = {
            viewModel.switchLyricsProvider(provider)
          },
          trailing = { ProviderStatusChip(providerStatus(provider, state)) },
          groupIndex = index + 1,
          groupSize = LyricsProvider.entries.size + 1,
        )
      }
    }
  }
}

private enum class ProviderStatus {
  CURRENT,
  FETCHING,
  FOUND,
  MISSING,
  FAILED,
  UNFETCHED,
}

private fun autoStatus(state: PlayerViewModel.LyricsUiState): ProviderStatus =
  when {
    state.isLoading && state.preferredProvider == null -> ProviderStatus.FETCHING
    state.preferredProvider == null && state.onlineProvider != null -> ProviderStatus.CURRENT
    state.preferredProvider == null && state.onlineLyrics == null &&
      (state.missingProviders.isNotEmpty() || state.failedProviders.isNotEmpty()) ->
      if (state.missingProviders.isEmpty()) ProviderStatus.FAILED else ProviderStatus.MISSING
    else -> ProviderStatus.UNFETCHED
  }

private fun providerStatus(
  provider: LyricsProvider,
  state: PlayerViewModel.LyricsUiState,
): ProviderStatus {
  val resolved =
    provider in state.fetchedProviders || provider in state.missingProviders || provider in state.failedProviders
  // The automatic lookup asks every eager provider at once; a chosen one is asked alone first.
  val beingAsked =
    state.isLoading &&
      (
        provider == state.preferredProvider ||
          (state.preferredProvider == null && !resolved && provider !in LyricsProvider.LAZY_PROVIDERS)
      )
  return when {
    provider == state.onlineProvider && state.onlineLyrics != null && !beingAsked -> ProviderStatus.CURRENT
    beingAsked -> ProviderStatus.FETCHING
    provider in state.fetchedProviders -> ProviderStatus.FOUND
    provider in state.failedProviders -> ProviderStatus.FAILED
    provider in state.missingProviders -> ProviderStatus.MISSING
    else -> ProviderStatus.UNFETCHED
  }
}

@Composable
private fun ProviderStatusChip(status: ProviderStatus) {
  val colors = MaterialTheme.colorScheme
  val (container, content) =
    when (status) {
      ProviderStatus.CURRENT -> colors.primary to colors.onPrimary
      ProviderStatus.FETCHING -> colors.secondaryContainer to colors.onSecondaryContainer
      ProviderStatus.FOUND -> colors.tertiaryContainer to colors.onTertiaryContainer
      ProviderStatus.MISSING -> colors.surfaceContainerHighest to colors.onSurfaceVariant
      ProviderStatus.FAILED -> colors.errorContainer to colors.onErrorContainer
      ProviderStatus.UNFETCHED -> Color.Transparent to colors.outline
    }
  val label =
    stringResource(
      when (status) {
        ProviderStatus.CURRENT -> R.string.lyrics_provider_status_current
        ProviderStatus.FETCHING -> R.string.lyrics_provider_status_fetching
        ProviderStatus.FOUND -> R.string.lyrics_provider_status_found
        ProviderStatus.MISSING -> R.string.lyrics_provider_status_missing
        ProviderStatus.FAILED -> R.string.lyrics_provider_status_failed
        ProviderStatus.UNFETCHED -> R.string.lyrics_provider_status_unfetched
      },
    )
  Surface(
    shape = RoundedCornerShape(50),
    color = container,
    contentColor = content,
    border = if (status == ProviderStatus.UNFETCHED) BorderStroke(1.dp, colors.outlineVariant) else null,
  ) {
    Row(
      modifier = Modifier.padding(start = 8.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
      horizontalArrangement = Arrangement.spacedBy(4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (status == ProviderStatus.FETCHING) {
        CircularProgressIndicator(
          modifier = Modifier.size(12.dp),
          strokeWidth = 1.5.dp,
          color = content,
        )
      } else {
        Icon(
          imageVector =
            when (status) {
              ProviderStatus.CURRENT -> Icons.RoundedFilled.CheckCircle
              ProviderStatus.FOUND -> Icons.RoundedFilled.CloudDone
              ProviderStatus.MISSING -> Icons.RoundedFilled.Block
              ProviderStatus.FAILED -> Icons.RoundedFilled.CloudOff
              else -> Icons.RoundedFilled.Remove
            },
          contentDescription = null,
          modifier = Modifier.size(14.dp),
        )
      }
      Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
      )
    }
  }
}
