/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.controls.components.sheets

import app.gyrolet.mpvrx.ui.player.PlaybackSession

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import app.gyrolet.mpvrx.ui.components.IconSwitch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.framecapture.FolderWriteResult
import app.gyrolet.mpvrx.domain.framecapture.FrameCapture
import app.gyrolet.mpvrx.domain.framecapture.FrameCaptureRepository
import app.gyrolet.mpvrx.domain.framecapture.SnapshotImageLoader
import app.gyrolet.mpvrx.preferences.PlayerPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.ui.framecapture.SnapshotFolderRow
import app.gyrolet.mpvrx.ui.framecapture.dialogs.SnapshotFolderNameDialog
import app.gyrolet.mpvrx.ui.framecapture.dialogs.SnapshotMoveTargetDialog
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.screenshot.FrameCaptureUnavailableException
import app.gyrolet.mpvrx.ui.player.screenshot.ScreenshotSaver
import app.gyrolet.mpvrx.ui.player.screenshot.ScreenshotSettings
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusHighlight
import app.gyrolet.mpvrx.ui.theme.spacing
import app.gyrolet.mpvrx.ui.utils.rememberAppHaptics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.compose.koinInject
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@Composable
fun FrameNavigationSheet(
  currentFrame: Int,
  totalFrames: Int,
  onUpdateFrameInfo: () -> Unit,
  onPause: () -> Unit,
  onUnpause: () -> Unit,
  onPauseUnpause: () -> Unit,
  onSeekToFrame: (Int, Boolean) -> Unit,
  onCancelFrameSeek: () -> Unit,
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val coroutineScope = rememberCoroutineScope()
  var isSnapshotLoading by remember { mutableStateOf(false) }
  var isSaveSnapshotLoading by remember { mutableStateOf(false) }
  var isFrameStepping by remember { mutableStateOf(false) }
  var pendingFrameSteps by remember { mutableIntStateOf(0) }
  var frameStepJob by remember { mutableStateOf<Job?>(null) }
  var saveFolderDialogOpen by remember { mutableStateOf(false) }
  var saveFolderNameDialogOpen by remember { mutableStateOf(false) }
  var saveFolderNameError by remember { mutableStateOf<String?>(null) }
  var snapshotFolders by remember { mutableStateOf<List<SnapshotFolderRow>>(emptyList()) }
  val playerPreferences: PlayerPreferences = koinInject()
  val frameCaptureRepository: FrameCaptureRepository = koinInject()
  val includeSubtitlesPrefState by playerPreferences.includeSubtitlesInSnapshot.collectAsState()
  var includeSubtitlesInSnapshot by remember { mutableStateOf(includeSubtitlesPrefState) }
  LaunchedEffect(includeSubtitlesPrefState) {
    includeSubtitlesInSnapshot = includeSubtitlesPrefState
  }

  /**
   * Captures the frame and records it against [targetFolderId], or the snapshot library's root when
   * that is null. Split out of the button's callback because the save dialog, the "new folder" path
   * and the abort path all end up here with a different destination.
   */
  fun saveSnapshotTo(targetFolderId: Long?) {
    coroutineScope.launch {
      isSaveSnapshotLoading = true
      try {
        playerPreferences.lastSnapshotFolderId.set(targetFolderId ?: PlayerPreferences.NO_SNAPSHOT_FOLDER)
        val result =
          withContext(Dispatchers.IO) {
            ScreenshotSaver.saveLosslessToGallery(
              context = context,
              includeSubtitles = includeSubtitlesInSnapshot,
            )
          }
        result
          .onSuccess { saved ->
            val positionSeconds = PlaybackSession.getPropertyDouble("time-pos") ?: 0.0
            val videoUri = PlaybackSession.persistableSourceUri()
            // Read from the file rather than from mpv: the saved PNG is what the mosaic tiles by,
            // and mpv's own width/height is the decoder's frame, before any rotation.
            val dimensions =
              withContext(Dispatchers.IO) {
                SnapshotImageLoader.readDimensions(
                  context = context,
                  imageUri = saved.uri?.toString(),
                  imagePath = saved.file?.absolutePath,
                )
              }
            val written =
              runCatching {
                withContext(Dispatchers.IO) {
                  frameCaptureRepository.record(
                    FrameCapture(
                      id = 0L,
                      imageUri = saved.uri?.toString(),
                      imagePath = saved.file?.absolutePath,
                      videoUri = videoUri,
                      videoPath = videoUri.takeIf { it.startsWith("/") },
                      videoTitle =
                        PlaybackSession.getPropertyString("media-title")
                          ?: PlaybackSession.getPropertyString("filename")
                          ?: videoUri.substringAfterLast('/'),
                      positionMs = (positionSeconds * 1000.0).toLong().coerceAtLeast(0L),
                      capturedAt = System.currentTimeMillis(),
                      folderId = targetFolderId,
                      imageWidth = dimensions?.width,
                      imageHeight = dimensions?.height,
                    ),
                  )
                }
              }.isSuccess

            val message =
              if (written) {
                context.getString(R.string.snapshot_saved)
              } else {
                context.getString(R.string.snapshot_database_write_failed)
              }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
          }.onFailure { error ->
            val failureMessage =
              if (error is FrameCaptureUnavailableException) {
                context.getString(R.string.snapshot_no_frame)
              } else {
                context.getString(
                  R.string.toast_failed_to_save_snapshot,
                  error.message ?: context.getString(R.string.generic_unknown_error),
                )
              }
            Toast.makeText(context, failureMessage, Toast.LENGTH_LONG).show()
          }
      } finally {
        isSaveSnapshotLoading = false
      }
    }
  }

  // Use rememberUpdatedState for lambda parameters used in effects
  val currentOnPause by rememberUpdatedState(onPause)
  val currentOnUnpause by rememberUpdatedState(onUnpause)
  val currentOnUpdateFrameInfo by rememberUpdatedState(onUpdateFrameInfo)

  // Use the same logic as PlayerControls for pause state
  val paused by PlaybackSession.propBoolean["pause"].collectAsState()
  val isPaused = paused ?: PlaybackSession.state.value.paused

  // Remember the initial pause state when the sheet opens
  val wasPausedInitially = remember { isPaused }
  val currentIsPaused by rememberUpdatedState(isPaused)

  // Use the same logic as PlayerControls for position and duration
  val position by PlaybackSession.propDouble["time-pos"].collectAsState()
  val duration by PlaybackSession.propDouble["duration"].collectAsState()
  val pos = position ?: 0.0
  val dur = duration ?: 0.0

  // Format timestamp based on current position
  val timestamp =
    remember(pos, dur, currentFrame, totalFrames) {
      val precisePositionSeconds =
        if (dur > 0.0 && totalFrames > 0) {
          currentFrame.toDouble() * dur / totalFrames
        } else {
          pos
        }
      formatFrameTimestamp(precisePositionSeconds)
    }

  // Pause playback when the sheet opens
  LaunchedEffect(Unit) {
    currentOnPause()
  }

  LaunchedEffect(pos) {
    if (!isFrameStepping) currentOnUpdateFrameInfo()
  }

  // Only resume playback when closing if it wasn't paused initially
  DisposableEffect(Unit) {
    onDispose {
      frameStepJob?.cancel()
      onCancelFrameSeek()
      if (!wasPausedInitially) {
        currentOnUnpause()
      }
    }
  }

  fun enqueueFrameSteps(stepCount: Int) {
    if (stepCount == 0) return
    onCancelFrameSeek()
    pendingFrameSteps += stepCount
    if (frameStepJob?.isActive == true) return

    frameStepJob =
      coroutineScope.launch {
        isFrameStepping = true
        try {
          if (!currentIsPaused) {
            currentOnPause()
            withTimeoutOrNull(FRAME_PAUSE_TIMEOUT_MS) {
              while (PlaybackSession.getPropertyBoolean("pause") != true) {
                delay(FRAME_PAUSE_POLL_INTERVAL_MS)
              }
            }
          }
          while (pendingFrameSteps != 0) {
            val direction = if (pendingFrameSteps > 0) 1 else -1
            pendingFrameSteps -= direction
            PlaybackSession.command(
              "no-osd",
              if (direction > 0) "frame-step" else "frame-back-step",
            )
            delay(FRAME_STEP_INTERVAL_MS)
            currentOnUpdateFrameInfo()
          }
        } finally {
          pendingFrameSteps = 0
          isFrameStepping = false
        }
      }
  }

  FrameReviewOverlay(
    currentFrame = currentFrame,
    totalFrames = totalFrames,
    timestamp = timestamp,
    duration = dur.toFloat(),
    position = pos.toFloat(),
    isPaused = isPaused,
    isSnapshotLoading = isSnapshotLoading,
    isFrameStepping = isFrameStepping,
    includeSubtitles = includeSubtitlesInSnapshot,
    onFrameSteps = ::enqueueFrameSteps,
    onPlayPause = {
      frameStepJob?.cancel()
      onCancelFrameSeek()
      pendingFrameSteps = 0
      isFrameStepping = false
      onPauseUnpause()
    },
    onSnapshot = {
      coroutineScope.launch {
        isSnapshotLoading = true
        try {
          val result =
            withContext(Dispatchers.IO) {
              ScreenshotSaver.save(
                context = context,
                settings = ScreenshotSettings.fromPreferences(playerPreferences),
                includeSubtitles = includeSubtitlesInSnapshot,
              )
            }
          result
            .onSuccess {
              Toast
                .makeText(
                  context,
                  context.getString(R.string.player_sheets_frame_navigation_snapshot_saved),
                  Toast.LENGTH_SHORT,
                ).show()
            }.onFailure { error ->
              Toast
                .makeText(
                  context,
                  context.getString(
                    R.string.toast_failed_to_save_snapshot,
                    error.message ?: context.getString(R.string.generic_unknown_error),
                  ),
                  Toast.LENGTH_LONG,
                ).show()
            }
        } finally {
          isSnapshotLoading = false
        }
      }
    },
    onSaveSnapshot = {
      coroutineScope.launch {
        // Read the folder list once, before the dialog opens: this is a one-shot choice, not a view
        // that has to stay live. The picker shows names only, so the count is a placeholder rather
        // than something worth joining the whole capture list for.
        snapshotFolders =
          withContext(Dispatchers.IO) {
            frameCaptureRepository.observeFolders().first().map { folder ->
              SnapshotFolderRow(id = folder.id, name = folder.name, captureCount = 0)
            }
          }
        saveFolderDialogOpen = true
      }
    },
    isSaveSnapshotLoading = isSaveSnapshotLoading,
    onSeekToFrame = { targetFrame, finished ->
      frameStepJob?.cancel()
      pendingFrameSteps = 0
      isFrameStepping = false
      onSeekToFrame(targetFrame, finished)
    },
    onIncludeSubtitlesChanged = { checked ->
      includeSubtitlesInSnapshot = checked
      coroutineScope.launch {
        playerPreferences.includeSubtitlesInSnapshot.set(checked)
      }
    },
    onDismissRequest = onDismissRequest,
    modifier = modifier,
  )

  if (saveFolderDialogOpen) {
    SnapshotMoveTargetDialog(
      folders = snapshotFolders,
      confirmLabel = stringResource(R.string.ui_save),
      preselectedFolderId =
        playerPreferences.lastSnapshotFolderId.get()
          .takeIf { it != PlayerPreferences.NO_SNAPSHOT_FOLDER }
          // A folder deleted since the last save drops out, so the dialog falls back to the root
          // instead of pointing at a folder that no longer exists.
          ?.takeIf { id -> snapshotFolders.any { it.id == id } },
      onConfirm = { targetFolderId ->
        saveFolderDialogOpen = false
        saveSnapshotTo(targetFolderId)
      },
      onCreateFolder = {
        saveFolderDialogOpen = false
        saveFolderNameError = null
        saveFolderNameDialogOpen = true
      },
      onDismiss = { saveFolderDialogOpen = false },
    )
  }

  if (saveFolderNameDialogOpen) {
    SnapshotFolderNameDialog(
      title = stringResource(R.string.snapshot_create_folder_title),
      initialName = "",
      errorMessage = saveFolderNameError,
      confirmLabel = stringResource(R.string.snapshot_folder_create),
      onConfirm = { name ->
        saveFolderNameError = null
        coroutineScope.launch {
          when (val result = withContext(Dispatchers.IO) { frameCaptureRepository.createFolder(name) }) {
            is FolderWriteResult.Ok -> {
              saveFolderNameDialogOpen = false
              // The folder was made for this capture, so the capture goes straight into it.
              saveSnapshotTo(result.id)
            }
            FolderWriteResult.BlankName ->
              saveFolderNameError = context.getString(R.string.snapshot_folder_name_blank)
            FolderWriteResult.DuplicateName ->
              saveFolderNameError = context.getString(R.string.snapshot_folder_name_taken)
          }
        }
      },
      onDismiss = {
        saveFolderNameDialogOpen = false
        saveFolderNameError = null
      },
    )
  }
}

