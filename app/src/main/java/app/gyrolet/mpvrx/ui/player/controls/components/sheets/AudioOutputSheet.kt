/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.player.controls.components.sheets

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRouter2
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.presentation.components.PlayerSheet
import app.gyrolet.mpvrx.presentation.components.PlayerSheetAction
import app.gyrolet.mpvrx.ui.components.AppSlider
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusHighlight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private const val TAG = "AudioOutputSheet"

private val musicAttributes = AudioAttributes.Builder()
  .setUsage(AudioAttributes.USAGE_MEDIA)
  .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
  .build()

@Composable
internal fun AudioOutputSheet(
  onDismissRequest: () -> Unit,
  onOpenAudioInfo: () -> Unit,
) {
  val context = LocalContext.current
  val settingsContext = LocalActivity.current ?: context
  val audioManager = remember(context) { checkNotNull(context.getSystemService(AudioManager::class.java)) }
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  val shape = MaterialTheme.shapes.large
  var routes by remember(audioManager) { mutableStateOf(emptyList<OutputDevice>()) }
  var volume by remember(audioManager) { mutableStateOf<MediaVolume?>(null) }
  var volumeWriteVersion by remember(audioManager) { mutableIntStateOf(0) }
  var outputPickerFailed by remember { mutableStateOf(false) }
  var volumeWriteFailed by remember { mutableStateOf(false) }

  LaunchedEffect(audioManager, lifecycle) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
      var canReadRoutes = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
      while (true) {
        val version = volumeWriteVersion
        val (currentRoutes, currentVolume) = withContext(Dispatchers.IO) {
          val devices = if (canReadRoutes && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
              audioManager.getAudioDevicesForAttributes(musicAttributes).distinctBy { it.id }.map {
                OutputDevice(it.type, it.productName.toString().trim())
              }
            } catch (error: SecurityException) {
              Log.w(TAG, "Android did not expose the current media route", error)
              null
            }
          } else null
          devices to audioManager.readMediaVolume()
        }
        if (currentRoutes == null) canReadRoutes = false
        routes = currentRoutes.orEmpty()
        // A read begun before the last slider change must not move the thumb backwards.
        if (version == volumeWriteVersion) volume = currentVolume
        delay(500L)
      }
    }
  }

  PlayerSheet(
    title = stringResource(R.string.pref_audio_output_title),
    onDismissRequest = onDismissRequest,
    actions = { PlayerSheetAction(Icons.RoundedFilled.Close, stringResource(R.string.ui_close), onDismissRequest) },
  ) {
    Column(
      modifier = Modifier.fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      if (routes.isEmpty()) {
        Surface(shape = shape, color = playerSheetSelectionColor(false)) {
          OutputRow(
            title = stringResource(R.string.audio_output_system_managed),
            icon = { AudioOutputGlyph() },
          )
        }
      } else {
        routes.forEach { device ->
          Surface(shape = shape, color = playerSheetSelectionColor(true)) {
            OutputRow(
              title = device.label(),
              subtitle = stringResource(R.string.audio_output_current_device),
              icon = { Icon(device.icon, contentDescription = null, modifier = Modifier.size(20.dp)) },
              trailing = { Icon(Icons.RoundedFilled.Check, contentDescription = null, modifier = Modifier.size(20.dp)) },
            )
          }
        }
      }

      Surface(
        onClick = { outputPickerFailed = !openAndroidOutputPicker(settingsContext) },
        shape = shape,
        color = playerSheetSelectionColor(false),
        modifier = Modifier.tvFocusHighlight(shape),
      ) {
        OutputRow(
          title = stringResource(R.string.audio_output_change_device),
          subtitle = stringResource(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) R.string.audio_output_android_picker
            else R.string.audio_output_android_settings,
          ),
          icon = { AudioOutputGlyph() },
          trailing = { Icon(Icons.RoundedFilled.ChevronRight, contentDescription = null, modifier = Modifier.size(20.dp)) },
        )
      }
      if (outputPickerFailed) {
        Text(stringResource(R.string.audio_output_picker_unavailable), color = MaterialTheme.colorScheme.error)
      }

      Surface(shape = shape, color = playerSheetSelectionColor(false)) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          val currentVolume = volume
          val label = stringResource(R.string.compressor_volume)
          Icon(
            if (currentVolume?.fraction == 0f) Icons.RoundedFilled.VolumeOff else Icons.RoundedFilled.VolumeUp,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
          )
          AppSlider(
            value = currentVolume?.fraction ?: 0f,
            enabled = currentVolume?.adjustable == true && !volumeWriteFailed,
            onValueChange = { fraction ->
              if (currentVolume != null && currentVolume.adjustable) {
                val target = currentVolume.min + (fraction.coerceIn(0f, 1f) * currentVolume.range).roundToInt()
                if (target != currentVolume.level) {
                  try {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                    volumeWriteVersion++
                    volume = currentVolume.copy(level = target)
                  } catch (error: SecurityException) {
                    Log.w(TAG, "Android rejected the media volume change", error)
                    volumeWriteFailed = true
                  }
                }
              }
            },
            modifier = Modifier.weight(1f).semantics { contentDescription = label },
          )
        }
      }
      if (volumeWriteFailed) {
        Text(stringResource(R.string.audio_output_volume_unavailable), color = MaterialTheme.colorScheme.error)
      } else if (volume?.adjustable == false) {
        Text(stringResource(R.string.audio_output_volume_fixed), style = MaterialTheme.typography.bodySmall)
      }

      Surface(
        onClick = onOpenAudioInfo,
        shape = shape,
        color = playerSheetSelectionColor(false),
        modifier = Modifier.tvFocusHighlight(shape),
      ) {
        OutputRow(
          title = stringResource(R.string.audio_information_title),
          icon = { Icon(Icons.RoundedFilled.Equalizer, contentDescription = null, modifier = Modifier.size(20.dp)) },
          trailing = { Icon(Icons.RoundedFilled.ChevronRight, contentDescription = null, modifier = Modifier.size(20.dp)) },
        )
      }
    }
  }
}

