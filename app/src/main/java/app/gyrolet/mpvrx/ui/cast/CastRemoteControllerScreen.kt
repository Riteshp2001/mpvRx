/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
 */

package app.gyrolet.mpvrx.ui.cast

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import app.gyrolet.mpvrx.ui.icons.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import app.gyrolet.mpvrx.ui.icons.AppIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gyrolet.mpvrx.ui.icons.Icons
import java.util.Locale
import kotlin.math.abs

private data class CastBitrateOption(val label: String, val bitrate: Int)

@Composable
fun CastRemoteControllerScreen(
  castState: CastSessionState,
  controller: CastPlaybackController,
  onBackClick: () -> Unit,
  onStopCasting: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var showSpeedDialog by remember { mutableStateOf(false) }
  var showBitrateDialog by remember { mutableStateOf(false) }

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(
        Brush.verticalGradient(
          colors = listOf(
            Color.Black.copy(alpha = 0.85f),
            Color.Black.copy(alpha = 0.95f),
          )
        )
      ),
  ) {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .windowInsetsPadding(WindowInsets.safeDrawing)
        .padding(horizontal = 24.dp, vertical = 16.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        IconButton(onClick = onBackClick, modifier = Modifier.align(Alignment.CenterVertically)) {
          Icon(Icons.RoundedFilled.KeyboardArrowDown, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(32.dp))
        }
        Spacer(modifier = Modifier.weight(1f))
        Text(
          text = castState.deviceName ?: "Cast",
          style = MaterialTheme.typography.labelSmall,
          color = Color.White.copy(alpha = 0.7f),
          letterSpacing = 1.sp,
        )
        Spacer(modifier = Modifier.weight(1f))
        IconButton(onClick = onStopCasting) {
          Icon(Icons.RoundedFilled.Cast, contentDescription = "Stop Casting", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
        }
      }

      Spacer(modifier = Modifier.height(32.dp))

      Column(
        modifier = Modifier.weight(1f).fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
      ) {
        Text(
          text = castState.title,
          style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
          color = Color.White,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(32.dp))

        CastSeekBar(castState = castState, controller = controller)

        Spacer(modifier = Modifier.height(24.dp))

        CastPlaybackControls(castState = castState, controller = controller)

        Spacer(modifier = Modifier.height(32.dp))

        CastOptionsRow(
          onShowSpeed = { showSpeedDialog = true },
          onShowBitrate = { showBitrateDialog = true },
        )
      }

      Spacer(modifier = Modifier.height(16.dp))

      CastVolumeSlider(castState = castState, controller = controller)
    }
  }

  if (showSpeedDialog) {
    CastSpeedDialog(
      currentSpeed = castState.playbackSpeed,
      onSpeedChange = { controller.setPlaybackSpeed(it) },
      onDismiss = { showSpeedDialog = false },
    )
  }

  if (showBitrateDialog) {
    CastBitrateDialog(
      castState = castState,
      controller = controller,
      onDismiss = { showBitrateDialog = false },
    )
  }
}

@Composable
private fun CastSeekBar(castState: CastSessionState, controller: CastPlaybackController) {
  var seekDragPosition by remember { mutableFloatStateOf(-1f) }

  val displayPosition = if (seekDragPosition >= 0f) seekDragPosition else castState.currentPosition.toFloat()
  val duration = castState.duration.toFloat().coerceAtLeast(1f)

  Slider(
    value = displayPosition,
    onValueChange = { seekDragPosition = it },
    onValueChangeFinished = {
      controller.seekTo(seekDragPosition.toLong())
      seekDragPosition = -1f
    },
    valueRange = 0f..duration,
    colors = SliderDefaults.colors(
      thumbColor = MaterialTheme.colorScheme.primary,
      activeTrackColor = MaterialTheme.colorScheme.primary,
      inactiveTrackColor = Color.White.copy(alpha = 0.2f),
    ),
    modifier = Modifier.fillMaxWidth(),
  )

  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Text(formatCastTime(displayPosition.toLong()), color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
    Text(formatCastTime((duration - displayPosition).toLong().coerceAtLeast(0)), color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
  }
}

@Composable
private fun CastPlaybackControls(castState: CastSessionState, controller: CastPlaybackController) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.Center,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    IconButton(onClick = { controller.seekTo((castState.currentPosition - 10_000).coerceAtLeast(0)) }, modifier = Modifier.size(56.dp)) {
      Icon(Icons.RoundedFilled.FastRewind, contentDescription = "Rewind 10s", tint = Color.White, modifier = Modifier.size(32.dp))
    }

    Spacer(modifier = Modifier.width(32.dp))

    Box(
      modifier = Modifier
        .size(72.dp)
        .clip(CircleShape)
        .background(MaterialTheme.colorScheme.primary)
        .clickable(
          interactionSource = remember { MutableInteractionSource() },
          indication = null,
          onClick = { if (castState.isPlaying) controller.pause() else controller.play() },
        ),
      contentAlignment = Alignment.Center,
    ) {
      if (castState.isBuffering) {
        CircularProgressIndicator(modifier = Modifier.size(40.dp), color = Color.White, strokeWidth = 3.dp)
      } else {
        Icon(
          imageVector = if (castState.isPlaying) Icons.RoundedFilled.Pause else Icons.RoundedFilled.PlayArrow,
          contentDescription = if (castState.isPlaying) "Pause" else "Play",
          tint = Color.White,
          modifier = Modifier.size(36.dp),
        )
      }
    }

    Spacer(modifier = Modifier.width(32.dp))

    IconButton(onClick = { controller.seekTo((castState.currentPosition + 30_000).coerceAtMost(castState.duration)) }, modifier = Modifier.size(56.dp)) {
      Icon(Icons.RoundedFilled.FastForward, contentDescription = "Forward 30s", tint = Color.White, modifier = Modifier.size(32.dp))
    }
  }
}

