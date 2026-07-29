/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
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
