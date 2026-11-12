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

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.remember
import app.gyrolet.mpvrx.ui.theme.appPressFeedback
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.lyrics.LyricsProvider
import app.gyrolet.mpvrx.domain.lyrics.LyricsTiming
import app.gyrolet.mpvrx.presentation.components.PlayerSheet
import app.gyrolet.mpvrx.ui.icons.AppIcon
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.PlayerViewModel
import app.gyrolet.mpvrx.ui.player.Sheets
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusHighlight
import app.gyrolet.mpvrx.ui.theme.AppConnectedShapeTokens
import app.gyrolet.mpvrx.ui.theme.ConnectedLayout
import app.gyrolet.mpvrx.ui.theme.rememberConnectedShape
import app.gyrolet.mpvrx.ui.utils.rememberAppHaptics

/**
 * Every online source as a row, with one plain line saying what a tap will do.
 *
 * The row is the switch: tapping a provider asks [PlayerViewModel.switchLyricsProvider]
 * for it, which reuses an answer already fetched and only spends a request on a source
 * the track has not seen. The leading badge and the subtitle are the status:
 *
 *  - the provider on screen is ticked and cannot be tapped again;
 *  - one not asked yet says it will be fetched on tap;
 *  - one that answered empty is crossed out and left alone;
 *  - one that errored or timed out stays tappable, because a retry can work.
 */
