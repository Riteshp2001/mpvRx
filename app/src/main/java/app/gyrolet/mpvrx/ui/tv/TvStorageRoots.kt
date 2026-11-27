/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.tv

import android.content.Context
import android.os.Environment
import app.gyrolet.mpvrx.BuildConfig
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.utils.storage.StorageVolumeUtils
import java.io.File
import java.io.IOException
import java.util.Locale

internal data class TvStorageRoot(
  val id: String,
  val directory: File,
  val label: String,
)

internal data class TvStorageLocation(
  val directory: File,
  val label: String,
  val rootId: String? = null,
)

internal data class TvPickerStorage(
  val roots: Set<String>,
  val locations: List<TvStorageLocation>,
)

internal fun tvPickerStorage(context: Context): TvPickerStorage {
  val roots = tvStorageRoots(context)
  val primary = roots.firstOrNull { it.id == "primary" }?.directory
  val appStorage = if (BuildConfig.SCOPED_STORAGE_ONLY) context.getExternalFilesDir(null) else null
  val locations =
    buildList {
      roots.forEach { add(TvStorageLocation(it.directory, it.label, it.id)) }
      if (appStorage != null) {
        add(TvStorageLocation(appStorage, context.getString(R.string.tv_picker_app_storage)))
      }
      if (primary != null) {
        for (name in sharedFolderNames) {
          val directory = File(primary, name)
          if (directory.isDirectory && directory.canRead()) add(TvStorageLocation(directory, name))
        }
      }
    }.distinctBy { it.directory }
  return TvPickerStorage(roots.mapTo(mutableSetOf()) { it.directory.absolutePath }, locations)
}

/** The browser and its document URIs must agree on roots, including OEM USB mounts. */
internal fun tvStorageRoots(context: Context): List<TvStorageRoot> {
  val volumes = StorageVolumeUtils.getAllStorageVolumes(context)
  val primary = Environment.getExternalStorageDirectory()
  val primaryLabel =
    volumes.firstOrNull { it.isPrimary }?.getDescription(context)
      ?: context.getString(R.string.tv_picker_internal_storage)
  val candidates =
    buildList {
      add(TvStorageRoot("primary", primary, primaryLabel))
      for (volume in volumes) {
        if (volume.isPrimary) continue
        val directory = StorageVolumeUtils.getVolumePath(volume)?.let(::File) ?: continue
        add(TvStorageRoot(volume.uuid ?: directory.name, directory, volume.getDescription(context)))
      }

      // Some TV firmwares mount USB drives without exposing them via StorageManager.
      for (parent in tvMountDirectories) {
        val mountDirectory = File(parent).safeCanonicalFile() ?: continue
        for (child in mountDirectory.listFiles().orEmpty().sortedBy { it.name }) {
          if (child.name in ignoredMountNames || !child.isDirectory || !child.canRead()) continue
          val directory = child.safeCanonicalFile() ?: continue
          if (directory.parentFile != mountDirectory) continue
          add(TvStorageRoot(child.name, directory, context.getString(R.string.tv_picker_removable_storage, child.name)))
        }
      }
    }
  val canonical =
    candidates.filter { it.id.isNotBlank() && ':' !in it.id }.mapNotNull { root ->
      root.directory.safeCanonicalFile()?.let { root.copy(directory = it) }
    }
  // Prefer a usable mount if an OEM reports the same volume at an inaccessible alias.
  return canonical
    .sortedByDescending { it.id == "primary" || it.directory.canRead() }
    .distinctBy { it.id.lowercase(Locale.ROOT) }
    .distinctBy { it.directory }
}

private val tvMountDirectories = listOf("/storage", "/mnt/media_rw", "/mnt/usb", "/mnt/usb_storage")
private val ignoredMountNames = setOf("emulated", "self", "enc_emulated")
private val sharedFolderNames =
  listOf(
    Environment.DIRECTORY_DOWNLOADS,
    Environment.DIRECTORY_MOVIES,
    Environment.DIRECTORY_MUSIC,
    Environment.DIRECTORY_PICTURES,
    Environment.DIRECTORY_DCIM,
  )

private fun File.safeCanonicalFile(): File? =
  try {
    canonicalFile
  } catch (_: IOException) {
    null
  }