@Composable
internal fun AudioOutputGlyph(
  modifier: Modifier = Modifier,
  label: String? = null,
  tint: Color = LocalContentColor.current,
) {
  Icon(Icons.RoundedFilled.MediaOutput, contentDescription = label, modifier = modifier, tint = tint)
}

@Composable
private fun OutputRow(
  title: String,
  icon: @Composable () -> Unit,
  subtitle: String? = null,
  trailing: @Composable () -> Unit = {},
) {
  Row(
    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 14.dp, vertical = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
      Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) { icon() }
    }
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
      subtitle?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
    trailing()
  }
}

private data class OutputDevice(val type: Int, val name: String) {
  @Composable
  fun label(): String = when (type) {
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE ->
      stringResource(R.string.audio_output_device_speakers)
    else -> name.ifBlank { stringResource(R.string.audio_output_system_managed) }
  }

  val icon
    get() = when (type) {
      AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
      AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET -> Icons.RoundedFilled.Headset
      AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY, AudioDeviceInfo.TYPE_USB_HEADSET -> Icons.RoundedFilled.Usb
      AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC -> Icons.RoundedFilled.Tv
      else -> Icons.RoundedFilled.VolumeUp
    }
}

private data class MediaVolume(val level: Int, val min: Int, val max: Int, val fixed: Boolean) {
  val range: Int get() = max - min
  val adjustable: Boolean get() = !fixed && range > 0
  val fraction: Float get() = if (range > 0) ((level - min).toFloat() / range).coerceIn(0f, 1f) else 0f
}

private fun AudioManager.readMediaVolume(): MediaVolume = MediaVolume(
  level = getStreamVolume(AudioManager.STREAM_MUSIC),
  min = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) getStreamMinVolume(AudioManager.STREAM_MUSIC) else 0,
  max = getStreamMaxVolume(AudioManager.STREAM_MUSIC),
  fixed = isVolumeFixed,
)

private fun openAndroidOutputPicker(context: Context): Boolean {
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
    try {
      if (MediaRouter2.getInstance(context).showSystemOutputSwitcher()) return true
    } catch (error: SecurityException) {
      Log.w(TAG, "Android did not allow the media output picker", error)
    } catch (error: IllegalStateException) {
      Log.w(TAG, "The media output picker is unavailable", error)
    }
  }
  for (action in listOf(Settings.ACTION_BLUETOOTH_SETTINGS, Settings.ACTION_SOUND_SETTINGS)) {
    try {
      context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      return true
    } catch (error: ActivityNotFoundException) {
      Log.d(TAG, "No Android settings activity for $action", error)
    } catch (error: SecurityException) {
      Log.w(TAG, "Android did not allow opening $action", error)
    }
  }
  return false
}
