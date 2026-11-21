/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.dialogs

import android.content.Context
import android.os.Environment
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.MarqueeAnimationMode
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.presentation.components.AppPickerSheet
import app.gyrolet.mpvrx.presentation.components.PlayerSheetSearchField
import app.gyrolet.mpvrx.ui.icons.AppIcon
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.controls.components.rememberTvInitialFocusRequester
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusHighlight
import app.gyrolet.mpvrx.ui.player.controls.components.tvInitialFocus
import app.gyrolet.mpvrx.ui.tv.TvPickerStorage
import app.gyrolet.mpvrx.utils.storage.FileTypeUtils
import app.gyrolet.mpvrx.utils.storage.StorageVolumeUtils
import app.gyrolet.mpvrx.utils.storage.mediaPathKey
import app.gyrolet.mpvrx.utils.storage.normalizeStoragePath
import app.gyrolet.mpvrx.utils.storage.storagePickerParentPath
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal val subtitlePickerExtensions =
  listOf(
    // Common & modern
    "srt",
    "vtt",
    "ass",
    "ssa",
    // DVD / Blu-ray
    "sub",
    "idx",
    "sup",
    // Streaming / XML / Professional
    "xml",
    "ttml",
    "dfxp",
    "itt",
    "ebu",
    "imsc",
    "usf",
    // Online platforms
    "sbv",
    "srv1",
    "srv2",
    "srv3",
    "json",
    // Legacy & niche
    "sami",
    "smi",
    "mpl",
    "pjs",
    "stl",
    "rt",
    "psb",
    "cap",
    // Broadcast captions
    "scc",
    "vttx",
    // Karaoke / lyrics
    "lrc",
    "krc",
    // Fallback / raw text
    "txt",
  )

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun FilePickerDialog(
  modifier: Modifier = Modifier,
  isOpen: Boolean,
  currentPath: String? = Environment.getExternalStorageDirectory().absolutePath,
  onDismiss: () -> Unit,
  onFileSelected: (String) -> Unit,
  onPathChanged: ((String?) -> Unit)? = null,
  onSystemPickerRequest: (() -> Unit)?,
  matchToName: String? = null,
  allowedExtensions: List<String> = subtitlePickerExtensions,
  title: String? = null,
  allowedMimeTypes: List<String>? = null,
  onFilesSelected: ((List<String>) -> Unit)? = null,
  tvStorage: TvPickerStorage? = null,
  storageRefresh: Int = 0,
  onStorageAccessRequest: (() -> Unit)? = null,
  onReturnToStorageLocations: (() -> Unit)? = null,
) {
  if (!isOpen) return

  val context = LocalContext.current
  val embeddedTvPicker = tvStorage != null

  // Get all available storage volumes
  val storageVolumes =
    remember(embeddedTvPicker) {
      if (embeddedTvPicker) emptyList() else StorageVolumeUtils.getAllStorageVolumes(context)
    }

  // If there's only one storage volume, start there directly
  // Otherwise, start at storage root view to show all volumes
  // Respect currentPath if it's valid and exists
  var selectedPath by rememberSaveable(isOpen, currentPath, storageVolumes.size) {
    val requested = currentPath?.let(::File)
    val initialPath = when {
      embeddedTvPicker -> currentPath
      requested?.isDirectory == true -> requested.absolutePath
      requested?.isFile == true -> requested.parentFile?.absolutePath
      storageVolumes.size == 1 -> StorageVolumeUtils.getVolumePath(storageVolumes.first())
      else -> null
    }
    mutableStateOf(initialPath)
  }
  var query by rememberSaveable(isOpen) { mutableStateOf("") }
  var selectedPaths by rememberSaveable(isOpen) { mutableStateOf(emptyList<String>()) }

  // Notify parent when path changes for state persistence
  LaunchedEffect(selectedPath) {
    onPathChanged?.invoke(selectedPath)
    query = ""
  }

  // Determine what to show based on selectedPath
  val showStorageRoot = selectedPath == null

  val storageRoots = remember(storageVolumes, tvStorage?.roots) {
    tvStorage?.roots ?: (storageVolumes.mapNotNull(StorageVolumeUtils::getVolumePath) +
      Environment.getExternalStorageDirectory().absolutePath)
      .mapNotNull(::normalizeStoragePath).distinctBy(::mediaPathKey).toSet()
  }
  val parentPath = remember(selectedPath, storageRoots) {
    storagePickerParentPath(selectedPath, storageRoots)
  }
  val navigateUp: () -> Unit = {
    selectedPath = parentPath
    if (parentPath == null) onReturnToStorageLocations?.invoke()
  }

  // One deduplicated snapshot supplies both keys and rows. Classify each path once and
  // namespace its key so it cannot collide with a file, folder or volume row.
  // Keep directory reads and file-type checks off the main thread while video is playing.
  val listing by produceState<FilePickerListing?>(
    null, selectedPath, matchToName, allowedExtensions, allowedMimeTypes, storageRefresh,
  ) {
    val path = selectedPath
    value = withContext(Dispatchers.IO) {
      val extensions = allowedExtensions.map { it.trim().removePrefix(".").lowercase() }
        .filter(String::isNotBlank).toSet()
      val children = runCatching { path?.let { File(it).listFiles() } }.getOrNull()
      val entries = children.orEmpty()
        .distinctBy { it.absolutePath }
        .mapNotNull { file ->
          when {
            file.isDirectory -> FilePickerEntry(file, isDirectory = true)
            file.isFile && (!embeddedTvPicker || file.canRead()) && if (allowedMimeTypes != null) {
              matchesPickerMimeType(file, allowedMimeTypes)
            } else {
              extensions.isEmpty() || file.extension.lowercase() in extensions
            } ->
              FilePickerEntry(file, isDirectory = false)
            else -> null
          }
        }
        .sortedWith { first, second ->
          val group = second.isDirectory.compareTo(first.isDirectory)
          val firstMatches = !first.isDirectory && matchToName != null && first.file.name.contains(matchToName, ignoreCase = true)
          val secondMatches = !second.isDirectory && matchToName != null && second.file.name.contains(matchToName, ignoreCase = true)
          when {
            group != 0 -> group
            firstMatches != secondMatches -> if (firstMatches) -1 else 1
            else -> app.gyrolet.mpvrx.utils.sort.SortUtils.NaturalOrderComparator.DEFAULT
              .compare(first.file.name, second.file.name)
          }
        }
      FilePickerListing(path, entries, path == null || children != null)
    }
  }
  val currentListing = listing?.takeIf { it.path == selectedPath }
  val entries = remember(currentListing, query) {
    val normalizedQuery = query.trim()
    currentListing?.entries.orEmpty().filter {
      normalizedQuery.isEmpty() || it.file.name.contains(normalizedQuery, ignoreCase = true)
    }
  }
  val canGrantAccess = onStorageAccessRequest != null
  val initialFocusRequester = rememberTvInitialFocusRequester(
    enabled = !embeddedTvPicker || showStorageRoot || currentListing != null,
    requestKey = if (embeddedTvPicker) selectedPath to canGrantAccess else isOpen,
  )
  val focusFirstRow = embeddedTvPicker && if (showStorageRoot) {
    !tvStorage?.locations.isNullOrEmpty()
  } else {
    entries.isNotEmpty()
  }
  val shape = RoundedCornerShape(12.dp)

  AppPickerSheet(
    onDismissRequest = onDismiss,
    title = title ?: androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_select_subtitle),
    subtitle = selectedPath
      ?: androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_select_storage_location),
    scrollContent = false,
    fixedHeight = true,
    embeddedTvPicker = embeddedTvPicker,
    headerActions = {
      NavigationButtons(
        selectedPath = selectedPath,
        onBack = navigateUp,
        onHome = when {
          !embeddedTvPicker -> ({ selectedPath = storageRoots.singleOrNull() })
          showStorageRoot -> null
          else -> ({
            selectedPath = null
            onReturnToStorageLocations?.invoke()
          })
        },
        onSystemPicker = if (embeddedTvPicker) null else onSystemPickerRequest,
        initialFocusRequester = if (embeddedTvPicker) null else initialFocusRequester,
        buttonSize = 40.dp,
        iconSize = 24.dp,
      )
    },
    actions = {
      if (embeddedTvPicker && onStorageAccessRequest != null) {
        TextButton(
          onClick = onStorageAccessRequest,
          modifier = Modifier.tvFocusHighlight(shape).then(
            if (focusFirstRow) Modifier else Modifier.tvInitialFocus(initialFocusRequester),
          ),
        ) {
          Text(androidx.compose.ui.res.stringResource(R.string.storage_permission_grant))
        }
      }
      if (embeddedTvPicker && onSystemPickerRequest != null) {
        TextButton(onClick = onSystemPickerRequest, modifier = Modifier.tvFocusHighlight(shape)) {
          Text(androidx.compose.ui.res.stringResource(R.string.ui_system_picker))
        }
      }
      TextButton(
        onClick = onDismiss,
        modifier = if (embeddedTvPicker) {
          Modifier.tvFocusHighlight(shape).then(
            if (focusFirstRow || canGrantAccess) Modifier else Modifier.tvInitialFocus(initialFocusRequester),
          )
        } else {
          Modifier
        },
      ) {
        Text(
          androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.generic_cancel),
          fontWeight = FontWeight.Medium,
        )
      }
      if (onFilesSelected != null) {
        TextButton(
          onClick = { onFilesSelected(selectedPaths) },
          enabled = selectedPaths.isNotEmpty(),
          modifier = if (embeddedTvPicker) Modifier.tvFocusHighlight(shape) else Modifier,
        ) {
          Text("${androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_done)} (${selectedPaths.size})")
        }
      }
    },
    modifier = modifier,
  ) {
    BackHandler(enabled = embeddedTvPicker || selectedPath != null) {
      if (showStorageRoot) onDismiss() else navigateUp()
    }
    Column(
      modifier = Modifier.fillMaxSize(),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      if (!showStorageRoot) {
        PlayerSheetSearchField(
          query = query,
          onQueryChange = { query = it },
          placeholder = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_search_files),
        )
      }

      key(if (embeddedTvPicker) selectedPath else Unit) {
        LazyColumn(
          modifier = Modifier.fillMaxWidth().weight(1f),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          if (showStorageRoot && tvStorage != null) {
            itemsIndexed(tvStorage.locations, key = { _, item -> item.directory.absolutePath }) { index, location ->
              StorageLocationItem(
                label = location.label,
                path = location.directory.absolutePath,
                icon = when (location.rootId) {
                  "primary" -> Icons.RoundedFilled.Home
                  null -> Icons.RoundedFilled.Folder
                  else -> Icons.RoundedFilled.Usb
                },
                onClick = { selectedPath = location.directory.absolutePath },
                modifier = if (index == 0) Modifier.tvInitialFocus(initialFocusRequester) else Modifier,
              )
            }
          } else if (showStorageRoot) {
            items(storageVolumes, key = { "volume:${StorageVolumeUtils.getVolumePath(it) ?: it.toString()}" }) { volume ->
              val volumePath = StorageVolumeUtils.getVolumePath(volume)
              if (volumePath != null) {
                StorageVolumeItem(
                  context = context,
                  volume = volume,
                  volumePath = volumePath,
                  onClick = { selectedPath = volumePath },
                )
              }
            }
            if (storageVolumes.isEmpty()) {
              item {
                Text(
                  androidx.compose.ui.res.stringResource(R.string.ui_no_storage_devices_found),
                  modifier = Modifier.padding(16.dp),
                )
              }
            }
          } else {
            itemsIndexed(
              items = entries,
              key = { _, entry -> entry.key },
              contentType = { _, entry -> entry.isDirectory },
            ) { index, entry ->
              val rowModifier = if (embeddedTvPicker && index == 0) {
                Modifier.tvInitialFocus(initialFocusRequester)
              } else {
                Modifier
              }
              if (entry.isDirectory) {
                FolderItem(
                  folder = entry.file,
                  onClick = { selectedPath = entry.file.absolutePath },
                  modifier = rowModifier,
                )
              } else {
                val path = entry.file.absolutePath
                FileItem(
                  file = entry.file,
                  modifier = rowModifier,
                  checked = if (onFilesSelected != null) path in selectedPaths else null,
                  onClick = {
                    if (onFilesSelected == null) {
                      onFileSelected(path)
                    } else {
                      selectedPaths = if (path in selectedPaths) selectedPaths - path else selectedPaths + path
                    }
                  },
                )
              }
            }
            if (currentListing == null) {
              item(key = "loading") {
                CircularProgressIndicator(modifier = Modifier.padding(16.dp).size(24.dp))
              }
            } else if (!currentListing.accessible) {
              item {
                Text(
                  androidx.compose.ui.res.stringResource(R.string.tv_picker_no_access),
                  modifier = Modifier.padding(16.dp),
                )
              }
            } else if (entries.isEmpty()) {
              item {
                Text(
                  androidx.compose.ui.res.stringResource(
                    if (query.isBlank()) R.string.ui_no_folders_or_supported_files else R.string.ui_no_matching_files,
                  ),
                  modifier = Modifier.padding(16.dp),
                )
              }
            }
          }
        }
      }
    }
  }
}

