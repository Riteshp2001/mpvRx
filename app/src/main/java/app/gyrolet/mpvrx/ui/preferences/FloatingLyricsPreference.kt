/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.preferences

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.AudioPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.ui.components.AppToast
import app.gyrolet.mpvrx.ui.components.AppToastType
import app.gyrolet.mpvrx.ui.preferences.components.SwitchPreference
import app.gyrolet.mpvrx.utils.logging.Log

@Composable
internal fun FloatingLyricsPreference(preferences: AudioPreferences) {
  val context = LocalContext.current
  val wanted by preferences.floatingLyricsEnabled.collectAsState()
  var allowed by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
  var asking by rememberSaveable { mutableStateOf(false) }

  fun warning(message: Int) {
    AppToast.makeText(context, message, AppToast.LENGTH_LONG).withType(AppToastType.Warning).show()
  }

  fun finishRequest() {
    allowed = Settings.canDrawOverlays(context)
    if (asking) {
      asking = false
      preferences.floatingLyricsEnabled.set(allowed)
      if (!allowed) warning(R.string.floating_lyrics_permission)
    } else if (preferences.floatingLyricsEnabled.get() && !allowed) {
      preferences.floatingLyricsEnabled.set(false)
      warning(R.string.floating_lyrics_permission)
    }
  }

  val permission =
    rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
      finishRequest()
    }
  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { finishRequest() }

  fun requestPermission() {
    asking = true
    val uri = Uri.parse("package:${context.packageName}")
    try {
      try {
        permission.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, uri))
      } catch (_: ActivityNotFoundException) {
        permission.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri))
      }
    } catch (error: ActivityNotFoundException) {
      asking = false
      Log.w("FloatingLyrics", "Overlay permission settings are unavailable", error)
      warning(R.string.floating_lyrics_unavailable)
    } catch (error: SecurityException) {
      asking = false
      Log.w("FloatingLyrics", "Overlay permission settings could not be opened", error)
      warning(R.string.floating_lyrics_unavailable)
    }
  }

  SwitchPreference(
    modifier = Modifier.settingsSearchTarget(R.string.pref_floating_lyrics_title),
    value = (wanted && allowed) || asking,
    onValueChange = { on ->
      if (!on) {
        asking = false
        preferences.floatingLyricsEnabled.set(false)
      } else if (Settings.canDrawOverlays(context)) {
        allowed = true
        preferences.floatingLyricsEnabled.set(true)
      } else {
        requestPermission()
      }
    },
    title = { Text(stringResource(R.string.pref_floating_lyrics_title)) },
    summary = {
      Text(
        stringResource(R.string.pref_floating_lyrics_summary),
        color = MaterialTheme.colorScheme.outline,
      )
    },
  )
}
