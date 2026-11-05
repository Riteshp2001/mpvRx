/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.browser.music

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import app.gyrolet.mpvrx.presentation.components.AppComboBoxChip
import app.gyrolet.mpvrx.presentation.components.AppComboBoxOption
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.MediaServerPreferences
import app.gyrolet.mpvrx.preferences.MusicSourceProvider
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.repository.JellyfinRepository
import app.gyrolet.mpvrx.repository.NavidromeRepository
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.preferences.MediaServersPreferencesScreen
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.navigateTo
import org.koin.compose.koinInject

/**
 * Compact music source selector dropdown located directly next to the TopBar title.
 */
@Composable
fun MusicSourceDropdown(
  modifier: Modifier = Modifier,
) {
  val backStack = LocalBackStack.current
  val mediaServerPreferences = koinInject<MediaServerPreferences>()
  val currentMusicSource by mediaServerPreferences.musicSourceProvider.collectAsState()

  val jellyfinRepository = koinInject<JellyfinRepository>()
  val jellyfinServers by jellyfinRepository.allServers.collectAsState(initial = emptyList())
  val hasJellyfin = jellyfinServers.isNotEmpty()

  val navidromeRepository = koinInject<NavidromeRepository>()
  val navidromeServers by navidromeRepository.allServers.collectAsState(initial = emptyList())
  val hasNavidrome = navidromeServers.isNotEmpty()

  val sources = buildList {
    add(MusicSourceProvider.LOCAL)
    if (hasJellyfin) add(MusicSourceProvider.JELLYFIN)
    if (hasNavidrome) add(MusicSourceProvider.NAVIDROME)
    add(MusicSourceProvider.AUDIOBOOKS)
  }
  AppComboBoxChip(
    options = sources,
    selected = currentMusicSource,
    onSelect = { mediaServerPreferences.musicSourceProvider.set(it) },
    optionLabel = { musicSourceLabel(it) },
    valueText = "",
    contentDescription = musicSourceLabel(currentMusicSource),
    leadingIcon = { MusicSourceIcon(currentMusicSource) },
    optionLeadingIcon = { MusicSourceIcon(it) },
    modifier = modifier,
    footer = { dismiss ->
      HorizontalDivider(Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
      AppComboBoxOption(
        text = stringResource(R.string.pref_media_servers_title),
        selected = false,
        index = 0,
        itemCount = 1,
        leadingIcon = {
          Icon(Icons.RoundedFilled.Settings, contentDescription = null, modifier = Modifier.size(20.dp))
        },
        trailingContent = {
          Icon(Icons.RoundedFilled.ChevronRight, contentDescription = null, modifier = Modifier.size(20.dp))
        },
        onClick = {
          dismiss()
          backStack.navigateTo(MediaServersPreferencesScreen)
        },
      )
    },
  )
}

@Composable
private fun musicSourceLabel(source: MusicSourceProvider): String =
  when (source) {
    MusicSourceProvider.JELLYFIN -> stringResource(R.string.pref_jellyfin_title)
    MusicSourceProvider.NAVIDROME -> stringResource(R.string.music_source_navidrome)
    MusicSourceProvider.AUDIOBOOKS -> stringResource(R.string.audiobooks_title)
    else -> stringResource(R.string.ui_music)
  }

@Composable
private fun MusicSourceIcon(source: MusicSourceProvider) {
  when (source) {
    MusicSourceProvider.JELLYFIN, MusicSourceProvider.NAVIDROME -> {
      androidx.compose.material3.Icon(
        painter = painterResource(
          if (source == MusicSourceProvider.JELLYFIN) R.drawable.ic_jellyfin else R.drawable.ic_navidrome,
        ),
        contentDescription = null,
        modifier = Modifier.size(20.dp),
        tint = MaterialTheme.colorScheme.primary,
      )
    }
    else -> {
      Icon(
        if (source == MusicSourceProvider.AUDIOBOOKS) Icons.RoundedFilled.Audiobookshelf
        else Icons.RoundedFilled.Folder,
        contentDescription = null,
        modifier = Modifier.size(20.dp),
      )
    }
  }
}
