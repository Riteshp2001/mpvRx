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
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.gyrolet.mpvrx.database.entities.RecentlyPlayedEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RecentlyPlayedDao {
  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(recentlyPlayed: RecentlyPlayedEntity)

  @Query("DELETE FROM RecentlyPlayedEntity WHERE filePath = :filePath")
  suspend fun deleteExistingEntriesForFile(filePath: String)

  @Query("SELECT * FROM RecentlyPlayedEntity WHERE filePath = :filePath LIMIT 1")
  suspend fun getByFilePath(filePath: String): RecentlyPlayedEntity?

  // id is the INTEGER PRIMARY KEY and therefore already indexed by SQLite. New history entries are
  // inserted with autoGenerate=true, so ordering by id avoids building a temporary timestamp sort
  // for the hot recent-history queries without introducing a database migration solely for indexes.
  @Query("SELECT * FROM RecentlyPlayedEntity ORDER BY id DESC LIMIT 1")
  suspend fun getLastPlayed(): RecentlyPlayedEntity?

  @Query("SELECT * FROM RecentlyPlayedEntity ORDER BY id DESC LIMIT 1")
  fun observeLastPlayed(): Flow<RecentlyPlayedEntity?>

  @Query(
    """
    SELECT * FROM RecentlyPlayedEntity 
    WHERE (launchSource IS NULL OR launchSource = '' OR launchSource = 'normal' OR launchSource = 'playlist' OR launchSource = 'video_list')
    AND (NOT (filePath LIKE '%.m3u%' OR filePath LIKE '%.m3u8%'))
    ORDER BY id DESC 
    LIMIT 1
  """,
  )
  suspend fun getLastPlayedForHighlight(): RecentlyPlayedEntity?

  @Query(
    """
    SELECT * FROM RecentlyPlayedEntity 
    WHERE (launchSource IS NULL OR launchSource = '' OR launchSource = 'normal' OR launchSource = 'playlist' OR launchSource = 'video_list')
    AND (NOT (filePath LIKE '%.m3u%' OR filePath LIKE '%.m3u8%'))
    ORDER BY id DESC 
    LIMIT 1
  """,
  )
  fun observeLastPlayedForHighlight(): Flow<RecentlyPlayedEntity?>

  @Query(
    """
    SELECT * FROM RecentlyPlayedEntity 
    WHERE (NOT (filePath LIKE '%.m3u%' OR filePath LIKE '%.m3u8%')) 
    ORDER BY id DESC 
    LIMIT :limit
  """,
  )
  suspend fun getRecentlyPlayed(limit: Int = 10): List<RecentlyPlayedEntity>

  @Query(
    """
    SELECT * FROM RecentlyPlayedEntity 
    WHERE (NOT (filePath LIKE '%.m3u%' OR filePath LIKE '%.m3u8%')) 
    ORDER BY id DESC 
    LIMIT :limit
  """,
  )
  fun observeRecentlyPlayed(limit: Int = 50): Flow<List<RecentlyPlayedEntity>>

  @Query(
    """
    SELECT * FROM RecentlyPlayedEntity 
    WHERE launchSource = :launchSource
    AND (NOT (filePath LIKE '%.m3u%' OR filePath LIKE '%.m3u8%'))
    ORDER BY id DESC 
    LIMIT :limit
  """,
  )
  suspend fun getRecentlyPlayedBySource(
    launchSource: String,
    limit: Int = 10,
  ): List<RecentlyPlayedEntity>

  @Query("DELETE FROM RecentlyPlayedEntity")
  suspend fun clearAll()

  @Query("DELETE FROM RecentlyPlayedEntity WHERE timestamp < :cutoffTime")
  suspend fun deleteOlderThan(cutoffTime: Long)

  @Query("DELETE FROM RecentlyPlayedEntity WHERE filePath = :filePath")
  suspend fun deleteByFilePath(filePath: String)

  @Query("DELETE FROM RecentlyPlayedEntity WHERE playlistId = :playlistId")
  suspend fun deleteByPlaylistId(playlistId: Int)

  @Query("UPDATE RecentlyPlayedEntity SET filePath = :newPath, fileName = :newFileName WHERE filePath = :oldPath")
  suspend fun updateFilePath(
    oldPath: String,
    newPath: String,
    newFileName: String,
  )

  @Query("UPDATE RecentlyPlayedEntity SET videoTitle = :videoTitle WHERE filePath = :filePath")
  suspend fun updateVideoTitle(
    filePath: String,
    videoTitle: String,
  )

  @Query(
    "UPDATE RecentlyPlayedEntity SET videoTitle = :videoTitle, duration = :duration, fileSize = :fileSize, width = :width, height = :height WHERE filePath = :filePath",
  )
  suspend fun updateVideoMetadata(
    filePath: String,
    videoTitle: String?,
    duration: Long,
    fileSize: Long,
    width: Int,
    height: Int,
  )

  @Query(
    """
    SELECT DISTINCT playlistId, MAX(timestamp) as timestamp
    FROM RecentlyPlayedEntity 
    WHERE playlistId IS NOT NULL
    GROUP BY playlistId
    ORDER BY timestamp DESC
    LIMIT :limit
  """,
  )
  suspend fun getRecentlyPlayedPlaylists(limit: Int = 10): List<RecentlyPlayedPlaylistInfo>

  @Query(
    """
    SELECT DISTINCT playlistId, MAX(timestamp) as timestamp
    FROM RecentlyPlayedEntity 
    WHERE playlistId IS NOT NULL
    GROUP BY playlistId
    ORDER BY timestamp DESC
    LIMIT :limit
  """,
  )
  fun observeRecentlyPlayedPlaylists(limit: Int = 50): Flow<List<RecentlyPlayedPlaylistInfo>>

  data class RecentlyPlayedPlaylistInfo(
    val playlistId: Int,
    val timestamp: Long,
  )

  @Query("SELECT * FROM RecentlyPlayedEntity ORDER BY id DESC")
  suspend fun getAllRecentlyPlayed(): List<RecentlyPlayedEntity>

  @Query("SELECT COUNT(*) FROM RecentlyPlayedEntity")
  suspend fun getRecentlyPlayedCount(): Int

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertAll(items: List<RecentlyPlayedEntity>)
}
