/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.dialogs

import app.gyrolet.mpvrx.ui.components.AppToast as Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.draw.alpha
import app.gyrolet.mpvrx.presentation.components.AppAlertDialog as AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import app.gyrolet.mpvrx.presentation.components.AppTextField as OutlinedTextField
import androidx.compose.material3.Text
import app.gyrolet.mpvrx.presentation.components.AppDialogTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.media.model.Video
import app.gyrolet.mpvrx.presentation.components.AppPickerSheet
import app.gyrolet.mpvrx.presentation.components.PlayerSheetSearchField
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.liquidglass.LocalPlayerGlassSurfaceActive
import app.gyrolet.mpvrx.ui.player.controls.components.sheets.playerSheetSelectionColor
import app.gyrolet.mpvrx.ui.theme.AppShapeScale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AddToPlaylistDialog(
  isOpen: Boolean,
  candidates: List<PlaylistAddCandidate> = emptyList(),
  onDismiss: () -> Unit,
  onSuccess: () -> Unit,
  isJellyfin: Boolean = false,
  modifier: Modifier = Modifier,
  onItemsAdded: () -> Unit = {},
  videos: List<Video> = emptyList(),
  usePlayerSheet: Boolean = false,
) {
  val viewModel: AddToPlaylistViewModel = viewModel()
  val playlistOptions by viewModel.playlistOptions.collectAsState()
  val isLoading by viewModel.isLoading.collectAsState()
  val scope = rememberCoroutineScope()
  var showCreateDialog by rememberSaveable(isOpen) { mutableStateOf(false) }
  var query by rememberSaveable(isOpen) { mutableStateOf("") }
  var submittingId by rememberSaveable(isOpen) { mutableStateOf<String?>(null) }
  val context = LocalContext.current
  val resolvedCandidates = remember(candidates, videos) {
    if (candidates.isNotEmpty()) candidates else videos.toPlaylistCandidates()
  }
  val isAudio = remember(resolvedCandidates) { resolvedCandidates.firstOrNull()?.isAudio == true }
  val compatibleCandidates = remember(resolvedCandidates, isAudio) {
    resolvedCandidates.filter { it.isAudio == isAudio }
  }
  val filteredOptions = remember(playlistOptions, query) {
    val normalized = query.trim()
    if (normalized.isEmpty()) playlistOptions else playlistOptions.filter { option ->
      option.name.contains(normalized, ignoreCase = true)
    }
  }
  val candidateCountText = pluralStringResource(
    if (isAudio) R.plurals.playlist_picker_song_count else R.plurals.playlist_picker_video_count,
    compatibleCandidates.size,
    compatibleCandidates.size,
  )

  androidx.compose.runtime.LaunchedEffect(isOpen, isAudio, isJellyfin) {
    if (isOpen) {
      viewModel.loadPlaylists(isAudio = isAudio, isJellyfin = isJellyfin)
    }
  }

  if (!isOpen) return

  if (showCreateDialog) {
    CreatePlaylistDialog(
      onDismiss = { showCreateDialog = false },
      onConfirm = { name ->
        if (submittingId != null || compatibleCandidates.isEmpty()) return@CreatePlaylistDialog
        submittingId = "new"
        scope.launch {
          try {
            viewModel.createAndAdd(name, compatibleCandidates, isJellyfin = isJellyfin).getOrThrow()
            val message =
              context.getString(
                if (isAudio) R.string.playlist_add_songs_success else R.string.playlist_add_videos_success,
                compatibleCandidates.size,
              )
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            showCreateDialog = false
            onItemsAdded()
            onSuccess()
            onDismiss()
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (_: Exception) {
            submittingId = null
            Toast.makeText(context, R.string.playlist_add_failed, Toast.LENGTH_SHORT).show()
          }
        }
      },
    )
    return
  }

  AppPickerSheet(
    onDismissRequest = onDismiss,
    title = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_add_to_playlist),
    subtitle = candidateCountText,
    warning = if (compatibleCandidates.isEmpty()) {
      androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.playlist_no_compatible_items)
    } else {
      null
    },
    scrollContent = false,
    usePlayerSheet = usePlayerSheet,
    actions = {
      TextButton(onClick = onDismiss, enabled = submittingId == null) {
        Text(
          androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.generic_cancel),
          fontWeight = FontWeight.Medium,
        )
      }
    },
    modifier = modifier,
  ) {
    Column(
      modifier = Modifier.fillMaxWidth(),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      OutlinedButton(
        onClick = { showCreateDialog = true },
        enabled = compatibleCandidates.isNotEmpty() && submittingId == null,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
      ) {
        Icon(
          imageVector = Icons.RoundedFilled.Add,
          contentDescription = null,
          modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text =
            androidx.compose.ui.res
              .stringResource(app.gyrolet.mpvrx.R.string.ui_create_new_playlist),
          fontWeight = FontWeight.Medium,
        )
      }

      PlayerSheetSearchField(
        query = query,
        onQueryChange = { query = it },
        placeholder = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.playlist_search_hint),
      )

      when {
        isLoading -> {
          Box(
            modifier = Modifier.fillMaxWidth().height(160.dp),
            contentAlignment = Alignment.Center,
          ) {
            CircularProgressIndicator(modifier = Modifier.size(32.dp))
          }
        }
        filteredOptions.isNotEmpty() -> {
          Text(
            text =
              androidx.compose.ui.res
                .stringResource(app.gyrolet.mpvrx.R.string.ui_existing_playlists),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
          )

          LazyColumn(
            modifier = Modifier.fillMaxWidth().height(300.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 4.dp),
          ) {
            items(filteredOptions, key = { it.id }) { option ->
              PlaylistItemCard(
                option = option,
                enabled = submittingId == null && compatibleCandidates.isNotEmpty(),
                isWorking = submittingId == option.id,
                onClick = {
                  if (submittingId != null || compatibleCandidates.isEmpty()) return@PlaylistItemCard
                  submittingId = option.id
                  scope.launch {
                    try {
                      viewModel.addToPlaylist(option, compatibleCandidates, isJellyfin = isJellyfin).getOrThrow()
                      val message =
                        context.getString(
                          if (isAudio) R.string.playlist_add_songs_success else R.string.playlist_add_videos_success,
                          compatibleCandidates.size,
                        )
                      Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                      onItemsAdded()
                      onSuccess()
                      onDismiss()
                    } catch (cancelled: CancellationException) {
                      throw cancelled
                    } catch (_: Exception) {
                      submittingId = null
                      Toast.makeText(context, R.string.playlist_add_failed, Toast.LENGTH_SHORT).show()
                    }
                  }
                },
              )
            }
          }
        }
        query.isNotBlank() -> EmptyPlaylistsMessage(
          title = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.playlist_search_empty),
          subtitle = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.playlist_search_empty_hint),
        )
        else -> EmptyPlaylistsMessage()
      }
    }
  }
}