@Composable
private fun FrameReviewOverlay(
  currentFrame: Int,
  totalFrames: Int,
  timestamp: String,
  duration: Float,
  position: Float,
  isPaused: Boolean,
  isSnapshotLoading: Boolean,
  isSaveSnapshotLoading: Boolean,
  isFrameStepping: Boolean,
  includeSubtitles: Boolean,
  onFrameSteps: (Int) -> Unit,
  onPlayPause: () -> Unit,
  onSnapshot: () -> Unit,
  onSaveSnapshot: () -> Unit,
  onSeekToFrame: (Int, Boolean) -> Unit,
  onIncludeSubtitlesChanged: (Boolean) -> Unit,
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val configuration = LocalConfiguration.current
  val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp
  val density = LocalDensity.current
  val haptic = rememberAppHaptics()
  val pixelsPerFrame = with(density) { FrameSwipeDistancePerFrame.toPx() }
  var accumulatedDrag by remember { mutableFloatStateOf(0f) }
  var requestedFrameDelta by remember { mutableIntStateOf(0) }
  var frameDeltaVisible by remember { mutableStateOf(false) }
  var frameDeltaRevision by remember { mutableIntStateOf(0) }
  var sliderStartFrame by remember { mutableIntStateOf(0) }
  var isScrubbing by remember { mutableStateOf(false) }
  var userSliderFrame by remember { mutableIntStateOf(0) }
  var settlingFrame by remember { mutableStateOf<Int?>(null) }
  var isSeeking by remember { mutableStateOf(false) }
  val lastFrame = (totalFrames - 1).coerceAtLeast(0)
  val actualFrame = currentFrame.coerceIn(0, lastFrame)
  val animatedFrame by animateFloatAsState(
    targetValue = actualFrame.toFloat(),
    animationSpec = tween(durationMillis = 140),
    label = "FrameReviewFrameSeekbar",
  )
  val displayedFrame = if (isSeeking) userSliderFrame else settlingFrame ?: actualFrame
  val seekbarFrame = if (isSeeking) userSliderFrame.toFloat() else settlingFrame?.toFloat() ?: animatedFrame
  val displayedTimestamp =
    remember(displayedFrame, duration, totalFrames, position, timestamp) {
      if (duration > 0.0 && totalFrames > 0) {
        formatFrameTimestamp(displayedFrame.toDouble() * duration / totalFrames)
      } else {
        timestamp
      }
    }

  LaunchedEffect(currentFrame, settlingFrame) {
    if (settlingFrame == currentFrame) settlingFrame = null
  }
  LaunchedEffect(settlingFrame) {
    if (settlingFrame != null) {
      delay(FRAME_SEEK_UI_SETTLE_TIMEOUT_MS)
      settlingFrame = null
    }
  }
  LaunchedEffect(frameDeltaRevision, isScrubbing, isFrameStepping, isSeeking) {
    if (!isScrubbing && !isFrameStepping && !isSeeking) {
      delay(1_500L)
      frameDeltaVisible = false
    }
  }

  fun showFrameDelta(delta: Int) {
    requestedFrameDelta = delta
    frameDeltaVisible = delta != 0
    frameDeltaRevision++
  }

  BackHandler(onBack = onDismissRequest)

  Box(
    modifier = modifier.fillMaxSize(),
  ) {
    Box(
      modifier =
        Modifier
          .fillMaxSize()
          .padding(bottom = if (isLandscape) 180.dp else 220.dp)
          .pointerInput(pixelsPerFrame) {
            detectHorizontalDragGestures(
              onDragStart = {
                accumulatedDrag = 0f
                requestedFrameDelta = 0
                frameDeltaVisible = false
                isScrubbing = true
              },
              onDragCancel = {
                accumulatedDrag = 0f
                isScrubbing = false
              },
              onDragEnd = {
                accumulatedDrag = 0f
                isScrubbing = false
              },
              onHorizontalDrag = { change, dragAmount ->
                change.consume()
                accumulatedDrag += dragAmount
                val steps = (accumulatedDrag / pixelsPerFrame).toInt()
                if (steps != 0) {
                  accumulatedDrag -= steps * pixelsPerFrame
                  showFrameDelta(requestedFrameDelta + steps)
                  settlingFrame = null
                  haptic.tick()
                  onFrameSteps(steps)
                }
              },
            )
          },
    )

    Row(
      modifier =
        Modifier
          .align(Alignment.TopCenter)
          .fillMaxWidth()
          .windowInsetsPadding(WindowInsets.statusBars)
          .padding(horizontal = 16.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.72f),
        contentColor = MaterialTheme.colorScheme.onSurface,
      ) {
        Text(
          text = stringResource(R.string.player_sheets_frame_navigation_title),
          style = MaterialTheme.typography.labelLarge,
          modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
        )
      }
      Spacer(Modifier.weight(1f))
      Surface(
        onClick = onDismissRequest,
        modifier = Modifier.size(48.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.72f),
        contentColor = MaterialTheme.colorScheme.onSurface,
      ) {
        Box(contentAlignment = Alignment.Center) {
          Icon(
            Icons.RoundedFilled.Close,
            stringResource(R.string.generic_cancel),
            modifier = Modifier.size(24.dp),
          )
        }
      }
    }

    Surface(
      modifier =
        Modifier
          .align(Alignment.BottomCenter)
          .fillMaxWidth()
          .pointerInput(Unit) {
            awaitPointerEventScope {
              while (true) awaitPointerEvent()
            }
          }.windowInsetsPadding(WindowInsets.navigationBars),
      color = Color.Transparent,
      contentColor = MaterialTheme.colorScheme.onSurface,
      tonalElevation = 0.dp,
      shadowElevation = 0.dp,
    ) {
      Column(
        modifier =
          Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Surface(
          modifier = Modifier.fillMaxWidth(),
          shape = MaterialTheme.shapes.extraLarge,
          color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.68f),
          contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
          if (isLandscape) {
            Row(
              modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              FrameInfoDisplay(
                currentFrame = displayedFrame,
                totalFrames = totalFrames,
                timestamp = displayedTimestamp,
                frameDelta = requestedFrameDelta,
                showFrameDelta = frameDeltaVisible,
                modifier = Modifier.weight(1f),
              )
              ControlButtons(
                onPreviousFrame = {
                  settlingFrame = null
                  showFrameDelta(if (actualFrame > 0) -1 else 0)
                  onFrameSteps(-1)
                },
                onPlayPause = {
                  settlingFrame = null
                  frameDeltaVisible = false
                  onPlayPause()
                },
                isPaused = isPaused,
                onNextFrame = {
                  settlingFrame = null
                  showFrameDelta(if (actualFrame < lastFrame) 1 else 0)
                  onFrameSteps(1)
                },
                onSnapshot = onSnapshot,
                onSaveSnapshot = onSaveSnapshot,
                isSnapshotLoading = isSnapshotLoading,
                isSaveSnapshotLoading = isSaveSnapshotLoading,
                buttonColors = frameReviewButtonColors(),
              )
              IncludeSubsToggle(
                includeSubs = includeSubtitles,
                setIncludeSubs = onIncludeSubtitlesChanged,
                modifier = Modifier.widthIn(min = 180.dp, max = 220.dp),
              )
            }
          } else {
            Column(
              modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
              verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              FrameInfoDisplay(
                currentFrame = displayedFrame,
                totalFrames = totalFrames,
                timestamp = displayedTimestamp,
                frameDelta = requestedFrameDelta,
                showFrameDelta = frameDeltaVisible,
              )
              IncludeSubsToggle(
                includeSubs = includeSubtitles,
                setIncludeSubs = onIncludeSubtitlesChanged,
                modifier = Modifier.fillMaxWidth(),
              )
              Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
              ) {
                ControlButtons(
                  onPreviousFrame = {
                    settlingFrame = null
                    showFrameDelta(if (actualFrame > 0) -1 else 0)
                    onFrameSteps(-1)
                  },
                  onPlayPause = {
                    settlingFrame = null
                    frameDeltaVisible = false
                    onPlayPause()
                  },
                  isPaused = isPaused,
                  onNextFrame = {
                    settlingFrame = null
                    showFrameDelta(if (actualFrame < lastFrame) 1 else 0)
                    onFrameSteps(1)
                  },
                  onSnapshot = onSnapshot,
                  onSaveSnapshot = onSaveSnapshot,
                  isSnapshotLoading = isSnapshotLoading,
                  isSaveSnapshotLoading = isSaveSnapshotLoading,
                  buttonColors = frameReviewButtonColors(),
                )
              }
            }
          }
        }

        Slider(
          value = seekbarFrame.coerceIn(0f, lastFrame.coerceAtLeast(1).toFloat()),
          onValueChange = { newValue ->
            val targetFrame = newValue.roundToInt().coerceIn(0, lastFrame)
            val targetChanged = !isSeeking || targetFrame != userSliderFrame
            if (!isSeeking) {
              sliderStartFrame = displayedFrame
              isSeeking = true
              settlingFrame = null
            }
            userSliderFrame = targetFrame
            if (targetChanged) {
              showFrameDelta(targetFrame - sliderStartFrame)
              onSeekToFrame(targetFrame, false)
            }
          },
          onValueChangeFinished = {
            if (!isSeeking) return@Slider
            settlingFrame = userSliderFrame
            isSeeking = false
            onSeekToFrame(userSliderFrame, true)
          },
          valueRange = 0f..lastFrame.coerceAtLeast(1).toFloat(),
          enabled = totalFrames > 1 && duration > 0.0 && !isSnapshotLoading && !isSaveSnapshotLoading,
          modifier =
            Modifier
              .fillMaxWidth()
              .tvFocusHighlight(
                MaterialTheme.shapes.small,
                enabled = totalFrames > 1 && duration > 0.0 && !isSnapshotLoading && !isSaveSnapshotLoading,
              ),
        )
      }
    }
  }
}

