package app.gyrolet.mpvrx.utils.media

import android.content.Context
import android.net.Uri
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

  val directories = buildList {
    runCatching { root.findFile(SUBTITLES_DIRECTORY_NAME) }
      .getOrNull()
      ?.takeIf { it.exists() && it.isDirectory }
      ?.let(::add)
    add(root)
  }

  return directories.distinctBy { it.uri.toString() }
}
