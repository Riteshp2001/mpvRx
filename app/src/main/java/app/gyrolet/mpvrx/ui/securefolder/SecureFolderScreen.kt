/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
 */

package app.gyrolet.mpvrx.ui.securefolder

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.gyrolet.mpvrx.database.entities.SecureMediaEntity
import app.gyrolet.mpvrx.domain.media.model.Video
import app.gyrolet.mpvrx.domain.thumbnail.ThumbnailRepository
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.ui.browser.states.EmptyState
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.popSafely
import app.gyrolet.mpvrx.utils.media.MediaUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject
import java.text.DecimalFormat
import kotlin.math.roundToInt
import kotlin.math.ln
import kotlin.math.pow

/**
 * The unlocked Secure Folder: a grid of everything currently hidden away, with multi-select
 * for bulk restore/delete, plus overflow-menu actions to hide/unhide the "Secure Folder" entry
 * point from the Preferences screen and to change the PIN / security question.
 *
 * Restore and delete-forever go through [SecureConfirmDialog] first (skippable per-action via
 * "don't ask again"), and a busy operation shows [SecureFolderProgressDialog] instead of the
 * old inline progress bar.
 */
@Serializable
data object SecureFolderScreen : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val context = LocalContext.current
    val backstack = LocalBackStack.current
    val viewModel: SecureFolderViewModel =
      viewModel(factory = SecureFolderViewModel.factory(context.applicationContext as android.app.Application))

    val media by viewModel.secureMedia.collectAsState()
    val selectedIds by viewModel.selectedIds.collectAsState()
    val isInSelectionMode by viewModel.isInSelectionMode.collectAsState()
    val isBusy by viewModel.isBusy.collectAsState()
    val operationProgress by viewModel.operationProgress.collectAsState()
    val operationResult by viewModel.operationResult.collectAsState()
    val isEntryPointHidden by viewModel.preferences.isEntryPointHidden.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val gridState = rememberLazyGridState()

    // Which confirm dialog (if any) is currently open — restore/delete share one flow since
    // only one bulk action can be pending at a time.
    var pendingAction by remember { mutableStateOf<PendingAction?>(null) }
    var changePinOpen by remember { mutableStateOf(false) }
    var changeSecurityQuestionOpen by remember { mutableStateOf(false) }
    // Confirmed separately from restore/delete since it's not a destructive data action, but
    // still needs a heads-up: once hidden, the only way back in is the header double-tap.
    var hideEntryPointConfirmOpen by remember { mutableStateOf(false) }

    LaunchedEffect(operationResult) {
      operationResult?.let {
        snackbarHostState.showSnackbar(it)
        viewModel.clearOperationResult()
      }
    }

    Scaffold(
      topBar = {
        SecureFolderTopBar(
          isInSelectionMode = isInSelectionMode,
          selectedCount = selectedIds.size,
          totalCount = media.size,
          isEntryPointHidden = isEntryPointHidden,
          isBusy = isBusy,
          onBack = { backstack.popSafely() },
          onSelectAll = { viewModel.selectAll() },
          onDeselectAll = { viewModel.clearSelection() },
          onRestoreRequest = {
            if (viewModel.preferences.dontAskBeforeRestore.get()) {
              viewModel.restoreSelected()
            } else {
              pendingAction = PendingAction.RESTORE
            }
          },
          onDeleteRequest = {
            if (viewModel.preferences.dontAskBeforeDelete.get()) {
              viewModel.deleteSelectedForever()
            } else {
              pendingAction = PendingAction.DELETE
            }
          },
          onToggleEntryPointHidden = {
            if (isEntryPointHidden) {
              // Un-hiding needs no confirmation — it only makes the entry more visible again.
              viewModel.toggleEntryPointHidden()
            } else if (viewModel.preferences.dontAskBeforeHideEntryPoint.get()) {
              viewModel.toggleEntryPointHidden()
            } else {
              hideEntryPointConfirmOpen = true
            }
          },
          onChangePin = { changePinOpen = true },
          onChangeSecurityQuestion = { changeSecurityQuestionOpen = true },
        )
      },
      snackbarHost = {
        SnackbarHost(snackbarHostState) { data ->
          Snackbar(snackbarData = data)
        }
      },
    ) { padding ->
      Column(
        modifier =
          Modifier
            .fillMaxSize()
            .padding(padding)
            .windowInsetsPadding(WindowInsets.systemBars),
      ) {
        if (media.isEmpty()) {
          Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(
              icon = Icons.RoundedFilled.Lock,
              title = stringResource(R.string.secure_folder_empty_title),
              message = stringResource(R.string.secure_folder_empty_message),
            )
          }
        } else {
          LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 110.dp),
            state = gridState,
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
          ) {
            items(media, key = { it.id }) { entity ->
              SecureMediaCard(
                entity = entity,
                isSelected = selectedIds.contains(entity.id),
                isInSelectionMode = isInSelectionMode,
                onClick = {
                  if (isInSelectionMode) {
                    viewModel.toggleSelection(entity.id)
                  } else {
                    MediaUtils.playFile(
                      entity.secureFilePath,
                      context,
                      launchSource = "secure_folder",
                      title = entity.fileName,
                    )
                  }
                },
                onLongClick = { viewModel.handleLongClick(entity.id) },
              )
            }
          }
        }
      }
    }

    SecureConfirmDialog(
      isOpen = pendingAction == PendingAction.RESTORE,
      title = stringResource(R.string.secure_folder_restore_confirm_title, selectedIds.size),
      subtitle = stringResource(R.string.secure_folder_restore_confirm_subtitle),
      dontAskAgain = viewModel.preferences.dontAskBeforeRestore,
      onConfirm = {
        pendingAction = null
        viewModel.restoreSelected()
      },
      onDismiss = { pendingAction = null },
    )

    SecureConfirmDialog(
      isOpen = pendingAction == PendingAction.DELETE,
      title = stringResource(R.string.secure_folder_delete_confirm_title, selectedIds.size),
      subtitle = stringResource(R.string.secure_folder_delete_confirm_subtitle),
      dontAskAgain = viewModel.preferences.dontAskBeforeDelete,
      onConfirm = {
        pendingAction = null
        viewModel.deleteSelectedForever()
      },
      onDismiss = { pendingAction = null },
    )

    SecureConfirmDialog(
      isOpen = hideEntryPointConfirmOpen,
      title = stringResource(R.string.secure_folder_hide_entry_title),
      subtitle = stringResource(R.string.secure_folder_hide_entry_subtitle),
      dontAskAgain = viewModel.preferences.dontAskBeforeHideEntryPoint,
      onConfirm = {
        viewModel.toggleEntryPointHidden()
        hideEntryPointConfirmOpen = false
      },
      onDismiss = { hideEntryPointConfirmOpen = false },
    )

    SecureFolderProgressDialog(
      isOpen = isBusy,
      progress = operationProgress,
      label = stringResource(R.string.secure_folder_working_on_it),
      onCancel = { viewModel.cancelCurrentOperation() },
    )

    ChangePinDialog(
      isOpen = changePinOpen,
      preferences = viewModel.preferences,
      onDismiss = { changePinOpen = false },
      onChanged = {
        // No separate ViewModel state to update — SecureFolderPreferences already
        // persisted the new hash, and the gate re-reads it on next entry.
      },
    )

    ChangeSecurityQuestionDialog(
      isOpen = changeSecurityQuestionOpen,
      preferences = viewModel.preferences,
      onDismiss = { changeSecurityQuestionOpen = false },
      onChanged = {},
    )
  }
}