@Composable
private fun frameReviewButtonColors() =
  ButtonDefaults.buttonColors(
    containerColor = MaterialTheme.colorScheme.primary,
    contentColor = MaterialTheme.colorScheme.onPrimary,
    disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.38f),
    disabledContentColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.6f),
  )

private val FrameSwipeDistancePerFrame = 20.dp
private const val FRAME_STEP_INTERVAL_MS = 24L
private const val FRAME_PAUSE_TIMEOUT_MS = 250L
private const val FRAME_PAUSE_POLL_INTERVAL_MS = 10L
private const val FRAME_SEEK_UI_SETTLE_TIMEOUT_MS = 3_000L

private fun formatFrameTimestamp(positionSeconds: Double): String {
  val totalMilliseconds = (positionSeconds * 1_000).roundToLong().coerceAtLeast(0L)
  val totalSeconds = totalMilliseconds / 1_000
  val hours = totalSeconds / 3_600
  val minutes = (totalSeconds % 3_600) / 60
  val seconds = totalSeconds % 60
  val milliseconds = totalMilliseconds % 1_000
  return String.format(Locale.US, "%02d:%02d:%02d.%03d", hours, minutes, seconds, milliseconds)
}

@Composable
private fun FrameInfoDisplay(
  currentFrame: Int,
  totalFrames: Int,
  timestamp: String,
  frameDelta: Int,
  showFrameDelta: Boolean,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier,
    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
  ) {
    Row(
      horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text =
          androidx.compose.ui.res
            .stringResource(app.gyrolet.mpvrx.R.string.ui_frame),
        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.ExtraBold),
        color = MaterialTheme.colorScheme.tertiary,
      )
      Text(
        text =
          if (totalFrames > 0) {
            "$currentFrame / $totalFrames"
          } else {
            "$currentFrame"
          },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f, fill = false),
      )
      Box(Modifier.width(64.dp).heightIn(min = 24.dp), contentAlignment = Alignment.CenterStart) {
        androidx.compose.animation.AnimatedVisibility(visible = showFrameDelta, enter = fadeIn(), exit = fadeOut()) {
          val darkSurface = MaterialTheme.colorScheme.surfaceContainerHigh.luminance() < 0.5f
          Text(
            text = String.format(Locale.US, "%+d", frameDelta),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = if (frameDelta > 0) {
              if (darkSurface) Color(0xFF81C784) else Color(0xFF1B5E20)
            } else {
              if (darkSurface) Color(0xFFEF9A9A) else Color(0xFFB71C1C)
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
    }
    Row(
      horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text =
          androidx.compose.ui.res
            .stringResource(app.gyrolet.mpvrx.R.string.ui_timestamp),
        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.ExtraBold),
        color = MaterialTheme.colorScheme.tertiary,
      )
      Text(
        text = timestamp,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f, fill = false),
      )
    }
  }
}

