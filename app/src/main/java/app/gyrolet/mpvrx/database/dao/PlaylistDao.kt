/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import app.gyrolet.mpvrx.database.entities.PlaylistEntity
import app.gyrolet.mpvrx.database.entities.PlaylistItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {
  // Playlist operations
  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertPlaylist(playlist: PlaylistEntity): Long

  @Update
  suspend fun updatePlaylist(playlist: PlaylistEntity)

  @Delete
  suspend fun deletePlaylist(playlist: PlaylistEntity)

  @Query("SELECT * FROM PlaylistEntity ORDER BY updatedAt DESC")
  fun observeAllPlaylists(): Flow<List<PlaylistEntity>>

  @Query("SELECT * FROM PlaylistEntity ORDER BY updatedAt DESC")
  suspend fun getAllPlaylists(): List<PlaylistEntity>

  @Query("SELECT * FROM PlaylistEntity WHERE id = :playlistId")
  suspend fun getPlaylistById(playlistId: Int): PlaylistEntity?

  @Query("SELECT * FROM PlaylistEntity WHERE isM3uPlaylist = 1 AND m3uSourceUrl = :sourceUrl LIMIT 1")
  suspend fun getRemotePlaylistBySourceUrl(sourceUrl: String): PlaylistEntity?

  @Query(
    """
    SELECT * FROM PlaylistEntity
    WHERE isXtreamPlaylist = 1 AND xtreamServerUrl = :serverUrl AND xtreamUsername = :username
    LIMIT 1
    """,
  )
  suspend fun getXtreamPlaylistByIdentity(
    serverUrl: String,
    username: String,
  ): PlaylistEntity?

  @Query("SELECT * FROM PlaylistEntity WHERE isXtreamPlaylist = 1 AND xtreamAccountKey = :accountKey LIMIT 1")
  suspend fun getXtreamPlaylistByAccountKey(accountKey: String): PlaylistEntity?

  @Query("SELECT * FROM PlaylistEntity WHERE id = :playlistId")
  fun observePlaylistById(playlistId: Int): Flow<PlaylistEntity?>

  // Playlist item operations
  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertPlaylistItem(item: PlaylistItemEntity)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertPlaylistItems(items: List<PlaylistItemEntity>)

  /** Inserts a potentially large item batch in one transaction without exceeding bind limits. */
  @Transaction
  suspend fun insertPlaylistItemsAtomically(items: List<PlaylistItemEntity>) {
    items.chunked(PLAYLIST_WRITE_CHUNK_SIZE).forEach { chunk -> insertPlaylistItems(chunk) }
  }

  /** Inserts playlist metadata and every item atomically, assigning the generated parent id. */
  @Transaction
  suspend fun insertPlaylistWithItems(
    playlist: PlaylistEntity,
    items: List<PlaylistItemEntity>,
  ): Long {
    val playlistId = insertPlaylist(playlist)
    items
      .map { item -> item.copy(playlistId = playlistId.toInt()) }
      .chunked(PLAYLIST_WRITE_CHUNK_SIZE)
      .forEach { chunk -> insertPlaylistItems(chunk) }
    return playlistId
  }

  /** Replaces a parsed playlist without exposing the old list to partial-delete failures. */
  @Transaction
  suspend fun replacePlaylistItems(
    playlist: PlaylistEntity,
    items: List<PlaylistItemEntity>,
  ) {
    deleteAllItemsFromPlaylist(playlist.id)
    items.chunked(PLAYLIST_WRITE_CHUNK_SIZE).forEach { chunk -> insertPlaylistItems(chunk) }
    updatePlaylist(playlist)
  }

  @Delete
  suspend fun deletePlaylistItems(items: List<PlaylistItemEntity>)

  @Query("SELECT * FROM PlaylistItemEntity WHERE playlistId = :playlistId ORDER BY position ASC")
  fun observePlaylistItems(playlistId: Int): Flow<List<PlaylistItemEntity>>

  @Query("SELECT * FROM PlaylistItemEntity WHERE playlistId = :playlistId ORDER BY position ASC, id ASC LIMIT 1")
  fun observeFirstPlaylistItem(playlistId: Int): Flow<PlaylistItemEntity?>

  @Query("SELECT * FROM PlaylistItemEntity WHERE playlistId = :playlistId ORDER BY position ASC, id ASC LIMIT :limit")
  fun observePlaylistPreviewItems(
    playlistId: Int,
    limit: Int = 2,
  ): Flow<List<PlaylistItemEntity>>

  @Query("SELECT * FROM PlaylistItemEntity WHERE playlistId = :playlistId ORDER BY position ASC")
  suspend fun getPlaylistItems(playlistId: Int): List<PlaylistItemEntity>

  @Query("SELECT COUNT(*) FROM PlaylistItemEntity WHERE playlistId = :playlistId")
  suspend fun getPlaylistItemCount(playlistId: Int): Int

  @Query("SELECT COUNT(*) FROM PlaylistItemEntity WHERE playlistId = :playlistId")
  fun observePlaylistItemCount(playlistId: Int): Flow<Int>

  @Query("DELETE FROM PlaylistItemEntity WHERE playlistId = :playlistId")
  suspend fun deleteAllItemsFromPlaylist(playlistId: Int)

  @Query("UPDATE PlaylistItemEntity SET position = :newPosition WHERE id = :itemId")
  suspend fun updateItemPosition(
    itemId: Int,
    newPosition: Int,
  )

  @Transaction
  suspend fun reorderPlaylistItems(
    playlistId: Int,
    newOrder: List<Int>,
  ) {
    newOrder.forEachIndexed { index, itemId ->
      updateItemPosition(itemId, index)
    }
  }

  @Query("SELECT MAX(position) FROM PlaylistItemEntity WHERE playlistId = :playlistId")
  suspend fun getMaxPosition(playlistId: Int): Int?

  // Play history operations
  @Query(
    """
    UPDATE PlaylistItemEntity 
    SET lastPlayedAt = :timestamp, playCount = playCount + 1, lastPosition = :position
    WHERE playlistId = :playlistId AND filePath = :filePath
    """,
  )
  suspend fun updatePlayHistory(
    playlistId: Int,
    filePath: String,
    timestamp: Long,
    position: Long,
  )

  @Query("UPDATE PlaylistItemEntity SET lastPosition = 0, lastPlayedAt = 0, playCount = 0 WHERE filePath = :filePath")
  suspend fun clearPlayHistoryForFile(filePath: String)

  @Query("UPDATE PlaylistItemEntity SET lastPlayedAt = :timestamp WHERE filePath = :filePath")
  suspend fun markFileLastPlayed(
    filePath: String,
    timestamp: Long,
  )

  @Query("UPDATE PlaylistItemEntity SET filePath = :newPath, fileName = :newName WHERE filePath = :oldPath")
  suspend fun updateFileLocation(
    oldPath: String,
    newPath: String,
    newName: String,
  )

  @Query("UPDATE PlaylistItemEntity SET lastPosition = 0 WHERE filePath = :filePath")
  suspend fun clearResumePositionForFile(filePath: String)

  @Query(
    """
    SELECT * FROM PlaylistItemEntity 
    WHERE playlistId = :playlistId 
    ORDER BY lastPlayedAt DESC
    LIMIT :limit
    """,
  )
  suspend fun getRecentlyPlayedInPlaylist(
    playlistId: Int,
    limit: Int,
  ): List<PlaylistItemEntity>

  @Query(
    """
    SELECT * FROM PlaylistItemEntity 
    WHERE playlistId = :playlistId 
    ORDER BY lastPlayedAt DESC
    LIMIT :limit
    """,
  )
  fun observeRecentlyPlayedInPlaylist(
    playlistId: Int,
    limit: Int,
  ): Flow<List<PlaylistItemEntity>>

  @Query(
    """
    SELECT * FROM PlaylistItemEntity 
    WHERE playlistId = :playlistId AND filePath = :filePath
    """,
  )
  suspend fun getPlaylistItemByPath(
    playlistId: Int,
    filePath: String,
  ): PlaylistItemEntity?

  // M3U category / group support
  @Query(
    """
    SELECT DISTINCT groupTitle FROM PlaylistItemEntity
    WHERE playlistId = :playlistId AND groupTitle IS NOT NULL AND groupTitle != ''
    ORDER BY groupTitle ASC
    """,
  )
  fun observeDistinctCategories(playlistId: Int): kotlinx.coroutines.flow.Flow<List<String>>

  @Query(
    """
    SELECT DISTINCT groupTitle FROM PlaylistItemEntity
    WHERE playlistId = :playlistId AND groupTitle IS NOT NULL AND groupTitle != ''
    ORDER BY groupTitle ASC
    """,
  )
  suspend fun getDistinctCategories(playlistId: Int): List<String>

  // Favorites
  @Query(
    """
    SELECT * FROM PlaylistItemEntity
    WHERE playlistId = :playlistId AND isFavorite = 1
    ORDER BY position ASC
    """,
  )
  fun observeFavoriteItems(playlistId: Int): kotlinx.coroutines.flow.Flow<List<PlaylistItemEntity>>

  @Query(
    """
    UPDATE PlaylistItemEntity SET isFavorite = CASE WHEN isFavorite = 0 THEN 1 ELSE 0 END
    WHERE id = :itemId
    """,
  )
  suspend fun toggleFavorite(itemId: Int)

  @Query("UPDATE PlaylistItemEntity SET isFavorite = :isFavorite WHERE id = :itemId")
  suspend fun setFavorite(
    itemId: Int,
    isFavorite: Boolean,
  )

  companion object {
    private const val PLAYLIST_WRITE_CHUNK_SIZE = 500
  }
}