private enum class PendingAction { RESTORE, DELETE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SecureFolderTopBar(
  isInSelectionMode: Boolean,
  selectedCount: Int,
  totalCount: Int,
  isEntryPointHidden: Boolean,
  isBusy: Boolean,
  onBack: () -> Unit,
  onSelectAll: () -> Unit,
  onDeselectAll: () -> Unit,
  onRestoreRequest: () -> Unit,
  onDeleteRequest: () -> Unit,
  onToggleEntryPointHidden: () -> Unit,
  onChangePin: () -> Unit,
  onChangeSecurityQuestion: () -> Unit,
) {
  if (isInSelectionMode) {
    TopAppBar(
      title = { Text(stringResource(R.string.secure_folder_selected_count, selectedCount)) },
      navigationIcon = {
        IconButton(onClick = onDeselectAll) {
          Icon(Icons.RoundedFilled.Close, contentDescription = stringResource(R.string.secure_folder_cancel_selection))
        }
      },
      actions = {
        IconButton(onClick = onSelectAll, enabled = !isBusy && selectedCount < totalCount) {
          Icon(Icons.RoundedFilled.SelectAll, contentDescription = stringResource(R.string.select_all))
        }
        IconButton(onClick = onRestoreRequest, enabled = !isBusy && selectedCount > 0) {
          Icon(Icons.RoundedFilled.Restore, contentDescription = stringResource(R.string.secure_folder_restore))
        }
        IconButton(onClick = onDeleteRequest, enabled = !isBusy && selectedCount > 0) {
          Icon(Icons.RoundedFilled.Delete, contentDescription = stringResource(R.string.secure_folder_delete_forever), tint = MaterialTheme.colorScheme.error)
        }
      },
    )
  } else {
    var menuExpanded by remember { mutableStateOf(false) }
    TopAppBar(
      title = { Text(stringResource(R.string.secure_folder_title)) },
      navigationIcon = {
        IconButton(onClick = onBack) {
          Icon(Icons.RoundedFilled.ArrowBack, contentDescription = null)
        }
      },
      actions = {
        Box {
          IconButton(onClick = { menuExpanded = true }) {
            Icon(Icons.RoundedFilled.MoreVert, contentDescription = null)
          }
          DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            DropdownMenuItem(
              text = {
                Text(
                  if (isEntryPointHidden) {
                    stringResource(R.string.secure_folder_show_in_preferences)
                  } else {
                    stringResource(R.string.secure_folder_hide_from_preferences)
                  }
                )
              },
              leadingIcon = {
                Icon(
                  if (isEntryPointHidden) Icons.RoundedFilled.Visibility else Icons.RoundedFilled.VisibilityOff,
                  contentDescription = null,
                )
              },
              onClick = {
                onToggleEntryPointHidden()
                menuExpanded = false
              },
            )
            DropdownMenuItem(
              text = { Text(stringResource(R.string.secure_folder_change_pin)) },
              leadingIcon = { Icon(Icons.RoundedFilled.Lock, contentDescription = null) },
              onClick = {
                onChangePin()
                menuExpanded = false
              },
            )
            DropdownMenuItem(
              text = { Text(stringResource(R.string.secure_folder_change_security_question)) },
              leadingIcon = { Icon(Icons.RoundedFilled.HelpOutline, contentDescription = null) },
              onClick = {
                onChangeSecurityQuestion()
                menuExpanded = false
              },
            )
          }
        }
      },
      colors = TopAppBarDefaults.topAppBarColors(),
    )
  }
}

