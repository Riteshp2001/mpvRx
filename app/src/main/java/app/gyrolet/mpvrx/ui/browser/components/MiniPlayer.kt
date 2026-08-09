/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.components

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.PlayerPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.MediaPlaybackService
import app.gyrolet.mpvrx.ui.player.PlaybackPhase
import app.gyrolet.mpvrx.ui.player.PlaybackSession
import app.gyrolet.mpvrx.ui.player.PlayerActivity
import app.gyrolet.mpvrx.utils.media.fileExtension
import app.gyrolet.mpvrx.utils.storage.FileTypeUtils
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun MiniPlayer(modifier: Modifier = Modifier) {
  val isServiceRunning = MediaPlaybackService.isForegroundActive()
  val context = LocalContext.current
  val sessionState by PlaybackSession.state.collectAsState()
  val playerPreferences: PlayerPreferences = koinInject()
  val enableVideoMiniPlayer by playerPreferences.enableVideoMiniPlayer.collectAsState()

  val currentItem = sessionState.currentItem
  val isMediaActive = isServiceRunning && currentItem != null &&
    sessionState.phase != PlaybackPhase.IDLE &&
    sessionState.phase != PlaybackPhase.UNINITIALIZED &&
    sessionState.phase != PlaybackPhase.ERROR

  AnimatedVisibility(
    visible = isMediaActive,
    enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
    exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
    modifier = modifier,
  ) {
    MiniPlayerContent(
      context = context,
      enableVideoMiniPlayer = enableVideoMiniPlayer,
    )
  }
}

