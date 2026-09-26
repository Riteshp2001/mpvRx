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

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.lyrics.LyricsProvider
import app.gyrolet.mpvrx.presentation.components.PlayerSheet
import app.gyrolet.mpvrx.ui.player.PlayerViewModel

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
    LazyColumn(
      modifier = modifier.fillMaxWidth(),
      contentPadding = PaddingValues(bottom = 8.dp),
    ) {
      item(key = "auto") {
        AudioTrackRow(
          title = stringResource(R.string.lyrics_provider_auto),
          isSelected = state.preferredProvider == null,
          details = autoDetails(state),
          onClick = {
            viewModel.switchLyricsProvider(null)
            onDismissRequest()
          },
        )
      }

      items(LyricsProvider.entries, key = { it.name }) { provider ->
        AudioTrackRow(
          title = provider.label,
          details = providerDetails(provider, state),
          isSelected = state.preferredProvider == provider,
          enabled = state.onlineEnabled,
          onClick = {
            viewModel.switchLyricsProvider(provider)
            onDismissRequest()
          },
        )
      }
    }
  }
}

@Composable
private fun autoDetails(state: PlayerViewModel.LyricsUiState): String =
  when {
    state.isLoading && state.preferredProvider == null ->
      stringResource(R.string.lyrics_provider_status_fetching)
    state.preferredProvider == null && state.onlineProvider != null ->
      stringResource(R.string.lyrics_provider_status_current)
    else -> stringResource(R.string.lyrics_provider_status_unfetched)
  }

@Composable
private fun providerDetails(
  provider: LyricsProvider,
  state: PlayerViewModel.LyricsUiState,
): String {
  val status =
    when {
      provider == state.onlineProvider -> stringResource(R.string.lyrics_provider_status_current)
      state.isLoading && provider == state.preferredProvider ->
        stringResource(R.string.lyrics_provider_status_fetching)
      provider in state.fetchedProviders -> stringResource(R.string.lyrics_provider_status_found)
      provider in state.missingProviders -> stringResource(R.string.lyrics_provider_status_missing)
      else -> stringResource(R.string.lyrics_provider_status_unfetched)
    }
  return if (provider.detail.isBlank()) status else "${provider.detail} • $status"
}
