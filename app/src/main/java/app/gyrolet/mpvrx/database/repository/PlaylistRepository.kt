package app.gyrolet.mpvrx.database.repository

import android.content.Context
import android.net.Uri
import app.gyrolet.mpvrx.database.dao.PlaylistDao
import app.gyrolet.mpvrx.database.entities.PlaylistEntity
import app.gyrolet.mpvrx.database.entities.PlaylistItemEntity
import app.gyrolet.mpvrx.utils.media.M3UParser
import app.gyrolet.mpvrx.utils.media.M3UParseResult
import app.gyrolet.mpvrx.utils.media.M3UPlaylistItem
import kotlinx.coroutines.flow.Flow

class PlaylistRepository(private val playlistDao: PlaylistDao) {

  companion object {
    private const val INSERT_CHUNK_SIZE = 500
  }

  // Playlist operations
  suspend fun createPlaylist(name: String): Long {
    val now = System.currentTimeMillis()
    return playlistDao.insertPlaylist(
      PlaylistEntity(
        name = name,
        createdAt = now,
        updatedAt = now,
      ),
    )
  }

  suspend fun updatePlaylist(playlist: PlaylistEntity) {
    playlistDao.updatePlaylist(playlist.copy(updatedAt = System.currentTimeMillis()))
  }

  suspend fun deletePlaylist(playlist: PlaylistEntity) {
    playlistDao.deletePlaylist(playlist)
  }

  fun observeAllPlaylists(): Flow<List<PlaylistEntity>> = playlistDao.observeAllPlaylists()

  suspend fun getAllPlaylists(): List<PlaylistEntity> = playlistDao.getAllPlaylists()

  suspend fun getPlaylistById(playlistId: Int): PlaylistEntity? = playlistDao.getPlaylistById(playlistId)

  fun observePlaylistById(playlistId: Int): Flow<PlaylistEntity?> = playlistDao.observePlaylistById(playlistId)

  // Playlist item operations
  suspend fun addItemToPlaylist(playlistId: Int, filePath: String, fileName: String) {
    val maxPosition = playlistDao.getMaxPosition(playlistId) ?: -1
    playlistDao.insertPlaylistItem(
      PlaylistItemEntity(
        playlistId = playlistId,
        filePath = filePath,
        fileName = fileName,
        position = maxPosition + 1,
        addedAt = System.currentTimeMillis(),
      ),
    )
    getPlaylistById(playlistId)?.let { playlist ->
      updatePlaylist(playlist)
    }
  }

  suspend fun addItemsToPlaylist(playlistId: Int, items: List<Pair<String, String>>) {
    val maxPosition = playlistDao.getMaxPosition(playlistId) ?: -1
    val now = System.currentTimeMillis()
    val playlistItems = items.mapIndexed { index, (filePath, fileName) ->
      PlaylistItemEntity(
        playlistId = playlistId,
        filePath = filePath,
        fileName = fileName,
        position = maxPosition + 1 + index,
        addedAt = now,
      )
    }
    insertInChunks(playlistItems)
    getPlaylistById(playlistId)?.let { playlist ->
      updatePlaylist(playlist)
    }
  }

  suspend fun removeItemFromPlaylist(item: PlaylistItemEntity) {
    playlistDao.deletePlaylistItem(item)
    getPlaylistById(item.playlistId)?.let { playlist ->
      updatePlaylist(playlist)
    }
  }

  suspend fun removeItemsFromPlaylist(items: List<PlaylistItemEntity>) {
    if (items.isEmpty()) return
    playlistDao.deletePlaylistItems(items)
    getPlaylistById(items.first().playlistId)?.let { playlist ->
      updatePlaylist(playlist)
    }
  }

  suspend fun removeItemById(itemId: Int) {
    playlistDao.deletePlaylistItemById(itemId)
  }

