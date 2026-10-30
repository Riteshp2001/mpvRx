/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Android TV player controls. Layout, scrims, button styling, accelerating D-pad scrubbing and
 * auto-hide rules are adapted from NuvioTV's player (https://github.com/NuvioMedia/NuvioTV, GPL-3.0).
 */

package app.gyrolet.mpvrx.ui.player.controls

import androidx.compose.ui.focus.onFocusChanged
import app.gyrolet.mpvrx.ui.player.controls.components.WindowedSeekPreview
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.PlayerPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.ui.icons.AppIcon
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.PlaybackSession
import app.gyrolet.mpvrx.ui.player.PlayerViewModel
import app.gyrolet.mpvrx.ui.player.Sheets
import app.gyrolet.mpvrx.ui.player.controls.components.AnimatedPlayPauseIcon
import kotlinx.coroutines.delay
import org.koin.compose.koinInject
import kotlin.math.abs

private object TvPlayerDefaults {
  val HorizontalPadding: Dp = 48.dp
  val VerticalPadding: Dp = 32.dp
  val TopScrimHeight: Dp = 180.dp
  val BottomScrimHeight: Dp = 280.dp
  val ButtonSize: Dp = 52.dp
  val PlayButtonSize: Dp = 60.dp
  val ButtonIconSize: Dp = 26.dp
  val ButtonSpacing: Dp = 8.dp
  val SeekBarHitHeight: Dp = 20.dp
  val SeekBarHeight: Dp = 6.dp
  val SeekBarFocusedHeight: Dp = 10.dp

  const val MinAutoHideMillis = 4_000L
  const val FadeInMillis = 220
  const val FadeOutMillis = 260
}

/** Seek step for a held D-pad key, growing with the key-repeat count (NuvioTV scrub rates). */
internal fun tvScrubStepSeconds(repeatCount: Int): Int =
  when {
    repeatCount >= 15 -> 60
    repeatCount >= 8 -> 30
    repeatCount >= 3 -> 20
    else -> 10
  }

