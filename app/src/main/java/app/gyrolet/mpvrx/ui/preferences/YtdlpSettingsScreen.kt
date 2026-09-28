/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.preferences

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.YtdlPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.runtime.OptionalRuntimePack
import app.gyrolet.mpvrx.runtime.OptionalRuntimePackManager
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.ytdlp.YtdlPlaylistMode
import app.gyrolet.mpvrx.ui.player.ytdlp.YtdlpInstallationStatus
import app.gyrolet.mpvrx.ui.player.ytdlp.YtdlpManager
import app.gyrolet.mpvrx.ui.player.ytdlp.YtdlpReleaseChannel
import app.gyrolet.mpvrx.ui.preferences.components.SwitchPreference
import app.gyrolet.mpvrx.ui.preferences.components.RuntimePackPreference
import app.gyrolet.mpvrx.ui.theme.spacing
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.currentMpvConfigOverrideOptions
import app.gyrolet.mpvrx.ui.utils.popSafely
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import me.zhanghai.compose.preference.ProvidePreferenceLocals
import org.koin.compose.koinInject

@Serializable
object YtdlpSettingsScreen : Screen {
  @OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
  @Composable
  override fun Content() {
    val context = LocalContext.current
    val backStack = LocalBackStack.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val settingsHighlight =
      rememberSettingsSearchHighlight(YtdlpSettingsScreen, scrollState, MaterialTheme.colorScheme.primary)
    var isRunning by remember { mutableStateOf(false) }
    var operationError by remember { mutableStateOf<String?>(null) }

    val ytdlPreferences = koinInject<YtdlPreferences>()
    val configOwnedOptions = currentMpvConfigOverrideOptions()
    val playbackOptionsEnabled = "ytdl-raw-options" !in configOwnedOptions
    val playlistMode by ytdlPreferences.playlistMode.collectAsState()
    val writeSubs by ytdlPreferences.writeSubs.collectAsState()
    val writeAutoSubs by ytdlPreferences.writeAutoSubs.collectAsState()
    val installationInfo by YtdlpManager.installationInfo.collectAsState()
    val onlineRuntimeInstalled by
      remember { OptionalRuntimePackManager.observeInstalled(context, OptionalRuntimePack.Online) }
        .collectAsState(initial = OptionalRuntimePackManager.isInstalled(context, OptionalRuntimePack.Online))

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

    Scaffold(
      topBar = {
        TopAppBar(
          title = {
            Text(
              text = stringResource(R.string.ui_yt_dlp_streaming),
              style = MaterialTheme.typography.titleLarge,
              fontWeight = FontWeight.Bold,
            )
          },
          navigationIcon = {
            IconButton(onClick = { backStack.popSafely() }) {
              Icon(Icons.RoundedFilled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
          },
          colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
        )
      },
    ) { padding ->
      ProvidePreferenceLocals {
        Column(
          modifier =
            Modifier
              .fillMaxSize()
              .padding(padding)
              .then(settingsHighlight)
              .verticalScroll(scrollState)
              .padding(bottom = 32.dp),
          verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
          PreferenceCard {
            RuntimePackPreference(
              pack = OptionalRuntimePack.Online,
              titleRes = R.string.runtime_pack_online_title,
              summaryRes = R.string.runtime_pack_online_summary,
            )
          }

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
            title = stringResource(R.string.ui_release_channel),
            modifier = Modifier.settingsSearchTarget(R.string.ui_yt_dlp_manager),
          )

          PreferenceCard {
            Column(
              modifier = Modifier.fillMaxWidth().padding(16.dp),
              verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              Button(
                onClick = {
                  runOperation { onLog ->
                    if (installationInfo?.channel == YtdlpReleaseChannel.STABLE) {
                      YtdlpManager.runUpdate(context, onLog)
                    } else {
                      YtdlpManager.runInstall(context, onLog)
                    }
                  }
                },
                enabled = !isRunning && onlineRuntimeInstalled,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
              ) {
                Icon(Icons.RoundedFilled.Download, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stableActionLabel)
              }

              OutlinedButton(
                onClick = {
                  runOperation { onLog -> YtdlpManager.runUpdateToNightly(context, onLog) }
                },
                enabled = !isRunning && isInstalled && onlineRuntimeInstalled,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
              ) {
                Icon(Icons.RoundedFilled.Update, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(nightlyActionLabel)
              }
            }
          }

          PreferenceSectionHeader(title = stringResource(R.string.ytdlp_subtitles_language))

          PreferenceCard {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
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
          }

          PreferenceSectionHeader(
            title = stringResource(R.string.ytdlp_playlist_behavior),
            modifier = Modifier.settingsSearchTarget(R.string.ytdlp_playlist_behavior),
          )

          PreferenceCard {
            FlowRow(
              modifier = Modifier.fillMaxWidth().padding(16.dp),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              YtdlPlaylistMode.entries.forEach { mode ->
                FilterChip(
                  selected = playlistMode == mode,
                  enabled = playbackOptionsEnabled,
                  onClick = { ytdlPreferences.playlistMode.set(mode) },
                  label = { Text(mode.title) },
                  leadingIcon =
                    if (playlistMode == mode) {
                      { Icon(Icons.RoundedFilled.Check, null, modifier = Modifier.size(16.dp)) }
                    } else {
                      null
                    },
                )
              }
            }
          }
        }
      }
    }
  }

}
