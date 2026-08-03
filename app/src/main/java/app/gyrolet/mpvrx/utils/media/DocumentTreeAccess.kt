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
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

fun openPersistedTreeDocument(
  context: Context,
  treeUriString: String,
  requireWrite: Boolean = false,
): DocumentFile? {
  if (treeUriString.isBlank()) return null

  val treeUri =
    runCatching { Uri.parse(treeUriString) }
      .getOrNull()
      ?: return null

  val hasPermission =
    context.contentResolver.persistedUriPermissions.any { permission ->
      permission.uri == treeUri &&
        permission.isReadPermission &&
        (!requireWrite || permission.isWritePermission)
    }

  if (!hasPermission) return null

  val root =
    runCatching { DocumentFile.fromTreeUri(context, treeUri) }
      .getOrNull()
      ?: return null

  val accessible =
    runCatching { root.exists() && root.isDirectory && root.canRead() }
      .getOrDefault(false)

  return root.takeIf { accessible }
}

fun listTreeFilesSafely(document: DocumentFile): Array<DocumentFile> =
  runCatching { document.listFiles() }
    .getOrDefault(emptyArray())