@Composable
internal fun TvPlayerControls(
  viewModel: PlayerViewModel,
  controlsShown: Boolean,
  overlayOpen: Boolean,
  paused: Boolean,
  mediaTitle: String?,
  chapterTitle: String?,
  durationSeconds: Float,
  chapterStarts: List<Float>,
  showBufferedRange: Boolean,
  hasSubtitles: Boolean,
  hasAudioTracks: Boolean,
  hasChapters: Boolean,
  showQualityButton: Boolean,
  playbackSpeed: Float,
  skipSegmentLabel: String?,
  showBuffering: Boolean,
  autoHideMillis: Long,
  onOpenSheet: (Sheets) -> Unit,
  modifier: Modifier = Modifier,
) {
  var lastInteraction by remember { mutableLongStateOf(0L) }
  val markInteraction = { lastInteraction = System.nanoTime() }
  val playFocusRequester = remember { FocusRequester() }
  val positionState = viewModel.precisePosition.collectAsState()
  val queue by PlaybackSession.queue.collectAsState()
  val hasPrevious = remember(queue) { viewModel.hasPrevious() }
  val hasNext = remember(queue) { viewModel.hasNext() }

  // Also re-runs when a sheet/panel closes, since the controls stay shown but lose focus.
  LaunchedEffect(controlsShown, overlayOpen) {
    if (controlsShown && !overlayOpen) {
      // Wait for the controls to be attached before asking for focus.
      withFrameNanos { }
      runCatching { playFocusRequester.requestFocus() }
    }
  }

  LaunchedEffect(controlsShown, paused, overlayOpen, lastInteraction) {
    if (controlsShown && !paused && !overlayOpen) {
      delay(autoHideMillis.coerceAtLeast(TvPlayerDefaults.MinAutoHideMillis))
      viewModel.hideControls()
    }
  }

  Box(
    modifier =
      modifier
        .fillMaxSize()
        .onPreviewKeyEvent {
          markInteraction()
          false
        },
  ) {
    WindowedSeekPreview(
      viewModel = viewModel,
      durationSeconds = durationSeconds,
      isPortrait = false,
      modifier = Modifier
        .align(Alignment.BottomCenter)
        .padding(horizontal = TvPlayerDefaults.HorizontalPadding)
        .padding(bottom = TvPlayerDefaults.VerticalPadding + 160.dp),
    )
    AnimatedVisibility(
      visible = controlsShown,
      enter = fadeIn(tween(TvPlayerDefaults.FadeInMillis)),
      exit = fadeOut(tween(TvPlayerDefaults.FadeOutMillis)),
      modifier = Modifier.fillMaxSize(),
    ) {
      Box(Modifier.fillMaxSize()) {
        Box(
          Modifier
            .fillMaxWidth()
            .height(TvPlayerDefaults.TopScrimHeight)
            .align(Alignment.TopCenter)
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.72f), Color.Transparent))),
        )
        Box(
          Modifier
            .fillMaxWidth()
            .height(TvPlayerDefaults.BottomScrimHeight)
            .align(Alignment.BottomCenter)
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)))),
        )

        TvPlayerTopBar(
          title = mediaTitle,
          modifier =
            Modifier
              .align(Alignment.TopStart)
              .fillMaxWidth()
              .padding(horizontal = TvPlayerDefaults.HorizontalPadding, vertical = TvPlayerDefaults.VerticalPadding),
        )

        Column(
          modifier =
            Modifier
              .align(Alignment.BottomStart)
              .fillMaxWidth()
              .padding(horizontal = TvPlayerDefaults.HorizontalPadding, vertical = TvPlayerDefaults.VerticalPadding),
        ) {
          if (skipSegmentLabel != null) {
            TvPillButton(
              text = skipSegmentLabel,
              onClick = {
                markInteraction()
                viewModel.skipActiveSegment()
              },
            )
            Spacer(Modifier.height(16.dp))
          }
          if (!chapterTitle.isNullOrBlank()) {
            Text(
              text = chapterTitle,
              style = MaterialTheme.typography.titleSmall,
              color = Color.White.copy(alpha = 0.85f),
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
          }
          TvSeekBar(
            positionState = positionState,
            durationSeconds = durationSeconds,
            chapterStarts = chapterStarts,
            showBufferedRange = showBufferedRange,
            onSeekBy = { seconds ->
              markInteraction()
              viewModel.seekFromTvSeekbar(seconds)
            },
            onSeekFinished = viewModel::finishTvSeek,
            onTogglePlayback = {
              markInteraction()
              viewModel.pauseUnpause()
            },
            onHideControls = viewModel::hideControls,
            playFocusRequester = playFocusRequester,
          )
          Spacer(Modifier.height(10.dp))
          TvTimeRow(positionState = positionState, durationSeconds = durationSeconds)
          Spacer(Modifier.height(18.dp))
          Row(
            modifier =
              Modifier
                .fillMaxWidth()
                .onPreviewKeyEvent { event ->
                  // DOWN from the button row dismisses the controls, as on NuvioTV.
                  if (event.key == Key.DirectionDown && event.type == KeyEventType.KeyDown) {
                    viewModel.hideControls()
                    true
                  } else {
                    false
                  }
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TvPlayerDefaults.ButtonSpacing),
          ) {
            if (hasPrevious) {
              TvPlayerButton(
                icon = Icons.RoundedFilled.SkipPrevious,
                contentDescription = stringResource(R.string.pref_gesture_media_previous),
                onClick = viewModel::playPrevious,
              )
            }
            TvPlayerButton(
              contentDescription = stringResource(R.string.tv_player_play_pause),
              onClick = viewModel::pauseUnpause,
              size = TvPlayerDefaults.PlayButtonSize,
              modifier = Modifier.focusRequester(playFocusRequester),
            ) { tint ->
              AnimatedPlayPauseIcon(
                isPlaying = !paused,
                tint = tint,
                modifier = Modifier.size(TvPlayerDefaults.ButtonIconSize + 4.dp),
              )
            }
            if (hasNext) {
              TvPlayerButton(
                icon = Icons.RoundedFilled.SkipNext,
                contentDescription = stringResource(R.string.pref_gesture_media_next),
                onClick = viewModel::playNext,
              )
            }

            Spacer(Modifier.weight(1f))

            if (hasSubtitles) {
              TvPlayerButton(
                icon = Icons.RoundedFilled.Subtitles,
                contentDescription = stringResource(R.string.tv_player_subtitles),
                onClick = { onOpenSheet(Sheets.SubtitleTracks) },
              )
            }
            if (hasAudioTracks) {
              TvPlayerButton(
                icon = Icons.RoundedFilled.Audiotrack,
                contentDescription = stringResource(R.string.tv_player_audio),
                onClick = { onOpenSheet(Sheets.AudioTracks) },
              )
            }
            if (hasChapters) {
              TvPlayerButton(
                icon = Icons.RoundedFilled.Bookmarks,
                contentDescription = stringResource(R.string.tv_player_chapters),
                onClick = { onOpenSheet(Sheets.Chapters) },
              )
            }
            TvSpeedButton(
              speed = playbackSpeed,
              contentDescription = stringResource(R.string.tv_player_speed),
              onClick = { onOpenSheet(Sheets.PlaybackSpeed) },
            )
            TvPlayerButton(
              icon = Icons.RoundedFilled.AspectRatio,
              contentDescription = stringResource(R.string.tv_player_aspect_ratio),
              onClick = { onOpenSheet(Sheets.AspectRatios) },
            )
            if (showQualityButton) {
              TvPlayerButton(
                icon = Icons.RoundedFilled.Hd,
                contentDescription = stringResource(R.string.tv_player_quality),
                onClick = { onOpenSheet(Sheets.VideoQuality) },
              )
            }
            TvPlayerButton(
              icon = Icons.RoundedFilled.MoreVert,
              contentDescription = stringResource(R.string.player_sheets_more_title),
              onClick = { onOpenSheet(Sheets.More) },
            )
          }
        }
      }
    }

    if (showBuffering) {
      CircularProgressIndicator(
        color = Color.White,
        strokeWidth = 4.dp,
        modifier =
          Modifier
            .align(Alignment.Center)
            .size(56.dp),
      )
    }
  }
}

