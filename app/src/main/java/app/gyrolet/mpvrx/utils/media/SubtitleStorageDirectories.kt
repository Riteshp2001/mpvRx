/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.utils.media

import android.content.Context
import androidx.documentfile.provider.DocumentFile

private const val SUBTITLES_DIRECTORY_NAME = "Subtitles"

fun resolveSubtitleStorageDirectory(
  context: Context,
  treeUriString: String,
  createIfMissing: Boolean = false,
): DocumentFile? {
  if (treeUriString.isBlank()) return null

  val root = openPersistedTreeDocument(context, treeUriString, requireWrite = createIfMissing) ?: return null

  if (root.name.equals(SUBTITLES_DIRECTORY_NAME, ignoreCase = true)) return root

  runCatching { root.findFile(SUBTITLES_DIRECTORY_NAME) }
    .getOrNull()
    ?.takeIf { it.exists() && it.isDirectory }
    ?.let { return it }

  return if (createIfMissing) root.createDirectory(SUBTITLES_DIRECTORY_NAME) else null
}

fun resolveSubtitleLookupDirectories(
  context: Context,
  treeUriString: String,
): List<DocumentFile> {
  if (treeUriString.isBlank()) return emptyList()

  val root = openPersistedTreeDocument(context, treeUriString) ?: return emptyList()

  if (root.name.equals(SUBTITLES_DIRECTORY_NAME, ignoreCase = true)) {
    return listOf(root)
  }

  val directories =
    buildList {
      runCatching { root.findFile(SUBTITLES_DIRECTORY_NAME) }
        .getOrNull()
        ?.takeIf { it.exists() && it.isDirectory }
        ?.let(::add)
      add(root)
    }

  return directories.distinctBy { it.uri.toString() }
}
