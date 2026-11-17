/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.preferences

import app.gyrolet.mpvrx.ui.tv.tvContentFocus

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.AdvancedPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.ui.player.ytdlp.YtdlpCookieControls
import app.gyrolet.mpvrx.ui.preferences.components.SwitchPreference
import app.gyrolet.mpvrx.ui.theme.spacing
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.popSafely
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

@Serializable
object NetworkConfigurationPreferencesScreen : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    if (!app.gyrolet.mpvrx.BuildConfig.ENABLE_YTDLP && !app.gyrolet.mpvrx.BuildConfig.ENABLE_TORRENT) {
      PreferencesScreen.Content()
      return
    }
    SettingsCategoryMenu(
      R.string.ui_network,
      buildList {
        if (app.gyrolet.mpvrx.BuildConfig.ENABLE_YTDLP) {
          add(SettingsPage.YTDLP.link)
          add(SettingsPage.COOKIES.link)
        }
        if (app.gyrolet.mpvrx.BuildConfig.ENABLE_TORRENT) add(SettingsPage.P2P.link)
      },
    )
  }

  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  internal fun DetailsContent(page: SettingsPage? = null) {
    if (!app.gyrolet.mpvrx.BuildConfig.ENABLE_YTDLP && !app.gyrolet.mpvrx.BuildConfig.ENABLE_TORRENT) {
      // Old saved navigation stacks may still contain this former settings destination.
      PreferencesScreen.Content()
      return
    }
    val backStack = LocalBackStack.current
    val preferences = koinInject<AdvancedPreferences>()
    val enableP2pStreaming by preferences.enableP2pStreaming.collectAsState()

    Scaffold(
      topBar = {
        SettingsTopAppBar(
          title = stringResource(page?.titleRes ?: R.string.ui_network),
          onBack = { backStack.popSafely() },
        )
      },
    ) { padding ->
      ProvideExpressivePreferenceLocals {
        val scrollState = rememberScrollState()
        Column(
          modifier =
            Modifier
              .fillMaxSize()
            .tvContentFocus()
              .padding(padding)
              .verticalScroll(scrollState)
              .padding(bottom = MaterialTheme.spacing.extraLarge),
          verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
          // Streaming tools live directly on Network. YT-DLP intentionally comes first,
          // followed by P2P, matching the order in which users choose a stream source.
          if (app.gyrolet.mpvrx.BuildConfig.ENABLE_YTDLP && SettingsPage.YTDLP.includes(page)) {
            YtdlpManagerSections(includeCookies = page == null)
          }
          if (app.gyrolet.mpvrx.BuildConfig.ENABLE_YTDLP && page == SettingsPage.COOKIES) {
            YtdlpCookieControls(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp))
          }

          if (app.gyrolet.mpvrx.BuildConfig.ENABLE_TORRENT && SettingsPage.P2P.includes(page)) {
            PreferenceSectionHeader(title = stringResource(R.string.pref_section_p2p_streaming))
            ExpressivePreferenceGroup {
              SwitchPreference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_enable_p2p_streaming_title),
                value = enableP2pStreaming,
                onValueChange = preferences.enableP2pStreaming::set,
                title = { Text(stringResource(R.string.pref_enable_p2p_streaming_title)) },
                summary = { Text(stringResource(R.string.pref_enable_p2p_streaming_summary), color = MaterialTheme.colorScheme.outline) },
                groupIndex = 0,
                groupSize = 1,
              )
            }
          }
        }
      }
    }
  }
}
