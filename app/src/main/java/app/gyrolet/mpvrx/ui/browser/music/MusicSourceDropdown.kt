/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.browser.music

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import app.gyrolet.mpvrx.presentation.components.AppComboBoxChip
import app.gyrolet.mpvrx.presentation.components.AppComboBoxOption
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.MediaServerPreferences
import app.gyrolet.mpvrx.domain.network.NetworkProtocol
import app.gyrolet.mpvrx.preferences.MusicSourceProvider
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.repository.JellyfinRepository
import app.gyrolet.mpvrx.repository.NavidromeRepository
import app.gyrolet.mpvrx.repository.NetworkRepository
import app.gyrolet.mpvrx.ui.browser.networkstreaming.NetworkStreamingScreen
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
  val networkRepository = koinInject<NetworkRepository>()
  val connections by remember(networkRepository) { networkRepository.getAllConnections() }.collectAsState(initial = emptyList())
  val hasDlna = connections.any { it.protocol == NetworkProtocol.DLNA }

  val sources = buildList {
    add(MusicSourceProvider.LOCAL)
    if (hasJellyfin) add(MusicSourceProvider.JELLYFIN)
    if (hasNavidrome) add(MusicSourceProvider.NAVIDROME)
    if (hasDlna || currentMusicSource == MusicSourceProvider.DLNA) add(MusicSourceProvider.DLNA)
    add(MusicSourceProvider.AUDIOBOOKS)
  }
  Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
    AppComboBoxChip(
    options = sources,
    selected = currentMusicSource,
    onSelect = { mediaServerPreferences.musicSourceProvider.set(it) },
    optionLabel = { musicSourceLabel(it) },
    valueText = "",
    contentDescription = musicSourceLabel(currentMusicSource),
    leadingIcon = { MusicSourceIcon(currentMusicSource) },
    optionLeadingIcon = { MusicSourceIcon(it) },
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
    if (currentMusicSource == MusicSourceProvider.DLNA) {
      DlnaMusicServerSelector(Modifier.widthIn(max = 180.dp))
    }
  }
}

@Composable
internal fun DlnaMusicServerSelector(modifier: Modifier = Modifier) {
  val repository = koinInject<NetworkRepository>()
  val preferences = koinInject<MediaServerPreferences>()
  val backStack = LocalBackStack.current
  val connections by remember(repository) { repository.getAllConnections() }.collectAsState(initial = emptyList())
  val servers = connections.filter { it.protocol == NetworkProtocol.DLNA }
  val selectedId by preferences.dlnaMusicConnectionId.collectAsState()
  val selected = if (selectedId < 0L) servers.firstOrNull() else servers.firstOrNull { it.id == selectedId }
  AppComboBoxChip(
    options = servers,
    selected = selected,
    onSelect = {
      preferences.dlnaMusicConnectionId.set(it.id)
      preferences.musicSourceProvider.set(MusicSourceProvider.DLNA)
    },
    optionLabel = { it.name },
    valueText = selected?.name ?: stringResource(R.string.music_dlna_server_missing),
    contentDescription = stringResource(R.string.music_source_dlna),
    leadingIcon = {
      androidx.compose.material3.Icon(
        painter = painterResource(R.drawable.ic_dlna),
        contentDescription = null,
        modifier = Modifier.size(20.dp),
      )
    },
    modifier = modifier,
    footer = { dismiss ->
      AppComboBoxOption(
        text = stringResource(R.string.music_dlna_manage_servers),
        selected = false,
        index = 0,
        itemCount = 1,
        leadingIcon = { Icon(Icons.RoundedFilled.Settings, null, Modifier.size(20.dp)) },
        onClick = {
          dismiss()
          backStack.navigateTo(NetworkStreamingScreen)
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
    MusicSourceProvider.DLNA -> stringResource(R.string.music_source_dlna)
    MusicSourceProvider.AUDIOBOOKS -> stringResource(R.string.audiobooks_title)
    else -> stringResource(R.string.ui_music)
  }

@Composable
private fun MusicSourceIcon(source: MusicSourceProvider) {
  // Match AppDropdownMenuItem / chip content color so every option icon shares one tint.
  val tint = LocalContentColor.current
  when (source) {
    MusicSourceProvider.JELLYFIN, MusicSourceProvider.NAVIDROME, MusicSourceProvider.DLNA -> {
      androidx.compose.material3.Icon(
        painter = painterResource(
          when (source) {
            MusicSourceProvider.JELLYFIN -> R.drawable.ic_jellyfin
            MusicSourceProvider.DLNA -> R.drawable.ic_dlna
            else -> R.drawable.ic_navidrome
          },
        ),
        contentDescription = null,
        modifier = Modifier.size(20.dp),
        tint = tint,
      )
    }
    else -> {
      Icon(
        if (source == MusicSourceProvider.AUDIOBOOKS) Icons.RoundedFilled.Audiobookshelf
        else Icons.RoundedFilled.Folder,
        contentDescription = null,
        modifier = Modifier.size(20.dp),
        tint = tint,
      )
    }
  }
}
