/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.controls.components.sheets

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.presentation.components.PlayerSheet
import app.gyrolet.mpvrx.presentation.components.PlayerSheetAction
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.TrackNode
import app.gyrolet.mpvrx.ui.theme.AppConnectedShapeTokens
import app.gyrolet.mpvrx.ui.theme.AppMotion
import app.gyrolet.mpvrx.ui.theme.ConnectedLayout
import app.gyrolet.mpvrx.ui.theme.rememberConnectedShape
import app.gyrolet.mpvrx.ui.utils.rememberAppHaptics

@Composable
fun VideoQualitySheet(
  tracks: List<TrackNode>,
  onSelect: (TrackNode) -> Unit,
  onDownload: ((TrackNode) -> Unit)? = null,
  onDismissRequest: () -> Unit,
) {
  PlayerSheet(onDismissRequest, title = stringResource(R.string.player_video_quality)) {
    val haptics = rememberAppHaptics()
    Column(modifier = Modifier.fillMaxWidth()) {
      LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(AppConnectedShapeTokens.spacing),
      ) {
        itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
          val containerColor by animateColorAsState(
            targetValue =
              if (track.isSelected) {
                MaterialTheme.colorScheme.secondaryContainer
              } else {
                MaterialTheme.colorScheme.surfaceContainerLow
              },
            animationSpec = AppMotion.spatial(AppMotion.Effect.Color, snap()),
            label = "videoQualitySelection",
          )
          val shape =
            rememberConnectedShape(
              index = index,
              itemCount = tracks.size,
              layout = ConnectedLayout.Vertical,
            )
          Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = shape,
            color = containerColor,
            tonalElevation = if (track.isSelected) 1.dp else 0.dp,
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier =
                Modifier
                  .fillMaxWidth()
                  .heightIn(min = 64.dp)
                  .selectable(selected = track.isSelected, role = Role.RadioButton) {
                    onSelect(track)
                    if (!track.isSelected) haptics.selection(true)
                    onDismissRequest()
                  }.padding(start = 16.dp, end = if (onDownload == null) 16.dp else 4.dp, top = 10.dp, bottom = 10.dp),
            ) {
              RadioButton(
                selected = track.isSelected,
                onClick = null,
              )
              Spacer(Modifier.width(12.dp))
              Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                  text = qualityLabel(track),
                  style = MaterialTheme.typography.bodyLarge,
                  fontWeight = if (track.isSelected) FontWeight.SemiBold else FontWeight.Normal,
                )
                qualityDetails(track)?.let { details ->
                  Text(
                    text = details,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
              if (onDownload != null) {
                PlayerSheetAction(
                  Icons.RoundedFilled.Download,
                  stringResource(R.string.downloads_download),
                  { onDownload(track) },
                )
              }
            }
          }
        }
      }
    }
  }
}

private fun qualityLabel(track: TrackNode): String {
  val height = track.demuxH?.takeIf { it > 0 }
  val width = track.demuxW?.takeIf { it > 0 }
  val qualityDimension =
    when {
      width != null && height != null -> minOf(width, height)
      height != null -> height
      width != null -> width
      else -> QUALITY_HEIGHT_REGEX.find(track.effectiveTitle.orEmpty())?.groupValues?.getOrNull(1)?.toLongOrNull()
    }
  val resolution =
    when {
      qualityDimension != null -> "${qualityDimension}p"
      !track.effectiveTitle.isNullOrBlank() -> track.effectiveTitle.orEmpty()
      !track.codecDesc.isNullOrBlank() -> track.codecDesc.orEmpty()
      else -> "#${track.id}"
    }
  val fps = track.demuxFps?.takeIf { it > 0.0 }?.let { value -> "${value.toInt()} fps" }
  return listOfNotNull(resolution, fps).joinToString(" • ")
}

private fun qualityDetails(track: TrackNode): String? {
  val dimensions =
    if ((track.demuxW ?: 0L) > 0L && (track.demuxH ?: 0L) > 0L) {
      "${track.demuxW}×${track.demuxH}"
    } else {
      null
    }
  val codec = track.codecDesc?.takeIf(String::isNotBlank) ?: track.codec?.takeIf(String::isNotBlank)
  val bitrate =
    track.effectiveBitrate
      ?.takeIf { it > 0L }
      ?.let { bitsPerSecond ->
        if (bitsPerSecond >= 1_000_000L) {
          "%.1f Mbps".format(bitsPerSecond / 1_000_000.0)
        } else {
          "${bitsPerSecond / 1_000L} kbps"
        }
      }
  return listOfNotNull(
    track.ytdlFormatId?.let { "#$it" } ?: "#${track.id}",
    track.effectiveTitle?.takeUnless { it == qualityLabel(track) },
    dimensions,
    codec,
    bitrate,
  ).distinct()
    .joinToString(" • ")
    .takeIf(String::isNotBlank)
}

private val QUALITY_HEIGHT_REGEX = Regex("""(?i)(\d{3,4})p""")
