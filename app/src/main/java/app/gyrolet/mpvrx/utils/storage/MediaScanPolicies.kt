/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.utils.storage

import android.net.Uri
import android.provider.DocumentsContract
import app.gyrolet.mpvrx.utils.media.isPathBasedDocumentAuthority
import java.io.File
import java.util.Locale

internal fun shouldRunFilesystemVideoCheck(
  forceFileSystemCheck: Boolean,
  mediaStoreResultCount: Int,
): Boolean = forceFileSystemCheck || mediaStoreResultCount == 0

internal fun shouldIncludePrimaryStorageInFilesystemFolderScan(
  options: MediaScanOptions,
  forceFileSystemCheck: Boolean,
): Boolean = forceFileSystemCheck || options.includeNoMediaFolders

internal fun getPrimaryStorageSupplementalScanRoots(primaryStorageRoot: File): List<File> {
  val normalizedRoot = normalizeStoragePath(primaryStorageRoot.absolutePath) ?: return emptyList()
  val candidates =
    listOf(
      File(primaryStorageRoot, "Android/data"),
      File(primaryStorageRoot, "Android/media"),
    )

  return candidates.filter { candidate ->
    val candidatePath = normalizeStoragePath(candidate.absolutePath) ?: return@filter false
    candidate.exists() &&
      candidate.canRead() &&
      candidate.isDirectory &&
      candidatePath.startsWith("$normalizedRoot/")
  }
}

internal fun isAndroidDataAccessiblePath(file: File?): Boolean {
  val normalizedPath =
    runCatching { storagePathKey(file?.absolutePath) }.getOrNull()
      ?: return false

  return normalizedPath.endsWith("/android") || normalizedPath.contains("/android/data")
}

private val REPEATED_SEPARATORS = Regex("/+")

internal fun normalizeStoragePath(path: String?): String? {
  val rawPath = path?.trim()?.replace('\\', '/') ?: return null
  if (rawPath.isBlank()) {
    return null
  }

  // Hot path: runs for every scanned row, and almost no path contains "//".
  val collapsedSeparators =
    if (rawPath.contains("//")) rawPath.replace(REPEATED_SEPARATORS, "/") else rawPath
  val normalized =
    if (collapsedSeparators.length > 1) {
      collapsedSeparators.trimEnd('/')
    } else {
      collapsedSeparators
    }

  return normalized.ifBlank { "/" }
}

internal fun storagePathKey(path: String?): String? = normalizeStoragePath(path)?.lowercase(Locale.ROOT)

internal fun mediaPathKey(path: String?): String? {
  if (path?.startsWith("content://") == true) return path
  val normalizedPath = normalizeStoragePath(path) ?: return null
  val canonicalPath = runCatching { File(normalizedPath).canonicalPath }.getOrNull()
  return storagePathKey(canonicalPath ?: normalizedPath)
}

internal fun isMediaPathInFolder(path: String, folderPath: String): Boolean {
  if (!path.startsWith("content://") || !folderPath.startsWith("content://")) {
    return path.equals(folderPath, ignoreCase = true) ||
      path.startsWith(if (folderPath.endsWith("/")) folderPath else "$folderPath/", ignoreCase = true)
  }
  val item = Uri.parse(path)
  val folder = Uri.parse(folderPath)
  if (item.authority != folder.authority) return false
  if (item == folder) return true
  if (!DocumentsContract.isTreeUri(item) || !DocumentsContract.isTreeUri(folder)) return false
  if (app.gyrolet.mpvrx.utils.media.ScopedHiddenFolderScanner.isIndexedDescendant(path, folderPath)) return true
  val folderSegments = folder.pathSegments
  val itemSegments = item.pathSegments
  if (folderSegments.size == 2) {
    return DocumentsContract.getTreeDocumentId(item) == DocumentsContract.getTreeDocumentId(folder)
  }
  if (folderSegments.size != 4 || folderSegments[2] != "document" ||
    itemSegments.size != 4 || itemSegments[2] != "document"
  ) return false
  val folderId = DocumentsContract.getDocumentId(folder)
  val itemId = DocumentsContract.getDocumentId(item)
  if (itemId == folderId) return true
  return isPathBasedDocumentAuthority(item.authority) &&
    itemId.startsWith("$folderId/")
}

internal fun areEquivalentStoragePaths(
  first: String?,
  second: String?,
): Boolean {
  val firstKey = storagePathKey(first)
  val secondKey = storagePathKey(second)
  return firstKey != null && firstKey == secondKey
}

internal fun parentStoragePath(path: String?): String? {
  val normalizedPath = normalizeStoragePath(path) ?: return null
  if (normalizedPath == "/") {
    return null
  }

  val parentPath = normalizedPath.substringBeforeLast('/', "")
  return when {
    parentPath.isEmpty() -> "/"
    parentPath == normalizedPath -> null
    else -> parentPath
  }
}

/**
 * The device's primary/emulated volume. Its leaf segment is the literal `0`, which is what
 * `leafStorageName` would otherwise surface as a folder literally called "0".
 */
internal const val INTERNAL_STORAGE_LABEL = "Internal Storage"

/** Aliases that all resolve to the same emulated volume as `Environment.getExternalStorageDirectory()`. */
private val INTERNAL_STORAGE_PATH_KEYS =
  setOf(
    "/storage/emulated/0",
    "/storage/self/primary",
    "/sdcard",
    "/storage/emulated/legacy",
  )

internal fun isInternalStoragePath(path: String?): Boolean =
  storagePathKey(path) in INTERNAL_STORAGE_PATH_KEYS

internal fun leafStorageName(path: String?): String {
  val normalized = normalizeStoragePath(path) ?: return ""
  if (isInternalStoragePath(normalized)) return INTERNAL_STORAGE_LABEL
  return normalized
    .substringAfterLast('/')
    .takeIf { it.isNotBlank() }
    ?: ""
}

internal fun choosePreferredStoragePath(
  existingPath: String,
  candidatePath: String,
): String {
  val normalizedExisting = normalizeStoragePath(existingPath) ?: existingPath
  val normalizedCandidate = normalizeStoragePath(candidatePath) ?: candidatePath

  if (areEquivalentStoragePaths(normalizedExisting, normalizedCandidate)) {
    val existingScore = storageDisplayScore(normalizedExisting)
    val candidateScore = storageDisplayScore(normalizedCandidate)
    return if (candidateScore > existingScore) normalizedCandidate else normalizedExisting
  }

  return normalizedExisting
}

private fun storageDisplayScore(path: String): Int {
  val uppercaseScore = path.count { it.isUpperCase() } * 2
  val mediaFolderBonus =
    listOf("DCIM", "Movies", "Pictures", "Download", "Camera")
      .count { preferredName -> path.contains("/$preferredName") || path.endsWith(preferredName) }
  return uppercaseScore + mediaFolderBonus
}
