/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.preferences

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.audiobookshelf.AudiobookshelfServer
import app.gyrolet.mpvrx.domain.jellyfin.JellyfinServer
import app.gyrolet.mpvrx.domain.navidrome.NavidromeServer
import app.gyrolet.mpvrx.domain.seerr.JellyseerrUser
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.presentation.components.RemoteImage
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import org.koin.compose.koinInject
import app.gyrolet.mpvrx.ui.browser.audiobooks.AddAudiobookshelfServerDialog
import app.gyrolet.mpvrx.ui.browser.audiobooks.AudiobookshelfViewModel
import app.gyrolet.mpvrx.ui.browser.jellyfin.AddJellyfinServerDialog
import app.gyrolet.mpvrx.ui.browser.jellyfin.JellyfinViewModel
import app.gyrolet.mpvrx.ui.browser.jellyfin.seerr.SeerrConnectionDialog
import app.gyrolet.mpvrx.ui.browser.jellyfin.seerr.SeerrViewModel
import app.gyrolet.mpvrx.ui.browser.navidrome.AddNavidromeServerDialog
import app.gyrolet.mpvrx.ui.browser.navidrome.NavidromeViewModel
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.popSafely
import kotlinx.serialization.Serializable
import me.zhanghai.compose.preference.Preference

@Serializable
object MediaServersPreferencesScreen : Screen {

  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val context = LocalContext.current
    val backStack = LocalBackStack.current
    val appearancePreferences = koinInject<AppearancePreferences>()

    val jellyfinViewModel: JellyfinViewModel =
      viewModel(factory = JellyfinViewModel.factory(context.applicationContext as Application))
    val jellyfinUiState by jellyfinViewModel.uiState.collectAsStateWithLifecycle()

    val seerrViewModel: SeerrViewModel =
      viewModel(factory = SeerrViewModel.factory(context.applicationContext as Application))
    val seerrUiState by seerrViewModel.uiState.collectAsStateWithLifecycle()

    val navidromeViewModel: NavidromeViewModel =
      viewModel(factory = NavidromeViewModel.factory(context.applicationContext as Application))
    val navidromeUiState by navidromeViewModel.uiState.collectAsStateWithLifecycle()

    val audiobookshelfViewModel: AudiobookshelfViewModel =
      viewModel(factory = AudiobookshelfViewModel.factory(context.applicationContext as Application))
    val audiobookshelfUiState by audiobookshelfViewModel.uiState.collectAsStateWithLifecycle()

    var isAddServerOpen by remember { mutableStateOf(false) }
    var serverToReauth by remember { mutableStateOf<JellyfinServer?>(null) }
    var isSeerrConnectionDialogOpen by remember { mutableStateOf(false) }
    var isAddNavidromeServerOpen by remember { mutableStateOf(false) }
    var navidromeServerToEdit by remember { mutableStateOf<NavidromeServer?>(null) }
    var isAddAudiobookshelfServerOpen by remember { mutableStateOf(false) }
    var audiobookshelfServerToEdit by remember { mutableStateOf<AudiobookshelfServer?>(null) }