@Composable
fun LyricsProviderSheet(
  viewModel: PlayerViewModel,
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val state by viewModel.lyricsUiState.collectAsState()

  PlayerSheet(
    onDismissRequest,
    title = stringResource(R.string.lyrics_provider_sheet_title),
    actions = { LyricsEditAction(viewModel, origin = Sheets.LyricsProvider) },
  ) {
    LazyColumn(
      modifier = modifier.fillMaxWidth(),
      contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(AppConnectedShapeTokens.spacing),
    ) {
      item(key = "auto") {
        val status = autoStatus(state)
        ProviderRow(
          title = stringResource(R.string.lyrics_provider_auto),
          subtitle =
            state.onlineProvider?.takeIf { status == ProviderStatus.CURRENT }?.let { provider ->
              val timing = state.providerTimings[provider]
              if (timing != null) {
                stringResource(R.string.lyrics_provider_auto_best_timing, provider.label, stringResource(timing.labelRes()))
              } else {
                stringResource(R.string.lyrics_provider_auto_best, provider.label)
              }
            } ?: statusText(status),
          status = status,
          autoRow = true,
          enabled = state.onlineEnabled,
          onClick = { viewModel.switchLyricsProvider(null) },
          groupIndex = 0,
          groupSize = LyricsProvider.available.size + 1,
        )
      }

      itemsIndexed(LyricsProvider.available, key = { _, provider -> provider.name }) { index, provider ->
        val status = providerStatus(provider, state)
        ProviderRow(
          title = provider.label,
          subtitle = statusText(status, state.providerTimings[provider]),
          status = status,
          enabled = state.onlineEnabled,
          onClick = { viewModel.switchLyricsProvider(provider) },
          groupIndex = index + 1,
          groupSize = LyricsProvider.available.size + 1,
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

private fun LyricsTiming.labelRes(): Int =
  when (this) {
    LyricsTiming.WORD -> R.string.lyrics_timing_word
    LyricsTiming.PARTIAL_WORD -> R.string.lyrics_timing_partial_word
    LyricsTiming.LINE -> R.string.lyrics_timing_line
    LyricsTiming.PLAIN -> R.string.lyrics_timing_plain
  }

/** A fetched provider also says how well its lyrics are timed, which is what Auto ranks by. */
@Composable
private fun statusText(
  status: ProviderStatus,
  timing: LyricsTiming?,
): String {
  val timingLabel = timing?.let { stringResource(it.labelRes()) }
  return when {
    timingLabel != null && status == ProviderStatus.CURRENT ->
      stringResource(R.string.lyrics_provider_row_current_timing, timingLabel)
    timingLabel != null && status == ProviderStatus.FOUND ->
      stringResource(R.string.lyrics_provider_row_found_timing, timingLabel)
    else -> statusText(status)
  }
}

@Composable
private fun statusText(status: ProviderStatus): String =
  stringResource(
    when (status) {
      ProviderStatus.CURRENT -> R.string.lyrics_provider_row_current
      ProviderStatus.FETCHING -> R.string.lyrics_provider_row_fetching
      ProviderStatus.FOUND -> R.string.lyrics_provider_row_found
      ProviderStatus.MISSING -> R.string.lyrics_provider_row_missing
      ProviderStatus.FAILED -> R.string.lyrics_provider_row_failed
      ProviderStatus.UNFETCHED -> R.string.lyrics_provider_row_unfetched
    },
  )

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ProviderRow(
  title: String,
  subtitle: String,
  status: ProviderStatus,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  autoRow: Boolean = false,
  enabled: Boolean = true,
  groupIndex: Int = 0,
  groupSize: Int = 1,
) {
  val haptics = rememberAppHaptics()
  val colors = MaterialTheme.colorScheme
  val current = status == ProviderStatus.CURRENT
  // A finished empty answer cannot be improved by asking again; a failure can.
  val tappable = enabled && !current && status != ProviderStatus.MISSING
  val interactionSource = remember { MutableInteractionSource() }
  val pressed by interactionSource.collectIsPressedAsState()
  val container = animatedPlayerSheetSelectionColor(
    current, label = "lyricsProviderRow", pressed = pressed, enabled = tappable,
  )
  val shape = rememberConnectedShape(
    groupIndex, groupSize, ConnectedLayout.Vertical, pressed = pressed && tappable, selected = current,
  )
  val dim = status == ProviderStatus.MISSING

  Surface(
    modifier =
      modifier
        .fillMaxWidth()
        .tvFocusHighlight(shape, enabled = tappable)
        .appPressFeedback(interactionSource, enabled = tappable, pressedScale = 0.985f)
        .clip(shape)
        .selectable(
          selected = current, enabled = tappable, role = Role.RadioButton,
          interactionSource = interactionSource, indication = androidx.compose.material3.ripple(),
          onClick = { onClick(); haptics.selection(true) },
        ),
    shape = shape,
    color = container,
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      StatusBadge(status = status, autoRow = autoRow)
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = title,
          style = MaterialTheme.typography.bodyLarge,
          fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
          color =
            when {
              current -> colors.onSecondaryContainer
              dim -> colors.onSurfaceVariant
              else -> colors.onSurface
            },
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = subtitle,
          style = MaterialTheme.typography.bodySmall,
          color =
            when {
              status == ProviderStatus.FAILED -> colors.error
              current -> colors.onSecondaryContainer.copy(alpha = 0.8f)
              else -> colors.onSurfaceVariant
            },
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

@Composable
private fun StatusBadge(
  status: ProviderStatus,
  autoRow: Boolean,
) {
  val colors = MaterialTheme.colorScheme
  val current = status == ProviderStatus.CURRENT
  val contentColor = if (current) colors.onSecondaryContainer else colors.onSurfaceVariant
  val icon: AppIcon =
    when (status) {
      ProviderStatus.CURRENT, ProviderStatus.FOUND -> Icons.RoundedFilled.Check
      ProviderStatus.MISSING -> Icons.RoundedFilled.Close
      ProviderStatus.FAILED -> Icons.RoundedFilled.Refresh
      else -> if (autoRow) Icons.RoundedFilled.AutoAwesome else Icons.RoundedFilled.Search
    }
  Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
    Surface(
      shape = CircleShape,
      color = if (current) colors.secondary.copy(alpha = 0.18f) else colors.surfaceContainerHighest,
      modifier = Modifier.size(40.dp),
    ) {}
    if (status == ProviderStatus.FETCHING) {
      CircularProgressIndicator(
        modifier = Modifier.size(20.dp),
        strokeWidth = 2.dp,
        color = contentColor,
      )
    } else {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = if (status == ProviderStatus.FAILED) colors.error else contentColor.copy(alpha = if (status == ProviderStatus.MISSING) 0.5f else 1f),
        modifier = Modifier.size(20.dp),
      )
    }
  }
}
