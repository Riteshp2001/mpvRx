/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.controls

import androidx.compose.foundation.BorderStroke
import app.gyrolet.mpvrx.ui.liquidglass.liquidGlassActive
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.PlayerButton
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.liquidglass.LiquidPillButton
import app.gyrolet.mpvrx.ui.player.Panels
import app.gyrolet.mpvrx.ui.player.PlayerActivity
import app.gyrolet.mpvrx.ui.player.PlayerViewModel
import app.gyrolet.mpvrx.ui.player.Sheets
import app.gyrolet.mpvrx.ui.player.VideoAspect
import app.gyrolet.mpvrx.ui.player.controls.components.ControlsButton
import app.gyrolet.mpvrx.ui.player.controls.components.playerButtonBorderColor
import app.gyrolet.mpvrx.ui.player.controls.components.playerButtonContainerColor
import app.gyrolet.mpvrx.ui.player.controls.components.playerButtonContentColor
import app.gyrolet.mpvrx.ui.theme.spacing
import dev.vivvvek.seeker.Segment
import org.koin.compose.koinInject

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
    val clickEvent = LocalPlayerButtonsClickEvent.current
    val enableLiquidGlass = liquidGlassActive()

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

        Column {
          if (enableLiquidGlass) {
            LiquidPillButton(
              onClick = {
                if (playlistModeEnabled) {
                  clickEvent()
                  onOpenSheet(Sheets.Playlist)
                }
              },
              isInteractive = playlistModeEnabled,
              height = 45.dp,
              horizontalPadding = MaterialTheme.spacing.medium,
            ) {
              Text(
                mediaTitle ?: "",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f, fill = false),
              )
              viewModel.getPlaylistInfo()?.let { playlistInfo ->
                Text(
                  " • $playlistInfo",
                  maxLines = 1,
                  overflow = TextOverflow.Visible,
                  style = MaterialTheme.typography.bodySmall,
                )
              }
            }
          } else {
            val titleInteractionSource = remember { MutableInteractionSource() }

            Box(
              modifier =
                Modifier
                  .height(45.dp)
                  .clip(CircleShape)
                  .clickable(
                    interactionSource = titleInteractionSource,
                    indication = ripple(bounded = true),
                    enabled = playlistModeEnabled,
                    onClick = {
                      clickEvent()
                      onOpenSheet(Sheets.Playlist)
                    },
                  ),
            ) {
              Surface(
                shape = CircleShape,
                color = playerButtonContainerColor(),
                contentColor = playerButtonContentColor(),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
                border = BorderStroke(1.dp, playerButtonBorderColor()),
              ) {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  modifier =
                    Modifier
                      .padding(
                        start = MaterialTheme.spacing.medium,
                        end = MaterialTheme.spacing.medium,
                        top = MaterialTheme.spacing.small,
                        bottom = MaterialTheme.spacing.small,
                      ),
                ) {
                  Text(
                    mediaTitle ?: "",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f, fill = false),
                  )
                  viewModel.getPlaylistInfo()?.let { playlistInfo ->
                    Text(
                      " • $playlistInfo",
                      maxLines = 1,
                      overflow = TextOverflow.Visible,
                      style = MaterialTheme.typography.bodySmall,
                    )
                  }
                }
              }
            }
          }
        }
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

