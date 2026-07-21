package app.gyrolet.mpvrx.ui.player.controls.components.sheets

import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.PlayerPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.components.PlayerSheet
import app.gyrolet.mpvrx.ui.player.screenshot.ScreenshotSaver
import app.gyrolet.mpvrx.ui.player.screenshot.ScreenshotSettings
import app.gyrolet.mpvrx.ui.theme.spacing
import `is`.xyz.mpv.MPVLib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.util.Locale

@Composable
fun FrameNavigationSheet(
  currentFrame: Int,
  totalFrames: Int,
  onUpdateFrameInfo: () -> Unit,
  onPause: () -> Unit,
  onUnpause: () -> Unit,
  onPauseUnpause: () -> Unit,
  onSeekTo: (Int, Boolean) -> Unit,
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val coroutineScope = rememberCoroutineScope()
  var isSnapshotLoading by remember { mutableStateOf(false) }
  var isFrameStepping by remember { mutableStateOf(false) }
  val playerPreferences: PlayerPreferences = koinInject()
  val includeSubtitlesPrefState by playerPreferences.includeSubtitlesInSnapshot.collectAsState()
  var includeSubtitlesInSnapshot by remember { mutableStateOf(includeSubtitlesPrefState) }
  LaunchedEffect(includeSubtitlesPrefState) {
    includeSubtitlesInSnapshot = includeSubtitlesPrefState
  }

  // Use rememberUpdatedState for lambda parameters used in effects
  val currentOnPause by rememberUpdatedState(onPause)
  val currentOnUnpause by rememberUpdatedState(onUnpause)
  val currentOnUpdateFrameInfo by rememberUpdatedState(onUpdateFrameInfo)

  // Use the same logic as PlayerControls for pause state
  val paused by MPVLib.propBoolean["pause"].collectAsState()
  val isPaused = paused ?: false

  // Remember the initial pause state when the sheet opens
  val wasPausedInitially = remember { isPaused }

  // Use the same logic as PlayerControls for position and duration
  val position by MPVLib.propInt["time-pos"].collectAsState()
  val duration by MPVLib.propInt["duration"].collectAsState()
  val pos = position ?: 0
  val dur = duration ?: 0

  // Format timestamp based on current position
  val timestamp =
    remember(pos) {
      val hours = pos / 3600
      val minutes = (pos % 3600) / 60
      val seconds = pos % 60
      String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }

  // Pause playback when the sheet opens
  LaunchedEffect(Unit) {
    currentOnPause()
  }

  LaunchedEffect(pos, currentFrame, totalFrames) {
    currentOnUpdateFrameInfo()
  }

  // Only resume playback when closing if it wasn't paused initially
  DisposableEffect(Unit) {
    onDispose {
      if (!wasPausedInitially) {
        currentOnUnpause()
      }
    }
  }

  PlayerSheet(onDismissRequest = onDismissRequest) {
    FrameNavigationCard(
      onPreviousFrame = {
        if (!isFrameStepping) {
          coroutineScope.launch {
            // Pause if not already paused
            if (!isPaused) {
              currentOnPause()
              delay(50)
            }
            MPVLib.command("no-osd", "frame-back-step")
            delay(100)
            currentOnUpdateFrameInfo()
          }
        }
      },
      onNextFrame = {
        if (!isFrameStepping) {
          coroutineScope.launch {
            // Pause if not already paused
            if (!isPaused) {
              currentOnPause()
              delay(50)
            }
            MPVLib.command("no-osd", "frame-step")
            delay(100)
            currentOnUpdateFrameInfo()
          }
        }
      },
      onPlayPause = {
        coroutineScope.launch {
          onPauseUnpause()
        }
      },
      isPaused = isPaused,
      onSnapshot = {
        coroutineScope.launch {
          isSnapshotLoading = true
          try {
            val result = withContext(Dispatchers.IO) {
              ScreenshotSaver.save(
                context = context,
                settings = ScreenshotSettings.fromPreferences(playerPreferences),
                includeSubtitles = includeSubtitlesInSnapshot,
              )
            }
            result.onSuccess {
              Toast
                .makeText(
                  context,
                  context.getString(R.string.player_sheets_frame_navigation_snapshot_saved),
                  Toast.LENGTH_SHORT,
                ).show()
            }.onFailure { error ->
              Toast.makeText(context, context.getString(R.string.toast_failed_to_save_snapshot, error.message ?: context.getString(R.string.generic_unknown_error)), Toast.LENGTH_LONG).show()
            }
          } finally {
            isSnapshotLoading = false
          }
        }
      },
      isSnapshotLoading = isSnapshotLoading,
      isFrameStepping = isFrameStepping,
      currentFrame = currentFrame,
      totalFrames = totalFrames,
      timestamp = timestamp,
      duration = dur.toFloat(),
      pos = pos.toFloat(),
      onSeekTo = onSeekTo,
      title = {
        Column {
          FrameNavigationCardTitle(onClose = onDismissRequest)
          IncludeSubsToggle(
            includeSubs = includeSubtitlesInSnapshot,
            setIncludeSubs = { checked ->
              includeSubtitlesInSnapshot = checked
              coroutineScope.launch {
                playerPreferences.includeSubtitlesInSnapshot.set(checked)
              }
            },
          )
        }
      },
      modifier = modifier,
    )
  }
}

