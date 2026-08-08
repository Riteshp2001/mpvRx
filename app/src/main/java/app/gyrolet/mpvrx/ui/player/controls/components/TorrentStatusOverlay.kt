/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.controls.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.torrent.TorrentStreamingState
import app.gyrolet.mpvrx.domain.torrent.formatTorrentBytes
import app.gyrolet.mpvrx.domain.torrent.formatTorrentSpeed
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons

@Composable
fun TorrentStatusOverlay(
  state: TorrentStreamingState,
  modifier: Modifier = Modifier,
) {
  val streamKey =
    when (state) {
      is TorrentStreamingState.Idle -> "idle"
      is TorrentStreamingState.Connecting -> "connecting"
      is TorrentStreamingState.Streaming -> state.localUrl
      is TorrentStreamingState.Error -> "error:${state.message}"
    }
  var isDismissed by remember(streamKey) { mutableStateOf(false) }

  AnimatedVisibility(
    visible = state !is TorrentStreamingState.Idle && !isDismissed,
    enter = fadeIn(),
    exit = fadeOut(),
    modifier = modifier,
  ) {
    Surface(
      shape = RoundedCornerShape(16.dp),
      color = Color.Black.copy(alpha = 0.78f),
      tonalElevation = 6.dp,
      modifier =
        Modifier
          .padding(16.dp)
          .fillMaxWidth(0.9f),
    ) {
      Box(modifier = Modifier.padding(14.dp)) {
        when (state) {
          is TorrentStreamingState.Connecting -> {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 2.5.dp,
              )
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = stringResource(R.string.torrent_status_engine),
                  style = MaterialTheme.typography.titleSmall,
                  fontWeight = FontWeight.Bold,
                  color = Color.White,
                )
                Text(
                  text = state.phase,
                  style = MaterialTheme.typography.bodySmall,
                  color = Color.White.copy(alpha = 0.8f),
                )
              }
            }
          }

          is TorrentStreamingState.Streaming -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
              ) {
                Text(
                  text = state.fileName,
                  style = MaterialTheme.typography.titleSmall,
                  fontWeight = FontWeight.Bold,
                  color = Color.White,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                  modifier = Modifier.weight(1f),
                )
                IconButton(
                  onClick = { isDismissed = true },
                  modifier = Modifier.size(32.dp),
                ) {
                  Icon(
                    imageVector = Icons.RoundedFilled.Close,
                    contentDescription = stringResource(R.string.torrent_status_hide),
                    tint = Color.White.copy(alpha = 0.7f),
                  )
                }
              }

              Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                TorrentMetric(
                  icon = Icons.RoundedFilled.Download,
                  tint = Color(0xFF4CAF50),
                  value = formatTorrentSpeed(state.downloadSpeed),
                )
                TorrentMetric(
                  icon = Icons.RoundedFilled.Upload,
                  tint = Color(0xFF2196F3),
                  value = formatTorrentSpeed(state.uploadSpeed),
                )
              }

              TorrentMetric(
                icon = Icons.RoundedFilled.Group,
                tint = Color(0xFFFFC107),
                value = stringResource(R.string.torrent_status_swarm, state.seeds, state.peers),
              )

              Column {
                Row(
                  horizontalArrangement = Arrangement.SpaceBetween,
                  modifier = Modifier.fillMaxWidth(),
                ) {
                  Text(
                    text = stringResource(R.string.torrent_status_downloaded, (state.totalProgress * 100).toInt()),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.7f),
                  )
                  Text(
                    text = "${formatTorrentBytes(state.downloadedBytes)} / ${formatTorrentBytes(state.fileSize)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.7f),
                  )
                }
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                  progress = { state.totalProgress.coerceIn(0f, 1f) },
                  modifier =
                    Modifier
                      .fillMaxWidth()
                      .height(4.dp)
                      .clip(RoundedCornerShape(2.dp)),
                  color = MaterialTheme.colorScheme.primary,
                  trackColor = Color.White.copy(alpha = 0.2f),
                )
              }
            }
          }

          is TorrentStreamingState.Error -> {
            Row(verticalAlignment = Alignment.Top) {
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = stringResource(R.string.torrent_status_error),
                  style = MaterialTheme.typography.titleSmall,
                  fontWeight = FontWeight.Bold,
                  color = MaterialTheme.colorScheme.error,
                )
                Text(
                  text = state.message,
                  style = MaterialTheme.typography.bodySmall,
                  color = Color.White.copy(alpha = 0.9f),
                )
              }
              IconButton(
                onClick = { isDismissed = true },
                modifier = Modifier.size(32.dp),
              ) {
                Icon(
                  imageVector = Icons.RoundedFilled.Close,
                  contentDescription = stringResource(R.string.torrent_status_hide),
                  tint = Color.White.copy(alpha = 0.7f),
                )
              }
            }
          }

          is TorrentStreamingState.Idle -> Unit
        }
      }
    }
  }
}

@Composable
private fun TorrentMetric(
  icon: app.gyrolet.mpvrx.ui.icons.AppIcon,
  tint: Color,
  value: String,
) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Icon(
      imageVector = icon,
      contentDescription = null,
      tint = tint,
      modifier = Modifier.size(16.dp),
    )
    Spacer(modifier = Modifier.width(4.dp))
    Text(
      text = value,
      style = MaterialTheme.typography.labelMedium,
      color = Color.White.copy(alpha = 0.9f),
    )
  }
}