@Composable
private fun TvPlayerTopBar(
  title: String?,
  modifier: Modifier = Modifier,
) {
  var clock by remember { mutableStateOf("") }
  val context = LocalContext.current
  val preferences = koinInject<PlayerPreferences>()
  val clockFormat by preferences.clockFormat.collectAsState()
  LaunchedEffect(context, clockFormat) {
    while (true) {
      clock = formatClock(context, clockFormat)
      delay(60_000L - System.currentTimeMillis() % 60_000L)
    }
  }
  Row(
    modifier = modifier,
    verticalAlignment = Alignment.Top,
  ) {
    Text(
      text = title.orEmpty(),
      style = MaterialTheme.typography.headlineSmall,
      fontWeight = FontWeight.SemiBold,
      color = Color.White,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
    )
    Spacer(Modifier.width(24.dp))
    Text(
      text = clock,
      style = MaterialTheme.typography.titleLarge,
      color = Color.White.copy(alpha = 0.92f),
    )
  }
}

@Composable
private fun TvSeekBar(
  positionState: State<Float>,
  durationSeconds: Float,
  chapterStarts: List<Float>,
  showBufferedRange: Boolean,
  onSeekBy: (Int) -> Unit,
  onSeekFinished: (Boolean) -> Unit,
  onTogglePlayback: () -> Unit,
  onHideControls: () -> Unit,
  playFocusRequester: FocusRequester,
) {
  val interactionSource = remember { MutableInteractionSource() }
  val focused by interactionSource.collectIsFocusedAsState()
  val barHeight by animateDpAsState(
    if (focused) TvPlayerDefaults.SeekBarFocusedHeight else TvPlayerDefaults.SeekBarHeight,
    tween(160),
    label = "tv_seekbar_height",
  )
  val trackColor by animateColorAsState(
    Color.White.copy(alpha = if (focused) 0.45f else 0.3f),
    tween(160),
    label = "tv_seekbar_track",
  )
  val accent = MaterialTheme.colorScheme.primary
  val description = stringResource(R.string.tv_player_seek_bar)
  // This is observed only while the seekbar is composed. Read its value in draw, not in the
  // full player composition, so cache updates do not wake the controls while they are hidden.
  val cacheTime =
    if (showBufferedRange) remember { PlaybackSession.propDouble["demuxer-cache-time"] }.collectAsState()
    else null

  Canvas(
    modifier =
      Modifier
        .fillMaxWidth()
        .height(TvPlayerDefaults.SeekBarHitHeight)
        .semantics { contentDescription = description }
        .focusProperties { down = playFocusRequester }
        .onFocusChanged { if (!it.isFocused) onSeekFinished(true) }
        .onKeyEvent { event ->
          if (event.type == KeyEventType.KeyUp && (event.key == Key.DirectionLeft || event.key == Key.DirectionRight)) {
            onSeekFinished(event.nativeKeyEvent.isCanceled)
            return@onKeyEvent true
          }
          if (event.type != KeyEventType.KeyDown) {
            return@onKeyEvent event.key == Key.DirectionLeft ||
              event.key == Key.DirectionRight ||
              event.key == Key.DirectionCenter ||
              event.key == Key.Enter || event.key == Key.NumPadEnter
          }
          val repeat = event.nativeKeyEvent.repeatCount
          when (event.key) {
            Key.DirectionLeft -> {
              onSeekBy(-tvScrubStepSeconds(repeat))
              true
            }
            Key.DirectionRight -> {
              onSeekBy(tvScrubStepSeconds(repeat))
              true
            }
            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
              if (repeat == 0) onTogglePlayback()
              true
            }
            Key.DirectionUp -> {
              onHideControls()
              true
            }
            else -> false
          }
        }.focusable(interactionSource = interactionSource),
  ) {
    val h = barHeight.toPx()
    val top = (size.height - h) / 2f
    val radius = CornerRadius(h / 2f, h / 2f)
    val fraction =
      if (durationSeconds > 0f) (positionState.value / durationSeconds).coerceIn(0f, 1f) else 0f

    drawRoundRect(trackColor, topLeft = Offset(0f, top), size = Size(size.width, h), cornerRadius = radius)
    val buffered = cacheTime?.value?.toFloat()?.takeIf { it.isFinite() && it > 0f }
    if (buffered != null && durationSeconds > 0f) {
      val bufferedFraction = (buffered / durationSeconds).coerceIn(0f, 1f)
      drawRoundRect(Color.White.copy(alpha = 0.5f), topLeft = Offset(0f, top),
        size = Size(size.width * bufferedFraction, h), cornerRadius = radius)
    }
    if (fraction > 0f) {
      drawRoundRect(accent, topLeft = Offset(0f, top), size = Size(size.width * fraction, h), cornerRadius = radius)
    }
    if (durationSeconds > 0f) {
      val tickWidth = 2.dp.toPx()
      chapterStarts.forEach { start ->
        if (start <= 0f || start >= durationSeconds) return@forEach
        val x = size.width * (start / durationSeconds)
        drawRect(Color.Black.copy(alpha = 0.6f), topLeft = Offset(x - tickWidth / 2f, top), size = Size(tickWidth, h))
      }
    }
    if (focused) {
      drawCircle(Color.White, radius = h * 1.1f, center = Offset(size.width * fraction, size.height / 2f))
    }
  }
}

