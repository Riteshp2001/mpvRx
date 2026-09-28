/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.preferences

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.AdvancedPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.runtime.OptionalRuntimePack
import app.gyrolet.mpvrx.runtime.OptionalRuntimePackManager
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.preferences.components.SwitchPreference
import app.gyrolet.mpvrx.ui.preferences.components.RuntimePackPreference
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.LocalShowSettingsBackArrow
import app.gyrolet.mpvrx.ui.utils.navigateTo
import app.gyrolet.mpvrx.ui.utils.popSafely
import kotlinx.serialization.Serializable
import me.zhanghai.compose.preference.Preference
import me.zhanghai.compose.preference.ProvidePreferenceLocals
import org.koin.compose.koinInject

@Serializable
object NetworkConfigurationPreferencesScreen : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val backStack = LocalBackStack.current
    val context = LocalContext.current
    val preferences = koinInject<AdvancedPreferences>()
    val enableP2pStreaming by preferences.enableP2pStreaming.collectAsState()
    val enableHlsProxy by preferences.enableHlsProxy.collectAsState()
    val torrentRuntimeInstalled by
      remember { OptionalRuntimePackManager.observeInstalled(context, OptionalRuntimePack.Torrent) }
        .collectAsState(initial = OptionalRuntimePackManager.isInstalled(context, OptionalRuntimePack.Torrent))

    Scaffold(
      topBar = {
        TopAppBar(
          title = {
            Text(
              text = stringResource(R.string.ui_network),
              style = MaterialTheme.typography.headlineSmall,
              fontWeight = FontWeight.ExtraBold,
              color = MaterialTheme.colorScheme.primary,
            )
          },
          navigationIcon = {
            if (LocalShowSettingsBackArrow.current) {
              IconButton(onClick = { backStack.popSafely() }) {
                Icon(Icons.RoundedFilled.ArrowBack, contentDescription = null)
              }
            }
          },
        )
      },
    ) { padding ->
      ProvidePreferenceLocals {
        val (settingsListState, settingsHighlight) =
          rememberSettingsSearchList(NetworkConfigurationPreferencesScreen, MaterialTheme.colorScheme.primary)
        LazyColumn(
          state = settingsListState,
          modifier = Modifier.fillMaxSize().padding(padding).then(settingsHighlight),
        ) {
          item {
            PreferenceSectionHeader(title = stringResource(R.string.pref_section_p2p_streaming))
          }
          item {
            PreferenceCard {
              RuntimePackPreference(
                pack = OptionalRuntimePack.Torrent,
                titleRes = R.string.runtime_pack_torrent_title,
                summaryRes = R.string.runtime_pack_torrent_summary,
              )
              PreferenceDivider()
              SwitchPreference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_enable_p2p_streaming_title),
                value = enableP2pStreaming && torrentRuntimeInstalled,
                enabled = torrentRuntimeInstalled,
                onValueChange = preferences.enableP2pStreaming::set,
                title = { Text(stringResource(R.string.pref_enable_p2p_streaming_title)) },
                summary = { Text(stringResource(R.string.pref_enable_p2p_streaming_summary), color = MaterialTheme.colorScheme.outline) },
              )
              PreferenceDivider()
              SwitchPreference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_enable_hls_proxy_title),
                value = enableHlsProxy,
                onValueChange = preferences.enableHlsProxy::set,
                title = { Text(stringResource(R.string.pref_enable_hls_proxy_title)) },
                summary = { Text(stringResource(R.string.pref_enable_hls_proxy_summary), color = MaterialTheme.colorScheme.outline) },
              )
            }
          }
          item {
            PreferenceSectionHeader(title = stringResource(R.string.ui_yt_dlp_manager))
          }
          item {
            PreferenceCard {
              Preference(
                modifier = Modifier.settingsSearchTarget(R.string.ui_yt_dlp_manager),
                title = { Text(stringResource(R.string.ui_yt_dlp_manager)) },
                summary = { Text(stringResource(R.string.ui_install_and_update_yt_dlp_for_streaming_support), color = MaterialTheme.colorScheme.outline) },
                icon = { Icon(Icons.RoundedFilled.CloudDownload, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                onClick = { backStack.navigateTo(YtdlpSettingsScreen) },
              )
            }
          }
        }
      }
    }
  }
}