@Composable
private fun CastOptionsRow(
  onShowSpeed: () -> Unit,
  onShowBitrate: () -> Unit,
) {
  Row(
    modifier = Modifier
      .clip(RoundedCornerShape(50))
      .background(Color.White.copy(alpha = 0.1f))
      .padding(vertical = 4.dp, horizontal = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    CastOptionButton(icon = Icons.RoundedFilled.Speed, label = "Speed", onClick = onShowSpeed)
    CastOptionButton(icon = Icons.RoundedFilled.Settings, label = "Quality", onClick = onShowBitrate)
  }
}

@Composable
private fun CastOptionButton(icon: AppIcon, label: String, onClick: () -> Unit) {
  IconButton(onClick = onClick) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      Icon(imageVector = icon, contentDescription = label, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(24.dp))
      Text(label, color = Color.White.copy(alpha = 0.6f), fontSize = 9.sp)
    }
  }
}

@Composable
private fun CastVolumeSlider(castState: CastSessionState, controller: CastPlaybackController) {
  var volumeSliderValue by remember(castState.volume) { mutableFloatStateOf(castState.volume.toFloat()) }

  Row(
    modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 16.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Icon(
      imageVector = if (volumeSliderValue <= 0f) Icons.RoundedFilled.VolumeOff else if (volumeSliderValue < 0.5f) Icons.RoundedFilled.VolumeDown else Icons.RoundedFilled.VolumeUp,
      contentDescription = "Volume",
      tint = Color.White.copy(alpha = 0.6f),
      modifier = Modifier.size(24.dp),
    )
    Slider(
      value = volumeSliderValue,
      onValueChange = { newValue ->
        volumeSliderValue = newValue
        controller.setVolume(newValue.toDouble())
      },
      valueRange = 0f..1f,
      colors = SliderDefaults.colors(
        thumbColor = MaterialTheme.colorScheme.primary,
        activeTrackColor = MaterialTheme.colorScheme.primary,
        inactiveTrackColor = Color.White.copy(alpha = 0.2f),
      ),
      modifier = Modifier.weight(1f),
    )
    Text(
      text = "${(volumeSliderValue * 100).toInt()}%",
      color = Color.White.copy(alpha = 0.6f),
      fontSize = 12.sp,
      modifier = Modifier.width(36.dp),
    )
  }
}

@Composable
private fun CastSpeedDialog(currentSpeed: Float, onSpeedChange: (Float) -> Unit, onDismiss: () -> Unit) {
  val speeds = listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Playback Speed") },
    text = {
      Column {
        speeds.forEach { speed ->
          val isSelected = speed == currentSpeed
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clickable { onSpeedChange(speed); onDismiss() }
              .padding(vertical = 12.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            RadioButton(selected = isSelected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary))
            Spacer(modifier = Modifier.width(16.dp))
            Text(text = "${speed}x", color = MaterialTheme.colorScheme.onSurface)
          }
        }
      }
    },
    confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}

@Composable
private fun CastBitrateDialog(castState: CastSessionState, controller: CastPlaybackController, onDismiss: () -> Unit) {
  val bitrateOptions = listOf(
    CastBitrateOption("Auto", 0),
    CastBitrateOption("4K (50 Mbps)", 50_000_000),
    CastBitrateOption("4K (25 Mbps)", 25_000_000),
    CastBitrateOption("1080p (16 Mbps)", 16_000_000),
    CastBitrateOption("1080p (8 Mbps)", 8_000_000),
    CastBitrateOption("720p (4 Mbps)", 4_000_000),
  )

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Quality / Bitrate") },
    text = {
      Column {
        bitrateOptions.forEach { option ->
          val isSelected = option.bitrate == 0
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clickable { onDismiss() }
              .padding(vertical = 12.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            RadioButton(selected = isSelected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary))
            Spacer(modifier = Modifier.width(16.dp))
            Text(text = option.label, color = MaterialTheme.colorScheme.onSurface)
          }
        }
      }
    },
    confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}

private fun formatCastTime(timeMs: Long): String {
  val totalSeconds = abs(timeMs / 1000).toInt()
  val hours = totalSeconds / 3600
  val minutes = (totalSeconds % 3600) / 60
  val seconds = totalSeconds % 60
  return if (hours > 0) String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
  else String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
}
