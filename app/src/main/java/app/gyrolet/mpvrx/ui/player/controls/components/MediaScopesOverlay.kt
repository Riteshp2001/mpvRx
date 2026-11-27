/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.controls.components

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.liquidglass.outsideShadow
import app.gyrolet.mpvrx.ui.player.PlaybackSession
import app.gyrolet.mpvrx.ui.player.PlayerViewModel
import app.gyrolet.mpvrx.ui.player.TrackNode
import app.gyrolet.mpvrx.ui.player.scopes.AudioWaveformData
import app.gyrolet.mpvrx.ui.player.scopes.AudioWaveformDecoder
import app.gyrolet.mpvrx.ui.player.scopes.MediaScopeTab
import app.gyrolet.mpvrx.ui.player.scopes.VideoScopeAnalyzer
import app.gyrolet.mpvrx.ui.player.scopes.VideoScopeMode
import app.gyrolet.mpvrx.ui.theme.fontFamilyForText
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.roundToInt

private sealed interface AudioWaveformLoadState {
  data object Loading : AudioWaveformLoadState

  data class Ready(
    val data: AudioWaveformData,
  ) : AudioWaveformLoadState

  data class Error(
    val message: String,
  ) : AudioWaveformLoadState
}

@Composable
fun MediaScopesOverlay(
  viewModel: PlayerViewModel,
  audioTracks: ImmutableList<TrackNode>,
  durationSeconds: Float,
  modifier: Modifier = Modifier,
) {
  val state by viewModel.mediaScopesUiState.collectAsState()
  val configuration = LocalConfiguration.current
  val isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT
  val resizeDescription = stringResource(R.string.scopes_resize)
  var requestedWidthDp by rememberSaveable(isPortrait) { mutableFloatStateOf(-1f) }
  var requestedHeightDp by rememberSaveable(isPortrait) { mutableFloatStateOf(-1f) }
  var requestedOffsetX by rememberSaveable(isPortrait) { mutableFloatStateOf(-1f) }
  var requestedOffsetY by rememberSaveable(isPortrait) { mutableFloatStateOf(-1f) }

  if (!state.overlayVisible) return

  BoxWithConstraints(modifier = modifier.fillMaxSize()) {
    val density = LocalDensity.current
    val edgeInset = 8.dp
    val availableWidth = (maxWidth - edgeInset * 2).coerceAtLeast(1.dp)
    val availableHeight = (maxHeight - edgeInset * 2).coerceAtLeast(1.dp)
    val minimumWidth = minOf(240.dp, availableWidth)
    val minimumHeight = minOf(180.dp, availableHeight)
    val defaultWidth =
      if (isPortrait) {
        (maxWidth * 0.92f).coerceIn(minimumWidth, availableWidth)
      } else {
        (maxWidth * 0.44f).coerceIn(minimumWidth, availableWidth)
      }
    val defaultHeight =
      if (isPortrait) {
        (maxHeight * 0.42f).coerceIn(minimumHeight, availableHeight)
      } else {
        (maxHeight * 0.74f).coerceIn(minimumHeight, availableHeight)
      }
    val panelWidth =
      (if (requestedWidthDp > 0f) requestedWidthDp.dp else defaultWidth)
        .coerceIn(minimumWidth, availableWidth)
    val panelHeight =
      (if (requestedHeightDp > 0f) requestedHeightDp.dp else defaultHeight)
        .coerceIn(minimumHeight, availableHeight)

    val parentWidthPx = constraints.maxWidth.toFloat()
    val parentHeightPx = constraints.maxHeight.toFloat()
    val insetPx = with(density) { edgeInset.toPx() }
    val panelWidthPx = with(density) { panelWidth.toPx() }
    val panelHeightPx = with(density) { panelHeight.toPx() }
    val latestPanelWidthPx by rememberUpdatedState(panelWidthPx)
    val latestPanelHeightPx by rememberUpdatedState(panelHeightPx)
    val minimumWidthPx = with(density) { minimumWidth.toPx() }
    val minimumHeightPx = with(density) { minimumHeight.toPx() }
    val maxOffsetX = (parentWidthPx - panelWidthPx - insetPx).coerceAtLeast(insetPx)
    val maxOffsetY = (parentHeightPx - panelHeightPx - insetPx).coerceAtLeast(insetPx)
    val defaultOffsetX =
      if (isPortrait) {
        ((parentWidthPx - panelWidthPx) / 2f).coerceIn(insetPx, maxOffsetX)
      } else {
        maxOffsetX
      }
    val defaultOffsetY =
      if (isPortrait) {
        (parentHeightPx - panelHeightPx - with(density) { 88.dp.toPx() })
          .coerceIn(insetPx, maxOffsetY)
      } else {
        ((parentHeightPx - panelHeightPx) / 2f).coerceIn(insetPx, maxOffsetY)
      }
    val panelOffsetX =
      (if (requestedOffsetX >= 0f) requestedOffsetX else defaultOffsetX)
        .coerceIn(insetPx, maxOffsetX)
    val panelOffsetY =
      (if (requestedOffsetY >= 0f) requestedOffsetY else defaultOffsetY)
        .coerceIn(insetPx, maxOffsetY)

    val moveModifier =
      if (state.expanded) {
        Modifier
      } else {
        Modifier.pointerInput(parentWidthPx, parentHeightPx, panelWidthPx, panelHeightPx) {
          var dragX = panelOffsetX
          var dragY = panelOffsetY
          detectDragGestures(
            onDragStart = {
              dragX =
                (if (requestedOffsetX >= 0f) requestedOffsetX else defaultOffsetX)
                  .coerceIn(insetPx, maxOffsetX)
              dragY =
                (if (requestedOffsetY >= 0f) requestedOffsetY else defaultOffsetY)
                  .coerceIn(insetPx, maxOffsetY)
            },
          ) { change, dragAmount ->
            change.consume()
            dragX = (dragX + dragAmount.x).coerceIn(insetPx, maxOffsetX)
            dragY = (dragY + dragAmount.y).coerceIn(insetPx, maxOffsetY)
            requestedOffsetX = dragX
            requestedOffsetY = dragY
          }
        }
      }

    val panelModifier =
      if (state.expanded) {
        Modifier
          .fillMaxSize()
          .padding(12.dp)
      } else {
        Modifier
          .offset { IntOffset(panelOffsetX.roundToInt(), panelOffsetY.roundToInt()) }
          .size(panelWidth, panelHeight)
      }
    Surface(
      modifier = panelModifier.outsideShadow(RoundedCornerShape(8.dp), radius = 8.dp),
      shape = RoundedCornerShape(8.dp),
      color = Color.Black.copy(alpha = if (state.expanded) 0.92f else 0.76f),
      contentColor = Color.White,
      tonalElevation = 0.dp,
    ) {
      Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
          Row(
            modifier = Modifier.fillMaxWidth().padding(start = 6.dp, top = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Row(
              modifier = Modifier.weight(1f).then(moveModifier).padding(start = 4.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              if (!state.expanded) {
                Icon(
                  imageVector = Icons.RoundedFilled.DragHandle,
                  contentDescription = null,
                  tint = Color.White.copy(alpha = 0.68f),
                  modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
              }
              Text(
                text =
                  stringResource(
                    if (state.tab == MediaScopeTab.Audio) R.string.scopes_audio_waveform else R.string.scopes_video,
                  ),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
              )
            }
            IconButton(onClick = viewModel::toggleMediaScopesExpanded) {
              Icon(Icons.RoundedFilled.ZoomOutMap, stringResource(R.string.scopes_expand))
            }
            IconButton(onClick = { viewModel.setMediaScopesOverlayVisible(false) }) {
              Icon(Icons.RoundedFilled.Close, stringResource(R.string.ui_close))
            }
          }

          when (state.tab) {
            MediaScopeTab.Audio ->
              AudioWaveformScope(
                viewModel = viewModel,
                audioTracks = audioTracks,
                durationSeconds = durationSeconds,
                modifier = Modifier.fillMaxSize(),
              )
            MediaScopeTab.Video ->
              VideoScope(
                mode = state.videoMode,
                resolution = state.analysisResolution,
                frameRate = state.frameRate,
                modifier = Modifier.fillMaxSize(),
              )
          }
        }

        if (!state.expanded) {
          Box(
            modifier =
              Modifier
                .align(Alignment.BottomEnd)
                .size(48.dp)
                .semantics { contentDescription = resizeDescription }
                .pointerInput(parentWidthPx, parentHeightPx, minimumWidthPx, minimumHeightPx) {
                  var resizeWidthPx = latestPanelWidthPx
                  var resizeHeightPx = latestPanelHeightPx
                  var resizeOriginX = panelOffsetX
                  var resizeOriginY = panelOffsetY
                  detectDragGestures(
                    onDragStart = {
                      resizeOriginX =
                        (if (requestedOffsetX >= 0f) requestedOffsetX else defaultOffsetX)
                          .coerceIn(insetPx, maxOffsetX)
                      resizeOriginY =
                        (if (requestedOffsetY >= 0f) requestedOffsetY else defaultOffsetY)
                          .coerceIn(insetPx, maxOffsetY)
                      resizeWidthPx = latestPanelWidthPx
                      resizeHeightPx = latestPanelHeightPx
                      requestedOffsetX = resizeOriginX
                      requestedOffsetY = resizeOriginY
                    },
                  ) { change, dragAmount ->
                    change.consume()
                    val maximumResizeWidth =
                      (parentWidthPx - resizeOriginX - insetPx).coerceAtLeast(minimumWidthPx)
                    val maximumResizeHeight =
                      (parentHeightPx - resizeOriginY - insetPx).coerceAtLeast(minimumHeightPx)
                    resizeWidthPx =
                      (resizeWidthPx + dragAmount.x).coerceIn(minimumWidthPx, maximumResizeWidth)
                    resizeHeightPx =
                      (resizeHeightPx + dragAmount.y).coerceIn(minimumHeightPx, maximumResizeHeight)
                    requestedWidthDp = with(density) { resizeWidthPx.toDp().value }
                    requestedHeightDp = with(density) { resizeHeightPx.toDp().value }
                  }
                },
            contentAlignment = Alignment.BottomEnd,
          ) {
            Canvas(modifier = Modifier.padding(end = 7.dp, bottom = 7.dp).size(18.dp)) {
              val gripColor = Color.White.copy(alpha = 0.62f)
              val strokeWidth = 1.5.dp.toPx()
              for (length in listOf(6.dp, 11.dp, 16.dp)) {
                val lengthPx = length.toPx()
                drawLine(
                  color = gripColor,
                  start = Offset(size.width - lengthPx, size.height),
                  end = Offset(size.width, size.height - lengthPx),
                  strokeWidth = strokeWidth,
                )
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun AudioWaveformScope(
  viewModel: PlayerViewModel,
  audioTracks: ImmutableList<TrackNode>,
  durationSeconds: Float,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val noTrackMessage = stringResource(R.string.scopes_no_audio_track)
  val unavailableMessage = stringResource(R.string.scopes_audio_unavailable)
  val path by PlaybackSession.propString["path"].collectAsState()
  val streamPath by PlaybackSession.propString["stream-open-filename"].collectAsState()
  val precisePosition by viewModel.precisePosition.collectAsState()
  val selectedTrack = audioTracks.firstOrNull(TrackNode::isSelected)
  val source =
    when {
      selectedTrack == null -> ""
      selectedTrack.external == true -> selectedTrack.externalFilename?.takeIf(String::isNotBlank).orEmpty()
      else -> path?.takeIf(String::isNotBlank) ?: streamPath?.takeIf(String::isNotBlank).orEmpty()
    }
  val audioTrackOrdinal =
    selectedTrack?.takeIf { it.external != true }?.let { selected ->
      audioTracks.filter { it.external != true }.indexOfFirst { it.id == selected.id }.coerceAtLeast(0)
    } ?: 0

  var loadState by remember(source, selectedTrack?.id, durationSeconds) {
    mutableStateOf<AudioWaveformLoadState>(AudioWaveformLoadState.Loading)
  }

  LaunchedEffect(source, selectedTrack?.id, audioTrackOrdinal, durationSeconds) {
    val track = selectedTrack
    if (track == null) {
      loadState = AudioWaveformLoadState.Error(noTrackMessage)
      return@LaunchedEffect
    }
    loadState = AudioWaveformLoadState.Loading
    loadState =
      try {
        val waveform =
          withContext(Dispatchers.IO) {
            AudioWaveformDecoder.decode(
              context = context.applicationContext,
              source = source,
              track = track,
              audioTrackOrdinal = audioTrackOrdinal,
              durationSeconds = durationSeconds,
              columnCount = 1_536,
            )
          }
        AudioWaveformLoadState.Ready(waveform)
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (_: Exception) {
        AudioWaveformLoadState.Error(unavailableMessage)
      }
  }

  when (val current = loadState) {
    AudioWaveformLoadState.Loading ->
      Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Row(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
          Text(stringResource(R.string.scopes_generating_waveform))
        }
      }

    is AudioWaveformLoadState.Error ->
      Box(modifier = modifier.padding(16.dp), contentAlignment = Alignment.Center) {
        Text(
          text = current.message,
          color = MaterialTheme.colorScheme.error,
          fontFamily = fontFamilyForText(current.message),
          textAlign = TextAlign.Center,
        )
      }

    is AudioWaveformLoadState.Ready -> {
      val waveform = current.data
      Column(
        modifier =
          modifier.pointerInput(waveform.durationSeconds) {
            detectTapGestures { offset ->
              if (size.width > 0 && waveform.durationSeconds > 0f) {
                val target = (offset.x / size.width).coerceIn(0f, 1f) * waveform.durationSeconds
                PlaybackSession.command("seek", target.toString(), "absolute+exact")
              }
            }
          },
      ) {
        waveform.channels.forEachIndexed { index, channel ->
          val channelColor =
            lerp(
              MaterialTheme.colorScheme.primary,
              MaterialTheme.colorScheme.tertiary,
              index / max(1f, waveform.channels.lastIndex.toFloat()),
            )
          Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Canvas(modifier = Modifier.fillMaxSize()) {
              val centerY = size.height / 2f
              drawLine(
                color = Color.White.copy(alpha = 0.14f),
                start = Offset(0f, centerY),
                end = Offset(size.width, centerY),
                strokeWidth = 1.dp.toPx(),
              )
              val columns = minOf(channel.minimum.size, channel.maximum.size)
              if (columns > 1) {
                val path = Path()
                for (column in 0 until columns) {
                  val x = column * size.width / (columns - 1)
                  val y = centerY - channel.maximum[column] * centerY * 0.92f
                  if (column == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                for (column in columns - 1 downTo 0) {
                  val x = column * size.width / (columns - 1)
                  val y = centerY - channel.minimum[column] * centerY * 0.92f
                  path.lineTo(x, y)
                }
                path.close()
                drawPath(path, color = channelColor.copy(alpha = 0.74f))
              }
              val positionFraction =
                if (waveform.durationSeconds > 0f) {
                  (precisePosition / waveform.durationSeconds).coerceIn(0f, 1f)
                } else {
                  0f
                }
              val playheadX = positionFraction * size.width
              drawLine(
                color = Color.White,
                start = Offset(playheadX, 0f),
                end = Offset(playheadX, size.height),
                strokeWidth = 1.5.dp.toPx(),
              )
              if (index < waveform.channels.lastIndex) {
                drawLine(
                  color = Color.White.copy(alpha = 0.18f),
                  start = Offset(0f, size.height - 0.5.dp.toPx()),
                  end = Offset(size.width, size.height - 0.5.dp.toPx()),
                  strokeWidth = 1.dp.toPx(),
                )
              }
            }
            Text(
              text = channel.label,
              style = MaterialTheme.typography.labelSmall,
              color = Color.White.copy(alpha = 0.72f),
              modifier = Modifier.align(Alignment.TopStart).padding(start = 5.dp, top = 2.dp),
            )
          }
        }
      }
    }
  }
}

@Composable
private fun VideoScope(
  mode: VideoScopeMode,
  resolution: Int,
  frameRate: Int,
  modifier: Modifier = Modifier,
) {
  val unavailableMessage = stringResource(R.string.scopes_video_unavailable)
  val paused by PlaybackSession.propBoolean["pause"].collectAsState()
  var scopeBitmap by remember(mode, resolution) { mutableStateOf<Bitmap?>(null) }
  var errorMessage by remember(mode, resolution) { mutableStateOf<String?>(null) }
  var loading by remember(mode, resolution) { mutableStateOf(true) }
  val displayedFrame = remember(mode, resolution) { AtomicReference<Bitmap?>() }

  DisposableEffect(mode, resolution) {
    onDispose { scopeBitmap?.takeUnless(Bitmap::isRecycled)?.recycle() }
  }

  LaunchedEffect(mode, resolution, frameRate, paused) {
    val frameIntervalMs = 1_000L / frameRate.coerceIn(5, 30)
    val pendingFrame = AtomicReference<Bitmap?>()
    try {
      do {
        currentCoroutineContext().ensureActive()
        val startedAt = System.nanoTime()
        withContext(Dispatchers.Default) {
          val source =
            PlaybackSession.grabThumbnail(resolution)
              ?: return@withContext
          try {
            pendingFrame.set(VideoScopeAnalyzer.analyze(source, mode))
          } finally {
            source.recycle()
          }
        }
        val analyzed = pendingFrame.getAndSet(null)
        if (analyzed == null) {
          errorMessage = unavailableMessage
        } else {
          val previous = scopeBitmap
          scopeBitmap = analyzed
          if (previous != null && previous !== displayedFrame.get()) {
            previous.takeUnless(Bitmap::isRecycled)?.recycle()
          }
          errorMessage = null
          loading = false
        }
        if (paused == true) break
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
        val cadenceDelayMs = (frameIntervalMs - elapsedMs).coerceAtLeast(1L)
        val processingBackoffMs = (elapsedMs / 3L).coerceAtLeast(1L)
        delay(maxOf(cadenceDelayMs, processingBackoffMs))
      } while (isActive)
    } finally {
      pendingFrame.getAndSet(null)?.takeUnless(Bitmap::isRecycled)?.recycle()
    }
  }

  Box(modifier = modifier, contentAlignment = Alignment.Center) {
    val bitmap = scopeBitmap
    if (bitmap != null && !bitmap.isRecycled) {
      DisposableEffect(bitmap) {
        displayedFrame.set(bitmap)
        onDispose {
          displayedFrame.compareAndSet(bitmap, null)
          bitmap.takeUnless(Bitmap::isRecycled)?.recycle()
        }
      }
      Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = null,
        contentScale = if (mode == VideoScopeMode.Vectorscope) ContentScale.Fit else ContentScale.FillBounds,
        modifier = Modifier.fillMaxSize(),
      )
      ScopeGraticule(mode = mode, modifier = Modifier.fillMaxSize())
    } else if (loading && errorMessage == null) {
      CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
    }

    errorMessage?.let { message ->
      Text(
        text = message,
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(16.dp),
      )
    }
  }
}

@Composable
private fun ScopeGraticule(
  mode: VideoScopeMode,
  modifier: Modifier = Modifier,
) {
  Canvas(modifier = modifier) {
    val gridColor = Color.White.copy(alpha = 0.22f)
    when (mode) {
      VideoScopeMode.LumaWaveform,
      VideoScopeMode.RgbyParade,
      -> {
        for (step in 0..4) {
          val y = size.height * step / 4f
          drawLine(gridColor, Offset(0f, y), Offset(size.width, y), 0.75.dp.toPx())
        }
        if (mode == VideoScopeMode.RgbyParade) {
          for (step in 1..3) {
            val x = size.width * step / 4f
            drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), 0.75.dp.toPx())
          }
        }
      }

      VideoScopeMode.Vectorscope -> {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = minOf(size.width, size.height) * 0.375f
        drawCircle(gridColor, radius, center, style = Stroke(width = 0.75.dp.toPx()))
        drawLine(gridColor, Offset(center.x, center.y - radius), Offset(center.x, center.y + radius), 0.75.dp.toPx())
        drawLine(gridColor, Offset(center.x - radius, center.y), Offset(center.x + radius, center.y), 0.75.dp.toPx())
      }
    }
  }
}
