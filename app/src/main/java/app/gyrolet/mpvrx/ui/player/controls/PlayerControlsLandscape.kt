/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.controls

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.PlayerViewModel
import app.gyrolet.mpvrx.ui.player.Sheets
import app.gyrolet.mpvrx.ui.player.controls.components.ControlsButton
import app.gyrolet.mpvrx.ui.player.controls.components.PlayerTitlePill
import app.gyrolet.mpvrx.ui.player.controls.components.playerButtonContentColor
import app.gyrolet.mpvrx.ui.theme.spacing

@Composable
fun TopLeftPlayerControlsLandscape(
  mediaTitle: String?,
  onBackPress: () -> Unit,
  onOpenSheet: (Sheets) -> Unit,
  viewModel: PlayerViewModel,
  isTranslatingSub: Boolean = false,
  isRealtimeSubsActive: Boolean = false,
  realtimeSubsLanguage: String = "",
  realtimeSubsStatus: String = "",
  translationStatus: String = "",
  translatingTrackName: String = "",
) {
  PlayerButtonTheme {
    val playlistModeEnabled = viewModel.hasPlaylistSupport()

    Column(
      modifier = Modifier.width(IntrinsicSize.Max),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
      ) {
        ControlsButton(
          icon = Icons.RoundedFilled.ArrowBack,
          onClick = onBackPress,
          color = playerButtonContentColor(),
          modifier = Modifier.size(48.dp),
        )

        PlayerTitlePill(
          title = mediaTitle,
          playlistInfo = viewModel.getPlaylistInfo(),
          onOpenInfo = { onOpenSheet(Sheets.MediaInfo) },
          onOpenPlaylist = if (playlistModeEnabled) ({ onOpenSheet(Sheets.Playlist) }) else null,
          modifier = Modifier.layout { measurable, constraints ->
            if (constraints.maxWidth < 48.dp.roundToPx()) {
              layout(0, 0) {}
            } else {
              val placeable = measurable.measure(constraints)
              layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
            }
          },
        )
      }

      val syncplayManager = org.koin.compose.koinInject<app.gyrolet.mpvrx.domain.syncplay.SyncplayManager>()
      val syncplayState by syncplayManager.state.collectAsState()

      androidx.compose.animation.AnimatedVisibility(
        visible = syncplayState.isConnected,
        enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically { -it },
        exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.slideOutVertically { -it },
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.padding(start = MaterialTheme.spacing.medium, top = 8.dp),
          horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          Icon(
            imageVector = Icons.RoundedFilled.CloudDownload,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.tertiary,
          )
          Text(
            text =
              stringResource(
                R.string.syncplay_player_status,
                syncplayState.room.orEmpty(),
                syncplayState.users.size,
              ),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.tertiary,
          )
        }
      }

      androidx.compose.animation.AnimatedVisibility(
        visible = isTranslatingSub || isRealtimeSubsActive,
        enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically { -it },
        exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.slideOutVertically { -it },
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.padding(start = MaterialTheme.spacing.medium, top = 12.dp),
          horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          Icon(
            imageVector = Icons.RoundedFilled.Translate,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.tertiary,
          )
          Text(
            text =
              if (isRealtimeSubsActive) {
                "${stringResource(
                  R.string.realtime_subtitles_label,
                )}: ${realtimeSubsLanguage.ifBlank { "?" }} ${realtimeSubsStatus.ifBlank { "" }}"
              } else {
                "Translating ${translatingTrackName.ifBlank { "subs" }} ${translationStatus.ifBlank { "" }}"
              },
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.tertiary,
          )
        }
      }
    }
  }
}
