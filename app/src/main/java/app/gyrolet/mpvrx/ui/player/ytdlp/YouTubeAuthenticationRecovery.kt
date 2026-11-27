// SPDX-License-Identifier: AGPL-3.0-or-later
package app.gyrolet.mpvrx.ui.player.ytdlp

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gyrolet.mpvrx.BuildConfig
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.YtdlPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.components.AppAlertDialog
import app.gyrolet.mpvrx.presentation.components.AppDialogTextButton
import app.gyrolet.mpvrx.ui.player.PlaybackPhase
import app.gyrolet.mpvrx.ui.player.PlaybackSession
import app.gyrolet.mpvrx.ui.utils.currentMpvConfigOverrideOptions
import app.gyrolet.mpvrx.utils.logging.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.io.IOException

@Composable
internal fun YouTubeAuthenticationRecovery(onRetry: (Long) -> Boolean) {
  if (!BuildConfig.ENABLE_YTDLP) return
  val session by PlaybackSession.state.collectAsStateWithLifecycle()
  var dismissedGeneration by rememberSaveable { mutableLongStateOf(-1L) }
  var busy by remember(session.generation) { mutableStateOf(false) }
  if (session.phase != PlaybackPhase.ERROR ||
    !session.youtubeAuthenticationRequired ||
    dismissedGeneration == session.generation
  ) {
    return
  }

  AppAlertDialog(
    onDismissRequest = { if (!busy) dismissedGeneration = session.generation },
    title = { Text(stringResource(R.string.ytdlp_auth_required_title)) },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Text(stringResource(R.string.ytdlp_auth_required_message))
        YtdlpCookieControls(
          retryAfterImport = true,
          onBusyChanged = { busy = it },
          onImported = { if (onRetry(session.generation)) dismissedGeneration = session.generation },
        )
      }
    },
    confirmButton = {
      AppDialogTextButton(
        enabled = !busy,
        onClick = { dismissedGeneration = session.generation },
      ) {
        Text(stringResource(R.string.generic_cancel))
      }
    },
  )
}

@Composable
internal fun YtdlpCookieControls(
  modifier: Modifier = Modifier,
  retryAfterImport: Boolean = false,
  onBusyChanged: (Boolean) -> Unit = {},
  onImported: () -> Unit = {},
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val preferences = koinInject<YtdlPreferences>()
  val configuredFile by preferences.cookiesFile.collectAsState()
  val rawOptions by preferences.customRawOptions.collectAsState()
  val ownedOptions = currentMpvConfigOverrideOptions()
  val cookiesOverridden =
    "ytdl-raw-options" in ownedOptions || YtdlpCookies.hasCookieOptionOverride(rawOptions)
  var busy by remember { mutableStateOf(false) }
  var operationError by remember { mutableStateOf<String?>(null) }

  fun runCookieOperation(operation: suspend () -> Unit) {
    if (busy) return
    busy = true
    onBusyChanged(true)
    operationError = null
    scope.launch {
      try {
        operation()
      } catch (error: InvalidYtdlpCookiesException) {
        operationError = context.getString(R.string.ytdlp_cookies_invalid)
      } catch (error: IOException) {
        Log.w("YtdlpCookies", "Could not update the private cookie file", error)
        operationError = context.getString(R.string.ytdlp_cookies_import_failed)
      } catch (error: SecurityException) {
        Log.w("YtdlpCookies", "Cookie document access was denied", error)
        operationError = context.getString(R.string.ytdlp_cookies_import_failed)
      } finally {
        busy = false
        onBusyChanged(false)
      }
    }
  }

  val picker =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      if (uri != null) {
        runCookieOperation {
          val file = withContext(Dispatchers.IO) { YtdlpCookies.importCookies(context, uri) }
          preferences.cookiesFile.set(file.absolutePath)
          onImported()
        }
      }
    }

  Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(
      stringResource(R.string.ytdlp_cookies_summary),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (configuredFile.isNotBlank()) {
      Text(
        stringResource(R.string.ytdlp_cookies_configured),
        style = MaterialTheme.typography.labelMedium,
      )
    }
    if (cookiesOverridden) {
      Text(
        stringResource(R.string.ytdlp_cookies_config_owned),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
      )
    }
    Button(
      modifier = Modifier.fillMaxWidth(),
      enabled = !busy && !cookiesOverridden,
      onClick = {
        operationError = null
        try {
          picker.launch(arrayOf("text/*", "application/octet-stream"))
        } catch (error: ActivityNotFoundException) {
          Log.w("YtdlpCookies", "No document picker is available", error)
          operationError = context.getString(R.string.ytdlp_cookies_import_failed)
        }
      },
    ) {
      Text(
        stringResource(
          if (retryAfterImport) R.string.ytdlp_cookies_import_retry else R.string.ytdlp_cookies_import,
        ),
      )
    }
    AppDialogTextButton(
      enabled = !busy,
      onClick = {
        try {
          context.startActivity(
            Intent(
              Intent.ACTION_VIEW,
              Uri.parse("https://github.com/yt-dlp/yt-dlp/wiki/Extractors#exporting-youtube-cookies"),
            ),
          )
        } catch (error: ActivityNotFoundException) {
          Log.w("YtdlpCookies", "No browser is available for cookie export instructions", error)
          operationError = context.getString(R.string.ytdlp_cookies_help_failed)
        }
      },
    ) {
      Text(stringResource(R.string.ytdlp_cookies_help))
    }
    if (configuredFile.isNotBlank()) {
      AppDialogTextButton(
        enabled = !busy,
        onClick = {
          runCookieOperation {
            withContext(Dispatchers.IO) { YtdlpCookies.clearManagedFile(context) }
            preferences.cookiesFile.set("")
          }
        },
      ) {
        Text(stringResource(R.string.generic_clear))
      }
    }
    if (busy) CircularProgressIndicator()
    operationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
  }
}
