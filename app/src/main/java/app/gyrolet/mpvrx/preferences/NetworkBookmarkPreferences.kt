package app.gyrolet.mpvrx.preferences

import app.gyrolet.mpvrx.domain.network.NetworkPath
import app.gyrolet.mpvrx.preferences.preference.Preference
import app.gyrolet.mpvrx.preferences.preference.PreferenceStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class NetworkFolderBookmark(
  val connectionId: Long,
  val path: String,
  val folderName: String,
)

class NetworkBookmarkPreferences(
  preferenceStore: PreferenceStore,
) {
  val bookmarks: Preference<List<NetworkFolderBookmark>> =
    preferenceStore.getObject(
      key = "network_folder_bookmarks",
      defaultValue = emptyList(),
      serializer = { value -> Json.encodeToString(value) },
      deserializer = { value ->
        runCatching { Json.decodeFromString<List<NetworkFolderBookmark>>(value) }
          .getOrDefault(emptyList())
      },
    )

  @Synchronized
  fun toggle(bookmark: NetworkFolderBookmark): Boolean {
    val normalized = bookmark.normalized()
    val current = bookmarks.get()
    val isBookmarked = current.any { it.matches(normalized.connectionId, normalized.path) }
    bookmarks.set(
      if (isBookmarked) {
        current.filterNot { it.matches(normalized.connectionId, normalized.path) }
      } else {
        current + normalized
      },
    )
    return !isBookmarked
  }

  @Synchronized
  fun remove(bookmark: NetworkFolderBookmark) {
    bookmarks.set(bookmarks.get().filterNot { it.matches(bookmark.connectionId, bookmark.path) })
  }

  @Synchronized
  fun removeForConnection(connectionId: Long) {
    bookmarks.set(bookmarks.get().filterNot { it.connectionId == connectionId })
  }

  fun contains(
    connectionId: Long,
    path: String,
  ): Boolean {
    val normalizedPath = NetworkPath.from(path).value
    return bookmarks.get().any { it.matches(connectionId, normalizedPath) }
  }

  private fun NetworkFolderBookmark.normalized(): NetworkFolderBookmark {
    val normalizedPath = NetworkPath.from(path)
    return copy(
      path = normalizedPath.value,
      folderName = folderName.trim().ifBlank { normalizedPath.segments.lastOrNull().orEmpty() },
    )
  }

  private fun NetworkFolderBookmark.matches(
    targetConnectionId: Long,
    targetPath: String,
  ): Boolean = connectionId == targetConnectionId && NetworkPath.from(path).value == NetworkPath.from(targetPath).value
}