    Scaffold(
      topBar = {
        SettingsTopAppBar(
          title = stringResource(R.string.pref_media_servers_title),
          onBack = { backStack.popSafely() },
        )
      },
    ) { padding ->
      ProvideExpressivePreferenceLocals {
        val (settingsListState, settingsHighlight) =
          rememberSettingsSearchList(MediaServersPreferencesScreen, MaterialTheme.colorScheme.primary)

        LazyColumn(
          state = settingsListState,
          modifier =
            Modifier
              .fillMaxSize()
              .padding(padding)
              .then(settingsHighlight),
        ) {
          // --- JELLYFIN SECTION ---
          item {
            PreferenceSectionHeader(
              title = stringResource(R.string.pref_jellyfin_title),
              modifier = Modifier.settingsSearchTarget(R.string.pref_media_servers_title),
            )
          }

          item {
            PreferenceCard {
              if (jellyfinUiState.servers.isEmpty()) {
                Preference(
                  modifier = Modifier.settingsSearchTarget(R.string.pref_jellyfin_server_management),
                  title = { Text(stringResource(R.string.pref_jellyfin_add_server)) },
                  summary = {
                    Text(
                      text = stringResource(R.string.pref_jellyfin_add_server_desc),
                      color = MaterialTheme.colorScheme.outline,
                    )
                  },
                  icon = {
                    Icon(
                      painter = painterResource(R.drawable.ic_jellyfin),
                      contentDescription = null,
                      tint = MaterialTheme.colorScheme.primary,
                      modifier = Modifier.size(32.dp),
                    )
                  },
                  onClick = {
                    serverToReauth = null
                    isAddServerOpen = true
                  },
                )
              } else {
                jellyfinUiState.servers.forEachIndexed { index, server ->
                  if (index > 0) {
                    PreferenceDivider()
                  }
                  val isActive = server.id == jellyfinUiState.activeServer?.id
                  ServerPreferenceItem(
                    modifier = Modifier.settingsSearchTarget(R.string.pref_jellyfin_server_management),
                    title = {
                      Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                      ) {
                        Text(
                          text = server.name,
                          style = MaterialTheme.typography.titleMedium,
                          fontWeight = if (isActive) FontWeight.Bold else FontWeight.SemiBold,
                          maxLines = 1,
                          overflow = TextOverflow.Ellipsis,
                        )
                        if (isActive) {
                          Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.primary,
                          ) {
                            Text(
                              text = stringResource(R.string.pref_server_active),
                              style = MaterialTheme.typography.labelSmall,
                              color = MaterialTheme.colorScheme.onPrimary,
                              modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                          }
                        }
                      }
                    },
                    summary = {
                      Text(
                        text = if (server.username.isNotBlank()) "${server.username} • ${server.serverUrl}" else server.serverUrl,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                      )
                    },
                    icon = {
                      JellyfinServerAvatar(
                        server = server,
                        isActive = isActive,
                      )
                    },
                    trailing = {
                      var menuExpanded by remember { mutableStateOf(false) }
                      Box {
                        IconButton(onClick = { menuExpanded = true }) {
                          Icon(
                            imageVector = Icons.RoundedFilled.MoreVert,
                            contentDescription = stringResource(R.string.pref_server_more_options),
                          )
                        }
                        DropdownMenu(
                          expanded = menuExpanded,
                          onDismissRequest = { menuExpanded = false },
                        ) {
                          if (!isActive) {
                            DropdownMenuItem(
                              text = { Text(stringResource(R.string.pref_server_set_active)) },
                              leadingIcon = {
                                Icon(
                                  imageVector = Icons.RoundedFilled.Check,
                                  contentDescription = null,
                                )
                              },
                              onClick = {
                                menuExpanded = false
                                jellyfinViewModel.selectServer(server)
                              },
                            )
                          }
                          DropdownMenuItem(
                            text = { Text(stringResource(R.string.ui_edit)) },
                            leadingIcon = {
                              Icon(
                                imageVector = Icons.RoundedFilled.Edit,
                                contentDescription = null,
                              )
                            },
                            onClick = {
                              menuExpanded = false
                              serverToReauth = server
                              isAddServerOpen = true
                            },
                          )
                          DropdownMenuItem(
                            text = {
                              Text(
                                text = stringResource(R.string.ui_disconnect),
                                color = MaterialTheme.colorScheme.error,
                              )
                            },
                            leadingIcon = {
                              Icon(
                                imageVector = Icons.RoundedFilled.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                              )
                            },
                            onClick = {
                              menuExpanded = false
                              jellyfinViewModel.deleteServer(server)
                            },
                          )
                        }
                      }
                    },
                    onClick = {
                      if (!isActive) {
                        jellyfinViewModel.selectServer(server)
                      }
                    },
                  )
                }

                PreferenceDivider()
                Preference(
                  title = { Text(stringResource(R.string.pref_jellyfin_add_another_server)) },
                  summary = {
                    Text(
                      text = stringResource(R.string.pref_jellyfin_add_another_server_desc),
                      color = MaterialTheme.colorScheme.outline,
                    )
                  },
                  icon = {
                    Icon(
                      imageVector = Icons.RoundedFilled.Add,
                      contentDescription = null,
                      tint = MaterialTheme.colorScheme.primary,
                      modifier = Modifier.size(24.dp),
                    )
                  },
                  onClick = {
                    serverToReauth = null
                    isAddServerOpen = true
                  },
                )
              }
            }
          }

          // --- SEERR SECTION ---
          item {
            PreferenceSectionHeader(title = stringResource(R.string.pref_seerr_title))
          }

          item {
            PreferenceCard {
              if (seerrUiState.isConnected) {
                val userText =
                  seerrUiState.currentUser?.displayName
                    ?: seerrUiState.currentUser?.username
                    ?: seerrUiState.currentUser?.email
                    ?: "Connected"
                ServerPreferenceItem(
                  modifier = Modifier.settingsSearchTarget(R.string.pref_seerr_server_management),
                  title = {
                    Row(
                      verticalAlignment = Alignment.CenterVertically,
                      horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                      Text(
                        text = userText,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                      )
                      Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primary,
                      ) {
                        Text(
                          text = stringResource(R.string.pref_server_active),
                          style = MaterialTheme.typography.labelSmall,
                          color = MaterialTheme.colorScheme.onPrimary,
                          modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                      }
                    }
                  },
                  summary = {
                    Text(
                      text = seerrUiState.serverUrl,
                      style = MaterialTheme.typography.bodyMedium,
                      color = MaterialTheme.colorScheme.outline,
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis,
                    )
                  },
                  icon = {
                    SeerrServerAvatar(
                      currentUser = seerrUiState.currentUser,
                      serverUrl = seerrUiState.serverUrl,
                    )
                  },
                  trailing = {
                    var menuExpanded by remember { mutableStateOf(false) }
                    Box {
                      IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                          imageVector = Icons.RoundedFilled.MoreVert,
                          contentDescription = stringResource(R.string.pref_server_more_options),
                        )
                      }
                      DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                      ) {
                        DropdownMenuItem(
                          text = { Text(stringResource(R.string.ui_edit)) },
                          leadingIcon = {
                            Icon(
                              imageVector = Icons.RoundedFilled.Edit,
                              contentDescription = null,
                            )
                          },
                          onClick = {
                            menuExpanded = false
                            isSeerrConnectionDialogOpen = true
                          },
                        )
                        DropdownMenuItem(
                          text = {
                            Text(
                              text = stringResource(R.string.ui_disconnect),
                              color = MaterialTheme.colorScheme.error,
                            )
                          },
                          leadingIcon = {
                            Icon(
                              imageVector = Icons.RoundedFilled.Delete,
                              contentDescription = null,
                              tint = MaterialTheme.colorScheme.error,
                            )
                          },
                          onClick = {
                            menuExpanded = false
                            seerrViewModel.disconnect()
                          },
                        )
                      }
                    }
                  },
                  onClick = { isSeerrConnectionDialogOpen = true },
                )
              } else {
                Preference(
                  modifier = Modifier.settingsSearchTarget(R.string.pref_seerr_server_management),
                  title = { Text(stringResource(R.string.pref_seerr_add_server)) },
                  summary = {
                    Text(
                      text = stringResource(R.string.pref_seerr_add_server_desc),
                      color = MaterialTheme.colorScheme.outline,
                    )
                  },
                  icon = {
                    Icon(
                      painter = painterResource(R.drawable.ic_seerr_logo),
                      contentDescription = null,
                      tint = MaterialTheme.colorScheme.primary,
                      modifier = Modifier.size(32.dp),
                    )
                  },
                  onClick = { isSeerrConnectionDialogOpen = true },
                )
              }
            }
          }

          // --- NAVIDROME SECTION ---
          item {
            PreferenceSectionHeader(
              title = stringResource(R.string.pref_navidrome_title),
              modifier = Modifier.settingsSearchTarget(R.string.pref_media_servers_title),
            )
          }

          item {
            PreferenceCard {
              if (navidromeUiState.servers.isEmpty()) {
                Preference(
                  modifier = Modifier.settingsSearchTarget(R.string.pref_navidrome_title),
                  title = { Text(stringResource(R.string.pref_navidrome_add_server)) },
                  summary = {
                    Text(
                      text = stringResource(R.string.pref_navidrome_add_server_desc),
                      color = MaterialTheme.colorScheme.outline,
                    )
                  },
                  icon = {
                    Icon(
                      painter = painterResource(R.drawable.ic_navidrome),
                      contentDescription = null,
                      tint = MaterialTheme.colorScheme.primary,
                      modifier = Modifier.size(32.dp),
                    )
                  },
                  onClick = {
                    navidromeServerToEdit = null
                    isAddNavidromeServerOpen = true
                  },
                )
              } else {
                navidromeUiState.servers.forEachIndexed { index, server ->
                  if (index > 0) {
                    PreferenceDivider()
                  }
                  val isActive = server.id == navidromeUiState.activeServer?.id
                  ServerPreferenceItem(
                    modifier = Modifier.settingsSearchTarget(R.string.pref_navidrome_title),
                    title = {
                      Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                      ) {
                        Text(
                          text = server.name,
                          style = MaterialTheme.typography.titleMedium,
                          fontWeight = if (isActive) FontWeight.Bold else FontWeight.SemiBold,
                          maxLines = 1,
                          overflow = TextOverflow.Ellipsis,
                        )
                        if (isActive) {
                          Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.primary,
                          ) {
                            Text(
                              text = stringResource(R.string.pref_server_active),
                              style = MaterialTheme.typography.labelSmall,
                              color = MaterialTheme.colorScheme.onPrimary,
                              modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                          }
                        }
                      }
                    },
                    summary = {
                      Text(
                        text = if (server.username.isNotBlank()) "${server.username} • ${server.serverUrl}" else server.serverUrl,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                      )
                    },
                    icon = {
                      NavidromeServerAvatar(
                        server = server,
                        isActive = isActive,
                      )
                    },
                    trailing = {
                      var menuExpanded by remember { mutableStateOf(false) }
                      Box {
                        IconButton(onClick = { menuExpanded = true }) {
                          Icon(
                            imageVector = Icons.RoundedFilled.MoreVert,
                            contentDescription = stringResource(R.string.pref_server_more_options),
                          )
                        }
                        DropdownMenu(
                          expanded = menuExpanded,
                          onDismissRequest = { menuExpanded = false },
                        ) {
                          if (!isActive) {
                            DropdownMenuItem(
                              text = { Text(stringResource(R.string.pref_server_set_active)) },
                              leadingIcon = {
                                Icon(
                                  imageVector = Icons.RoundedFilled.Check,
                                  contentDescription = null,
                                )
                              },
                              onClick = {
                                menuExpanded = false
                                navidromeViewModel.selectServer(server)
                              },
                            )
                          }
                          DropdownMenuItem(
                            text = { Text(stringResource(R.string.ui_edit)) },
                            leadingIcon = {
                              Icon(
                                imageVector = Icons.RoundedFilled.Edit,
                                contentDescription = null,
                              )
                            },
                            onClick = {
                              menuExpanded = false
                              navidromeServerToEdit = server
                              isAddNavidromeServerOpen = true
                            },
                          )
                          DropdownMenuItem(
                            text = {
                              Text(
                                text = stringResource(R.string.delete),
                                color = MaterialTheme.colorScheme.error,
                              )
                            },
                            leadingIcon = {
                              Icon(
                                imageVector = Icons.RoundedFilled.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                              )
                            },
                            onClick = {
                              menuExpanded = false
                              navidromeViewModel.deleteServer(server)
                            },
                          )
                        }
                      }
                    },
                    onClick = {
                      navidromeServerToEdit = server
                      isAddNavidromeServerOpen = true
                    },
                  )
                }

                PreferenceDivider()

                Preference(
                  modifier = Modifier.settingsSearchTarget(R.string.pref_navidrome_title),
                  title = { Text(stringResource(R.string.pref_navidrome_add_another_server)) },
                  summary = {
                    Text(
                      text = stringResource(R.string.pref_navidrome_add_another_server_desc),
                      color = MaterialTheme.colorScheme.outline,
                    )
                  },
                  icon = {
                    Icon(
                      imageVector = Icons.RoundedFilled.Add,
                      contentDescription = null,
                      tint = MaterialTheme.colorScheme.primary,
                    )
                  },
                  onClick = {
                    navidromeServerToEdit = null
                    isAddNavidromeServerOpen = true
                  },
                )
              }
            }
          }

          // --- AUDIOBOOKSHELF SECTION ---
          item {
            PreferenceSectionHeader(
              title = stringResource(R.string.pref_audiobookshelf_title),
              modifier = Modifier.settingsSearchTarget(R.string.pref_media_servers_title),
            )
          }

          item {
            PreferenceCard {
              if (audiobookshelfUiState.servers.isEmpty()) {
                Preference(
                  modifier = Modifier.settingsSearchTarget(R.string.pref_audiobookshelf_title),
                  title = { Text(stringResource(R.string.pref_audiobookshelf_add_server)) },
                  summary = {
                    Text(
                      text = stringResource(R.string.pref_audiobookshelf_add_server_desc),
                      color = MaterialTheme.colorScheme.outline,
                    )
                  },
                  icon = {
                    Icon(
                      painter = painterResource(R.drawable.ic_audiobookshelf),
                      contentDescription = null,
                      tint = MaterialTheme.colorScheme.primary,
                      modifier = Modifier.size(32.dp),
                    )
                  },
                  onClick = {
                    audiobookshelfServerToEdit = null
                    isAddAudiobookshelfServerOpen = true
                  },
                )
              } else {
                audiobookshelfUiState.servers.forEachIndexed { index, server ->
                  if (index > 0) {
                    PreferenceDivider()
                  }
                  val isActive = server.id == audiobookshelfUiState.activeServer?.id
                  ServerPreferenceItem(
                    modifier = Modifier.settingsSearchTarget(R.string.pref_audiobookshelf_title),
                    title = {
                      Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                      ) {
                        Text(
                          text = server.name,
                          style = MaterialTheme.typography.titleMedium,
                          fontWeight = if (isActive) FontWeight.Bold else FontWeight.SemiBold,
                          maxLines = 1,
                          overflow = TextOverflow.Ellipsis,
                        )
                        if (isActive) {
                          Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.primary,
                          ) {
                            Text(
                              text = stringResource(R.string.pref_server_active),
                              style = MaterialTheme.typography.labelSmall,
                              color = MaterialTheme.colorScheme.onPrimary,
                              modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                          }
                        }
                      }
                    },
                    summary = {
                      Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        if (server.username.isNotBlank()) {
                          Text(
                            text = server.username,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                          )
                        }
                        Text(
                          text = server.serverUrl,
                          style = MaterialTheme.typography.bodySmall,
                          color = MaterialTheme.colorScheme.outline,
                          maxLines = 1,
                          overflow = TextOverflow.Ellipsis,
                        )
                      }
                    },
                    icon = {
                      AudiobookshelfServerAvatar(
                        server = server,
                        isActive = isActive,
                      )
                    },
                    trailing = {
                      var menuExpanded by remember { mutableStateOf(false) }
                      Box {
                        IconButton(onClick = { menuExpanded = true }) {
                          Icon(
                            imageVector = Icons.RoundedFilled.MoreVert,
                            contentDescription = stringResource(R.string.pref_server_more_options),
                          )
                        }
                        DropdownMenu(
                          expanded = menuExpanded,
                          onDismissRequest = { menuExpanded = false },
                        ) {
                          if (!isActive) {
                            DropdownMenuItem(
                              text = { Text(stringResource(R.string.pref_server_set_active)) },
                              leadingIcon = {
                                Icon(
                                  imageVector = Icons.RoundedFilled.Check,
                                  contentDescription = null,
                                )
                              },
                              onClick = {
                                menuExpanded = false
                                audiobookshelfViewModel.selectServer(server)
                              },
                            )
                          }
                          DropdownMenuItem(
                            text = { Text(stringResource(R.string.ui_edit)) },
                            leadingIcon = {
                              Icon(
                                imageVector = Icons.RoundedFilled.Edit,
                                contentDescription = null,
                              )
                            },
                            onClick = {
                              menuExpanded = false
                              audiobookshelfServerToEdit = server
                              isAddAudiobookshelfServerOpen = true
                            },
                          )
                          DropdownMenuItem(
                            text = {
                              Text(
                                text = stringResource(R.string.delete),
                                color = MaterialTheme.colorScheme.error,
                              )
                            },
                            leadingIcon = {
                              Icon(
                                imageVector = Icons.RoundedFilled.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                              )
                            },
                            onClick = {
                              menuExpanded = false
                              audiobookshelfViewModel.deleteServer(server)
                            },
                          )
                        }
                      }
                    },
                    onClick = {
                      audiobookshelfServerToEdit = server
                      isAddAudiobookshelfServerOpen = true
                    },
                  )
                }

                PreferenceDivider()

                Preference(
                  modifier = Modifier.settingsSearchTarget(R.string.pref_audiobookshelf_title),
                  title = { Text(stringResource(R.string.pref_audiobookshelf_add_another_server)) },
                  summary = {
                    Text(
                      text = stringResource(R.string.pref_audiobookshelf_add_another_server_desc),
                      color = MaterialTheme.colorScheme.outline,
                    )
                  },
                  icon = {
                    Icon(
                      imageVector = Icons.RoundedFilled.Add,
                      contentDescription = null,
                      tint = MaterialTheme.colorScheme.primary,
                    )
                  },
                  onClick = {
                    audiobookshelfServerToEdit = null
                    isAddAudiobookshelfServerOpen = true
                  },
                )
              }
            }
          }
        }
      }
    }

    // Add / Re-authenticate Jellyfin Server Dialog
    AddJellyfinServerDialog(
      isOpen = isAddServerOpen,
      isLoading = jellyfinUiState.isAuthenticating,
      errorMessage = jellyfinUiState.authError,
      initialServer = serverToReauth,
      onDismiss = {
        isAddServerOpen = false
        serverToReauth = null
      },
      onConnect = { serverUrl, serverName, authMode, username, password, token ->
        val existingId = serverToReauth?.id
        jellyfinViewModel.addServer(
          serverUrl = serverUrl,
          serverName = serverName,
          authMode = authMode,
          username = username,
          password = password,
          token = token,
          existingServerId = existingId,
          onSuccess = {
            isAddServerOpen = false
            serverToReauth = null
            if (!appearancePreferences.showJellyfinTab.get()) {
              appearancePreferences.showJellyfinTab.set(true)
            }
          },
        )
      },
    )

    // Seerr Connection Dialog
    SeerrConnectionDialog(
      isOpen = isSeerrConnectionDialogOpen,
      isConnected = seerrUiState.isConnected,
      currentUser = seerrUiState.currentUser,
      currentServerUrl = seerrUiState.serverUrl,
      currentApiKey = seerrUiState.apiKey,
      activeJellyfinServer = jellyfinUiState.activeServer,
      isConnecting = seerrUiState.isConnecting,
      errorMessage = seerrUiState.connectionError,
      onDismiss = { isSeerrConnectionDialogOpen = false },
      onConnectWithCredentials = { url, user, pass, useJellyfin ->
        seerrViewModel.connectWithCredentials(url, user, pass, useJellyfin)
      },
      onConnectWithApiKey = { url, apiKey ->
        seerrViewModel.connectWithApiKey(url, apiKey)
      },
      onDisconnect = {
        seerrViewModel.disconnect()
      },
    )

    // Add / Edit Navidrome Server Dialog
    AddNavidromeServerDialog(
      isOpen = isAddNavidromeServerOpen,
      isLoading = navidromeUiState.isConnectingServer,
      errorMessage = navidromeUiState.connectServerError,
      initialServer = navidromeServerToEdit,
      onDismiss = {
        isAddNavidromeServerOpen = false
        navidromeServerToEdit = null
      },
      onConnect = { url, name, authMode, username, password, token ->
        navidromeViewModel.connectServer(
          serverUrl = url,
          serverName = name,
          authMode = authMode,
          username = username,
          password = password,
          token = token,
          existingServer = navidromeServerToEdit,
          onSuccess = {
            isAddNavidromeServerOpen = false
            navidromeServerToEdit = null
          },
        )
      },
    )

    // Add / Edit Audiobookshelf Server Dialog
    AddAudiobookshelfServerDialog(
      isOpen = isAddAudiobookshelfServerOpen,
      isLoading = audiobookshelfUiState.isConnectingServer,
      errorMessage = audiobookshelfUiState.connectServerError,
      initialServer = audiobookshelfServerToEdit,
      onDismiss = {
        isAddAudiobookshelfServerOpen = false
        audiobookshelfServerToEdit = null
      },
      onConnect = { url, name, isToken, username, password, token ->
        audiobookshelfViewModel.connectServer(
          serverUrl = url,
          serverName = name,
          isToken = isToken,
          username = username,
          password = password,
          token = token,
          existingServer = audiobookshelfServerToEdit,
          onSuccess = {
            isAddAudiobookshelfServerOpen = false
            audiobookshelfServerToEdit = null
          },
        )
      },
    )
  }
}