@Composable
private fun MiniPlayerContent(
  context: Context,
  enableVideoMiniPlayer: Boolean,
) {
  val sessionState by PlaybackSession.state.collectAsState()
  val currentItem = sessionState.currentItem
  val paused by PlaybackSession.propBoolean["pause"].collectAsState()
  val rawMediaTitle by PlaybackSession.propString["media-title"].collectAsState()
  val duration by PlaybackSession.propInt["duration"].collectAsState()
  val position by PlaybackSession.propInt["time-pos"].collectAsState()
  val videoAspectRaw by PlaybackSession.propDouble["video-params/aspect"].collectAsState()
  val videoWidth by PlaybackSession.propLong["video-params/w"].collectAsState()
  val videoHeight by PlaybackSession.propLong["video-params/h"].collectAsState()

  val isPlaying = paused == false
  val title = rawMediaTitle?.takeIf { it.isNotBlank() }
    ?: currentItem?.title?.takeIf { it.isNotBlank() }
    ?: "Media Track"

  val ext = (currentItem?.originalUri ?: currentItem?.title ?: "").fileExtension()
  val isAudioOnlyItem = ext in FileTypeUtils.AUDIO_EXTENSIONS

  val isVideoMode = !isAudioOnlyItem && enableVideoMiniPlayer

  val dur = duration?.toFloat() ?: 0f
  val pos = position?.toFloat() ?: 0f
  val progressFraction = if (dur > 0f) (pos / dur).coerceIn(0f, 1f) else 0f

  val coroutineScope = rememberCoroutineScope()
  var offsetX by remember { mutableFloatStateOf(0f) }
  val density = LocalDensity.current
  val dismissThresholdPx = with(density) { 100.dp.toPx() }

  val launchPlayer = remember(context) {
    {
      val intent = Intent(context, PlayerActivity::class.java).apply {
        action = MediaPlaybackService.ACTION_OPEN_PLAYER
        putExtra("is_audio", isAudioOnlyItem)
        putExtra("internal_launch", true)
        putExtra("launch_source", "mini_player")
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
      }
      context.startActivity(intent)
      if (context is Activity) {
        context.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
      }
    }
  }

  val dismissPlayer = remember(context) {
    {
      context.startService(
        Intent(context, MediaPlaybackService::class.java).setAction(
          MediaPlaybackService.ACTION_NOTIFICATION_STOP,
        ),
      )
    }
  }

  val containerWidthModifier = if (isVideoMode) {
    Modifier.widthIn(max = 360.dp)
  } else {
    Modifier.widthIn(max = 330.dp)
  }

  Surface(
    modifier = Modifier
      .then(containerWidthModifier)
      .offset { IntOffset(offsetX.roundToInt(), 0) }
      .pointerInput(Unit) {
        detectHorizontalDragGestures(
          onDragEnd = {
            if (abs(offsetX) > dismissThresholdPx) {
              dismissPlayer()
            } else {
              coroutineScope.launch {
                androidx.compose.animation.core.Animatable(offsetX).animateTo(0f) {
                  offsetX = value
                }
              }
            }
          },
          onDragCancel = {
            coroutineScope.launch {
              androidx.compose.animation.core.Animatable(offsetX).animateTo(0f) {
                offsetX = value
              }
            }
          },
          onHorizontalDrag = { _, dragAmount ->
            offsetX += dragAmount
          },
        )
      }
      .clip(RoundedCornerShape(20.dp))
      .clickable { launchPlayer() },
    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f),
    tonalElevation = 8.dp,
    shadowElevation = 10.dp,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
  ) {
    val primaryContainerColor = MaterialTheme.colorScheme.primaryContainer

    if (isVideoMode) {
      // Calculate aspect ratio for video surface container
      val aspect = videoAspectRaw?.toFloat()
        ?: if ((videoWidth ?: 0L) > 0L && (videoHeight ?: 0L) > 0L) {
          videoWidth!!.toFloat() / videoHeight!!.toFloat()
        } else {
          16f / 9f
        }

      // Constrain aspect ratio between 0.5 (portrait) and 2.4 (ultrawide)
      val safeAspect = aspect.coerceIn(0.5f, 2.39f)

      Row(
        modifier = Modifier
          .fillMaxWidth()
          .height(76.dp)
          .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        // Video MPV Surface View Container
        Box(
          modifier = Modifier
            .fillMaxHeight()
            .aspectRatio(safeAspect)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black),
          contentAlignment = Alignment.Center,
        ) {
          AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
              SurfaceView(viewContext).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                  override fun surfaceCreated(holder: SurfaceHolder) {
                    val vid = PlaybackSession.getPropertyString("vid")
                    if (vid == "no" || vid == "0") {
                      PlaybackSession.setPropertyString("vid", "auto")
                    }
                    PlaybackSession.bindSurface(holder.surface, width, height)
                  }

                  override fun surfaceChanged(
                    holder: SurfaceHolder,
                    format: Int,
                    width: Int,
                    height: Int,
                  ) {
                    val vid = PlaybackSession.getPropertyString("vid")
                    if (vid == "no" || vid == "0") {
                      PlaybackSession.setPropertyString("vid", "auto")
                    }
                    PlaybackSession.bindSurface(holder.surface, width, height)
                  }

                  override fun surfaceDestroyed(holder: SurfaceHolder) {
                    PlaybackSession.unbindSurface()
                  }
                })
              }
            },
            update = { surfaceView ->
              if (surfaceView.holder.surface.isValid) {
                val vid = PlaybackSession.getPropertyString("vid")
                if (vid == "no" || vid == "0") {
                  PlaybackSession.setPropertyString("vid", "auto")
                }
                PlaybackSession.bindSurface(surfaceView.holder.surface, surfaceView.width, surfaceView.height)
              }
            },
          )
        }

        Spacer(modifier = Modifier.width(10.dp))

        // Title and Status
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
            text = if (isPlaying) "Playing Video" else "Paused",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
          )
        }

        // Control Buttons
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
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
              label = "mini_video_play_pause",
            ) { playing ->
              Icon(
                imageVector = if (playing) Icons.RoundedFilled.Pause else Icons.RoundedFilled.PlayArrow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(26.dp),
              )
            }
          }

          IconButton(
            onClick = { dismissPlayer() },
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
    } else {
      // Audio Mini Player View
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .drawBehind {
            if (progressFraction > 0f) {
              drawRect(
                color = primaryContainerColor.copy(alpha = 0.35f),
                size = Size(
                  width = size.width * progressFraction,
                  height = size.height,
                ),
              )
            }
          }
          .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        // Music Icon Badge
        Box(
          modifier = Modifier
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
          onClick = { dismissPlayer() },
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
}