  suspend fun clearPlaylist(playlistId: Int) {
    playlistDao.deleteAllItemsFromPlaylist(playlistId)
    getPlaylistById(playlistId)?.let { playlist ->
      updatePlaylist(playlist)
    }
  }

  fun observePlaylistItems(playlistId: Int): Flow<List<PlaylistItemEntity>> =
    playlistDao.observePlaylistItems(playlistId)

  suspend fun getPlaylistItems(playlistId: Int): List<PlaylistItemEntity> =
    playlistDao.getPlaylistItems(playlistId)

  fun observePlaylistItemCount(playlistId: Int): Flow<Int> =
    playlistDao.observePlaylistItemCount(playlistId)

  suspend fun getPlaylistItemCount(playlistId: Int): Int =
    playlistDao.getPlaylistItemCount(playlistId)

  suspend fun reorderPlaylistItems(playlistId: Int, newOrder: List<Int>) {
    playlistDao.reorderPlaylistItems(playlistId, newOrder)
    getPlaylistById(playlistId)?.let { playlist ->
      updatePlaylist(playlist)
    }
  }

  suspend fun getPlaylistItemsAsUris(playlistId: Int): List<Uri> {
    return getPlaylistItems(playlistId).map { Uri.parse(it.filePath) }
  }

  /**
   * Get a windowed subset of playlist items as URIs to avoid loading huge playlists at once.
   */
  suspend fun getPlaylistItemsWindowAsUris(
    playlistId: Int,
    centerIndex: Int = 0,
    windowSize: Int = 100,
  ): List<Uri> {
    val totalCount = getPlaylistItemCount(playlistId)
    if (totalCount == 0) return emptyList()

    if (totalCount <= windowSize) {
      return getPlaylistItemsAsUris(playlistId)
    }

    val halfWindow = windowSize / 2
    val startPosition = (centerIndex - halfWindow).coerceAtLeast(0)
    val endPosition = (startPosition + windowSize).coerceAtMost(totalCount)

    return playlistDao.getPlaylistItemsInRange(playlistId, startPosition, endPosition)
      .map { Uri.parse(it.filePath) }
  }

  // Play history operations
  suspend fun updatePlayHistory(playlistId: Int, filePath: String, position: Long = 0) {
    playlistDao.updatePlayHistory(playlistId, filePath, System.currentTimeMillis(), position)
  }

  suspend fun getRecentlyPlayedInPlaylist(playlistId: Int, limit: Int = 20): List<PlaylistItemEntity> {
    return playlistDao.getRecentlyPlayedInPlaylist(playlistId, limit)
  }

  fun observeRecentlyPlayedInPlaylist(playlistId: Int, limit: Int = 20): Flow<List<PlaylistItemEntity>> {
    return playlistDao.observeRecentlyPlayedInPlaylist(playlistId, limit)
  }

  suspend fun getPlaylistItemByPath(playlistId: Int, filePath: String): PlaylistItemEntity? {
    return playlistDao.getPlaylistItemByPath(playlistId, filePath)
  }

  // Category / Favorites
  fun observeDistinctCategories(playlistId: Int): Flow<List<String>> =
    playlistDao.observeDistinctCategories(playlistId)

  suspend fun getDistinctCategories(playlistId: Int): List<String> =
    playlistDao.getDistinctCategories(playlistId)

  fun observeFavoriteItems(playlistId: Int): Flow<List<PlaylistItemEntity>> =
    playlistDao.observeFavoriteItems(playlistId)

  suspend fun toggleFavorite(itemId: Int) = playlistDao.toggleFavorite(itemId)

  suspend fun setFavorite(itemId: Int, isFavorite: Boolean) = playlistDao.setFavorite(itemId, isFavorite)

