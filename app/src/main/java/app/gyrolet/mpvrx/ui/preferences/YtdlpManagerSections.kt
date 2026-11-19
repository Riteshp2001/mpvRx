/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.preferences

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.YtdlPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.ytdlp.YtdlPlaylistMode
import app.gyrolet.mpvrx.ui.player.ytdlp.YtdlpInstallationStatus
import app.gyrolet.mpvrx.ui.player.ytdlp.YtdlpManager
import app.gyrolet.mpvrx.ui.player.ytdlp.YtdlpReleaseChannel
import app.gyrolet.mpvrx.ui.player.ytdlp.YtdlpCookieControls
import app.gyrolet.mpvrx.ui.preferences.components.SwitchPreference
import app.gyrolet.mpvrx.ui.preferences.components.AppListPreference as ListPreference
import app.gyrolet.mpvrx.ui.theme.ConnectedLayout
import app.gyrolet.mpvrx.ui.theme.rememberConnectedShape
import app.gyrolet.mpvrx.ui.theme.spacing
import app.gyrolet.mpvrx.ui.utils.currentMpvConfigOverrideOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

/** Complete manager UI, shared by the Network page and the legacy deep-link screen. */
@Composable
internal fun YtdlpManagerSections(modifier: Modifier = Modifier) {
    if (!app.gyrolet.mpvrx.BuildConfig.ENABLE_YTDLP) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isRunning by remember { mutableStateOf(false) }
    var operationError by remember { mutableStateOf<String?>(null) }

    val ytdlPreferences = koinInject<YtdlPreferences>()
    val configOwnedOptions = currentMpvConfigOverrideOptions()
    val playbackOptionsEnabled = "ytdl-raw-options" !in configOwnedOptions
    val playlistMode by ytdlPreferences.playlistMode.collectAsState()
    val writeSubs by ytdlPreferences.writeSubs.collectAsState()
    val writeAutoSubs by ytdlPreferences.writeAutoSubs.collectAsState()
    val installationInfo by YtdlpManager.installationInfo.collectAsState()

    LaunchedEffect(Unit) {
      YtdlpManager.refreshInstallationInfo(context)
    }

    fun runOperation(operation: suspend ((String) -> Unit) -> Boolean) {
      if (isRunning) return
      isRunning = true
      operationError = null
      scope.launch {
        val output = StringBuilder()
        try {
          val succeeded = operation { message ->
            output.append(message)
            if (output.length > 8_192) output.delete(0, output.length - 8_192)
          }
          if (!succeeded) {
            operationError = output.toString().trim().ifBlank { context.getString(R.string.generic_unknown_error) }
          }
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (error: Exception) {
          operationError = error.message ?: context.getString(R.string.generic_unknown_error)
        } finally {
          isRunning = false
        }
      }
    }

    val isInstalled = installationInfo?.isInstalled == true
    val stableActionLabel =
      when {
        !isInstalled -> stringResource(R.string.ui_install_stable)
        installationInfo?.channel == YtdlpReleaseChannel.STABLE -> stringResource(R.string.ui_update_stable)
        else -> stringResource(R.string.ui_switch_to_stable)
      }
    val nightlyActionLabel =
      if (installationInfo?.channel == YtdlpReleaseChannel.NIGHTLY) {
        stringResource(R.string.ui_update_nightly)
      } else {
        stringResource(R.string.ui_switch_to_nightly)
      }

    ProvideExpressivePreferenceLocals {
      Column(
          modifier = modifier,
          verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
          PreferenceSectionHeader(position = PreferenceSectionPosition.First, 
            title = stringResource(R.string.ui_yt_dlp_streaming),
            topPadding = 12.dp,
            modifier = Modifier.settingsSearchTarget(R.string.ui_yt_dlp_manager),
          )
          YtdlpInstallationStatus(
            info = installationInfo,
            isRunning = isRunning,
            modifier = Modifier.padding(horizontal = 16.dp),
          )

          operationError?.let { error ->
            Column(
              modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
              verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Text(stringResource(R.string.ytdlp_install_failed), color = MaterialTheme.colorScheme.error)
              SelectionContainer {
                Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
              }
            }
          }

          PreferenceSectionHeader(
            position = PreferenceSectionPosition.Middle,
            title = stringResource(R.string.ui_release_channel),
            topPadding = 18.dp,
            modifier = Modifier.settingsSearchTarget(R.string.ui_release_channel),
          )

          ExpressivePreferenceGroup {
            SettingsNavigationRow(
              title = stableActionLabel,
              icon = Icons.RoundedFilled.Download,
              onClick = {
                runOperation { onLog ->
                  if (installationInfo?.channel == YtdlpReleaseChannel.STABLE) {
                    YtdlpManager.runUpdate(context, onLog)
                  } else {
                    YtdlpManager.runInstall(context, onLog)
                  }
                }
              },
              enabled = !isRunning,
              showChevron = false,
              shape = rememberConnectedShape(index = 0, itemCount = 2, layout = ConnectedLayout.Vertical),
            )
            SettingsNavigationRow(
              title = nightlyActionLabel,
              icon = Icons.RoundedFilled.Update,
              onClick = {
                runOperation { onLog -> YtdlpManager.runUpdateToNightly(context, onLog) }
              },
              enabled = !isRunning && isInstalled,
              showChevron = false,
              shape = rememberConnectedShape(index = 1, itemCount = 2, layout = ConnectedLayout.Vertical),
            )
          }

          PreferenceSectionHeader(
            position = PreferenceSectionPosition.Middle,
            title = stringResource(R.string.ytdlp_subtitles_language),
            modifier = Modifier.settingsSearchTarget(R.string.ytdlp_subtitles_language),
          )

          PreferenceCard {
            SwitchPreference(
              modifier = Modifier.settingsSearchTarget(R.string.ui_download_media_subtitles),
              value = writeSubs,
              enabled = playbackOptionsEnabled,
              onValueChange = { ytdlPreferences.writeSubs.set(it) },
              title = { Text(stringResource(R.string.ui_download_media_subtitles)) },
              summary = {
                Text(stringResource(R.string.ui_automatically_extract_and_load_physical_subtitle_tracks_from_sup))
              },
            )
            PreferenceDivider()
            SwitchPreference(
              modifier = Modifier.settingsSearchTarget(R.string.ui_include_auto_generated_subtitles),
              value = writeAutoSubs,
              enabled = playbackOptionsEnabled,
              onValueChange = { ytdlPreferences.writeAutoSubs.set(it) },
              title = { Text(stringResource(R.string.ui_include_auto_generated_subtitles)) },
              summary = {
                Text(stringResource(R.string.ui_fetch_auto_caption_tracks_e_g_youtube_speech_to_text_when_regula))
              },
            )
          }

          PreferenceSectionHeader(
            position = PreferenceSectionPosition.Middle,
            title = stringResource(R.string.ytdlp_cookies_title),
            modifier = Modifier.settingsSearchTarget(R.string.ytdlp_cookies_title),
          )
          ExpressivePreferenceGroup {
            ExpressivePreferenceTile(index = 0, itemCount = 1) {
              YtdlpCookieControls(modifier = Modifier.fillMaxWidth().padding(16.dp))
            }
          }

          PreferenceCard {
            ExpressivePreferenceItem {
              ListPreference(
                modifier = Modifier.settingsSearchTarget(R.string.ytdlp_playlist_behavior),
                value = playlistMode,
                values = YtdlPlaylistMode.entries,
                onValueChange = ytdlPreferences.playlistMode::set,
                enabled = playbackOptionsEnabled,
                valueToText = { AnnotatedString(it.title) },
                title = { Text(stringResource(R.string.ytdlp_playlist_behavior)) },
                summary = { Text(playlistMode.title, color = MaterialTheme.colorScheme.onSurfaceVariant) },
              )
            }
          }
        }
      }
}

/** Restores old saved navigation stacks directly into the new one-level-up Network page. */
@Serializable
object YtdlpSettingsScreen : Screen {
  @Composable
  override fun Content() = NetworkConfigurationPreferencesScreen.Content()
}