@Composable
fun TopRightPlayerControlsLandscape(
  buttons: List<PlayerButton>,
  chapters: List<Segment>,
  currentChapter: Int?,
  isSpeedNonOne: Boolean,
  currentZoom: Float,
  aspect: VideoAspect,
  mediaTitle: String?,
  decoder: app.gyrolet.mpvrx.ui.player.Decoder,
  playbackSpeed: Float,
  onBackPress: () -> Unit,
  onOpenSheet: (Sheets) -> Unit,
  onOpenPanel: (Panels) -> Unit,
  viewModel: PlayerViewModel,
  activity: PlayerActivity,
) {
  PlayerButtonTheme {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
    ) {
      buttons.forEach { button ->
        RenderPlayerButton(
          button = button,
          chapters = chapters,
          currentChapter = currentChapter,
          isPortrait = false,
          isSpeedNonOne = isSpeedNonOne,
          currentZoom = currentZoom,
          aspect = aspect,
          mediaTitle = mediaTitle,
          decoder = decoder,
          playbackSpeed = playbackSpeed,
          onBackPress = onBackPress,
          onOpenSheet = onOpenSheet,
          onOpenPanel = onOpenPanel,
          viewModel = viewModel,
          activity = activity,
          buttonSize = 48.dp,
        )
      }
    }
  }
}

@Composable
fun BottomRightPlayerControlsLandscape(
  buttons: List<PlayerButton>,
  showVideoQualitySelector: Boolean,
  chapters: List<Segment>,
  currentChapter: Int?,
  isSpeedNonOne: Boolean,
  currentZoom: Float,
  aspect: VideoAspect,
  mediaTitle: String?,
  decoder: app.gyrolet.mpvrx.ui.player.Decoder,
  playbackSpeed: Float,
  onBackPress: () -> Unit,
  onOpenSheet: (Sheets) -> Unit,
  onOpenPanel: (Panels) -> Unit,
  viewModel: PlayerViewModel,
  activity: PlayerActivity,
  modifier: Modifier = Modifier,
  horizontalArrangement: Arrangement.Horizontal =
    Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
) {
  PlayerButtonTheme {
    Row(
      modifier = modifier,
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = horizontalArrangement,
    ) {
      buttons.forEach { button ->
        RenderPlayerButton(
          button = button,
          chapters = chapters,
          currentChapter = currentChapter,
          isPortrait = false,
          isSpeedNonOne = isSpeedNonOne,
          currentZoom = currentZoom,
          aspect = aspect,
          mediaTitle = mediaTitle,
          decoder = decoder,
          playbackSpeed = playbackSpeed,
          onBackPress = onBackPress,
          onOpenSheet = onOpenSheet,
          onOpenPanel = onOpenPanel,
          viewModel = viewModel,
          activity = activity,
          buttonSize = 48.dp,
        )
      }
      if (showVideoQualitySelector) {
        ControlsButton(
          icon = Icons.RoundedFilled.Hd,
          onClick = { onOpenSheet(Sheets.VideoQuality) },
          title = stringResource(R.string.player_video_quality_button),
          modifier = Modifier.size(48.dp),
        )
      }
    }
  }
}

@Composable
fun BottomLeftPlayerControlsLandscape(
  buttons: List<PlayerButton>,
  chapters: List<Segment>,
  currentChapter: Int?,
  isSpeedNonOne: Boolean,
  currentZoom: Float,
  aspect: VideoAspect,
  mediaTitle: String?,
  decoder: app.gyrolet.mpvrx.ui.player.Decoder,
  playbackSpeed: Float,
  onBackPress: () -> Unit,
  onOpenSheet: (Sheets) -> Unit,
  onOpenPanel: (Panels) -> Unit,
  viewModel: PlayerViewModel,
  activity: PlayerActivity,
  modifier: Modifier = Modifier,
) {
  PlayerButtonTheme {
    Row(
      modifier = modifier,
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
    ) {
      buttons.forEach { button ->
        RenderPlayerButton(
          button = button,
          chapters = chapters,
          currentChapter = currentChapter,
          isPortrait = false,
          isSpeedNonOne = isSpeedNonOne,
          currentZoom = currentZoom,
          aspect = aspect,
          mediaTitle = mediaTitle,
          decoder = decoder,
          playbackSpeed = playbackSpeed,
          onBackPress = onBackPress,
          onOpenSheet = onOpenSheet,
          onOpenPanel = onOpenPanel,
          viewModel = viewModel,
          activity = activity,
          buttonSize = 48.dp,
        )
      }
    }
  }
}