@Composable
private fun FrameNavigationCard(
  onPreviousFrame: () -> Unit,
  onNextFrame: () -> Unit,
  onPlayPause: () -> Unit,
  isPaused: Boolean,
  onSnapshot: () -> Unit,
  isSnapshotLoading: Boolean,
  isFrameStepping: Boolean,
  currentFrame: Int,
  totalFrames: Int,
  timestamp: String,
  duration: Float,
  pos: Float,
  onSeekTo: (Int, Boolean) -> Unit,
  title: @Composable () -> Unit,
  modifier: Modifier = Modifier,
) {
  val panelCardsColors: @Composable () -> CardColors = {
    val colors = CardDefaults.cardColors()
    colors.copy(
      containerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.6f),
      disabledContainerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.6f),
    )
  }

  val configuration = LocalConfiguration.current
  val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp

  // Seekbar state management
  var userSliderPosition by remember { mutableFloatStateOf(0f) }
  var isSeeking by remember { mutableStateOf(false) }
  val videoProgress = if (duration > 0) pos / duration else 0f

  val seekbarProgress =
    if (isSeeking) {
      userSliderPosition
    } else {
      videoProgress
    }
  val animatedProgress by animateFloatAsState(
    targetValue = seekbarProgress,
    label = "seekbar",
  )

  Card(
    modifier =
      modifier
        .widthIn(max = 520.dp)
        .animateContentSize(),
    colors = panelCardsColors(),
  ) {
    Column(
      Modifier
        .verticalScroll(rememberScrollState())
        .padding(
          horizontal = MaterialTheme.spacing.medium,
          vertical = MaterialTheme.spacing.smaller,
        ),
      verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.smaller),
    ) {
      title()

      // Video seeking slider
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Slider(
          value = animatedProgress.coerceIn(0f, 1f),
          onValueChange = { newValue ->
            if (!isSeeking) isSeeking = true
            userSliderPosition = newValue.coerceIn(0f, 1f)
            // Optional live-seek for responsiveness
            val newPosition = (userSliderPosition * duration).toInt()
            onSeekTo(newPosition, false)
          },
          onValueChangeFinished = {
            // Commit final seek and return control to player updates
            val finalPosition = (userSliderPosition * duration).toInt()
            onSeekTo(finalPosition, true)
            isSeeking = false
          },
          modifier = Modifier.fillMaxWidth(),
        )
      }

      // Define button colors to make disabled buttons look the same as enabled
      val buttonColors =
        ButtonDefaults.buttonColors(
          containerColor = MaterialTheme.colorScheme.primary,
          contentColor = MaterialTheme.colorScheme.onPrimary,
          disabledContainerColor = MaterialTheme.colorScheme.primary,
          disabledContentColor = MaterialTheme.colorScheme.onPrimary,
        )

      // Frame info, timestamp and navigation buttons
      if (isLandscape) {
        FrameNavigationLandscape(
          currentFrame = currentFrame,
          totalFrames = totalFrames,
          timestamp = timestamp,
          onPreviousFrame = onPreviousFrame,
          onPlayPause = onPlayPause,
          isPaused = isPaused,
          onNextFrame = onNextFrame,
          onSnapshot = onSnapshot,
          isSnapshotLoading = isSnapshotLoading,
          isFrameStepping = isFrameStepping,
          buttonColors = buttonColors,
        )
      } else {
        FrameNavigationPortrait(
          currentFrame = currentFrame,
          totalFrames = totalFrames,
          timestamp = timestamp,
          onPreviousFrame = onPreviousFrame,
          onPlayPause = onPlayPause,
          isPaused = isPaused,
          onNextFrame = onNextFrame,
          onSnapshot = onSnapshot,
          isSnapshotLoading = isSnapshotLoading,
          isFrameStepping = isFrameStepping,
          buttonColors = buttonColors,
        )
      }
    }
  }
}

@Composable
private fun FrameNavigationLandscape(
  currentFrame: Int,
  totalFrames: Int,
  timestamp: String,
  onPreviousFrame: () -> Unit,
  onPlayPause: () -> Unit,
  isPaused: Boolean,
  onNextFrame: () -> Unit,
  onSnapshot: () -> Unit,
  isSnapshotLoading: Boolean,
  isFrameStepping: Boolean,
  buttonColors: androidx.compose.material3.ButtonColors,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    // Left side: Frame and timestamp info
    FrameInfoDisplay(currentFrame, totalFrames, timestamp)

    // Right side: Control buttons
    ControlButtons(
      onPreviousFrame = onPreviousFrame,
      onPlayPause = onPlayPause,
      isPaused = isPaused,
      onNextFrame = onNextFrame,
      onSnapshot = onSnapshot,
      isSnapshotLoading = isSnapshotLoading,
      isFrameStepping = isFrameStepping,
      buttonColors = buttonColors,
    )
  }
}