  // M3U Playlist operations
  suspend fun createM3UPlaylist(url: String, userAgent: String? = null): Result<Long> {
    return try {
      val parseResult = M3UParser.parseFromUrl(url, userAgent)

      when (parseResult) {
        is M3UParseResult.Success -> {
          val now = System.currentTimeMillis()
          val playlistId = playlistDao.insertPlaylist(
            PlaylistEntity(
              name = parseResult.playlistName,
              createdAt = now,
              updatedAt = now,
              m3uSourceUrl = url,
              isM3uPlaylist = true,
              userAgent = userAgent,
            )
          )

          val items = parseResult.items.mapIndexed { index, m3uItem ->
            m3uItem.toEntity(playlistId.toInt(), index, now)
          }

          insertInChunks(items)
          Result.success(playlistId)
        }
        is M3UParseResult.Error -> {
          Result.failure(Exception(parseResult.message, parseResult.exception))
        }
      }
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  suspend fun createM3UPlaylistFromFile(context: Context, uri: Uri): Result<Long> {
    return try {
      val parseResult = M3UParser.parseFromUri(context, uri)

      when (parseResult) {
        is M3UParseResult.Success -> {
          val now = System.currentTimeMillis()
          val playlistId = playlistDao.insertPlaylist(
            PlaylistEntity(
              name = parseResult.playlistName,
              createdAt = now,
              updatedAt = now,
              m3uSourceUrl = null,
              isM3uPlaylist = true,
            )
          )

          val items = parseResult.items.mapIndexed { index, m3uItem ->
            m3uItem.toEntity(playlistId.toInt(), index, now)
          }

          insertInChunks(items)
          Result.success(playlistId)
        }
        is M3UParseResult.Error -> {
          Result.failure(Exception(parseResult.message, parseResult.exception))
        }
      }
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  suspend fun refreshM3UPlaylist(playlistId: Int): Result<Unit> {
    return try {
      val playlist = getPlaylistById(playlistId)
        ?: return Result.failure(Exception("Playlist not found"))

      if (!playlist.isM3uPlaylist || playlist.m3uSourceUrl == null) {
        return Result.failure(Exception("Not an M3U playlist or no source URL available"))
      }

      val parseResult = M3UParser.parseFromUrl(playlist.m3uSourceUrl, playlist.userAgent)

      when (parseResult) {
        is M3UParseResult.Success -> {
          // Preserve favorite URLs before clearing
          val favoritePaths = playlistDao.getFavoriteFilePaths(playlistId).toSet()

          playlistDao.deleteAllItemsFromPlaylist(playlistId)

          val now = System.currentTimeMillis()
          val items = parseResult.items.mapIndexed { index, m3uItem ->
            m3uItem.toEntity(
              playlistId = playlistId,
              position = index,
              now = now,
              // Restore favorite status for paths that were favorited before refresh
              isFavorite = m3uItem.url in favoritePaths,
            )
          }

          insertInChunks(items)
          updatePlaylist(playlist)

          Result.success(Unit)
        }
        is M3UParseResult.Error -> {
          Result.failure(Exception(parseResult.message, parseResult.exception))
        }
      }
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  // Batched insert to avoid SQLite transaction size limits on huge M3U playlists
  private suspend fun insertInChunks(items: List<PlaylistItemEntity>) {
    items.chunked(INSERT_CHUNK_SIZE).forEach { chunk ->
      playlistDao.insertPlaylistItems(chunk)
    }
  }
}

private fun M3UPlaylistItem.toEntity(
  playlistId: Int,
  position: Int,
  now: Long,
  isFavorite: Boolean = false,
): PlaylistItemEntity = PlaylistItemEntity(
  playlistId = playlistId,
  filePath = url,
  fileName = title ?: tvgName ?: url.substringAfterLast('/').take(80).ifBlank { "Item ${position + 1}" },
  position = position,
  addedAt = now,
  tvgId = tvgId,
  tvgLogo = tvgLogo,
  groupTitle = groupTitle,
  licenseType = licenseType,
  licenseKey = licenseKey,
  userAgent = userAgent,
  isFavorite = isFavorite,
)