@Composable
internal fun StorageVolumeItem(
  context: Context,
  volume: android.os.storage.StorageVolume,
  volumePath: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val description = volume.getDescription(context)
  val isPrimary = volume.isPrimary
  val isRemovable = volume.isRemovable

  val icon =
    when {
      isPrimary -> Icons.RoundedFilled.Home
      isRemovable && volumePath.contains("usb", ignoreCase = true) -> Icons.RoundedFilled.Usb
      isRemovable -> Icons.RoundedFilled.SdCard
      else -> Icons.RoundedFilled.Folder
    }

  StorageLocationItem(description, volumePath, icon, onClick, modifier)
}

@Composable
private fun StorageLocationItem(
  label: String,
  path: String,
  icon: AppIcon,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .tvFocusHighlight(RoundedCornerShape(12.dp))
        .clickable(onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 12.dp),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      imageVector = icon,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.primary,
      modifier = Modifier.size(32.dp),
    )
    Column(
      verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      Text(
        text = label,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = path,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun FolderItem(
  folder: File,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .tvFocusHighlight(RoundedCornerShape(12.dp))
        .clickable(onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      imageVector = Icons.RoundedFilled.Folder,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.primary,
      modifier = Modifier.size(28.dp),
    )
    Text(
      text = folder.name,
      style = MaterialTheme.typography.bodyLarge,
      fontWeight = FontWeight.Medium,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier =
        Modifier.basicMarquee(
          animationMode = MarqueeAnimationMode.Immediately,
          repeatDelayMillis = 2000,
        ),
    )
  }
}

@Composable
private fun FileItem(
  file: File,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  checked: Boolean? = null,
) {
  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .tvFocusHighlight(RoundedCornerShape(12.dp))
        .clickable(onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      imageVector = Icons.RoundedFilled.InsertDriveFile,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.secondary,
      modifier = Modifier.size(28.dp),
    )
    Text(
      text = file.name,
      style = MaterialTheme.typography.bodyLarge,
      fontWeight = FontWeight.Normal,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier =
        Modifier.weight(1f).basicMarquee(
          animationMode = MarqueeAnimationMode.Immediately,
          repeatDelayMillis = 2000,
        ),
    )
    if (checked != null) Checkbox(checked = checked, onCheckedChange = null)
  }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun NavigationButtons(
  selectedPath: String?,
  onBack: () -> Unit,
  onHome: (() -> Unit)?,
  onSystemPicker: (() -> Unit)?,
  initialFocusRequester: FocusRequester?,
  buttonSize: Dp,
  iconSize: Dp,
) {
  if (selectedPath != null) {
    FilledTonalIconButton(
      onClick = onBack,
      modifier = Modifier.size(buttonSize),
      colors =
        IconButtonDefaults.filledTonalIconButtonColors(
          containerColor = MaterialTheme.colorScheme.secondaryContainer,
          contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
      Icon(
        Icons.RoundedFilled.ArrowBack,
        androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.generic_back),
        modifier = Modifier.size(iconSize),
      )
    }
  }

  if (onHome != null) {
    FilledTonalIconButton(
      onClick = onHome,
      modifier = Modifier.size(buttonSize).then(
        if (initialFocusRequester == null) Modifier else Modifier.tvInitialFocus(initialFocusRequester),
      ),
      colors =
        IconButtonDefaults.filledTonalIconButtonColors(
          containerColor = MaterialTheme.colorScheme.secondaryContainer,
          contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
      Icon(
        Icons.RoundedFilled.Home,
        androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.generic_home),
        modifier = Modifier.size(iconSize),
      )
    }
  }

  if (onSystemPicker != null) {
    FilledTonalIconButton(
      onClick = onSystemPicker,
      modifier = Modifier.size(buttonSize),
      colors =
        IconButtonDefaults.filledTonalIconButtonColors(
          containerColor = MaterialTheme.colorScheme.tertiaryContainer,
          contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
    ) {
      Icon(
        Icons.RoundedFilled.DriveFolderUpload,
        androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_system_picker),
        modifier = Modifier.size(iconSize),
      )
    }
  }
}

internal fun matchesPickerMimeType(file: File, accepted: List<String>): Boolean {
  val extension = file.extension.lowercase(Locale.ROOT)
  val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
    ?: "application/octet-stream"
  return accepted.any { requested ->
    requested == "*/*" || requested.equals(mimeType, ignoreCase = true) ||
      (requested.endsWith("/*") && requested.substringBefore('/').equals(mimeType.substringBefore('/'), true)) ||
      (requested.equals("video/*", true) && extension in FileTypeUtils.VIDEO_EXTENSIONS) ||
      (requested.equals("audio/*", true) && extension in FileTypeUtils.AUDIO_EXTENSIONS)
  }
}

private data class FilePickerEntry(val file: File, val isDirectory: Boolean) {
  val key: String = "${if (isDirectory) "folder" else "file"}:${file.absolutePath}"
}

private data class FilePickerListing(val path: String?, val entries: List<FilePickerEntry>, val accessible: Boolean)