@Composable
private fun TvTimeRow(
  positionState: State<Float>,
  durationSeconds: Float,
) {
  val position = positionState.value
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Text(
      text = formatTvTime(position),
      style = MaterialTheme.typography.titleSmall,
      color = Color.White,
    )
    Spacer(Modifier.weight(1f))
    if (durationSeconds > 0f) {
      Text(
        text = "-${formatTvTime((durationSeconds - position).coerceAtLeast(0f))}  /  ${formatTvTime(durationSeconds)}",
        style = MaterialTheme.typography.titleSmall,
        color = Color.White.copy(alpha = 0.78f),
      )
    }
  }
}

@Composable
private fun TvPlayerButton(
  icon: AppIcon,
  contentDescription: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  size: Dp = TvPlayerDefaults.ButtonSize,
) {
  TvPlayerButton(
    contentDescription = contentDescription,
    onClick = onClick,
    modifier = modifier,
    size = size,
  ) { tint ->
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(TvPlayerDefaults.ButtonIconSize))
  }
}

@Composable
private fun TvPlayerButton(
  contentDescription: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  size: Dp = TvPlayerDefaults.ButtonSize,
  content: @Composable (tint: Color) -> Unit,
) {
  val interactionSource = remember { MutableInteractionSource() }
  val focused by interactionSource.collectIsFocusedAsState()
  val background by animateColorAsState(
    if (focused) Color.White else Color.White.copy(alpha = 0.12f),
    tween(150),
    label = "tv_button_bg",
  )
  val tint by animateColorAsState(if (focused) Color.Black else Color.White, tween(150), label = "tv_button_tint")
  val scale by animateFloatAsState(if (focused) 1.1f else 1f, tween(150), label = "tv_button_scale")

  Box(
    modifier =
      modifier
        .size(size)
        .graphicsLayer {
          scaleX = scale
          scaleY = scale
        }.clip(CircleShape)
        .background(background)
        .semantics { this.contentDescription = contentDescription }
        .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    content(tint)
  }
}

@Composable
private fun TvSpeedButton(
  speed: Float,
  contentDescription: String,
  onClick: () -> Unit,
) {
  if (abs(speed - 1f) < 0.001f) {
    TvPlayerButton(icon = Icons.RoundedFilled.Speed, contentDescription = contentDescription, onClick = onClick)
  } else {
    TvPlayerButton(contentDescription = contentDescription, onClick = onClick) { tint ->
      Text(
        text = "${"%.2f".format(speed).trimEnd('0').trimEnd('.')}x",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = tint,
      )
    }
  }
}

@Composable
private fun TvPillButton(
  text: String,
  onClick: () -> Unit,
) {
  val interactionSource = remember { MutableInteractionSource() }
  val focused by interactionSource.collectIsFocusedAsState()
  val background by animateColorAsState(
    if (focused) Color.White else Color.Black.copy(alpha = 0.6f),
    tween(150),
    label = "tv_pill_bg",
  )
  Box(
    modifier =
      Modifier
        .clip(RoundedCornerShape(50))
        .background(background)
        .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
        .padding(horizontal = 22.dp, vertical = 12.dp),
  ) {
    Text(
      text = text,
      style = MaterialTheme.typography.titleSmall,
      fontWeight = FontWeight.Bold,
      color = if (focused) Color.Black else Color.White,
    )
  }
}

private fun formatTvTime(seconds: Float): String {
  val total = seconds.toLong().coerceAtLeast(0L)
  val h = total / 3600
  val m = (total % 3600) / 60
  val s = total % 60
  return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