@Composable
private fun SecureMediaCard(
  entity: SecureMediaEntity,
  isSelected: Boolean,
  isInSelectionMode: Boolean,
  onClick: () -> Unit,
  onLongClick: () -> Unit,
) {
  // Secure files live in app-private storage, outside MediaStore, so they aren't backed by a
  // real Video row anywhere. ThumbnailRepository only needs a local file path though (it uses
  // MediaMetadataRetriever.setDataSource(video.path) for anything without a "://" scheme), so
  // a minimal Video wrapper around the secure file path lets us reuse the same thumbnail
  // pipeline/cache as every other screen instead of building a separate one.
  val thumbnailRepository = koinInject<ThumbnailRepository>()
  val isAudio = entity.mimeType.startsWith("audio/")
  val video =
    remember(entity.id, entity.secureFilePath) {
      Video(
        id = entity.id,
        title = entity.fileName,
        displayName = entity.fileName,
        path = entity.secureFilePath,
        uri = android.net.Uri.fromFile(java.io.File(entity.secureFilePath)),
        duration = 0L,
        durationFormatted = "",
        size = entity.fileSize,
        sizeFormatted = "",
        dateModified = entity.dateHidden,
        dateAdded = entity.dateHidden,
        mimeType = entity.mimeType,
        bucketId = "",
        bucketDisplayName = "",
        width = 0,
        height = 0,
        fps = 0f,
        resolution = "--",
        isAudio = isAudio,
      )
    }

  val thumbWidthPx = with(LocalDensity.current) { 160.dp.roundToPx() }
  val aspect = if (isAudio) 1f else 16f / 9f
  val thumbHeightPx = (thumbWidthPx / aspect).roundToInt()

  val thumbnailKey =
    remember(video.id, video.dateModified, video.size, thumbWidthPx, thumbHeightPx) {
      thumbnailRepository.thumbnailKey(video, thumbWidthPx, thumbHeightPx)
    }

  var thumbnail by
    remember(thumbnailKey) {
      mutableStateOf(thumbnailRepository.getThumbnailFromMemory(video, thumbWidthPx, thumbHeightPx))
    }

  LaunchedEffect(thumbnailKey) {
    thumbnailRepository.thumbnailReadyKeys
      .filter { key -> thumbnailRepository.isThumbnailKeyForVideo(key, video) }
      .collect {
        thumbnail =
          withContext(Dispatchers.IO) {
            thumbnailRepository.getCachedThumbnail(video, thumbWidthPx, thumbHeightPx)
          }
      }
  }

  LaunchedEffect(thumbnailKey) {
    if (thumbnail == null && !isAudio) {
      thumbnail =
        withContext(Dispatchers.IO) {
          thumbnailRepository.getThumbnail(video, thumbWidthPx, thumbHeightPx)
        }
    }
  }

  Card(
    modifier =
      Modifier
        .fillMaxWidth()
        .aspectRatio(0.8f)
        .combinedClickable(
          interactionSource = remember { MutableInteractionSource() },
          indication = null,
          onClick = onClick,
          onLongClick = onLongClick,
        ),
    colors =
      CardDefaults.cardColors(
        containerColor =
          if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer
          } else {
            MaterialTheme.colorScheme.surfaceVariant
          },
      ),
  ) {
    Box(modifier = Modifier.fillMaxSize()) {
      Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Box(
          modifier =
            Modifier
              .fillMaxWidth()
              .aspectRatio(aspect)
              .background(MaterialTheme.colorScheme.surfaceContainerHigh),
          contentAlignment = Alignment.Center,
        ) {
          thumbnail?.let {
            Image(
              bitmap = it.asImageBitmap(),
              contentDescription = entity.fileName,
              modifier = Modifier.matchParentSize(),
              contentScale = ContentScale.Crop,
            )
          } ?: run {
            Icon(
              if (entity.mimeType.startsWith("image/")) Icons.RoundedFilled.CameraAlt else Icons.RoundedFilled.Movie,
              contentDescription = null,
              modifier = Modifier.size(36.dp),
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }

        Column(
          modifier = Modifier.fillMaxWidth().padding(8.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          Text(
            entity.fileName,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
          )
          Text(
            formatFileSize(entity.fileSize),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      if (isInSelectionMode) {
        Box(
          modifier =
            Modifier
              .padding(6.dp)
              .align(Alignment.TopEnd)
              .size(22.dp)
              .clip(RoundedCornerShape(50))
              .background(
                if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
              ),
          contentAlignment = Alignment.Center,
        ) {
          if (isSelected) {
            Icon(
              Icons.RoundedFilled.Check,
              contentDescription = null,
              modifier = Modifier.size(16.dp),
              tint = MaterialTheme.colorScheme.onPrimary,
            )
          }
        }
      }
    }
  }
}

private fun formatFileSize(bytes: Long): String {
  if (bytes <= 0) return "0 B"
  val units = arrayOf("B", "KB", "MB", "GB")
  val digitGroups = (ln(bytes.toDouble()) / ln(1024.0)).toInt().coerceIn(0, units.size - 1)
  return "${DecimalFormat("#,##0.#").format(bytes / 1024.0.pow(digitGroups))} ${units[digitGroups]}"
}
