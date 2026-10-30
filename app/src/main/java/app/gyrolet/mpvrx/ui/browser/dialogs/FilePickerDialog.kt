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
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.MarqueeAnimationMode
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.presentation.components.AppPickerSheet
import app.gyrolet.mpvrx.presentation.components.PlayerSheetSearchField
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.utils.storage.StorageVolumeUtils
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FilePickerDialog(
  modifier: Modifier = Modifier,
  isOpen: Boolean,
  currentPath: String = Environment.getExternalStorageDirectory().absolutePath,
  onDismiss: () -> Unit,
  onFileSelected: (String) -> Unit,
  onPathChanged: ((String?) -> Unit)? = null,
  onSystemPickerRequest: () -> Unit,
  matchToName: String? = null,
  allowedExtensions: List<String> =
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
    ),
  title: String? = null,
) {
  if (!isOpen) return

  val context = LocalContext.current

  // Get all available storage volumes
  val storageVolumes =
    remember(isOpen) {
      StorageVolumeUtils.getAllStorageVolumes(context)
    }

  // If there's only one storage volume, start there directly
  // Otherwise, start at storage root view to show all volumes
  // Respect currentPath if it's valid and exists
  var selectedPath by rememberSaveable(isOpen, currentPath, storageVolumes.size) {
    val requested = File(currentPath)
    val initialPath = when {
      requested.isDirectory -> requested.absolutePath
      requested.isFile -> requested.parentFile?.absolutePath
      storageVolumes.size == 1 -> StorageVolumeUtils.getVolumePath(storageVolumes.first())
      else -> null
    }
    mutableStateOf(initialPath)
  }
  var query by rememberSaveable(isOpen) { mutableStateOf("") }

  // Notify parent when path changes for state persistence
  LaunchedEffect(selectedPath) {
    onPathChanged?.invoke(selectedPath)
    query = ""
  }

  // Determine what to show based on selectedPath
  val showStorageRoot = selectedPath == null

  val currentDir =
    remember(selectedPath) {
      selectedPath?.let { File(it) }
    }

  // One deduplicated snapshot supplies both keys and rows. Classify each path once and
  // namespace its key so it cannot collide with a file, folder or volume row.
  // Keep directory reads and file-type checks off the main thread while video is playing.
  val listing by produceState<FilePickerListing?>(null, selectedPath, matchToName, allowedExtensions) {
    val path = selectedPath
    value = withContext(Dispatchers.IO) {
      val extensions = allowedExtensions.map { it.trim().removePrefix(".").lowercase() }
        .filter(String::isNotBlank).toSet()
      val entries = runCatching { path?.let { File(it).listFiles() } }.getOrNull().orEmpty()
        .distinctBy { it.absolutePath }
        .mapNotNull { file ->
          when {
            file.isDirectory -> FilePickerEntry(file, isDirectory = true)
            file.isFile && (extensions.isEmpty() || file.extension.lowercase() in extensions) ->
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
      FilePickerListing(path, entries)
    }
  }
  val currentListing = listing?.takeIf { it.path == selectedPath }
  val entries = remember(currentListing, query) {
    val normalizedQuery = query.trim()
    currentListing?.entries.orEmpty().filter {
      normalizedQuery.isEmpty() || it.file.name.contains(normalizedQuery, ignoreCase = true)
    }
  }

  AppPickerSheet(
    onDismissRequest = onDismiss,
    title = title ?: androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_select_subtitle),
    subtitle = selectedPath
      ?: androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_select_storage_location),
    scrollContent = false,
    headerActions = {
      NavigationButtons(
        selectedPath = selectedPath,
        onBack = { selectedPath = currentDir?.parent },
        onHome = { selectedPath = Environment.getExternalStorageDirectory().absolutePath },
        onSystemPicker = onSystemPickerRequest,
        buttonSize = 40.dp,
        iconSize = 24.dp,
      )
    },
    actions = {
      TextButton(onClick = onDismiss) {
        Text(
          androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.generic_cancel),
          fontWeight = FontWeight.Medium,
        )
      }
    },
    modifier = modifier,
  ) {
    BackHandler(enabled = selectedPath != null) {
      selectedPath = currentDir?.parent
    }
    Column(
      modifier = Modifier.fillMaxWidth(),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      if (!showStorageRoot) {
        PlayerSheetSearchField(
          query = query,
          onQueryChange = { query = it },
          placeholder = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_search_files),
        )
      }

      LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        if (showStorageRoot) {
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
                androidx.compose.ui.res.stringResource(
                  app.gyrolet.mpvrx.R.string.ui_no_storage_devices_found,
                ),
                modifier = Modifier.padding(16.dp),
              )
            }
          }
        } else {
          items(entries, key = { it.key }, contentType = { it.isDirectory }) { entry ->
            if (entry.isDirectory) {
              FolderItem(folder = entry.file, onClick = { selectedPath = entry.file.absolutePath })
            } else {
              FileItem(file = entry.file, onClick = { onFileSelected(entry.file.absolutePath) })
            }
          }
          if (currentListing == null) {
            item(key = "loading") {
              CircularProgressIndicator(modifier = Modifier.padding(16.dp).size(24.dp))
            }
          } else if (entries.isEmpty()) {
            item {
              Text(
                androidx.compose.ui.res.stringResource(
                  if (query.isBlank()) {
                    app.gyrolet.mpvrx.R.string.ui_no_folders_or_supported_files
                  } else {
                    app.gyrolet.mpvrx.R.string.ui_no_matching_files
                  },
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

  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(12.dp))
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
        text = description,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = volumePath,
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
        .clip(RoundedCornerShape(12.dp))
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
) {
  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(12.dp))
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
        Modifier.basicMarquee(
          animationMode = MarqueeAnimationMode.Immediately,
          repeatDelayMillis = 2000,
        ),
    )
  }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun NavigationButtons(
  selectedPath: String?,
  onBack: () -> Unit,
  onHome: () -> Unit,
  onSystemPicker: () -> Unit,
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

  FilledTonalIconButton(
    onClick = onHome,
    modifier = Modifier.size(buttonSize),
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

private data class FilePickerEntry(val file: File, val isDirectory: Boolean) {
  val key: String = "${if (isDirectory) "folder" else "file"}:${file.absolutePath}"
}

private data class FilePickerListing(val path: String?, val entries: List<FilePickerEntry>)
