/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.components

import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.MediaPlaybackService
import app.gyrolet.mpvrx.ui.player.PlaybackSession
import app.gyrolet.mpvrx.ui.player.PlayerActivity

@Composable
fun AudioMiniPlayer(modifier: Modifier = Modifier) {
  val isServiceRunning = MediaPlaybackService.isForegroundActive()
  val context = LocalContext.current
  val sessionState by PlaybackSession.state.collectAsStateWithLifecycle()
  val paused by PlaybackSession.propBoolean["pause"].collectAsStateWithLifecycle()
  val rawMediaTitle by PlaybackSession.propString["media-title"].collectAsStateWithLifecycle()
  val duration by PlaybackSession.propInt["duration"].collectAsStateWithLifecycle()
  val position by PlaybackSession.propInt["time-pos"].collectAsStateWithLifecycle()

  if (!isServiceRunning || sessionState.currentItem == null) return

  val isPlaying = paused == false
  val title =
    sessionState.currentItem?.title?.takeIf { it.isNotBlank() }
      ?: rawMediaTitle?.takeIf { it.isNotBlank() }
      ?: "Audio Track"

  Surface(
    modifier =
      modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(24.dp))
        .clickable {
          val intent =
            Intent(context, PlayerActivity::class.java).apply {
              action = MediaPlaybackService.ACTION_OPEN_PLAYER
              putExtra("is_audio", true)
              putExtra("media_library_audio", true)
              putExtra("internal_launch", true)
              putExtra("launch_source", "notification")
              flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
          context.startActivity(intent)
        },
    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f),
    tonalElevation = 6.dp,
    shadowElevation = 8.dp,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
  ) {
    val primaryContainerColor = MaterialTheme.colorScheme.primaryContainer

    Row(
      modifier =
        Modifier
          .fillMaxWidth()
          .drawBehind {
            val dur = duration?.toFloat() ?: 0f
            val pos = position?.toFloat() ?: 0f
            val progressFraction = if (dur > 0f) (pos / dur).coerceIn(0f, 1f) else 0f
            if (progressFraction > 0f) {
              drawRect(
                color = primaryContainerColor.copy(alpha = 0.35f),
                size =
                  Size(
                    width = size.width * progressFraction,
                    height = size.height,
                  ),
              )
            }
          }.padding(horizontal = 12.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      // Music Icon Badge
      Box(
        modifier =
          Modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = Icons.RoundedFilled.Audiotrack,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(24.dp),
        )
      }

      Spacer(modifier = Modifier.width(12.dp))

      // Title & Track Status
      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.Center,
      ) {
        Text(
          text = title,
          style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.basicMarquee(),
        )
        Text(
          text = if (isPlaying) "Playing" else "Paused",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
        )
      }

      Spacer(modifier = Modifier.width(8.dp))

      // Play / Pause Action Button
      IconButton(
        onClick = {
          context.startService(
            Intent(context, MediaPlaybackService::class.java).setAction(
              MediaPlaybackService.ACTION_NOTIFICATION_PLAY_PAUSE,
            ),
          )
        },
        modifier = Modifier.size(36.dp),
      ) {
        AnimatedContent(
          targetState = isPlaying,
          transitionSpec = { fadeIn() togetherWith fadeOut() },
          label = "mini_play_pause",
        ) { playing ->
          Icon(
            imageVector = if (playing) Icons.RoundedFilled.Pause else Icons.RoundedFilled.PlayArrow,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(26.dp),
          )
        }
      }

      // Next Track Action Button
      IconButton(
        onClick = {
          context.startService(
            Intent(context, MediaPlaybackService::class.java).setAction(
              MediaPlaybackService.ACTION_NOTIFICATION_NEXT,
            ),
          )
        },
        modifier = Modifier.size(36.dp),
      ) {
        Icon(
          imageVector = Icons.RoundedFilled.SkipNext,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurface,
          modifier = Modifier.size(24.dp),
        )
      }

      // Close Action Button
      IconButton(
        onClick = {
          context.startService(
            Intent(context, MediaPlaybackService::class.java).setAction(
              MediaPlaybackService.ACTION_NOTIFICATION_STOP,
            ),
          )
        },
        modifier = Modifier.size(32.dp),
      ) {
        Icon(
          imageVector = Icons.RoundedFilled.Close,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.size(18.dp),
        )
      }
    }
  }
}