@Composable
private fun PlaylistItemCard(
  option: PlaylistOption,
  enabled: Boolean,
  isWorking: Boolean,
  onClick: () -> Unit,
) {
  Card(
    modifier =
      Modifier
        .fillMaxWidth()
        .clip(AppShapeScale.medium)
        .clickable(enabled = enabled, onClick = onClick)
        .alpha(if (enabled || isWorking) 1f else 0.55f),
    shape = AppShapeScale.medium,
    colors =
      CardDefaults.cardColors(
        containerColor =
          if (LocalPlayerGlassSurfaceActive.current) {
            playerSheetSelectionColor(isWorking)
          } else {
            MaterialTheme.colorScheme.surfaceVariant
          },
      ),
  ) {
    Row(
      modifier =
        Modifier
          .fillMaxWidth()
          .padding(16.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (isWorking) {
        CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
      } else {
        Icon(
          imageVector = Icons.RoundedFilled.PlaylistPlay,
          contentDescription = null,
          modifier = Modifier.size(40.dp),
          tint = MaterialTheme.colorScheme.primary,
        )
      }
      Spacer(modifier = Modifier.width(12.dp))
      Column(
        modifier = Modifier.weight(1f),
      ) {
        Text(
          text = option.name,
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = pluralStringResource(R.plurals.playlist_item_count_value, option.itemCount, option.itemCount),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
private fun EmptyPlaylistsMessage(
  title: String = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_no_playlists_yet),
  subtitle: String =
    androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_create_your_first_playlist_above),
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = AppShapeScale.medium,
    colors =
      CardDefaults.cardColors(
        containerColor =
          if (LocalPlayerGlassSurfaceActive.current) {
            playerSheetSelectionColor(false)
          } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
          },
      ),
  ) {
    Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .padding(24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Icon(
        imageVector = Icons.RoundedFilled.PlaylistAdd,
        contentDescription = null,
        modifier = Modifier.size(48.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(
        text = subtitle,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun CreatePlaylistDialog(
  onDismiss: () -> Unit,
  onConfirm: (String) -> Unit,
) {
  var playlistName by remember { mutableStateOf("") }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text(
        text =
          androidx.compose.ui.res
            .stringResource(app.gyrolet.mpvrx.R.string.ui_create_new_playlist),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
      )
    },
    text = {
      OutlinedTextField(
        value = playlistName,
        onValueChange = { playlistName = it },
        label = {
          Text(
            androidx.compose.ui.res
              .stringResource(app.gyrolet.mpvrx.R.string.ui_playlist_name),
          )
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
      )
    },
    confirmButton = {
      Button(
        onClick = {
          if (playlistName.isNotBlank()) {
            onConfirm(playlistName.trim())
          }
        },
        enabled = playlistName.isNotBlank(),
        shape = MaterialTheme.shapes.extraLarge,
      ) {
        Text(
          androidx.compose.ui.res
            .stringResource(app.gyrolet.mpvrx.R.string.ui_create),
          fontWeight = FontWeight.Bold,
        )
      }
    },
    dismissButton = {
      TextButton(
        onClick = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
      ) {
        Text(
          androidx.compose.ui.res
            .stringResource(app.gyrolet.mpvrx.R.string.generic_cancel),
          fontWeight = FontWeight.Medium,
        )
      }
    },
    containerColor = MaterialTheme.colorScheme.surface,
    tonalElevation = 6.dp,
    shape = MaterialTheme.shapes.extraLarge,
  )
}

private fun formatDate(timestamp: Long): String {
  val sdf = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
  return sdf.format(Date(timestamp))
}