@Composable
private fun AudiobookshelfServerAvatar(
  server: AudiobookshelfServer,
  isActive: Boolean,
  modifier: Modifier = Modifier,
) {
  Box(
    modifier = modifier.size(40.dp),
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      painter = painterResource(R.drawable.ic_audiobookshelf),
      contentDescription = null,
      tint =
        if (isActive) {
          MaterialTheme.colorScheme.primary
        } else {
          MaterialTheme.colorScheme.onSurfaceVariant
        },
      modifier = Modifier.size(36.dp),
    )
  }
}

@Composable
private fun NavidromeServerAvatar(
  server: NavidromeServer,
  isActive: Boolean,
  modifier: Modifier = Modifier,
) {
  Box(
    modifier = modifier.size(40.dp),
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      painter = painterResource(R.drawable.ic_navidrome),
      contentDescription = null,
      tint =
        if (isActive) {
          MaterialTheme.colorScheme.primary
        } else {
          MaterialTheme.colorScheme.onSurfaceVariant
        },
      modifier = Modifier.size(36.dp),
    )
  }
}

@Composable
private fun JellyfinServerAvatar(
  server: JellyfinServer,
  isActive: Boolean,
  modifier: Modifier = Modifier,
) {
  val avatarUrl = remember(server.serverUrl, server.userId, server.accessToken) {
    if (server.serverUrl.isNotBlank() && server.userId.isNotBlank()) {
      val tokenParam = if (server.accessToken.isNotBlank()) "&api_key=${server.accessToken}" else ""
      "${server.serverUrl.trimEnd('/')}/Users/${server.userId}/Images/Primary?maxWidth=120&quality=80$tokenParam"
    } else null
  }

  Surface(
    shape = CircleShape,
    color =
      if (isActive) {
        MaterialTheme.colorScheme.primaryContainer
      } else {
        MaterialTheme.colorScheme.surfaceContainerHighest
      },
    modifier = modifier.size(40.dp),
  ) {
    Box(
      modifier = Modifier.fillMaxSize(),
      contentAlignment = Alignment.Center,
    ) {
      val initial = server.username.trim().take(1).uppercase()
      if (initial.isNotBlank()) {
        Text(
          text = initial,
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
          color =
            if (isActive) {
              MaterialTheme.colorScheme.onPrimaryContainer
            } else {
              MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
      } else {
        Icon(
          painter = painterResource(R.drawable.ic_jellyfin),
          contentDescription = null,
          tint =
            if (isActive) {
              MaterialTheme.colorScheme.primary
            } else {
              MaterialTheme.colorScheme.onSurfaceVariant
            },
          modifier = Modifier.size(36.dp),
        )
      }

      if (avatarUrl != null) {
        RemoteImage(
          url = avatarUrl,
          contentDescription = null,
          contentScale = ContentScale.Crop,
          modifier = Modifier.fillMaxSize().clip(CircleShape),
        )
      }
    }
  }
}

@Composable
private fun SeerrServerAvatar(
  currentUser: JellyseerrUser?,
  serverUrl: String,
  modifier: Modifier = Modifier,
) {
  val rawAvatar = currentUser?.avatar
  val avatarUrl = remember(rawAvatar, serverUrl) {
    when {
      rawAvatar.isNullOrBlank() -> null
      rawAvatar.startsWith("http") -> rawAvatar
      serverUrl.isNotBlank() -> "${serverUrl.trimEnd('/')}/${rawAvatar.trimStart('/')}"
      else -> null
    }
  }

  Surface(
    shape = CircleShape,
    color = MaterialTheme.colorScheme.primaryContainer,
    modifier = modifier.size(40.dp),
  ) {
    Box(
      modifier = Modifier.fillMaxSize(),
      contentAlignment = Alignment.Center,
    ) {
      val initial = (currentUser?.displayName ?: currentUser?.username ?: "").trim().take(1).uppercase()
      if (initial.isNotBlank()) {
        Text(
          text = initial,
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
      } else {
        Icon(
          painter = painterResource(R.drawable.ic_seerr_logo),
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(36.dp),
        )
      }

      if (avatarUrl != null) {
        RemoteImage(
          url = avatarUrl,
          contentDescription = null,
          contentScale = ContentScale.Crop,
          modifier = Modifier.fillMaxSize().clip(CircleShape),
        )
      }
    }
  }
}

@Composable
private fun ServerPreferenceItem(
  title: @Composable () -> Unit,
  summary: @Composable () -> Unit,
  icon: @Composable () -> Unit,
  trailing: @Composable () -> Unit,
  modifier: Modifier = Modifier,
  onClick: (() -> Unit)? = null,
) {
  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      modifier = Modifier.padding(end = 16.dp),
      contentAlignment = Alignment.Center,
    ) {
      icon()
    }

    Column(
      modifier =
        Modifier
          .weight(1f)
          .padding(end = 8.dp),
      verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      title()
      summary()
    }

    trailing()
  }
}