@Composable
private fun FrameNavigationPortrait(
  currentFrame: Int,
  totalFrames: Int,
  timestamp: String,
  onPreviousFrame: () -> Unit,
  onPlayPause: () -> Unit,
  isPaused: Boolean,
  onNextFrame: () -> Unit,
  onSnapshot: () -> Unit,
  isSnapshotLoading: Boolean,
  isFrameStepping: Boolean,
  buttonColors: androidx.compose.material3.ButtonColors,
) {
  Column(
    modifier = Modifier.fillMaxWidth(),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
  ) {
    // Frame and timestamp info
    FrameInfoDisplay(currentFrame, totalFrames, timestamp)

    // Control buttons
    ControlButtons(
      onPreviousFrame = onPreviousFrame,
      onPlayPause = onPlayPause,
      isPaused = isPaused,
      onNextFrame = onNextFrame,
      onSnapshot = onSnapshot,
      isSnapshotLoading = isSnapshotLoading,
      isFrameStepping = isFrameStepping,
      buttonColors = buttonColors,
    )
  }
}

@Composable
private fun FrameInfoDisplay(
  currentFrame: Int,
  totalFrames: Int,
  timestamp: String,
) {
  Column(
    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
  ) {
    Row(
      horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(text = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_frame),
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
      )
    }
    Row(
      horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(text = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_timestamp),
        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.ExtraBold),
        color = MaterialTheme.colorScheme.tertiary,
      )
      Text(
        text = timestamp,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
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
  isSnapshotLoading: Boolean,
  isFrameStepping: Boolean,
  buttonColors: androidx.compose.material3.ButtonColors,
) {
  Row(
    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.smaller),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Button(
      onClick = onPreviousFrame,
      modifier = Modifier.size(56.dp),
      enabled = !isSnapshotLoading && !isFrameStepping,
      colors = buttonColors,
      contentPadding = PaddingValues(0.dp),
    ) {
      Icon(
        Icons.RoundedFilled.FastRewind,
        contentDescription = null,
        modifier = Modifier.size(32.dp),
      )
    }

    Button(
      onClick = onPlayPause,
      modifier = Modifier.size(56.dp),
      enabled = !isSnapshotLoading && !isFrameStepping,
      colors = buttonColors,
      contentPadding = PaddingValues(0.dp),
    ) {
      Icon(
        if (isPaused) Icons.RoundedFilled.PlayArrow else Icons.RoundedFilled.Pause,
        contentDescription = null,
        modifier = Modifier.size(32.dp),
      )
    }

    Button(
      onClick = onNextFrame,
      modifier = Modifier.size(56.dp),
      enabled = !isSnapshotLoading && !isFrameStepping,
      colors = buttonColors,
      contentPadding = PaddingValues(0.dp),
    ) {
      Icon(
        Icons.RoundedFilled.FastForward,
        contentDescription = null,
        modifier = Modifier.size(32.dp),
      )
    }

    Button(
      onClick = onSnapshot,
      modifier = Modifier.size(56.dp),
      enabled = !isSnapshotLoading && !isFrameStepping,
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
          contentDescription = null,
          modifier = Modifier.size(32.dp),
        )
      }
    }
  }
}

@Composable
private fun FrameNavigationCardTitle(
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      stringResource(R.string.player_sheets_frame_navigation_title),
      style = MaterialTheme.typography.headlineMedium,
    )
    IconButton(onClose) {
      Icon(
        Icons.RoundedFilled.Close,
        null,
        modifier = Modifier.size(32.dp),
      )
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
        .fillMaxWidth()
        .padding(bottom = MaterialTheme.spacing.extraSmall),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.Start,
  ) {
    Switch(
      checked = includeSubs,
      onCheckedChange = setIncludeSubs,
      modifier = Modifier.scale(0.8f),
      thumbContent = {
        Crossfade(
          targetState = includeSubs,
          animationSpec = tween(durationMillis = 200),
          label = "SwitchIconAnimation"
        ) { isChecked ->
          if (isChecked) {
            Icon(
              Icons.RoundedFilled.Check,
              contentDescription = null,
              modifier = Modifier.size(SwitchDefaults.IconSize),
              tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
          } else {
            Icon(
              Icons.RoundedFilled.Close,
              contentDescription = null,
              modifier = Modifier.size(SwitchDefaults.IconSize),
              tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
        }
      }
    )
    Text(
      text = stringResource(R.string.player_sheets_frame_navigation_include_subtitles),
      style = MaterialTheme.typography.bodyMedium,
      modifier = Modifier.padding(start = MaterialTheme.spacing.smaller),
    )
  }
}