@Composable
private fun ControlButtons(
  onPreviousFrame: () -> Unit,
  onPlayPause: () -> Unit,
  isPaused: Boolean,
  onNextFrame: () -> Unit,
  onSnapshot: () -> Unit,
  onSaveSnapshot: () -> Unit,
  isSnapshotLoading: Boolean,
  isSaveSnapshotLoading: Boolean,
  buttonColors: androidx.compose.material3.ButtonColors,
) {
  Row(
    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.smaller),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Button(
      onClick = onPreviousFrame,
      modifier = Modifier.size(56.dp),
      enabled = !isSnapshotLoading,
      colors = buttonColors,
      contentPadding = PaddingValues(0.dp),
    ) {
      Icon(
        Icons.RoundedFilled.FastRewind,
        contentDescription = stringResource(R.string.ui_previous_frame),
        modifier = Modifier.size(32.dp),
      )
    }

    Button(
      onClick = onPlayPause,
      modifier = Modifier.size(56.dp),
      enabled = !isSnapshotLoading,
      colors = buttonColors,
      contentPadding = PaddingValues(0.dp),
    ) {
      Icon(
        if (isPaused) Icons.RoundedFilled.PlayArrow else Icons.RoundedFilled.Pause,
        contentDescription = stringResource(R.string.pref_gesture_double_tap_play),
        modifier = Modifier.size(32.dp),
      )
    }

    Button(
      onClick = onNextFrame,
      modifier = Modifier.size(56.dp),
      enabled = !isSnapshotLoading,
      colors = buttonColors,
      contentPadding = PaddingValues(0.dp),
    ) {
      Icon(
        Icons.RoundedFilled.FastForward,
        contentDescription = stringResource(R.string.ui_next_frame),
        modifier = Modifier.size(32.dp),
      )
    }

    Button(
      onClick = onSnapshot,
      modifier = Modifier.size(56.dp),
      enabled = !isSnapshotLoading && !isSaveSnapshotLoading,
      colors = buttonColors,
      contentPadding = PaddingValues(0.dp),
    ) {
      if (isSnapshotLoading) {
        CircularProgressIndicator(
          modifier = Modifier.size(32.dp),
          strokeWidth = 2.dp,
          color = MaterialTheme.colorScheme.onPrimary,
        )
      } else {
        Icon(
          Icons.RoundedFilled.Aperture,
          contentDescription = stringResource(R.string.ui_take_screenshot),
          modifier = Modifier.size(32.dp),
        )
      }
    }

    Button(
      onClick = onSaveSnapshot,
      modifier = Modifier.size(56.dp),
      enabled = !isSnapshotLoading && !isSaveSnapshotLoading,
      colors = buttonColors,
      contentPadding = PaddingValues(0.dp),
    ) {
      if (isSaveSnapshotLoading) {
        CircularProgressIndicator(
          modifier = Modifier.size(32.dp),
          strokeWidth = 2.dp,
          color = MaterialTheme.colorScheme.onPrimary,
        )
      } else {
        Icon(
          Icons.RoundedFilled.Bookmarks,
          contentDescription = stringResource(R.string.snapshot_save_button),
          modifier = Modifier.size(32.dp),
        )
      }
    }
  }
}

@Composable
private fun IncludeSubsToggle(
  includeSubs: Boolean,
  setIncludeSubs: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier =
      modifier
        .padding(bottom = MaterialTheme.spacing.extraSmall),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.Start,
  ) {
    IconSwitch(
      checked = includeSubs,
      onCheckedChange = setIncludeSubs,
      modifier = Modifier.scale(0.8f),
    )
    Text(
      text = stringResource(R.string.player_sheets_frame_navigation_include_subtitles),
      style = MaterialTheme.typography.bodyMedium,
      modifier = Modifier.padding(start = MaterialTheme.spacing.smaller),
    )
  }
}
